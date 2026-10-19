package com.xianyu.copilot

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 卡密、发货规则与多账号管理 SQLite 模块。
 * 全局单例，所有写操作串行化于 [writeLock]，出库使用事务保证原子性。
 */
class CardDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "xianyu_copilot.db"
        private const val DB_VERSION = 2

        private const val T_CARDS = "card_keys"
        private const val T_RULES = "delivery_rules"
        private const val T_LOGS = "delivery_logs"
        private const val T_SETTINGS = "app_settings"
        private const val T_ACCOUNTS = "accounts"

        private const val SETTING_COOKIE = "cookie"

        @Volatile
        private var instance: CardDatabase? = null

        fun get(context: Context): CardDatabase =
            instance ?: synchronized(this) {
                instance ?: CardDatabase(context).also { instance = it }
            }
    }

    private val writeLock = Any()

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $T_CARDS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                keyword TEXT NOT NULL,
                sku TEXT NOT NULL DEFAULT '',
                card_content TEXT NOT NULL,
                is_used INTEGER NOT NULL DEFAULT 0,
                order_id TEXT,
                used_at INTEGER,
                created_at INTEGER NOT NULL,
                UNIQUE(keyword, sku, card_content)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cards_lookup ON $T_CARDS(keyword, sku, is_used)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_card_avail ON $T_CARDS(keyword, sku, id) WHERE is_used = 0")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS uk_card_order ON $T_CARDS(order_id) WHERE order_id IS NOT NULL")

        db.execSQL(
            """
            CREATE TABLE $T_RULES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                keyword TEXT NOT NULL,
                sku TEXT NOT NULL DEFAULT '',
                use_pool INTEGER NOT NULL DEFAULT 1,
                fixed_content TEXT NOT NULL DEFAULT '',
                trade_text TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_rules_keyword ON $T_RULES(keyword)")

        db.execSQL(
            """
            CREATE TABLE $T_LOGS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                order_id TEXT NOT NULL,
                keyword TEXT NOT NULL DEFAULT '',
                delivery_content TEXT NOT NULL DEFAULT '',
                confirm_status TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_logs_time ON $T_LOGS(created_at DESC)")

        db.execSQL(
            """
            CREATE TABLE $T_SETTINGS (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )

        createAccountsTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createAccountsTable(db)
        }
    }

    private fun createAccountsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $T_ACCOUNTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id TEXT UNIQUE,
                nick TEXT NOT NULL,
                avatar TEXT DEFAULT '',
                cookie TEXT NOT NULL,
                is_active INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'ACTIVE',
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_accounts_active ON $T_ACCOUNTS(is_active) WHERE is_active = 1"
        )
    }

    // ---------------------------------------------------------------------
    // 数据模型
    // ---------------------------------------------------------------------
    data class CardItem(
        val id: Long,
        val keyword: String,
        val sku: String,
        val cardContent: String,
        val isUsed: Boolean,
        val orderId: String?,
        val usedAt: Long?,
        val createdAt: Long
    )

    data class DeliveryRule(
        val id: Long,
        val keyword: String,
        val sku: String,
        val usePool: Boolean,
        val fixedContent: String,
        val tradeText: String,
        val createdAt: Long
    )

    data class DeliveryLog(
        val id: Long,
        val orderId: String,
        val keyword: String,
        val deliveryContent: String,
        val confirmStatus: String,
        val createdAt: Long
    )

    data class Stats(
        val todayDelivered: Long,
        val availableCards: Long,
        val usedCards: Long,
        val totalRules: Long
    )

    data class AccountItem(
        val id: Long,
        val userId: String,
        val nick: String,
        val avatar: String,
        val cookie: String,
        val isActive: Boolean,
        val status: String,
        val updatedAt: Long
    )

    private fun now(): Long = System.currentTimeMillis()

    private fun String?.orEmpty(): String = this ?: ""

    private inline fun <T> SQLiteDatabase.safeTransaction(block: (SQLiteDatabase) -> T): T {
        beginTransaction()
        return try {
            val r = block(this)
            setTransactionSuccessful()
            r
        } finally {
            endTransaction()
        }
    }

    // ---------------------------------------------------------------------
    // 多账号管理
    // ---------------------------------------------------------------------

    /**
     * 新增或更新账号（按 user_id 唯一键 upsert）。
     * activate=true 时，同一事务内先将所有账号 is_active 归零，再置本账号为 1。
     */
    fun addOrUpdateAccount(
        userId: String,
        nick: String,
        avatar: String,
        cookie: String,
        activate: Boolean
    ): Long = synchronized(writeLock) {
        val uid = userId.trim()
        if (uid.isEmpty() || cookie.isBlank()) return -1
        val ts = now()
        val db = writableDatabase

        db.safeTransaction { tx ->
            if (activate) {
                tx.execSQL("UPDATE $T_ACCOUNTS SET is_active = 0 WHERE is_active = 1")
            }

            val existingId = tx.rawQuery(
                "SELECT id FROM $T_ACCOUNTS WHERE user_id=? LIMIT 1",
                arrayOf(uid)
            ).use { if (it.moveToFirst()) it.getLong(0) else -1L }

            val cv = ContentValues().apply {
                put("user_id", uid)
                put("nick", nick.orEmpty())
                put("avatar", avatar.orEmpty())
                put("cookie", cookie)
                put("status", "ACTIVE")
                put("updated_at", ts)
                if (activate) put("is_active", 1)
            }

            val targetId: Long
            if (existingId > 0) {
                tx.update(T_ACCOUNTS, cv, "id=?", arrayOf(existingId.toString()))
                targetId = existingId
            } else {
                cv.put("is_active", if (activate) 1 else 0)
                targetId = tx.insert(T_ACCOUNTS, null, cv)
            }

            if (activate) {
                saveSetting("cookie", cookie)
                saveSetting("cookie_nick", nick)
            }
            targetId
        }
    }

    /** 全部账号，激活优先、更新时间倒序。 */
    fun getAccounts(): List<AccountItem> {
        val out = ArrayList<AccountItem>()
        readableDatabase.rawQuery(
            "SELECT id,user_id,nick,avatar,cookie,is_active,status,updated_at " +
                "FROM $T_ACCOUNTS ORDER BY is_active DESC, updated_at DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    AccountItem(
                        id = c.getLong(0),
                        userId = c.getString(1).orEmpty(),
                        nick = c.getString(2).orEmpty(),
                        avatar = c.getString(3).orEmpty(),
                        cookie = c.getString(4).orEmpty(),
                        isActive = c.getInt(5) == 1,
                        status = c.getString(6).orEmpty(),
                        updatedAt = c.getLong(7)
                    )
                )
            }
        }
        return out
    }

    /** 当前激活账号；无激活时返回排序第一条；表空返回 null。 */
    fun getActiveAccount(): AccountItem? {
        val sql = """
            SELECT id,user_id,nick,avatar,cookie,is_active,status,updated_at
            FROM $T_ACCOUNTS
            ORDER BY is_active DESC, updated_at DESC
            LIMIT 1
        """.trimIndent()
        readableDatabase.rawQuery(sql, null).use { c ->
            if (!c.moveToFirst()) return null
            return AccountItem(
                id = c.getLong(0),
                userId = c.getString(1).orEmpty(),
                nick = c.getString(2).orEmpty(),
                avatar = c.getString(3).orEmpty(),
                cookie = c.getString(4).orEmpty(),
                isActive = c.getInt(5) == 1,
                status = c.getString(6).orEmpty(),
                updatedAt = c.getLong(7)
            )
        }
    }

    /** 切换激活账号；目标不存在返回 false。 */
    fun switchAccount(id: Long): Boolean = synchronized(writeLock) {
        val db = writableDatabase
        db.safeTransaction { tx ->
            val account = tx.rawQuery(
                "SELECT cookie, nick FROM $T_ACCOUNTS WHERE id=? LIMIT 1",
                arrayOf(id.toString())
            ).use {
                if (it.moveToFirst()) Pair(it.getString(0), it.getString(1)) else null
            } ?: return@safeTransaction false

            tx.execSQL("UPDATE $T_ACCOUNTS SET is_active = 0 WHERE is_active = 1")
            tx.execSQL("UPDATE $T_ACCOUNTS SET is_active = 1, updated_at = ? WHERE id = ?",
                arrayOf(now(), id.toString()))

            saveSetting("cookie", account.first)
            saveSetting("cookie_nick", account.second)
            true
        }
    }

    /** 删除账号；若删除激活账号，剩余账号中排序第一条自动激活。 */
    fun deleteAccount(id: Long): Boolean = synchronized(writeLock) {
        val db = writableDatabase
        db.safeTransaction { tx ->
            val wasActive = tx.rawQuery(
                "SELECT is_active FROM $T_ACCOUNTS WHERE id=? LIMIT 1",
                arrayOf(id.toString())
            ).use { if (it.moveToFirst()) it.getInt(0) == 1 else return@safeTransaction false }

            val rows = tx.delete(T_ACCOUNTS, "id=?", arrayOf(id.toString()))
            if (rows <= 0) return@safeTransaction false

            if (wasActive) {
                val next = tx.rawQuery(
                    "SELECT id, cookie, nick FROM $T_ACCOUNTS ORDER BY updated_at DESC, id DESC LIMIT 1",
                    null
                ).use {
                    if (it.moveToFirst()) Triple(it.getLong(0), it.getString(1), it.getString(2)) else null
                }
                if (next != null) {
                    tx.execSQL("UPDATE $T_ACCOUNTS SET is_active = 1 WHERE id = ?", arrayOf(next.first.toString()))
                    saveSetting("cookie", next.second)
                    saveSetting("cookie_nick", next.third)
                } else {
                    saveSetting("cookie", "")
                    saveSetting("cookie_nick", "")
                }
            }
            true
        }
    }

    /** 更新账号状态（如 ACTIVE / EXPIRED 等）。 */
    fun updateAccountStatus(id: Long, status: String) = synchronized(writeLock) {
        val cv = ContentValues().apply {
            put("status", status.orEmpty())
            put("updated_at", now())
        }
        writableDatabase.update(T_ACCOUNTS, cv, "id=?", arrayOf(id.toString()))
    }

    /**
     * 快捷取当前 cookie：
     * 1) 激活账号 cookie；2) 兜底 app_settings['cookie']；3) 空串。
     */
    fun getActiveCookie(): String {
        getActiveAccount()?.cookie?.takeIf { it.isNotBlank() }?.let { return it }
        readableDatabase.rawQuery(
            "SELECT value FROM $T_SETTINGS WHERE key=? LIMIT 1",
            arrayOf(SETTING_COOKIE)
        ).use { c ->
            return if (c.moveToFirst()) c.getString(0).orEmpty() else ""
        }
    }

    // ---------------------------------------------------------------------
    // 卡密出库 (经 Hark 审查加固：幂等查询 + 单语句原子抢占 + BEGIN IMMEDIATE)
    // ---------------------------------------------------------------------

    private val SQL_FIND_BY_ORDER =
        "SELECT card_content FROM $T_CARDS WHERE order_id=? LIMIT 1"

    private val SQL_CLAIM = """
        UPDATE $T_CARDS SET is_used=1, order_id=?, used_at=?
        WHERE id = (
            SELECT id FROM $T_CARDS
            WHERE keyword=? AND sku=? AND is_used=0
            ORDER BY id ASC LIMIT 1
        ) AND is_used=0
    """.trimIndent()

    private val SQL_CLAIM_NO_SKU = """
        UPDATE $T_CARDS SET is_used=1, order_id=?, used_at=?
        WHERE id = (
            SELECT id FROM $T_CARDS
            WHERE keyword=? AND is_used=0
            ORDER BY id ASC LIMIT 1
        ) AND is_used=0
    """.trimIndent()

    private fun SQLiteDatabase.queryString(sql: String, vararg args: String): String? =
        rawQuery(sql, args).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun claimCardAtomic(keyword: String, sku: String, orderId: String): String? {
        val k = keyword.trim()
        val s = sku.trim()
        val o = orderId.trim()
        if (k.isEmpty() || o.isEmpty()) return null

        val db = writableDatabase
        db.beginTransactionNonExclusive()
        try {
            db.queryString(SQL_FIND_BY_ORDER, o)?.let {
                db.setTransactionSuccessful()
                return it
            }

            val sql = if (s.isNotEmpty()) SQL_CLAIM else SQL_CLAIM_NO_SKU
            val rows = db.compileStatement(sql).use { st ->
                st.bindString(1, o)
                st.bindLong(2, now())
                st.bindString(3, k)
                if (s.isNotEmpty()) st.bindString(4, s)
                st.executeUpdateDelete()
            }

            val content = if (rows == 1) db.queryString(SQL_FIND_BY_ORDER, o) else null
            db.setTransactionSuccessful()
            return content
        } catch (_: Exception) {
            return null
        } finally {
            db.endTransaction()
        }
    }

    fun addCards(keyword: String, sku: String, list: List<String>): Pair<Int, Int> {
        val k = keyword.trim()
        if (k.isEmpty() || list.isEmpty()) return 0 to 0
        val s = sku.trim()
        val seen = HashSet<String>()
        var added = 0
        var duplicated = 0
        val ts = now()

        synchronized(writeLock) {
            val db = writableDatabase
            db.safeTransaction { tx ->
                val cv = ContentValues()
                for (raw in list) {
                    val content = raw.trim()
                    if (content.isEmpty()) continue
                    if (!seen.add(content)) { duplicated++; continue }

                    val exists = tx.rawQuery(
                        "SELECT 1 FROM $T_CARDS WHERE keyword=? AND sku=? AND card_content=? LIMIT 1",
                        arrayOf(k, s, content)
                    ).use { it.moveToFirst() }
                    if (exists) { duplicated++; continue }

                    cv.clear()
                    cv.put("keyword", k)
                    cv.put("sku", s)
                    cv.put("card_content", content)
                    cv.put("is_used", 0)
                    cv.put("created_at", ts)
                    val id = tx.insert(T_CARDS, null, cv)
                    if (id > 0) added++ else duplicated++
                }
            }
        }
        return added to duplicated
    }

    fun getCards(keyword: String, status: Int): List<CardItem> {
        val args = ArrayList<String>()
        val sb = StringBuilder("SELECT id,keyword,sku,card_content,is_used,order_id,used_at,created_at FROM $T_CARDS WHERE 1=1")
        if (keyword.isNotBlank()) { sb.append(" AND keyword=?"); args.add(keyword.trim()) }
        when (status) {
            0, 1 -> { sb.append(" AND is_used=?"); args.add(status.toString()) }
            else -> Unit
        }
        sb.append(" ORDER BY id DESC")

        val out = ArrayList<CardItem>()
        readableDatabase.rawQuery(sb.toString(), args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                out.add(
                    CardItem(
                        id = c.getLong(0),
                        keyword = c.getString(1).orEmpty(),
                        sku = c.getString(2).orEmpty(),
                        cardContent = c.getString(3).orEmpty(),
                        isUsed = c.getInt(4) == 1,
                        orderId = if (c.isNull(5)) null else c.getString(5),
                        usedAt = if (c.isNull(6)) null else c.getLong(6),
                        createdAt = c.getLong(7)
                    )
                )
            }
        }
        return out
    }

    fun deleteCard(id: Long): Boolean = synchronized(writeLock) {
        writableDatabase.delete(T_CARDS, "id=? AND is_used=0", arrayOf(id.toString())) > 0
    }

    fun clearCards(): Int = synchronized(writeLock) {
        writableDatabase.delete(T_CARDS, "is_used=0", null)
    }

    // ---------------------------------------------------------------------
    // 发货规则
    // ---------------------------------------------------------------------

    fun saveRule(
        id: Long,
        keyword: String,
        sku: String,
        usePool: Boolean,
        fixedContent: String,
        tradeText: String
    ): Long = synchronized(writeLock) {
        val k = keyword.trim()
        if (k.isEmpty()) return -1
        val cv = ContentValues().apply {
            put("keyword", k)
            put("sku", sku.trim())
            put("use_pool", if (usePool) 1 else 0)
            put("fixed_content", fixedContent.orEmpty())
            put("trade_text", tradeText.orEmpty())
        }
        val db = writableDatabase
        if (id > 0) {
            val rows = db.update(T_RULES, cv, "id=?", arrayOf(id.toString()))
            if (rows > 0) id else -1
        } else {
            cv.put("created_at", now())
            db.insert(T_RULES, null, cv)
        }
    }

    fun getRules(): List<DeliveryRule> {
        val out = ArrayList<DeliveryRule>()
        readableDatabase.rawQuery(
            "SELECT id,keyword,sku,use_pool,fixed_content,trade_text,created_at FROM $T_RULES ORDER BY id DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    DeliveryRule(
                        id = c.getLong(0),
                        keyword = c.getString(1).orEmpty(),
                        sku = c.getString(2).orEmpty(),
                        usePool = c.getInt(3) == 1,
                        fixedContent = c.getString(4).orEmpty(),
                        tradeText = c.getString(5).orEmpty(),
                        createdAt = c.getLong(6)
                    )
                )
            }
        }
        return out
    }

    fun deleteRule(id: Long): Boolean = synchronized(writeLock) {
        writableDatabase.delete(T_RULES, "id=?", arrayOf(id.toString())) > 0
    }

    fun matchRule(title: String, sku: String): DeliveryRule? {
        val t = title.orEmpty()
        val s = sku.trim()
        val sql = """
            SELECT id,keyword,sku,use_pool,fixed_content,trade_text,created_at
            FROM $T_RULES
            WHERE (sku=? OR sku='')
            ORDER BY CASE WHEN sku=? THEN 0 ELSE 1 END, id DESC
        """.trimIndent()
        readableDatabase.rawQuery(sql, arrayOf(s, s)).use { c ->
            while (c.moveToNext()) {
                val kw = c.getString(1).orEmpty()
                if (kw.isEmpty() || t.contains(kw, ignoreCase = true)) {
                    return DeliveryRule(
                        id = c.getLong(0),
                        keyword = kw,
                        sku = c.getString(2).orEmpty(),
                        usePool = c.getInt(3) == 1,
                        fixedContent = c.getString(4).orEmpty(),
                        tradeText = c.getString(5).orEmpty(),
                        createdAt = c.getLong(6)
                    )
                }
            }
        }
        return null
    }

    // ---------------------------------------------------------------------
    // 发货流水
    // ---------------------------------------------------------------------

    fun recordLog(orderId: String, keyword: String, content: String, status: String): Long =
        synchronized(writeLock) {
            val cv = ContentValues().apply {
                put("order_id", orderId.orEmpty())
                put("keyword", keyword.orEmpty())
                put("delivery_content", content.orEmpty())
                put("confirm_status", status.orEmpty())
                put("created_at", now())
            }
            writableDatabase.insert(T_LOGS, null, cv)
        }

    fun getLogs(limit: Int): List<DeliveryLog> {
        val lim = if (limit <= 0) 100 else limit
        val out = ArrayList<DeliveryLog>()
        readableDatabase.rawQuery(
            "SELECT id,order_id,keyword,delivery_content,confirm_status,created_at FROM $T_LOGS ORDER BY id DESC LIMIT ?",
            arrayOf(lim.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    DeliveryLog(
                        id = c.getLong(0),
                        orderId = c.getString(1).orEmpty(),
                        keyword = c.getString(2).orEmpty(),
                        deliveryContent = c.getString(3).orEmpty(),
                        confirmStatus = c.getString(4).orEmpty(),
                        createdAt = c.getLong(5)
                    )
                )
            }
        }
        return out
    }

    // ---------------------------------------------------------------------
    // 统计
    // ---------------------------------------------------------------------

    fun getStats(): Stats {
        val db = readableDatabase
        val todayStart = run {
            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            cal.timeInMillis
        }
        fun count(sql: String, args: Array<String>): Long =
            db.rawQuery(sql, args).use { if (it.moveToFirst()) it.getLong(0) else 0 }

        return Stats(
            todayDelivered = count("SELECT COUNT(*) FROM $T_LOGS WHERE created_at>=?", arrayOf(todayStart.toString())),
            availableCards = count("SELECT COUNT(*) FROM $T_CARDS WHERE is_used=0", emptyArray()),
            usedCards = count("SELECT COUNT(*) FROM $T_CARDS WHERE is_used=1", emptyArray()),
            totalRules = count("SELECT COUNT(*) FROM $T_RULES", emptyArray())
        )
    }

    // ---------------------------------------------------------------------
    // 设置
    // ---------------------------------------------------------------------

    fun getSetting(key: String, default: String): String {
        readableDatabase.rawQuery("SELECT value FROM $T_SETTINGS WHERE key=? LIMIT 1", arrayOf(key)).use { c ->
            return if (c.moveToFirst()) c.getString(0).orEmpty() else default
        }
    }

    fun saveSetting(key: String, value: String) {
        synchronized(writeLock) {
            val db = writableDatabase
            db.safeTransaction { tx ->
                val cv = ContentValues().apply {
                    put("key", key)
                    put("value", value.orEmpty())
                }
                tx.insertWithOnConflict(T_SETTINGS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }
}
