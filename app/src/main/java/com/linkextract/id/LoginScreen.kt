package com.linkextract.id

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/** 伪装成普通 Chrome Mobile，去掉 WebView 的 "; wv" 标识 */
private const val MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 15; V2405A) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(
    onCancel: () -> Unit,
    onDone: (String) -> Unit
) {
    val context = LocalContext.current

    var progress by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    // 诊断信息
    var mainError by remember { mutableStateOf<String?>(null) }
    var subErrors by remember { mutableStateOf<List<String>>(emptyList()) }
    var pageProbe by remember { mutableStateOf<String?>(null) }

    fun reload() {
        mainError = null
        subErrors = emptyList()
        pageProbe = null
        webView?.reload()
    }

    BackHandler {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else onCancel()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancel) { Text("返回") }

            Text(
                text = "登录短剧版权中心",
                modifier = Modifier.weight(1f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )

            TextButton(onClick = {
                val cookie = CookieManager.getInstance().getCookie(IpApi.SITE)
                if (cookie.isNullOrBlank()) {
                    message = "尚未登录成功，请先在页面内完成登录"
                } else {
                    onDone(cookie)
                }
            }) { Text("完成") }
        }

        message?.let {
            Text(
                text = it,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 诊断区：只有在出错或探测异常时出现
        val diagnosis = buildDiagnosis(mainError, subErrors, pageProbe)
        if (diagnosis != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = diagnosis,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { reload() }) { Text("刷新", fontSize = 12.sp) }
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(IpApi.loginUrl()))
                            )
                        }
                    }) { Text("用浏览器打开", fontSize = 12.sp) }
                }
            }
        }

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                    settings.userAgentString = MOBILE_UA

                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(
                            view: WebView?,
                            url: String?,
                            favicon: Bitmap?
                        ) {
                            mainError = null
                            pageProbe = null
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            view?.postDelayed({
                                view.evaluateJavascript(
                                    "(function(){try{" +
                                        "return JSON.stringify({" +
                                        "t:document.title||''," +
                                        "len:document.documentElement?document.documentElement.outerHTML.length:-1," +
                                        "txt:(document.body&&document.body.innerText?document.body.innerText:'').replace(/\\s+/g,' ').slice(0,120)," +
                                        "scripts:document.scripts?document.scripts.length:0" +
                                        "});}catch(e){return 'ERR:'+e.message}})()"
                                ) { result ->
                                    pageProbe = "DOM探测: $result"
                                }
                            }, 2500)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            val desc = "${error?.errorCode} ${error?.description}"
                            if (request?.isForMainFrame == true) {
                                mainError = "主页面加载失败: $desc"
                            } else {
                                val host = request?.url?.host ?: "?"
                                val path = request?.url?.path ?: ""
                                subErrors = (subErrors + "$host$path → $desc").take(6)
                            }
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: WebResourceResponse?
                        ) {
                            val code = errorResponse?.statusCode ?: 0
                            if (request?.isForMainFrame == true) {
                                mainError = "主页面 HTTP $code"
                            } else {
                                val host = request?.url?.host ?: "?"
                                val path = request?.url?.path ?: ""
                                subErrors = (subErrors + "$host$path → HTTP $code").take(6)
                            }
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                            if (newProgress >= 100) message = null
                        }

                        override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                            if (msg == null) return true
                            val level = msg.messageLevel()
                            if (level == ConsoleMessage.MessageLevel.ERROR) {
                                subErrors = (subErrors + "JS错误: ${msg.message().take(160)}").take(6)
                            }
                            return true
                        }
                    }

                    loadUrl(IpApi.loginUrl())
                    webView = this
                }
            }
        )
    }
}

private fun buildDiagnosis(
    mainError: String?,
    subErrors: List<String>,
    pageProbe: String?
): String? {
    val domLen = pageProbe
        ?.let { Regex("\"len\":(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
    val renderFailed = domLen != null && domLen < 3000

    if (mainError == null && subErrors.isEmpty() && !renderFailed) return null

    return buildString {
        mainError?.let { appendLine(it) }

        if (pageProbe != null) {
            appendLine(pageProbe)
            if (renderFailed) appendLine("→ 页面骨架过小（${domLen} 字节），JS 未完成渲染")
        }

        if (subErrors.isNotEmpty()) {
            appendLine("资源/脚本异常 ${subErrors.size} 条：")
            subErrors.forEach { appendLine("· $it") }
        }
    }.trim().ifBlank { null }
}
