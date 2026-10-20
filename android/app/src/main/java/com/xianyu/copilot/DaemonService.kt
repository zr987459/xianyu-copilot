package com.xianyu.copilot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DaemonService : Service() {

    companion object {
        private const val TAG = "DaemonService"
        private const val CHANNEL_ID = "xianyu_copilot_daemon"
        private const val NOTIF_ID = 1001
        private const val DEFAULT_INTERVAL = 15_000L
        private const val COOKIE_CHECK_INTERVAL_MS = 5 * 60 * 1000L

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, DaemonService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, DaemonService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "stop failed", e)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private val scanMutex = Mutex()
    private var localServer: LocalServer? = null

    /** Cookie 健康检查节流（每 5 分钟最多提醒一次）。 */
    @Volatile private var lastCookieCheckAt: Long = 0L
    @Volatile private var cookieAlerted: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning) return START_STICKY
        isRunning = true
        try {
            startForeground(NOTIF_ID, buildNotification("闲鱼管家正在守护中 · 自动发货就绪"))
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        ensureLocalServer()
        // 扫码登录需要 ApplicationContext 才能落库
        QrLoginService.init(applicationContext)
        startLoop()
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "闲鱼管家后台守护", NotificationManager.IMPORTANCE_LOW).apply {
                        setShowBadge(false)
                        enableVibration(false)
                    }
                )
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("闲鱼管家")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.notify(NOTIF_ID, buildNotification(text))
        } catch (e: Exception) {
            Log.e(TAG, "updateNotification failed", e)
        }
    }

    private fun ensureLocalServer() {
        if (localServer == null) {
            localServer = LocalServer(applicationContext).also { it.start() }
        }
    }

    /**
     * Cookie 失效时主动提醒。
     * 修复：旧版守护层 cookie 过期只是静默 return，用户完全不知道已停止发货。
     */
    /**
     * Cookie 失效时主动提醒。
     *
     * 修复：旧版读的是全局 last_check（只在登录成功时写过一次 success=true），
     * 所以永远不会报警。现在按当前激活账号读取它自己的校验状态。
     */
    private fun notifyIfCookieBroken(db: CardDatabase) {
        val now = System.currentTimeMillis()
        if (now - lastCookieCheckAt < COOKIE_CHECK_INTERVAL_MS) return
        lastCookieCheckAt = now

        val active = db.getActiveCookie()
        val unb = MtopSigner.parseCookies(active)["unb"].orEmpty()
        if (unb.isBlank()) return

        val (checkedAt, ok, detail) = db.getAccountCheck(unb)
        // 从未校验过（刚升级/刚添加）不误报
        if (checkedAt == 0L) return
        // 直接按账号 status 判断：status 现在只在真失效时才会被写成 EXPIRED，
        // 不再依赖会被后续错误覆盖的 detail 文本。
        if (!ok) {
            val status = db.getAccountStatus(unb)
            if (status != "EXPIRED") return
        }

        if (ok) {
            if (cookieAlerted) {
                cookieAlerted = false
                updateNotification("闲鱼管家正在守护中 · 自动发货就绪")
            }
            return
        }
        if (!cookieAlerted) {
            cookieAlerted = true
            updateNotification("⚠️ 账号已失效，请打开 App 重新登录")
            Log.w(TAG, "active account $unb invalid: $detail")
        }
    }

    private fun startLoop() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            while (isActive) {
                val db = CardDatabase.get(applicationContext)
                val interval = runCatching {
                    db.getSetting("scan_interval_ms", DEFAULT_INTERVAL.toString())
                        .toLongOrNull()?.coerceIn(5_000L, 600_000L) ?: DEFAULT_INTERVAL
                }.getOrDefault(DEFAULT_INTERVAL)

                delay(interval)
                runCatching { tick() }
                    .onFailure { Log.e(TAG, "tick error", it) }
            }
        }
    }

    private suspend fun tick() = scanMutex.withLock {
        val db = CardDatabase.get(applicationContext)
        val enabled = db.getSetting("daemon_enabled", "true") == "true"
        if (!enabled) return@withLock

        var cookie = db.getActiveCookie()
        if (cookie.isBlank()) {
            notifyIfCookieBroken(db)
            return@withLock
        }
        val unb = MtopSigner.parseCookies(cookie)["unb"].orEmpty()

        // 每次调用的真实结果都写回该账号的健康状态，失效提醒才有依据
        val recordResult: (Boolean, String) -> Unit = { ok, ret ->
            if (unb.isNotBlank()) db.recordAccountCheck(unb, ok, ret)
        }

        val orders = try {
            // 令牌过期时自动刷新 _m_h5_tk 并回写数据库，避免「跑几小时就失效」
            MtopClient.querySoldOrders(
                cookie,
                onCookieRefreshed = { refreshed ->
                    AccountManager.persistRefreshedCookie(applicationContext, refreshed)
                },
                onResult = recordResult
            )
        } catch (e: Exception) {
            Log.w(TAG, "querySoldOrders failed", e)
            if (unb.isNotBlank()) db.recordAccountCheck(unb, false, e.message ?: "异常")
            notifyIfCookieBroken(db)
            return@withLock
        }

        // 修复：querySoldOrders 期间可能已刷新令牌，后面的发货必须用最新 cookie，
        // 否则每单都要先撞一次过期再重试。
        if (unb.isNotBlank()) {
            db.getAccountCookie(unb).takeIf { it.isNotBlank() }?.let { cookie = it }
        }

        notifyIfCookieBroken(db)

        if (orders.isEmpty()) return@withLock

        var handled = 0
        for (order in orders) {
            runCatching {
                // 幂等：已经发过货的订单直接跳过，绝不重复发货
                if (db.hasOrderDelivered(order.orderId)) return@runCatching

                val rule = db.matchRule(order.title, order.sku) ?: return@runCatching
                val cards = if (rule.usePool) {
                    db.claimCardsAtomic(rule.keyword, rule.sku, order.orderId, order.quantity)
                } else emptyList()

                val content = buildString {
                    if (cards.isNotEmpty()) {
                        append("🎫 自动发货卡密 [${cards.size}件]:\n")
                        cards.forEachIndexed { i, c -> append("${i + 1}. $c\n") }
                    }
                    if (rule.fixedContent.isNotBlank()) {
                        if (cards.isNotEmpty()) append("\n")
                        append(rule.fixedContent)
                    }
                }.trim()

                val (ok, msg) = MtopClient.confirmDummyShipping(
                    cookie, order.orderId, rule.tradeText,
                    onCookieRefreshed = { refreshed ->
                        AccountManager.persistRefreshedCookie(applicationContext, refreshed)
                    },
                    onResult = recordResult
                )
                db.recordLog(order.orderId, rule.keyword, content, if (ok) "自动发货完成" else "发货反馈: $msg")
                handled++
            }.onFailure {
                Log.w(TAG, "handle order failed: ${order.orderId}", it)
            }
        }

        if (handled > 0) {
            updateNotification("🎉 今日已自动发货 $handled 单")
        }
    }

    override fun onDestroy() {
        isRunning = false
        loopJob?.cancel()
        scope.cancel()
        localServer?.stop()
        localServer = null
        runCatching { stopForeground(true) }
        super.onDestroy()
    }
}
