package com.xianyu.copilot

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.5.0 新增的「运营数据」库：消息中枢、自动回复、通知、订单与发货任务。
 *
 * 刻意与 [CardDatabase]（卡密 / 账号 / 发货流水）分库：
 *  - 卡密出库与账号健康状态是核心资产，迁移风险最小化，不动它的 schema 主体；
 *  - 这里的数据丢了也只是聊天记录 / 配置，可重新同步。
 */
class OpsDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "xianyu_ops.db", null, 1) {

    companion object {
        @Volatile private var instance: OpsDatabase? = null
        fun get(context: Context): OpsDatabase =
            instance ?: synchronized(this) {
                instance ?: OpsDatabase(context).also { instance = it }
            }

        fun todayStart(): Long {
            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
    }

    private val lock = Any()

    init { setWriteAheadLoggingEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE conversations (
                account_id TEXT NOT NULL, chat_id TEXT NOT NULL,
                buyer_id TEXT NOT NULL DEFAULT '', buyer_nick TEXT NOT NULL DEFAULT '',
                buyer_avatar TEXT NOT NULL DEFAULT '', item_id TEXT NOT NULL DEFAULT '',
                item_title TEXT NOT NULL DEFAULT '', last_msg TEXT NOT NULL DEFAULT '',
                last_ts INTEGER NOT NULL DEFAULT 0, unread INTEGER NOT NULL DEFAULT 0,
                paused_until INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(account_id, chat_id))"""
        )
        db.execSQL("CREATE INDEX idx_conv_ts ON conversations(account_id, last_ts DESC)")
        db.execSQL(
            """CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                account_id TEXT NOT NULL, chat_id TEXT NOT NULL,
                direction TEXT NOT NULL, sender_id TEXT NOT NULL DEFAULT '',
                sender_nick TEXT NOT NULL DEFAULT '', content TEXT NOT NULL DEFAULT '',
                item_id TEXT NOT NULL DEFAULT '', ts INTEGER NOT NULL,
                kind INTEGER NOT NULL DEFAULT 0, intent TEXT NOT NULL DEFAULT '',
                UNIQUE(account_id, chat_id, ts, direction, content))"""
        )
        db.execSQL("CREATE INDEX idx_msg_chat ON messages(account_id, chat_id, ts)")
        db.execSQL("CREATE INDEX idx_msg_ts ON messages(ts)")
        db.execSQL(
            """CREATE TABLE keyword_rules (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                account_id TEXT NOT NULL DEFAULT '', keyword TEXT NOT NULL,
                match_type TEXT NOT NULL DEFAULT 'contains', reply TEXT NOT NULL,
                item_id TEXT NOT NULL DEFAULT '', enabled INTEGER NOT NULL DEFAULT 1,
                priority INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE item_replies (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                account_id TEXT NOT NULL DEFAULT '', item_id TEXT NOT NULL,
                reply TEXT NOT NULL DEFAULT '', floor_price TEXT NOT NULL DEFAULT '',
                enabled INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL,
                UNIQUE(account_id, item_id))"""
        )
        db.execSQL(
            """CREATE TABLE sensitive_words (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                word TEXT NOT NULL UNIQUE, replacement TEXT NOT NULL DEFAULT '')"""
        )
        db.execSQL(
            """CREATE TABLE ship_templates (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL, content TEXT NOT NULL, created_at INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE notify_channels (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type TEXT NOT NULL, name TEXT NOT NULL DEFAULT '',
                config TEXT NOT NULL DEFAULT '{}', events TEXT NOT NULL DEFAULT '',
                enabled INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE orders (
                order_id TEXT PRIMARY KEY, account_id TEXT NOT NULL DEFAULT '',
                item_id TEXT NOT NULL DEFAULT '', title TEXT NOT NULL DEFAULT '',
                sku TEXT NOT NULL DEFAULT '', quantity INTEGER NOT NULL DEFAULT 1,
                price TEXT NOT NULL DEFAULT '', buyer_id TEXT NOT NULL DEFAULT '',
                buyer_nick TEXT NOT NULL DEFAULT '', chat_id TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT '', source TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX idx_orders_created ON orders(created_at DESC)")
        db.execSQL(
            """CREATE TABLE delivery_tasks (
                order_id TEXT PRIMARY KEY, account_id TEXT NOT NULL DEFAULT '',
                chat_id TEXT NOT NULL DEFAULT '', buyer_id TEXT NOT NULL DEFAULT '',
                buyer_nick TEXT NOT NULL DEFAULT '', item_id TEXT NOT NULL DEFAULT '',
                title TEXT NOT NULL DEFAULT '', sku TEXT NOT NULL DEFAULT '',
                quantity INTEGER NOT NULL DEFAULT 1, source TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT 'PENDING', scheduled_at INTEGER NOT NULL DEFAULT 0,
                attempts INTEGER NOT NULL DEFAULT 0, last_error TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX idx_tasks_due ON delivery_tasks(status, scheduled_at)")
        db.execSQL(
            """CREATE TABLE items (
                item_id TEXT PRIMARY KEY, account_id TEXT NOT NULL DEFAULT '',
                title TEXT NOT NULL DEFAULT '', price TEXT NOT NULL DEFAULT '',
                description TEXT NOT NULL DEFAULT '', pic TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL)"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private fun now() = System.currentTimeMillis()

    // ------------------------------------------------------------ 通用 JSON 查询

    private fun rowJson(c: Cursor): JSONObject {
        val o = JSONObject()
        for (i in 0 until c.columnCount) {
            val name = c.getColumnName(i)
            when (c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> o.put(name, JSONObject.NULL)
                Cursor.FIELD_TYPE_INTEGER -> o.put(name, c.getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> o.put(name, c.getDouble(i))
                else -> o.put(name, c.getString(i))
            }
        }
        return o
    }

    fun queryJson(sql: String, vararg args: String): JSONArray {
        val arr = JSONArray()
        readableDatabase.rawQuery(sql, args).use { c -> while (c.moveToNext()) arr.put(rowJson(c)) }
        return arr
    }

    fun queryOne(sql: String, vararg args: String): JSONObject? {
        readableDatabase.rawQuery(sql, args).use { c -> return if (c.moveToFirst()) rowJson(c) else null }
    }

    fun count(sql: String, vararg args: String): Long =
        readableDatabase.rawQuery(sql, args).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private fun cvFrom(o: JSONObject, cols: List<String>): ContentValues {
        val cv = ContentValues()
        for (k in cols) {
            if (!o.has(k)) continue
            when (val v = o.opt(k)) {
                is Int -> cv.put(k, v)
                is Long -> cv.put(k, v)
                is Boolean -> cv.put(k, if (v) 1 else 0)
                is Number -> cv.put(k, v.toDouble())
                null, JSONObject.NULL -> Unit
                else -> cv.put(k, v.toString())
            }
        }
        return cv
    }

    /** 通用 upsert：id>0 更新，否则插入。只写白名单列，防止前端随意改表。 */
    fun saveRow(table: String, o: JSONObject, cols: List<String>): Long = synchronized(lock) {
        val id = o.optLong("id", 0L)
        val cv = cvFrom(o, cols)
        if (id > 0) {
            writableDatabase.update(table, cv, "id=?", arrayOf(id.toString()))
            id
        } else {
            if (table != "sensitive_words") cv.put("created_at", now())
            writableDatabase.insertWithOnConflict(table, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun deleteRow(table: String, id: Long): Boolean = synchronized(lock) {
        writableDatabase.delete(table, "id=?", arrayOf(id.toString())) > 0
    }

    // ------------------------------------------------------------ 消息中枢

    /**
     * 写入一条消息并刷新会话摘要。重复消息（同会话同时间同内容同方向）返回 false。
     */
    fun saveMessage(
        accountId: String, chatId: String, direction: String, senderId: String, senderNick: String,
        content: String, itemId: String, ts: Long, kind: Int = 0, intent: String = "",
        buyerId: String = "", buyerNick: String = "", countUnread: Boolean = direction == "in"
    ): Boolean = synchronized(lock) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("account_id", accountId); put("chat_id", chatId); put("direction", direction)
            put("sender_id", senderId); put("sender_nick", senderNick); put("content", content)
            put("item_id", itemId); put("ts", ts); put("kind", kind); put("intent", intent)
        }
        val rowId = db.insertWithOnConflict("messages", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        if (rowId == -1L) return false
        upsertConversation(accountId, chatId, buyerId, buyerNick, itemId, content, ts, if (countUnread) 1 else 0)
        true
    }

    fun upsertConversation(
        accountId: String, chatId: String, buyerId: String, buyerNick: String,
        itemId: String, lastMsg: String, ts: Long, unreadInc: Int, avatar: String = "", itemTitle: String = ""
    ) = synchronized(lock) {
        val db = writableDatabase
        db.execSQL(
            "INSERT OR IGNORE INTO conversations(account_id,chat_id,last_ts) VALUES(?,?,0)",
            arrayOf(accountId, chatId)
        )
        db.execSQL(
            """UPDATE conversations SET
                buyer_id = CASE WHEN ?<>'' THEN ? ELSE buyer_id END,
                buyer_nick = CASE WHEN ?<>'' THEN ? ELSE buyer_nick END,
                buyer_avatar = CASE WHEN ?<>'' THEN ? ELSE buyer_avatar END,
                item_id = CASE WHEN ?<>'' THEN ? ELSE item_id END,
                item_title = CASE WHEN ?<>'' THEN ? ELSE item_title END,
                last_msg = CASE WHEN ?>=last_ts AND ?<>'' THEN ? ELSE last_msg END,
                last_ts = MAX(last_ts, ?),
                unread = unread + ?
              WHERE account_id=? AND chat_id=?""",
            arrayOf<Any>(
                buyerId, buyerId, buyerNick, buyerNick, avatar, avatar, itemId, itemId,
                itemTitle, itemTitle, ts, lastMsg, lastMsg, ts, unreadInc, accountId, chatId
            )
        )
    }

    fun markRead(accountId: String, chatId: String) = synchronized(lock) {
        writableDatabase.execSQL(
            "UPDATE conversations SET unread=0 WHERE account_id=? AND chat_id=?", arrayOf(accountId, chatId)
        )
    }

    fun setPaused(accountId: String, chatId: String, until: Long) = synchronized(lock) {
        writableDatabase.execSQL(
            "INSERT OR IGNORE INTO conversations(account_id,chat_id,last_ts) VALUES(?,?,0)", arrayOf(accountId, chatId)
        )
        writableDatabase.execSQL(
            "UPDATE conversations SET paused_until=? WHERE account_id=? AND chat_id=?",
            arrayOf<Any>(until, accountId, chatId)
        )
    }

    fun pausedUntil(accountId: String, chatId: String): Long =
        count("SELECT paused_until FROM conversations WHERE account_id=? AND chat_id=?", accountId, chatId)

    fun conversation(accountId: String, chatId: String): JSONObject? =
        queryOne("SELECT * FROM conversations WHERE account_id=? AND chat_id=?", accountId, chatId)

    /** 找该买家已有的会话（用于订单轮询发现的订单把卡密发到 IM）。 */
    fun chatIdForBuyer(accountId: String, buyerId: String): String {
        if (buyerId.isBlank()) return ""
        return queryOne(
            "SELECT chat_id FROM conversations WHERE account_id=? AND buyer_id=? ORDER BY last_ts DESC LIMIT 1",
            accountId, buyerId
        )?.optString("chat_id").orEmpty()
    }

    fun recentMessages(accountId: String, chatId: String, limit: Int): JSONArray {
        val arr = queryJson(
            "SELECT * FROM messages WHERE account_id=? AND chat_id=? ORDER BY ts DESC, id DESC LIMIT ?",
            accountId, chatId, limit.toString()
        )
        val out = JSONArray()
        for (i in arr.length() - 1 downTo 0) out.put(arr.get(i))
        return out
    }

    fun bargainCount(accountId: String, chatId: String): Long =
        count(
            "SELECT COUNT(*) FROM messages WHERE account_id=? AND chat_id=? AND direction='in' AND intent='price'",
            accountId, chatId
        )

    fun hasAutoReplied(accountId: String, chatId: String, kind: Int): Boolean =
        count(
            "SELECT COUNT(*) FROM messages WHERE account_id=? AND chat_id=? AND direction='out' AND kind=?",
            accountId, chatId, kind.toString()
        ) > 0

    // ------------------------------------------------------------ 自动回复

    data class KeywordRule(
        val id: Long, val accountId: String, val keyword: String, val matchType: String,
        val reply: String, val itemId: String
    )

    fun enabledKeywordRules(accountId: String): List<KeywordRule> {
        val out = ArrayList<KeywordRule>()
        readableDatabase.rawQuery(
            """SELECT id,account_id,keyword,match_type,reply,item_id FROM keyword_rules
               WHERE enabled=1 AND (account_id='' OR account_id=?)
               ORDER BY CASE WHEN item_id<>'' THEN 0 ELSE 1 END, priority DESC, id ASC""",
            arrayOf(accountId)
        ).use { c ->
            while (c.moveToNext()) out.add(
                KeywordRule(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5))
            )
        }
        return out
    }

    fun itemReply(accountId: String, itemId: String): JSONObject? {
        if (itemId.isBlank()) return null
        return queryOne(
            """SELECT * FROM item_replies WHERE item_id=? AND (account_id='' OR account_id=?)
               ORDER BY CASE WHEN account_id=? THEN 0 ELSE 1 END LIMIT 1""",
            itemId, accountId, accountId
        )
    }

    fun sensitiveWords(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        readableDatabase.rawQuery("SELECT word, replacement FROM sensitive_words", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0) to c.getString(1))
        }
        return out
    }

    fun template(id: Long): String? =
        if (id <= 0) null else queryOne("SELECT content FROM ship_templates WHERE id=?", id.toString())?.optString("content")

    // ------------------------------------------------------------ 商品缓存

    fun upsertItem(itemId: String, accountId: String, title: String, price: String, desc: String, pic: String, status: String) =
        synchronized(lock) {
            if (itemId.isBlank()) return@synchronized
            val db = writableDatabase
            db.execSQL("INSERT OR IGNORE INTO items(item_id, updated_at) VALUES(?, ?)", arrayOf<Any>(itemId, now()))
            db.execSQL(
                """UPDATE items SET
                   account_id = CASE WHEN ?<>'' THEN ? ELSE account_id END,
                   title = CASE WHEN ?<>'' THEN ? ELSE title END,
                   price = CASE WHEN ?<>'' THEN ? ELSE price END,
                   description = CASE WHEN ?<>'' THEN ? ELSE description END,
                   pic = CASE WHEN ?<>'' THEN ? ELSE pic END,
                   status = CASE WHEN ?<>'' THEN ? ELSE status END,
                   updated_at = ? WHERE item_id=?""",
                arrayOf<Any>(accountId, accountId, title, title, price, price, desc, desc, pic, pic, status, status, now(), itemId)
            )
        }

    fun item(itemId: String): JSONObject? =
        if (itemId.isBlank()) null else queryOne("SELECT * FROM items WHERE item_id=?", itemId)

    // ------------------------------------------------------------ 订单

    fun upsertOrder(
        orderId: String, accountId: String, itemId: String, title: String, sku: String, qty: Int,
        price: String, buyerId: String, buyerNick: String, chatId: String, status: String, source: String
    ): Boolean = synchronized(lock) {
        if (orderId.isBlank()) return false
        val db = writableDatabase
        val ts = now()
        val inserted = db.insertWithOnConflict("orders", null, ContentValues().apply {
            put("order_id", orderId); put("created_at", ts); put("updated_at", ts); put("source", source)
        }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        db.execSQL(
            """UPDATE orders SET
               account_id = CASE WHEN ?<>'' THEN ? ELSE account_id END,
               item_id = CASE WHEN ?<>'' THEN ? ELSE item_id END,
               title = CASE WHEN ?<>'' THEN ? ELSE title END,
               sku = CASE WHEN ?<>'' THEN ? ELSE sku END,
               quantity = CASE WHEN ?>1 THEN ? ELSE quantity END,
               price = CASE WHEN ?<>'' THEN ? ELSE price END,
               buyer_id = CASE WHEN ?<>'' THEN ? ELSE buyer_id END,
               buyer_nick = CASE WHEN ?<>'' THEN ? ELSE buyer_nick END,
               chat_id = CASE WHEN ?<>'' THEN ? ELSE chat_id END,
               status = CASE WHEN ?<>'' THEN ? ELSE status END,
               updated_at = ? WHERE order_id=?""",
            arrayOf<Any>(
                accountId, accountId, itemId, itemId, title, title, sku, sku, qty, qty, price, price,
                buyerId, buyerId, buyerNick, buyerNick, chatId, chatId, status, status, ts, orderId
            )
        )
        inserted
    }

    fun setOrderStatus(orderId: String, status: String) = synchronized(lock) {
        writableDatabase.execSQL(
            "UPDATE orders SET status=?, updated_at=? WHERE order_id=?", arrayOf<Any>(status, now(), orderId)
        )
    }

    // ------------------------------------------------------------ 发货任务（跨 IM / 轮询的幂等闸门）

    data class Task(
        val orderId: String, val accountId: String, val chatId: String, val buyerId: String,
        val buyerNick: String, val itemId: String, val title: String, val sku: String,
        val quantity: Int, val source: String, val status: String, val scheduledAt: Long, val attempts: Int,
        val createdAt: Long
    )

    private fun taskFrom(c: Cursor) = Task(
        c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5),
        c.getString(6), c.getString(7), c.getInt(8), c.getString(9), c.getString(10), c.getLong(11), c.getInt(12),
        c.getLong(13)
    )

    private val taskCols =
        "order_id,account_id,chat_id,buyer_id,buyer_nick,item_id,title,sku,quantity,source,status,scheduled_at,attempts,created_at"

    fun task(orderId: String): Task? =
        readableDatabase.rawQuery("SELECT $taskCols FROM delivery_tasks WHERE order_id=?", arrayOf(orderId))
            .use { if (it.moveToFirst()) taskFrom(it) else null }

    /**
     * 登记发货任务。INSERT OR IGNORE 保证同一订单无论被 IM 还是轮询发现多少次，都只有一行；
     * 已存在时只补齐缺失字段（IM 带来会话/买家 ID，轮询带来标题/规格/件数）。
     * @return true 表示首次登记
     */
    fun enqueueTask(
        orderId: String, accountId: String, chatId: String, buyerId: String, buyerNick: String,
        itemId: String, title: String, sku: String, qty: Int, source: String, scheduledAt: Long
    ): Boolean = synchronized(lock) {
        val db = writableDatabase
        val ts = now()
        val inserted = db.insertWithOnConflict("delivery_tasks", null, ContentValues().apply {
            put("order_id", orderId); put("source", source); put("status", "PENDING")
            put("scheduled_at", scheduledAt); put("created_at", ts); put("updated_at", ts)
        }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        db.execSQL(
            """UPDATE delivery_tasks SET
               account_id = CASE WHEN account_id='' THEN ? ELSE account_id END,
               chat_id = CASE WHEN ?<>'' THEN ? ELSE chat_id END,
               buyer_id = CASE WHEN ?<>'' THEN ? ELSE buyer_id END,
               buyer_nick = CASE WHEN ?<>'' THEN ? ELSE buyer_nick END,
               item_id = CASE WHEN ?<>'' THEN ? ELSE item_id END,
               title = CASE WHEN ?<>'' THEN ? ELSE title END,
               sku = CASE WHEN ?<>'' THEN ? ELSE sku END,
               quantity = CASE WHEN ?>1 THEN ? ELSE quantity END,
               updated_at = ? WHERE order_id=?""",
            arrayOf<Any>(accountId, chatId, chatId, buyerId, buyerId, buyerNick, buyerNick, itemId, itemId,
                title, title, sku, sku, qty, qty, ts, orderId)
        )
        inserted
    }

    /** 到期待处理的任务。 */
    fun dueTasks(nowTs: Long): List<Task> {
        val out = ArrayList<Task>()
        readableDatabase.rawQuery(
            "SELECT $taskCols FROM delivery_tasks WHERE status='PENDING' AND scheduled_at<=? ORDER BY scheduled_at LIMIT 20",
            arrayOf(nowTs.toString())
        ).use { c -> while (c.moveToNext()) out.add(taskFrom(c)) }
        return out
    }

    /** CAS 抢占任务：只有 fromStatus 匹配时才能改成 toStatus。 */
    fun casTask(orderId: String, fromStatus: String, toStatus: String, error: String = ""): Boolean = synchronized(lock) {
        val cv = ContentValues().apply {
            put("status", toStatus); put("updated_at", now()); put("last_error", error)
        }
        if (toStatus == "RUNNING") {
            writableDatabase.execSQL("UPDATE delivery_tasks SET attempts=attempts+1 WHERE order_id=? AND status=?", arrayOf(orderId, fromStatus))
        }
        writableDatabase.update("delivery_tasks", cv, "order_id=? AND status=?", arrayOf(orderId, fromStatus)) == 1
    }

    fun rescheduleTask(orderId: String, fromStatus: String, at: Long, error: String = ""): Boolean = synchronized(lock) {
        val cv = ContentValues().apply {
            put("status", "PENDING"); put("scheduled_at", at); put("updated_at", now()); put("last_error", error)
        }
        writableDatabase.update("delivery_tasks", cv, "order_id=? AND status=?", arrayOf(orderId, fromStatus)) == 1
    }

    /** 进程被杀时 RUNNING 的任务回到 PENDING（发货链路本身仍有卡密 CAS + 发货流水兜底幂等）。 */
    fun recoverRunningTasks() = synchronized(lock) {
        writableDatabase.execSQL(
            "UPDATE delivery_tasks SET status='PENDING' WHERE status='RUNNING' AND updated_at<?",
            arrayOf<Any>(now() - 5 * 60_000L)
        )
    }
}
