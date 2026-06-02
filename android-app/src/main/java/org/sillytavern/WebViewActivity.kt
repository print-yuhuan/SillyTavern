package org.sillytavern

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.getSystemService
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 承载 SillyTavern 前端的 WebView（实现方案 §8 / 工作计划第七阶段）。
 *
 * - 经典「全窗口 View 承载」：WebView 置于 weight=1 的 LinearLayout，触摸/渲染最稳。
 * - 不设 useWideViewPort/loadWithOverviewMode：布局视口=视图边界，避免 SillyTavern 100vh/innerHeight
 *   计算错乱导致输入栏跑顶。
 * - 顶栏为图标按钮（返回/刷新/用浏览器打开）；工具栏 + 系统栏 + WebView 背景跟随网页
 *   `<meta name=theme-color>`（SillyTavern 主题色），实现沉浸；图标/状态栏图标按亮度自动取反色。
 */
class WebViewActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var titleView: TextView
    private lateinit var topBar: LinearLayout
    private val iconButtons = mutableListOf<ImageButton>()
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
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 允许桌面 Chrome 通过 chrome://inspect 远程调试 SillyTavern 前端。
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
                // 不设 useWideViewPort/loadWithOverviewMode：让布局视口等于 WebView 视图边界，
                // 使 window.innerHeight / 100vh / 100dvh 与可视区域一致（否则 SillyTavern 布局塌陷）。
            }
            // 仅加载本机可信的 SillyTavern；接口只接收颜色字符串，无敏感能力。
            addJavascriptInterface(ThemeBridge(), "STAndroid")
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, u: String?, favicon: Bitmap?) {
                    progressBar.visibility = View.VISIBLE
                }

                override fun onPageFinished(view: WebView?, u: String?) {
                    progressBar.visibility = View.GONE
                    injectThemeColorWatcher()
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
        // 初始用 SillyTavern 默认 theme-color (#333)，页面就绪后由注入脚本回传实际主题色。
        applyThemeColor(0xFF333333.toInt())

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

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        topBar.addView(iconButton(R.drawable.ic_web_back, getString(R.string.web_back)) {
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
        topBar.addView(titleView)
        topBar.addView(iconButton(R.drawable.ic_web_refresh, getString(R.string.web_reload)) {
            webView.reload()
        })
        topBar.addView(iconButton(R.drawable.ic_web_open_external, getString(R.string.web_open_external)) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webView.url ?: pageUrl))) }
        })
        root.addView(topBar, LinearLayout.LayoutParams(MATCH, WRAP))

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        root.addView(progressBar, LinearLayout.LayoutParams(MATCH, WRAP))

        root.addView(webView, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return root
    }

    private fun iconButton(iconRes: Int, desc: String, onClick: () -> Unit): ImageButton =
        ImageButton(this, null, android.R.attr.borderlessButtonStyle).apply {
            setImageResource(iconRes)
            contentDescription = desc
            val size = dp(40)
            layoutParams = LinearLayout.LayoutParams(size, size)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { onClick() }
            iconButtons.add(this)
        }

    /** Android 15 强制 edge-to-edge：把系统栏/输入法内边距加到根视图，内容不被系统栏遮挡。 */
    private fun applyInsets() {
        val content = window.decorView.findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(sys.left, sys.top, sys.right, maxOf(sys.bottom, ime.bottom))
            insets
        }
    }

    /** 注入脚本：读取并跟随 SillyTavern 的 <meta name=theme-color>（主题切换时回传）。 */
    private fun injectThemeColorWatcher() {
        val js = """
            (function(){
              try{
                var send=function(){
                  var m=document.querySelector('meta[name=theme-color]');
                  if(window.STAndroid&&m){window.STAndroid.onThemeColor(m.getAttribute('content')||'');}
                };
                send();
                var m=document.querySelector('meta[name=theme-color]');
                if(m&&!m.__stObserved){m.__stObserved=true;new MutationObserver(send).observe(m,{attributes:true,attributeFilter:['content']});}
              }catch(e){}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /** 把工具栏 + 系统栏 + WebView 背景染成网页主题色，前景按亮度取反色。 */
    private fun applyThemeColor(color: Int) {
        val opaque = (color and 0x00FFFFFF) or 0xFF000000.toInt()
        val light = ColorUtils.calculateLuminance(opaque) > 0.5
        val fg = if (light) 0xFF202020.toInt() else 0xFFFFFFFF.toInt()

        topBar.setBackgroundColor(opaque)
        titleView.setTextColor(fg)
        iconButtons.forEach { it.setColorFilter(fg) }
        webView.setBackgroundColor(opaque)
        window.decorView.setBackgroundColor(opaque)

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = light
        controller.isAppearanceLightNavigationBars = light
    }

    private inner class ThemeBridge {
        @JavascriptInterface
        fun onThemeColor(value: String) {
            val color = parseCssColor(value) ?: return
            runOnUiThread { applyThemeColor(color) }
        }
    }

    /** 解析 CSS 颜色：#rgb / #rrggbb / #aarrggbb / rgb()/rgba()（忽略 alpha，按浏览器对 theme-color 的处理取不透明）。 */
    private fun parseCssColor(input: String): Int? {
        val s = input.trim()
        return try {
            when {
                s.startsWith("#") -> {
                    val hex = s.substring(1)
                    when (hex.length) {
                        3 -> Color.rgb(
                            (hex[0].toString().repeat(2)).toInt(16),
                            (hex[1].toString().repeat(2)).toInt(16),
                            (hex[2].toString().repeat(2)).toInt(16),
                        )
                        6, 8 -> Color.parseColor("#$hex")
                        else -> null
                    }
                }
                s.startsWith("rgb") -> {
                    val nums = s.substringAfter('(').substringBefore(')').split(',')
                    if (nums.size >= 3) {
                        Color.rgb(
                            nums[0].trim().toFloat().toInt().coerceIn(0, 255),
                            nums[1].trim().toFloat().toInt().coerceIn(0, 255),
                            nums[2].trim().toFloat().toInt().coerceIn(0, 255),
                        )
                    } else {
                        null
                    }
                }
                else -> null
            }
        } catch (_: Exception) {
            null
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
