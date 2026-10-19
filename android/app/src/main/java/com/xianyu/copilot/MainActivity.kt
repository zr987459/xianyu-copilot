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
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 启动本地原生前台守护服务（同时常驻本地 9005 服务端与后台发货监听）
        DaemonService.start(this)

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
            addJavascriptInterface(AndroidBridge(), "androidBridge")
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
    private fun showWebLoginDialog() {
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
                userAgentString = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            }
        }

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)

        loginView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val cGoofish = cm.getCookie("https://www.goofish.com") ?: ""
                val cTb = cm.getCookie("https://login.taobao.com") ?: ""
                val cMtb = cm.getCookie("https://m.taobao.com") ?: ""
                val allCookie = "$cGoofish; $cTb; $cMtb"

                if (allCookie.contains("cookie2=") && (allCookie.contains("_m_h5_tk=") || allCookie.contains("unb="))) {
                    Thread {
                        val (ok, testNick) = MtopClient.testCookie(allCookie)
                        val map = MtopSigner.parseCookies(allCookie)
                        val unb = map["unb"] ?: map["tracknick"] ?: ("uid_" + System.currentTimeMillis().toString().takeLast(8))
                        val finalNick = if (ok && testNick.isNotBlank()) testNick else (map["tracknick"] ?: unb)

                        CardDatabase.get(this@MainActivity).addOrUpdateAccount(
                            userId = unb,
                            nick = finalNick,
                            avatar = "",
                            cookie = allCookie,
                            activate = true
                        )

                        handler.post {
                            try { dialog.dismiss() } catch (_: Exception) {}
                            webView.evaluateJavascript("window.refreshAccounts && window.refreshAccounts();", null)
                        }
                    }.start()
                }
            }
        }

        loginView.loadUrl("https://login.m.taobao.com/login.htm")
        root.addView(loginView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        // 浮动关闭按钮
        val closeBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(0x88000000.toInt())
            setPadding(24, 24, 24, 24)
            setOnClickListener { dialog.dismiss() }
        }
        val btnParams = FrameLayout.LayoutParams(120, 120).apply {
            setMargins(32, 64, 0, 0)
        }
        root.addView(closeBtn, btnParams)

        dialog.setContentView(root)
        dialog.show()
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
