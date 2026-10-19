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

        val cookie = db.getActiveCookie()
        if (cookie.isBlank()) return@withLock

        val orders = try {
            MtopClient.querySoldOrders(cookie)
        } catch (e: Exception) {
            Log.w(TAG, "querySoldOrders failed", e)
            return@withLock
        }

        if (orders.isEmpty()) return@withLock

        var handled = 0
        for (order in orders) {
            runCatching {
                val rule = db.matchRule(order.title, order.sku) ?: return@runCatching
                val card = if (rule.usePool) {
                    db.claimCardAtomic(rule.keyword, rule.sku, order.orderId)
                } else null

                val content = buildString {
                    if (!card.isNullOrBlank()) append("🎫 卡密: $card\n")
                    if (rule.fixedContent.isNotBlank()) append(rule.fixedContent)
                }.trim()

                val (ok, msg) = MtopClient.confirmDummyShipping(cookie, order.orderId, rule.tradeText)
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
