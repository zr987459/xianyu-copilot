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
    @Volatile private var riskAlerted: Boolean = false

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
        // v1.5.0：消息中枢（IM 长连接）与发货任务循环
        ImManager.init(applicationContext)
        runCatching { ImManager.sync(applicationContext) }
        startLoop()
        startTaskLoop()
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
            val nick = db.getActiveAccount()?.nick.orEmpty()
            Notifier.notify(applicationContext, Notifier.Event.ACCOUNT_EXPIRED, "账号已失效",
                "账号 ${nick.ifBlank { unb }} 登录态失效，自动发货已停止，请打开 App 重新登录",
                dedupeKey = unb, throttleMs = 3600_000L)
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

        // 每次调用的真实结果都写回该账号的健康状态，失效提醒才有依据。
        // 关键：准备写 EXPIRED 前必须用 loginuser.get 探针二次确认，
        // 否则某个未验证过的接口返回会话类错误码时，会把好账号误标成失效。
        val recordResult: (Boolean, String) -> Unit = { ok, ret ->
            if (unb.isNotBlank()) {
                if (ok) {
                    db.recordAccountCheck(unb, true, ret)
                    // 恢复正常后把风控提示收回，避免通知栏一直挂着告警
                    if (riskAlerted) {
                        riskAlerted = false
                        updateNotification("闲鱼管家正在守护中 · 自动发货就绪")
                    }
                } else if (MtopClient.isSessionDead(ret)) {
                    val probe = MtopClient.testCookieDetailed(db.getAccountCookie(unb).ifBlank { cookie })
                    // 探针刷新出的新 Cookie 要落库，否则每次都要重新握手
                    if (probe.cookie.isNotBlank() && probe.cookie != cookie) {
                        AccountManager.persistRefreshedCookie(applicationContext, probe.cookie)
                    }
                    if (probe.valid) {
                        // 探针通过 ⇒ 账号是好的，只记诊断，绝不翻转状态
                        db.recordAccountCheck(unb, true, "接口报 ${ret.take(40)}，但登录态探针正常")
                    } else if (MtopClient.isSessionDead(probe.message)) {
                        // 探针也确认失效，才允许写 EXPIRED
                        db.recordAccountCheck(unb, false, ret)
                    } else {
                        // 探针自身遭遇网络抖动/风控：传 false，recordAccountCheck 在没有失效标记时
                        // 本来就不会动 status，只记诊断；传 true 会把真失效账号翻回在线。
                        db.recordAccountCheck(unb, false, "探针未确认失效(${probe.message.take(30)})")
                    }
                } else {
                    db.recordAccountCheck(unb, false, ret)
                    if (MtopClient.isRiskControlPublic(ret)) {
                        riskAlerted = true
                        updateNotification("⚠️ 触发闲鱼风控，自动发货稍后自动重试")
                    }
                }
            }
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
            if (unb.isNotBlank()) recordResult(false, e.message ?: "异常")
            notifyIfCookieBroken(db)
            return@withLock
        }

        // 修复：querySoldOrders 期间可能已刷新令牌，后面的发货必须用最新 cookie，
        // 否则每单都要先撞一次过期再重试。
        if (unb.isNotBlank()) {
            db.getAccountCookie(unb).takeIf { it.isNotBlank() }?.let { cookie = it }
        }

        notifyIfCookieBroken(db)

        // v1.5.0：订单交给统一发货引擎（与 IM 付款消息共用幂等任务表，绝不重复发货）
        for (order in orders) {
            runCatching {
                if (db.hasOrderDelivered(order.orderId)) return@runCatching
                DeliveryEngine.enqueue(
                    applicationContext, order.orderId, unb, "POLL",
                    buyerId = order.buyerId, buyerNick = order.buyerNick, itemId = order.itemId,
                    title = order.title, sku = order.sku, quantity = order.quantity, price = order.price,
                    processNow = false
                )
            }.onFailure { Log.w(TAG, "enqueue order failed: ${order.orderId}", it) }
        }
        val handled = DeliveryEngine.processDue(applicationContext, DeliveryEngine.HealthHook(unb, recordResult))

        if (handled > 0) {
            updateNotification("🎉 本轮自动发货 $handled 单")
        }
    }

    /** 每 5 秒处理到期的发货任务（延时发货 / IM 触发），并同步 IM 连接与每日擦亮。 */
    private var taskJob: Job? = null

    private fun startTaskLoop() {
        if (taskJob?.isActive == true) return
        taskJob = scope.launch {
            var n = 0
            while (isActive) {
                delay(5_000L)
                val ctx = applicationContext
                val db = CardDatabase.get(ctx)
                if (db.getSetting("daemon_enabled", "true") != "true") continue
                runCatching { DeliveryEngine.processDue(ctx, null) }.onFailure { Log.w(TAG, "task loop", it) }
                if (++n % 6 == 0) {
                    runCatching { ImManager.sync(ctx) }
                    runCatching { dailyPolish(db) }
                }
            }
        }
    }

    /** 每日定时擦亮（设置 auto_polish_time=HH:mm，留空关闭）。参考 automation_loop.daily_polish。 */
    private fun dailyPolish(db: CardDatabase) {
        val target = db.getSetting("auto_polish_time", "").trim()
        if (!Regex("\\d{2}:\\d{2}").matches(target)) return
        val now = java.util.Calendar.getInstance()
        val hhmm = String.format(java.util.Locale.US, "%02d:%02d", now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE))
        val today = String.format(java.util.Locale.US, "%tF", now)
        if (hhmm < target || db.getSetting("auto_polish_last", "") == today) return
        db.saveSetting("auto_polish_last", today)
        val cookie = db.getActiveCookie()
        if (cookie.isBlank()) return
        scope.launch {
            val res = MtopClient.queryOnSaleItems(cookie, onCookieRefreshed = {
                AccountManager.persistRefreshedCookie(applicationContext, it)
            })
            var ok = 0
            for (it in res.items) {
                if (MtopClient.polishItem(db.getActiveCookie().ifBlank { cookie }, it.id).first) ok++
                delay(3_000L)
            }
            db.saveSetting("auto_polish_result", "$today 擦亮 $ok/${res.items.size}")
        }
    }

    override fun onDestroy() {
        isRunning = false
        loopJob?.cancel()
        taskJob?.cancel()
        runCatching { ImManager.stopAll() }
        scope.cancel()
        localServer?.stop()
        localServer = null
        runCatching { stopForeground(true) }
        super.onDestroy()
    }
}
