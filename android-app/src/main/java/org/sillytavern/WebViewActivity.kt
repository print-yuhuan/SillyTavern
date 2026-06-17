package org.sillytavern

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.LinearLayout
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
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder

/**
 * 承载 SillyTavern 前端的 WebView（实现方案 §8 / 工作计划第七阶段）。
 *
 * - 经典「全窗口 View 承载」：WebView 置于 weight=1 的 LinearLayout，触摸/渲染最稳。
 * - 不设 useWideViewPort/loadWithOverviewMode：布局视口=视图边界，避免输入栏跑顶。
 * - 顶栏图标按钮（返回/刷新/用浏览器打开），不显示加载横条。
 * - 工具栏 + 系统栏 + WebView 背景跟随网页 `<meta name=theme-color>`，沉浸。
 * - 下载像浏览器：http(s) 走 DownloadManager；blob:/data: 读成 base64 回传后写入下载目录，全类型支持。
 */
class WebViewActivity : ComponentActivity() {

    private lateinit var webView: WebView
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
        // 仅在 debug 构建开启：发行版若开启，任何能访问 WebView devtools 的进程/ADB 即可
        // 读取 SillyTavern 的 API 密钥、访问密码与全部聊天记录。
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

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
                // 不设 useWideViewPort/loadWithOverviewMode：布局视口=视图边界，避免 100vh/innerHeight 错乱。
            }
            // 仅加载本机可信的 SillyTavern；接口只接收颜色字符串与下载数据，无其它敏感能力。
            addJavascriptInterface(ThemeBridge(), "STAndroid")
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, u: String?, favicon: Bitmap?) = Unit

                override fun onPageFinished(view: WebView?, u: String?) {
                    injectThemeColorWatcher()
                }

                // 受信边界落地：仅本机回环留在内置 WebView；外部 URL 交系统浏览器，
                // 避免 STAndroid JS 桥被导航到的远端页面调用。
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return false
                    if (!isTrustedLocalUrl(uri)) {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                        return true
                    }
                    return false
                }

                // SillyTavern 启用 HTTPS 时使用自签证书：仅对本机回环放行，其余一律取消。
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError?) {
                    val host = runCatching { Uri.parse(error?.url ?: "").host }.getOrNull()
                    if (host in TRUSTED_LOCAL_HOSTS) handler.proceed() else handler.cancel()
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

    /** 根布局：顶栏 + WebView(weight 1)。无加载进度条。 */
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

    /** 是否为本机回环且 http(s) 的可信地址（决定是否留在内置 WebView 内加载）。 */
    private fun isTrustedLocalUrl(uri: Uri): Boolean {
        val host = uri.host ?: return false
        return uri.scheme?.lowercase() in setOf("http", "https") && host in TRUSTED_LOCAL_HOSTS
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

        /** blob:/data: 下载：JS 把内容读成 base64 dataURL 回传，这里解码写入下载目录（在 JS 线程，非主线程）。 */
        @JavascriptInterface
        fun onBlobDownload(dataUrl: String, fileName: String, mime: String) {
            val bytes = decodeDataUrl(dataUrl)
            if (bytes == null) {
                toastMain(getString(R.string.web_download_failed))
                return
            }
            saveToDownloads(bytes, fileName.ifBlank { "download" }, mime.ifBlank { null })
        }
    }

    /** 解析 CSS 颜色：#rgb / #rrggbb / #aarrggbb / rgb()/rgba()（忽略 alpha 取不透明）。 */
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

    // ── 下载：像浏览器一样支持全部类型（http/https/blob/data） ────────────────
    private fun handleDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        when {
            url.startsWith("blob:", true) ->
                webView.evaluateJavascript(blobDownloadJs(url, fileName, mimeType ?: ""), null)
            url.startsWith("data:", true) -> {
                val bytes = decodeDataUrl(url)
                if (bytes != null) saveToDownloads(bytes, fileName, mimeType) else toastMain(getString(R.string.web_download_failed))
            }
            else -> httpDownload(url, userAgent, contentDisposition, mimeType, fileName)
        }
    }

    /** http(s)：经 DownloadManager 下载（任意类型），带 Cookie/UA 以支持访问密码后的下载。 */
    private fun httpDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?, fileName: String) {
        try {
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
            toastMain(getString(R.string.web_download_started, fileName))
        } catch (_: Exception) {
            toastMain(getString(R.string.web_download_failed))
        }
    }

    /** 在页面上下文读取 blob 内容为 base64 dataURL 回传（blob: 只能在创建它的文档内访问）。 */
    private fun blobDownloadJs(blobUrl: String, fileName: String, mime: String): String {
        val u = JSONObject.quote(blobUrl)
        val n = JSONObject.quote(fileName)
        val m = JSONObject.quote(mime)
        return """
            (function(){
              try{
                var xhr=new XMLHttpRequest();
                xhr.open('GET',$u,true);
                xhr.responseType='blob';
                xhr.onload=function(){
                  if(xhr.status===200||xhr.status===0){
                    var r=new FileReader();
                    r.onloadend=function(){ if(window.STAndroid&&typeof r.result==='string'){ window.STAndroid.onBlobDownload(r.result,$n,$m); } };
                    r.readAsDataURL(xhr.response);
                  }
                };
                xhr.send();
              }catch(e){}
            })();
        """.trimIndent()
    }

    private fun decodeDataUrl(dataUrl: String): ByteArray? = try {
        val comma = dataUrl.indexOf(',')
        if (!dataUrl.startsWith("data:") || comma < 0) {
            null
        } else {
            val meta = dataUrl.substring(5, comma)
            val payload = dataUrl.substring(comma + 1)
            if (meta.contains("base64", ignoreCase = true)) {
                Base64.decode(payload, Base64.DEFAULT)
            } else {
                URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.UTF_8)
            }
        }
    } catch (_: Exception) {
        null
    }

    /** 文件名净化：去掉路径成分并替换非法字符。下载桥可被页面内任意脚本调用，文件名不可信，
     *  否则 API 28 的 `File(dir, fileName)` 会被 `../` 穿越出下载目录。 */
    private fun sanitizeFileName(name: String): String =
        File(name.ifBlank { "download" }).name
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .ifBlank { "download" }

    /** 把字节写入公共下载目录（API 29+ 走 MediaStore，无需权限；API 28 落 App 外部下载目录）。 */
    private fun saveToDownloads(bytes: ByteArray, fileName: String, mime: String?) {
        val safeName = sanitizeFileName(fileName)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    if (!mime.isNullOrBlank()) put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("MediaStore insert 失败")
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null,
                    null,
                )
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                File(dir, safeName).writeBytes(bytes)
            }
            toastMain(getString(R.string.web_download_started, safeName))
        } catch (_: Exception) {
            toastMain(getString(R.string.web_download_failed))
        }
    }

    private fun toastMain(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

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

        /** 内置 WebView 仅信任本机回环；其余 URL 交系统浏览器、自签 SSL 仅对其放行。 */
        private val TRUSTED_LOCAL_HOSTS = setOf("127.0.0.1", "localhost", "::1")

        const val EXTRA_URL = "extra_url"

        fun start(context: Context, url: String) {
            context.startActivity(
                Intent(context, WebViewActivity::class.java).putExtra(EXTRA_URL, url),
            )
        }
    }
}
