package com.xianyu.copilot

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import java.net.Socket

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val handler = Handler(Looper.getMainLooper())

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
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    // 外部链接（如闲鱼网页版登录、帮助等）可在外置浏览器打开
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
