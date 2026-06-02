package org.sillytavern

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 承载 SillyTavern 前端的 WebView（实现方案 §8 / 工作计划第七阶段）。
 *
 * 采用经典的「全窗口 View 承载」：WebView 放入带 weight=1 的 LinearLayout，触摸/渲染最稳；
 * 顶部为轻量控制条（返回/刷新/用浏览器打开），下方细进度条。仅加载本机 127.0.0.1 健康 URL，
 * 加载前已在首页通过健康检查（仅 Running 可进入）。
 *
 * 注：曾用 Compose(AndroidView)+edge-to-edge 承载，导致 SillyTavern 触摸失效/布局塌陷，已回退。
 */
class WebViewActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var titleView: TextView
    private var pageUrl: String = ""

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        filePathCallback?.onReceiveValue(uris ?: emptyArray())
        filePathCallback = null
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val request = pendingPermissionRequest
        pendingPermissionRequest = null
        if (request != null) {
            if (granted) request.grant(request.resources) else request.deny()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION") // WebSettings.databaseEnabled 在新版为 no-op，保留以兼容旧设备
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 允许桌面 Chrome 通过 chrome://inspect 远程调试 SillyTavern 前端（便于排查渲染/触摸问题）。
        WebView.setWebContentsDebuggingEnabled(true)

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            Toast.makeText(this, R.string.web_not_ready, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        pageUrl = url

        webView = WebView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                javaScriptCanOpenWindowsAutomatically = true
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, u: String?, favicon: Bitmap?) {
                    progressBar.visibility = View.VISIBLE
                }

                override fun onPageFinished(view: WebView?, u: String?) {
                    progressBar.visibility = View.GONE
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        Toast.makeText(this@WebViewActivity, R.string.web_load_error, Toast.LENGTH_LONG).show()
                    }
                }
            }
            webChromeClient = createChromeClient()
            setDownloadListener { downloadUrl, userAgent, contentDisposition, mimeType, _ ->
                handleDownload(downloadUrl, userAgent, contentDisposition, mimeType)
            }
        }

        setContentView(buildLayout())
        applyInsets()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        webView.loadUrl(url)
    }

    /** 根布局：顶栏 + 进度条 + WebView(weight 1)。 */
    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, MATCH)
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        bar.addView(toolbarButton(getString(R.string.web_back)) {
            if (webView.canGoBack()) webView.goBack() else finish()
        })
        titleView = TextView(this).apply {
            text = getString(R.string.web_title)
            textSize = 16f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        bar.addView(titleView)
        bar.addView(toolbarButton(getString(R.string.web_reload)) {
            webView.reload()
        })
        bar.addView(toolbarButton(getString(R.string.web_open_external)) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webView.url ?: pageUrl))) }
        })
        root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        root.addView(progressBar, LinearLayout.LayoutParams(MATCH, WRAP))

        root.addView(webView, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return root
    }

    private fun toolbarButton(label: String, onClick: () -> Unit): Button =
        Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = label
            textSize = 13f
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { onClick() }
        }

    /** 处理 Android 15 强制 edge-to-edge：把系统栏/输入法内边距加到根视图，避免内容被系统栏遮挡。 */
    private fun applyInsets() {
        val root = window.decorView.findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(sys.left, sys.top, sys.right, maxOf(sys.bottom, ime.bottom))
            insets
        }
    }

    private fun createChromeClient() = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            progressBar.progress = newProgress
            progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            if (!title.isNullOrBlank()) titleView.text = title
        }

        override fun onShowFileChooser(
            view: WebView?,
            callback: ValueCallback<Array<Uri>>?,
            params: FileChooserParams?,
        ): Boolean {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = callback
            return try {
                val intent = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                fileChooserLauncher.launch(intent)
                true
            } catch (_: Exception) {
                filePathCallback = null
                false
            }
        }

        override fun onPermissionRequest(request: PermissionRequest?) {
            request ?: return
            val wantsMic = request.resources.any { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
            if (wantsMic) {
                pendingPermissionRequest = request
                micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            } else {
                request.deny()
            }
        }
    }

    /** 经 DownloadManager 下载导出文件；带上 Cookie/UA 以支持启用访问密码后的下载。 */
    private fun handleDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        if (!url.startsWith("http")) {
            Toast.makeText(this, R.string.web_download_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setTitle(fileName)
                if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
                CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }
                    ?.let { addRequestHeader("Cookie", it) }
                if (!userAgent.isNullOrBlank()) addRequestHeader("User-Agent", userAgent)
            }
            getSystemService<DownloadManager>()?.enqueue(request)
            Toast.makeText(this, getString(R.string.web_download_started, fileName), Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.web_download_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        if (this::webView.isInitialized) {
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        const val EXTRA_URL = "extra_url"

        fun start(context: Context, url: String) {
            context.startActivity(
                Intent(context, WebViewActivity::class.java).putExtra(EXTRA_URL, url),
            )
        }
    }
}
