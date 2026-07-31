// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.annotation.SuppressLint
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 内置浏览器 - 智能代理检测
 *
 * - 全局模式（VPN）开启时 → 不走代理，流量自动走 Tailscale 隧道
 * - 应用代理模式（VPN 关闭）→ 走 127.0.0.1:8080 代理
 */
class BuiltInBrowserActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var urlEditText: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView

    companion object {
        private const val TAG = "BuiltInBrowser"
        private const val PROXY_HOST = "127.0.0.1"
        private const val PROXY_PORT = 8080
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val vpnActive = isVpnActive()
        val useProxy = !vpnActive  // VPN 关 → 走代理; VPN 开 → 不走代理

        // 创建布局
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 状态栏
        statusText = TextView(this).apply {
            setPadding(8, 4, 8, 4)
            textSize = 12f
            text = if (vpnActive) {
                "🟢 全局模式 - 流量自动走 Tailscale 隧道"
            } else {
                "🔵 应用代理模式 - 通过 SOCKS5/HTTP 代理访问"
            }
        }

        // URL 栏
        val urlBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 8)
        }

        urlEditText = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            hint = "http://100.x.x.x/"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }

        val goButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_play)
            setOnClickListener {
                val url = urlEditText.text.toString()
                if (url.isNotBlank()) {
                    loadUrl(url)
                }
            }
        }

        val backButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_rew)
            setOnClickListener {
                if (webView.canGoBack()) {
                    webView.goBack()
                }
            }
        }

        val forwardButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_ff)
            setOnClickListener {
                if (webView.canGoForward()) {
                    webView.goForward()
                }
            }
        }

        urlBar.addView(backButton)
        urlBar.addView(forwardButton)
        urlBar.addView(urlEditText)
        urlBar.addView(goButton)

        // 进度条
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                4
            )
            max = 100
        }

        // WebView
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
        }

        // 配置代理
        configureProxy(webView, useProxy)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                urlEditText.setText(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = android.view.View.GONE
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress < 100) {
                    progressBar.visibility = android.view.View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = android.view.View.GONE
                }
            }
        }

        layout.addView(statusText)
        layout.addView(urlBar)
        layout.addView(progressBar)
        layout.addView(webView)

        setContentView(layout)

        // 加载欢迎页
        val initialUrl = intent.getStringExtra("url")
        if (initialUrl != null) {
            urlEditText.setText(initialUrl)
            loadUrl(initialUrl)
        } else {
            showWelcomePage(useProxy)
        }
    }

    /**
     * 配置 WebView 代理
     * - useProxy=true（应用代理模式）→ 设置系统代理 127.0.0.1:8080
     * - useProxy=false（全局VPN模式）→ 清除代理，流量直接走隧道
     */
    @Suppress("DEPRECATION")
    private fun configureProxy(webView: WebView, useProxy: Boolean) {
        if (useProxy) {
            // 应用代理模式 - 通过 HTTP 代理访问
            try {
                System.setProperty("http.proxyHost", PROXY_HOST)
                System.setProperty("http.proxyPort", PROXY_PORT.toString())
                System.setProperty("https.proxyHost", PROXY_HOST)
                System.setProperty("https.proxyPort", PROXY_PORT.toString())
                Log.d(TAG, "应用代理模式 - 代理已配置: $PROXY_HOST:$PROXY_PORT")
            } catch (e: Exception) {
                Log.e(TAG, "代理配置失败: ${e.message}")
            }
        } else {
            // 全局VPN模式 - 清除代理，流量自动走 Tailscale 隧道
            try {
                System.clearProperty("http.proxyHost")
                System.clearProperty("http.proxyPort")
                System.clearProperty("https.proxyHost")
                System.clearProperty("https.proxyPort")
                Log.d(TAG, "全局VPN模式 - 代理已清除，流量自动走隧道")
            } catch (e: Exception) {
                Log.e(TAG, "清除代理失败: ${e.message}")
            }
        }
    }

    private fun showWelcomePage(useProxy: Boolean) {
        val modeLabel = if (useProxy) {
            "🔵 应用代理模式（通过 127.0.0.1:8080 代理访问 Tailnet 设备）"
        } else {
            "🟢 全局VPN模式（流量自动走 Tailscale 隧道，无需代理）"
        }

        webView.loadData(
            """
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body { font-family: sans-serif; padding: 20px; background: #f5f5f5; }
                    h1 { color: #333; font-size: 20px; }
                    .mode { background: ${if (useProxy) "#e3f2fd" else "#e8f5e9"}; padding: 12px; border-radius: 8px; margin: 12px 0; font-size: 15px; }
                    .info { background: white; padding: 15px; border-radius: 8px; margin: 10px 0; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }
                    .info h2 { font-size: 16px; color: #555; margin-bottom: 8px; }
                    .info p { font-size: 14px; color: #666; margin: 4px 0; }
                </style>
            </head>
            <body>
                <h1>🌐 Tailscale 内置浏览器</h1>
                <div class="mode">
                    <strong>当前模式:</strong><br>$modeLabel
                </div>
                <div class="info">
                    <h2>使用方法</h2>
                    <p>在地址栏输入 Tailnet 内设备的 URL：</p>
                    <p>• http://100.64.0.1/ - 访问 HTTP 服务</p>
                    <p>• http://100.64.0.2:8080/ - 访问带端口的服务</p>
                    <p>• http://device-name/ - 使用 MagicDNS 名称</p>
                </div>
                <div class="info">
                    <h2>模式说明</h2>
                    <p>• <strong>全局VPN模式</strong>: VPN 开关开启，所有流量自动走隧道</p>
                    <p>• <strong>应用代理模式</strong>: VPN 关闭，浏览器通过代理访问 Tailnet</p>
                </div>
            </body>
            </html>
            """.trimIndent(),
            "text/html",
            "UTF-8"
        )
    }

    /**
     * 检测 VPN 是否激活
     */
    private fun isVpnActive(): Boolean {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun loadUrl(url: String) {
        var targetUrl = url.trim()
        if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
            targetUrl = "http://$targetUrl"
        }
        webView.loadUrl(targetUrl)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
