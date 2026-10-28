package com.linkextract.id

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import java.io.ByteArrayInputStream

private const val MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 15; V2405A) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

/** 桌面版页面不引用移动端专用资源，可绕开 tosv.byted.org 的加载失败 */
private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

/** 该域名公网 DNS 无记录（NXDOMAIN），拦截以免拖住页面 */
private const val BLOCK_HOST = "tosv.byted.org"

private const val FORCE_VISIBLE_JS =
    "(function(){try{" +
        "var all=document.querySelectorAll('*');" +
        "for(var i=0;i<all.length;i++){var el=all[i];" +
        "var t=el.tagName;if(t==='SCRIPT'||t==='STYLE'||t==='NOSCRIPT'||t==='TEMPLATE')continue;" +
        "var cs=window.getComputedStyle(el);" +
        "if(cs.display==='none')el.style.setProperty('display','block','important');" +
        "if(cs.visibility==='hidden')el.style.setProperty('visibility','visible','important');" +
        "if(cs.opacity==='0')el.style.setProperty('opacity','1','important');}" +
        "var el=document.querySelector('input');" +
        "if(el&&el.scrollIntoView)el.scrollIntoView({block:'center'});" +
        "}catch(e){}})()"

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

    var mainError by remember { mutableStateOf<String?>(null) }
    var subErrors by remember { mutableStateOf<List<String>>(emptyList()) }
    var pageProbe by remember { mutableStateOf<String?>(null) }

    var useDesktop by rememberSaveable { mutableStateOf(true) }
    var showPaste by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }

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

    if (showPaste) {
        AlertDialog(
            onDismissRequest = { showPaste = false },
            title = { Text("手动粘贴 Cookie") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "此方式需要电脑，手机上无法直接复制 Cookie。\n\n在电脑浏览器操作：\n" +
                            "1. 登录 shortdramas.com 并进入 IP 库\n" +
                            "2. 按 F12 打开开发者工具\n" +
                            "3. 切到 Network（网络）标签，刷新页面\n" +
                            "4. 点列表里任意一个同名请求\n" +
                            "5. 在 Headers → Request Headers 找到 Cookie\n" +
                            "6. 复制冒号后面的整段内容，粘贴到下面",
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                    OutlinedTextField(
                        value = pasteText,
                        onValueChange = { pasteText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 110.dp),
                        placeholder = { Text("粘贴 Cookie 值…", fontSize = 12.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = pasteText.trim()
                    if (v.isNotBlank()) {
                        onDone(v)
                        showPaste = false
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showPaste = false }) { Text("取消") }
            }
        )
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = buildSummary(mainError, pageProbe),
                modifier = Modifier.weight(1f),
                fontSize = 9.sp,
                lineHeight = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { useDesktop = !useDesktop }) {
                Text(if (useDesktop) "桌面" else "手机", fontSize = 11.sp)
            }
            TextButton(onClick = { reload() }) { Text("刷新", fontSize = 11.sp) }
        }

        val diagnosis = buildDiagnosis(mainError, subErrors, pageProbe)
        if (diagnosis != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 150.dp)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = diagnosis,
                    fontSize = 9.sp,
                    lineHeight = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(IpApi.loginUrl()))
                            )
                        }
                    }) { Text("用浏览器打开", fontSize = 11.sp) }
                    TextButton(onClick = { showPaste = true }) { Text("粘贴 Cookie", fontSize = 11.sp) }
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
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.userAgentString = if (useDesktop) DESKTOP_UA else MOBILE_UA

                    if (Build.VERSION.SDK_INT >= 29) {
                        runCatching { settings.forceDark = WebSettings.FORCE_DARK_OFF }
                    }
                    if (Build.VERSION.SDK_INT >= 33) {
                        runCatching { settings.isAlgorithmicDarkeningAllowed = false }
                    }

                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            if (request?.url?.host == BLOCK_HOST) {
                                return WebResourceResponse(
                                    "text/plain", "utf-8", 404, "Not Found",
                                    emptyMap(), ByteArrayInputStream(ByteArray(0))
                                )
                            }
                            return null
                        }

                        override fun onPageStarted(
                            view: WebView?,
                            url: String?,
                            favicon: Bitmap?
                        ) {
                            mainError = null
                            pageProbe = null
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            view?.evaluateJavascript(FORCE_VISIBLE_JS, null)
                            view?.postDelayed({ view.evaluateJavascript(FORCE_VISIBLE_JS, null) }, 1200)
                            view?.postDelayed({ view.evaluateJavascript(FORCE_VISIBLE_JS, null) }, 3000)

                            view?.postDelayed({
                                view.evaluateJavascript(
                                    "(function(){try{" +
                                        "var b=document.body;" +
                                        "var cs=b?getComputedStyle(b):null;" +
                                        "var L=document.documentElement?document.documentElement.outerHTML.length:-1;" +
                                        "var H=document.documentElement?document.documentElement.scrollHeight:-1;" +
                                        "var T=(document.title||'').replace(/\\|/g,'/');" +
                                        "var BG=cs?cs.backgroundColor.replace(/\\s/g,''):'';" +
                                        "var FG=cs?cs.color.replace(/\\s/g,''):'';" +
                                        "var D=(window.matchMedia&&window.matchMedia('(prefers-color-scheme: dark)').matches)?'1':'0';" +
                                        "var I=document.querySelectorAll('input').length;" +
                                        "var X=(b&&b.innerText?b.innerText:'').replace(/\\s+/g,' ').slice(0,90).replace(/\\|/g,'/');" +
                                        "return [L,H,BG,FG,D,'input:'+I,T,X].join('|');" +
                                        "}catch(e){return 'ERR|'+e.message}})()"
                                ) { result ->
                                    pageProbe = result
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
                            if (msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                subErrors = (subErrors + "JS错误: ${msg.message().take(160)}").take(6)
                            }
                            return true
                        }
                    }

                    loadUrl(IpApi.loginUrl())
                    webView = this
                }
            },
            update = { wv ->
                val ua = if (useDesktop) DESKTOP_UA else MOBILE_UA
                if (wv.settings.userAgentString != ua) {
                    wv.settings.userAgentString = ua
                    mainError = null
                    subErrors = emptyList()
                    pageProbe = null
                    wv.loadUrl(IpApi.loginUrl())
                }
            }
        )
    }
}

private fun parseProbe(raw: String?): List<String> {
    if (raw == null) return emptyList()
    return raw.trim().removeSurrounding("\"").split("|")
}

private fun buildSummary(mainError: String?, pageProbe: String?): String {
    if (mainError != null) return mainError

    val p = parseProbe(pageProbe)
    if (p.size < 6) return "加载中…（等待渲染探测）"

    return "DOM ${p[0]}B · 高 ${p[1]}px · bg ${p[2]} · ${p[5]}"
}

private fun buildDiagnosis(
    mainError: String?,
    subErrors: List<String>,
    pageProbe: String?
): String? {
    if (mainError == null && subErrors.isEmpty() && pageProbe == null) return null

    val p = parseProbe(pageProbe)
    val domLen = p.getOrNull(0)?.toIntOrNull()
    val renderFailed = domLen != null && domLen < 3000

    return buildString {
        mainError?.let { appendLine(it) }
        if (p.size >= 8) {
            appendLine("标题: ${p[6]}")
            appendLine("正文: ${p[7]}")
        } else if (pageProbe != null) {
            appendLine("原始探测: ${pageProbe.take(300)}")
        }
        if (renderFailed) appendLine("→ 骨架过小（${domLen}B），JS 未完成渲染")
        if (subErrors.isNotEmpty()) {
            appendLine("异常 ${subErrors.size} 条：")
            subErrors.forEach { appendLine("· $it") }
        }
    }.trim().ifBlank { null }
}
