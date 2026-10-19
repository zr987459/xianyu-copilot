package com.xianyu.copilot

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import java.net.Socket

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val handler = Handler(Looper.getMainLooper())

    inner class AndroidBridge {
        @JavascriptInterface
        fun openWebLogin() {
            handler.post {
                showWebLoginDialog()
            }
        }

        /**
         * 访问令牌只交给 App 自己的 WebView，不再写进 HTML。
         * 否则本机任意 App GET / 就能把令牌读走，鉴权形同虚设。
         */
        @JavascriptInterface
        fun getApiToken(): String = LocalServer.current()?.apiToken.orEmpty()

        /** 安全验证：在 App 内打开闲鱼的风控验证页，完成后复用网页登录的抓 Cookie 逻辑。 */
        @JavascriptInterface
        fun openVerification(url: String) {
            handler.post {
                showWebLoginDialog(overrideUrl = url.ifBlank { null })
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 启动本地原生前台守护服务（同时常驻本地 9005 服务端与后台发货监听）
        DaemonService.start(this)

        // 扫码登录需要 Context 才能落库
        QrLoginService.init(this)

        // Android 13+ 请求通知栏权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                useWideViewPort = true
                loadWithOverviewMode = true
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            clearCache(true)
            addJavascriptInterface(AndroidBridge(), "AndroidBridge")
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (url.startsWith("https://") || url.startsWith("http://")) {
                        if (!url.contains("127.0.0.1:9005") && !url.contains("localhost:9005")) {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            startActivity(intent)
                            return true
                        }
                    }
                    return false
                }
            }
        }

        setContentView(webView)

        // 等待本地 9005 服务准备就绪后载入原生同源界面
        waitForServerAndLoad()
    }

    @SuppressLint("SetJavaScriptEnabled")
    /**
     * App 内网页登录。
     *
     * @param overrideUrl 非空时直接打开该地址（用于风控验证页），验证完成后
     *                    复用同一套抓 Cookie + saveLogin 逻辑。
     */
    private fun showWebLoginDialog(overrideUrl: String? = null) {
        val dialog = Dialog(this, android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen)
        val root = FrameLayout(this)
        root.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        val loginView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                userAgentString =
                    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            }
        }

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)

        // 修复：onPageFinished 每次导航都会触发，必须只跑一次，
        // 否则会反复起线程写库、反复弹关闭。
        val handled = java.util.concurrent.atomic.AtomicBoolean(false)

        fun collectAndSave() {
            // 分域收集后按名字去重，goofish 域优先
            val cGoofish = cm.getCookie("https://www.goofish.com") ?: ""
            val cTb = cm.getCookie("https://login.taobao.com") ?: ""
            val cMtb = cm.getCookie("https://m.taobao.com") ?: ""
            val merged = CookieNormalizer.normalize(cGoofish, cTb, cMtb)

            // 未登录就什么也不做（等用户继续操作）
            if (!CookieNormalizer.hasCoreTokens(merged)) return
            if (!handled.compareAndSet(false, true)) return

            Thread {
                // 统一走 AccountManager（去重、令牌自愈、校验、激活策略）
                val result = AccountManager.saveLogin(
                    this@MainActivity,
                    merged,
                    AccountManager.Source.WEB
                )
                handler.post {
                    if (result.ok) {
                        try { dialog.dismiss() } catch (_: Exception) {}
                        notifyFrontendLoginSuccess(result.nick)
                    } else {
                        handled.set(false)
                        toast("登录未完成：${result.message}")
                    }
                }
            }.start()
        }

        loginView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (handled.get()) return
                val u = url.orEmpty()
                // 已经是闲鱼/淘宝域就尝试抓一次；抓不到也没关系，下面的轮询会兜住
                if (isLoginDone(u)) collectAndSave()
            }
        }

        // 修复：闲鱼网页登录常在弹层/iframe 内完成，不一定触发 onPageFinished，
        // 所以每 1.5 秒主动轮询一次 CookieManager，拿到直接落库。
        val poller = object : Runnable {
            override fun run() {
                if (handled.get()) return
                collectAndSave()
                if (!handled.get() && dialog.isShowing) handler.postDelayed(this, 1500L)
            }
        }

        dialog.setOnDismissListener { handler.removeCallbacks(poller) }

        root.addView(
            loginView,
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        // 浮动关闭按钮
        val closeBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(0x88000000.toInt())
            setPadding(24, 24, 24, 24)
            setOnClickListener { dialog.dismiss() }
        }
        root.addView(closeBtn, FrameLayout.LayoutParams(120, 120).apply { setMargins(32, 64, 0, 0) })

        dialog.setContentView(root)
        dialog.show()

        val target = overrideUrl?.takeIf { it.isNotBlank() } ?: "https://www.goofish.com/"
        if (overrideUrl.isNullOrBlank()) {
            // 修复：removeAllCookies 是异步的，必须等清完再 loadUrl，
            // 否则旧账号 cookie 可能还没清掉就已经开始导航。
            cm.removeAllCookies {
                cm.flush()
                handler.post { loginView.loadUrl(target) }
            }
        } else {
            // 验证页不清 Cookie：需要沿用当前扫码会话的登录态
            loginView.loadUrl(target)
        }

        handler.postDelayed(poller, 1500L)
    }

    /** 判断当前 URL 是否已完成登录（已跳出登录页并落到闲鱼/淘宝首页）。 */
    private fun isLoginDone(url: String): Boolean {
        if (url.isBlank()) return false
        val isLoginPage = url.contains("login.") ||
            url.contains("/login") ||
            url.contains("passport.")
        if (isLoginPage) return false
        return url.contains("goofish.com") || url.contains("taobao.com")
    }

    /**
     * 通知前端登录成功。
     * 修复：旧代码调的 window.refreshAccounts 在前端根本不存在，
     * 这里改为直接调前端真实的 loadAccounts() / loadStats()，并兼容自定义事件。
     */
    private fun notifyFrontendLoginSuccess(nick: String) {
        val safeNick = nick.replace("\\", "\\\\").replace("'", "\\'")
        val js = """
            (function(){
              try {
                if (typeof loadAccounts === 'function') loadAccounts();
                if (typeof loadStats === 'function') loadStats();
                window.dispatchEvent(new CustomEvent('xianyu-login-success', {detail:{nick:'$safeNick'}}));
              } catch (e) { console.warn('refresh failed', e); }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /** 轻量 toast（不依赖布局文件）。 */
    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun waitForServerAndLoad() {
        Thread {
            for (i in 1..30) {
                try {
                    val s = Socket("127.0.0.1", 9005)
                    s.close()
                    break
                } catch (e: Exception) {
                    Thread.sleep(150)
                }
            }
            handler.post {
                webView.loadUrl("http://127.0.0.1:9005/")
            }
        }.start()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (webView.canGoBack()) {
                webView.goBack()
                return true
            }
            // 返回键切后台守护，不销毁 Activity
            moveTaskToBack(true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
    }
}
