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
import androidx.appcompat.app.AppCompatActivity

/**
 * 内置浏览器 - 自动检测 VPN 状态
 * - VPN 开启时：流量自动走 Tailscale 隧道，无需配置代理
 * - VPN 关闭时：显示提示，需要先开启 VPN 才能访问 Tailnet 设备
 */
class BuiltInBrowserActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var urlEditText: EditText
    private lateinit var progressBar: ProgressBar

    companion object {
        private const val TAG = "BuiltInBrowser"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val vpnActive = isVpnActive()

        // 创建布局
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
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
                    if (vpnActive) {
                        loadUrl(url)
                    } else {
                        showVpnOffWarning()
                    }
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

        // VPN 开启时不需要代理，流量自动走 Tailscale 隧道
        if (!vpnActive) {
            Log.d(TAG, "VPN 未激活 - 浏览器仅作为提醒")
        } else {
            Log.d(TAG, "VPN 已激活 - 流量自动走 Tailscale 隧道")
        }

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

        layout.addView(urlBar)
        layout.addView(progressBar)
        layout.addView(webView)

        setContentView(layout)

        // 加载初始 URL 或显示欢迎页
        val initialUrl = intent.getStringExtra("url")
        if (initialUrl != null && vpnActive) {
            urlEditText.setText(initialUrl)
            loadUrl(initialUrl)
        } else {
            val modeText = if (vpnActive) {
                "✅ VPN 已开启 - 流量自动走 Tailscale 隧道"
            } else {
                "⚠️ VPN 已关闭 - 请先开启 VPN 开关"
            }
            webView.loadData(
                """
                <html>
                <head>
                    <meta charset="UTF-8">
                    <style>
                        body { font-family: sans-serif; padding: 20px; background: #f0f0f0; }
                        h1 { color: #333; }
                        .info { background: white; padding: 15px; border-radius: 8px; margin: 10px 0; }
                        .mode { background: ${if (vpnActive) "#e8f5e9" else "#fff3e0"}; padding: 10px; border-radius: 5px; margin: 10px 0; font-size: 16px; }
                    </style>
                </head>
                <body>
                    <h1>🌐 Tailscale 内置浏览器</h1>
                    <div class="mode">
                        <strong>当前状态:</strong> $modeText
                    </div>
                    <div class="info">
                        <h2>使用方法</h2>
                        <ol>
                            <li>在地址栏输入 URL（如 http://100.x.x.x/）</li>
                            <li>点击播放按钮或按回车</li>
                            ${if (vpnActive) "<li>浏览器会通过 Tailscale 隧道访问目标</li>" else "<li>请先在主页面开启 VPN 开关</li>"}
                        </ol>
                    </div>
                    <div class="info">
                        <h2>示例地址</h2>
                        <p>http://100.64.0.1/ - 访问 Tailnet 内的 HTTP 服务器</p>
                        <p>http://100.64.0.2:8080/ - 访问带端口的服务</p>
                    </div>
                </body>
                </html>
                """.trimIndent(),
                "text/html",
                "UTF-8"
            )
        }
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

    /**
     * VPN 关闭时显示警告
     */
    private fun showVpnOffWarning() {
        android.widget.Toast.makeText(
            this,
            "请先在主页面开启 VPN 开关，才能通过 Tailscale 访问其他设备",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }

    private fun loadUrl(url: String) {
        var targetUrl = url.trim()
        if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
            targetUrl = "http://$targetUrl"
        }
        webView.loadUrl(targetUrl)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
