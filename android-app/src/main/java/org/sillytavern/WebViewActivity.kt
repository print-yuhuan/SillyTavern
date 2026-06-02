package org.sillytavern

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.getSystemService
import org.sillytavern.ui.theme.SillyTavernTheme

/**
 * 承载 SillyTavern 前端的 WebView（实现方案 §8 / 工作计划第七阶段）。
 *
 * 仅加载本机或配置允许的 SillyTavern 地址；加载前已在首页通过健康检查（仅 Running 可进入）。
 * 顶部保留必要控制：返回、刷新、用浏览器打开；底部叠加加载进度与失败重连。
 */
class WebViewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            Toast.makeText(this, R.string.web_not_ready, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            SillyTavernTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    WebViewScreen(url = url, onClose = { finish() })
                }
            }
        }
    }

    companion object {
        const val EXTRA_URL = "extra_url"

        fun start(context: Context, url: String) {
            context.startActivity(
                Intent(context, WebViewActivity::class.java).putExtra(EXTRA_URL, url),
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION") // WebSettings.databaseEnabled 在新版为 no-op，但保留以兼容旧设备
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebViewScreen(url: String, onClose: () -> Unit) {
    val context = LocalContext.current

    var progress by remember { mutableStateOf(0) }
    var loadError by remember { mutableStateOf(false) }
    var pageTitle by remember { mutableStateOf<String?>(null) }

    // 文件选择 / 麦克风权限的回调载体（WebChromeClient 与启动器共享）。
    val filePathCallback = remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val pendingPermission = remember { mutableStateOf<PermissionRequest?>(null) }

    val fileChooser = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        filePathCallback.value?.onReceiveValue(uris ?: emptyArray())
        filePathCallback.value = null
    }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val request = pendingPermission.value
        pendingPermission.value = null
        if (request != null) {
            if (granted) request.grant(request.resources) else request.deny()
        }
    }

    val webView = remember {
        WebView(context).apply {
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
                override fun onPageStarted(view: WebView?, u: String?, favicon: android.graphics.Bitmap?) {
                    loadError = false
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    // 仅主框架加载失败才视为页面错误，忽略子资源失败。
                    if (request?.isForMainFrame == true) loadError = true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progress = newProgress
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    pageTitle = title
                }

                override fun onShowFileChooser(
                    view: WebView?,
                    callback: ValueCallback<Array<Uri>>?,
                    params: FileChooserParams?,
                ): Boolean {
                    filePathCallback.value?.onReceiveValue(null)
                    filePathCallback.value = callback
                    return try {
                        val intent = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "*/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }
                        fileChooser.launch(intent)
                        true
                    } catch (_: Exception) {
                        filePathCallback.value = null
                        false
                    }
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    request ?: return
                    val wantsMic = request.resources.any { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
                    if (wantsMic) {
                        pendingPermission.value = request
                        micLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    } else {
                        request.deny()
                    }
                }
            }
            setDownloadListener { downloadUrl, userAgent, contentDisposition, mimeType, _ ->
                handleDownload(context, downloadUrl, userAgent, contentDisposition, mimeType)
            }
            loadUrl(url)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
    }

    BackHandler { if (webView.canGoBack()) webView.goBack() else onClose() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = pageTitle?.takeIf { it.isNotBlank() } ?: stringResource(R.string.web_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (webView.canGoBack()) webView.goBack() else onClose() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.web_back))
                    }
                },
                actions = {
                    IconButton(onClick = { loadError = false; webView.reload() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.web_reload))
                    }
                    IconButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webView.url ?: url)))
                        }
                    }) {
                        Icon(Icons.Filled.OpenInBrowser, contentDescription = stringResource(R.string.web_open_external))
                    }
                },
            )
        },
    ) { inner ->
        Box(modifier = Modifier.padding(inner).fillMaxSize()) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                )
            }
            if (loadError) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.web_load_error), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = { loadError = false; webView.loadUrl(url) }) {
                        Text(stringResource(R.string.web_reconnect))
                    }
                }
            }
        }
    }
}

/** 经 DownloadManager 下载导出文件；带上 Cookie/UA 以支持启用访问密码后的下载。 */
private fun handleDownload(
    context: Context,
    url: String,
    userAgent: String?,
    contentDisposition: String?,
    mimeType: String?,
) {
    if (!url.startsWith("http")) {
        Toast.makeText(context, R.string.web_download_unsupported, Toast.LENGTH_SHORT).show()
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
        context.getSystemService<DownloadManager>()?.enqueue(request)
        Toast.makeText(context, context.getString(R.string.web_download_started, fileName), Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {
        Toast.makeText(context, R.string.web_download_failed, Toast.LENGTH_SHORT).show()
    }
}
