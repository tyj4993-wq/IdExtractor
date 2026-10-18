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
import kotlinx.coroutines.delay
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
                    ExtractorScreen(initialText = sharedText)
                }
            }
        }
    }

    /** 支持从其他应用「分享」链接直接进入本应用 */
    private fun readSharedText(intent: Intent?): String? {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            return intent.getStringExtra(Intent.EXTRA_TEXT)
        }
        return null
    }
}

@Composable
private fun ExtractorScreen(initialText: String?) {
    val context = LocalContext.current

    var input by rememberSaveable { mutableStateOf(initialText.orEmpty()) }
    var resultId by rememberSaveable { mutableStateOf<String?>(null) }
    var hint by rememberSaveable { mutableStateOf<String?>(null) }
    var errorText by rememberSaveable { mutableStateOf<String?>(null) }
    var parsing by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    // 用于判定异步结果是否已过期：输入变化时会递增
    val requestSeq = remember { AtomicInteger(0) }

    LaunchedEffect(input) {
        val seq = requestSeq.incrementAndGet()
        val text = input.trim()

        resultId = null
        hint = null
        errorText = null
        copied = false
        parsing = false

        if (text.isEmpty()) return@LaunchedEffect

        // 1. 先做本地匹配：长链接、纯 ID 可立即命中
        val direct = IdParser.extractDirect(text)
        if (direct != null) {
            resultId = direct
            hint = "已从链接参数中直接识别"
            return@LaunchedEffect
        }

        // 2. 本地未命中，判断是否含链接，需要联网解析
        val url = IdParser.firstUrl(text)
        if (url == null) {
            errorText = "没有识别到链接，请粘贴完整的分享链接"
            return@LaunchedEffect
        }

        // 输入停顿后再发起请求，避免逐字输入触发多次网络
        delay(400)
        if (requestSeq.get() != seq) return@LaunchedEffect

        parsing = true
        try {
            val resolved = IdParser.resolve(url)
            if (requestSeq.get() == seq) {
                if (resolved != null) {
                    resultId = resolved.first
                    hint = "短链已解析 · 来源：${resolved.second}"
                } else {
                    errorText = "解析失败。请在浏览器中打开该链接，复制地址栏里的完整长链接后重新粘贴"
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (requestSeq.get() == seq) {
                errorText = "网络请求失败：${e.message ?: "未知错误"}"
            }
        } finally {
            if (requestSeq.get() == seq) parsing = false
        }
    }

    // 复制成功后 2 秒恢复按钮文案
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    fun copyToClipboard(id: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("book_id", id))
        copied = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "链接 ID 提取",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "粘贴分享链接，自动提取 book_id，点击结果即可复制",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 148.dp),
            label = { Text("分享链接") },
            placeholder = { Text("在此粘贴链接…", fontSize = 14.sp) },
            shape = RoundedCornerShape(14.dp),
            trailingIcon = {
                if (input.isNotEmpty()) {
                    TextButton(onClick = { input = "" }) { Text("清空") }
                }
            }
        )

        when {
            parsing -> ParsingRow()
            errorText != null -> MessageCard(text = errorText!!, isError = true)
            resultId != null -> ResultCard(
                id = resultId!!,
                hint = hint,
                copied = copied,
                onCopy = { copyToClipboard(resultId!!) }
            )
            else -> MessageCard(text = "粘贴后自动识别，无需手动操作", isError = false)
        }

        Spacer(Modifier.height(2.dp))

        Text(
            text = "长链接可直接识别；短链接需要联网跟随跳转，约需 1 秒。",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ParsingRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "正在解析短链接…",
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
private fun ResultCard(
    id: String,
    hint: String?,
    copied: Boolean,
    onCopy: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable { onCopy() }
            .padding(horizontal = 20.dp, vertical = 22.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "提取结果",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.65f)
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = id,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = if (copied) "已复制到剪贴板" else "点击复制",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
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
}
