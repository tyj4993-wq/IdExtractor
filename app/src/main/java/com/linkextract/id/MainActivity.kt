package com.linkextract.id

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val sharedText = readSharedText(intent)
        setContent {
            val darkTheme = isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppRoot(initialText = sharedText)
                }
            }
        }
    }

    private fun readSharedText(intent: Intent?): String? {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            return intent.getStringExtra(Intent.EXTRA_TEXT)
        }
        return null
    }
}

@Composable
private fun AppRoot(initialText: String?) {
    val context = LocalContext.current
    var showLogin by remember { mutableStateOf(false) }
    var cookie by remember { mutableStateOf(Prefs.getCookie(context)) }

    if (showLogin) {
        LoginScreen(
            onCancel = { showLogin = false },
            onDone = { c ->
                Prefs.setCookie(context, c)
                cookie = c
                showLogin = false
            }
        )
    } else {
        MainScreen(
            initialText = initialText,
            cookie = cookie,
            onLogin = { showLogin = true },
            onLogout = {
                Prefs.clearCookie(context)
                cookie = null
            }
        )
    }
}

@Composable
private fun MainScreen(
    initialText: String?,
    cookie: String?,
    onLogin: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current

    var input by rememberSaveable { mutableStateOf(initialText.orEmpty()) }
    var novelId by rememberSaveable { mutableStateOf<String?>(null) }
    var idHint by rememberSaveable { mutableStateOf<String?>(null) }
    var extractError by rememberSaveable { mutableStateOf<String?>(null) }
    var resolving by remember { mutableStateOf(false) }

    var searching by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<IpItem>>(emptyList()) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var needLogin by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var expandedRaw by remember { mutableStateOf<String?>(null) }

    val extractSeq = remember { AtomicInteger(0) }
    val searchSeq = remember { AtomicInteger(0) }

    // 第一步：从输入里提取小说 ID
    LaunchedEffect(input) {
        val seq = extractSeq.incrementAndGet()

        novelId = null
        idHint = null
        extractError = null
        items = emptyList()
        searchError = null
        needLogin = false

        val text = input.trim()
        if (text.isEmpty()) return@LaunchedEffect

        val direct = IdParser.extractDirect(text)
        if (direct != null) {
            novelId = direct
            idHint = "已从链接参数中识别"
            return@LaunchedEffect
        }

        val url = IdParser.firstUrl(text)
        if (url == null) {
            extractError = "没有识别到链接，请粘贴完整的分享链接"
            return@LaunchedEffect
        }

        delay(400)
        if (extractSeq.get() != seq) return@LaunchedEffect

        resolving = true
        try {
            val resolved = IdParser.resolve(url)
            if (extractSeq.get() == seq) {
                if (resolved != null) {
                    novelId = resolved.first
                    idHint = "短链已解析 · ${resolved.second}"
                } else {
                    extractError = "解析失败，请改用浏览器地址栏里的完整长链接"
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (extractSeq.get() == seq) extractError = e.message ?: "解析失败"
        } finally {
            if (extractSeq.get() == seq) resolving = false
        }
    }

    // 第二步：拿到 ID 且已登录时，查询 IP 库
    LaunchedEffect(novelId, cookie) {
        val id = novelId ?: return@LaunchedEffect
        val seq = searchSeq.incrementAndGet()

        items = emptyList()
        searchError = null
        needLogin = false

        if (cookie.isNullOrBlank()) {
            needLogin = true
            return@LaunchedEffect
        }

        searching = true
        try {
            val result = withContext(Dispatchers.IO) { IpApi.search(id, cookie) }
            if (searchSeq.get() != seq) return@LaunchedEffect
            when (result) {
                is IpResult.Ok -> items = result.items
                is IpResult.NeedLogin -> needLogin = true
                is IpResult.ApiError -> searchError = "接口错误 ${result.code}：${result.msg}"
                is IpResult.Fail -> searchError = result.msg
            }
        } finally {
            if (searchSeq.get() == seq) searching = false
        }
    }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    fun copyToClipboard(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("novel_id", text))
        copied = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        HeaderRow(
            loggedIn = !cookie.isNullOrBlank(),
            onLogin = onLogin,
            onLogout = onLogout
        )

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 132.dp),
            label = { Text("分享链接") },
            placeholder = { Text("在此粘贴链接…", fontSize = 14.sp) },
            shape = RoundedCornerShape(14.dp),
            trailingIcon = {
                if (input.isNotEmpty()) {
                    TextButton(onClick = { input = "" }) { Text("清空") }
                }
            }
        )

        if (resolving) {
            LoadingRow("正在解析短链接…")
        }

        novelId?.let { id ->
            IdCard(
                id = id,
                hint = idHint,
                copied = copied,
                onCopy = { copyToClipboard(id) }
            )
        }

        extractError?.let { MessageCard(it, isError = true) }

        when {
            needLogin -> LoginPromptCard(onLogin)
            searching -> LoadingRow("正在查询 IP 库…")
            searchError != null -> MessageCard(searchError!!, isError = true)
            items.isNotEmpty() -> {
                Text(
                    text = "IP 库查询结果（${items.size} 条）",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                items.forEach { item ->
                    IpCard(
                        item = item,
                        expanded = expandedRaw == item.title,
                        onToggleRaw = {
                            expandedRaw = if (expandedRaw == item.title) null else item.title
                        }
                    )
                }
            }
            novelId != null && !searching -> MessageCard("未查到该 ID 对应的作品", isError = false)
        }

        Spacer(Modifier.height(2.dp))

        Text(
            text = "登录凭据仅保存在本机，用于调用短剧版权中心的查询接口。",
            fontSize = 11.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HeaderRow(loggedIn: Boolean, onLogin: () -> Unit, onLogout: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "链接 ID 提取",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = if (loggedIn) "已登录 · 粘贴链接自动查 IP 库" else "未登录 · 登录后自动查 IP 库",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (loggedIn) {
            TextButton(onClick = onLogout) { Text("退出", fontSize = 13.sp) }
        } else {
            TextButton(onClick = onLogin) { Text("登录", fontSize = 13.sp) }
        }
    }
}

@Composable
private fun LoadingRow(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MessageCard(text: String, isError: Boolean) {
    val bg = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(text = text, fontSize = 13.sp, lineHeight = 19.sp, color = fg)
    }
}

@Composable
private fun LoginPromptCard(onLogin: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "需要先登录短剧版权中心",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Text(
            text = "登录一次即可，之后粘贴链接会自动完成提取和查询。",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
        )
        Button(onClick = onLogin) { Text("去登录") }
    }
}

@Composable
private fun IdCard(
    id: String,
    hint: String?,
    copied: Boolean,
    onCopy: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable { onCopy() }
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "小说 ID" + if (copied) " · 已复制" else " · 点击复制",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.65f)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = id,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        if (hint != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = hint,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.55f)
            )
        }
    }
}

@Composable
private fun IpCard(item: IpItem, expanded: Boolean, onToggleRaw: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = item.title,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        item.fields.forEach { field ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = field.label,
                    modifier = Modifier.width(98.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = field.value,
                    modifier = Modifier.weight(1f),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        TextButton(
            onClick = onToggleRaw,
            contentPadding = PaddingValues(0.dp)
        ) {
            Text(if (expanded) "收起原始数据" else "查看原始数据", fontSize = 11.sp)
        }

        if (expanded) {
            Text(
                text = item.rawJson,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
