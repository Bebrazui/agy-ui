package dev.trustybird.agy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AgyTheme { AgyApp() } }
    }
}

private val AgyColors = darkColorScheme(
    primary = Color(0xFF7AD7F0),
    secondary = Color(0xFFB9A7FF),
    background = Color(0xFF0E1013),
    surface = Color(0xFF161A1F),
    onSurfaceVariant = Color(0xFF9BA3AD),
)

@Composable
fun AgyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AgyColors, content = content)
}

private enum class Kind { USER, AGENT, TOOL, ERROR, INFO }

private data class ChatItem(val kind: Kind, val text: String, val extra: String = "")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgyApp() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var installed by remember { mutableStateOf(AgyBinary.isInstalled(ctx)) }
    var version by remember { mutableStateOf(AgyBinary.installedVersion(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(version?.let { "agy $it" }) }
    var showSettings by remember { mutableStateOf(false) }
    var net by remember { mutableStateOf(NetConfig.load(ctx)) }
    var conversationId by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf(AgentMode.AUTO) }

    val items = remember { mutableStateListOf<ChatItem>() }
    val runner = remember { AgyRunner(ctx) }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val cwd = remember { java.io.File(ctx.filesDir, "workspace").apply { mkdirs() }.absolutePath }

    LaunchedEffect(items.size) {
        if (items.isNotEmpty()) listState.animateScrollToItem(items.lastIndex)
    }

    fun append(kind: Kind, text: String, extra: String = "") {
        if (kind == Kind.AGENT && items.lastOrNull()?.kind == Kind.AGENT) {
            val last = items.removeAt(items.lastIndex)
            items.add(last.copy(text = last.text + text))
        } else {
            items.add(ChatItem(kind, text, extra))
        }
    }

    fun send() {
        val prompt = input.trim()
        if (prompt.isEmpty() || busy) return
        input = ""
        items.add(ChatItem(Kind.USER, prompt))
        busy = true
        scope.launch {
            runner.stream(prompt, cwd, net, conversationId, mode).collect { ev ->
                when (ev) {
                    is AgyEvent.Ready -> {
                        conversationId = ev.conversationId
                        status = "инструментов: ${ev.tools}"
                    }
                    is AgyEvent.Text -> append(Kind.AGENT, ev.delta)
                    is AgyEvent.Step -> append(Kind.TOOL, ev.type, "(${ev.state}) ${ev.detail}")
                    is AgyEvent.Comment -> append(Kind.INFO, ev.text)
                    is AgyEvent.Failed -> {
                        append(Kind.ERROR, ev.message)
                        busy = false
                    }
                    is AgyEvent.TurnDone -> {
                        busy = false
                        conversationId = ev.conversationId
                        status = buildString {
                            append(ev.status)
                            append(" · ${ev.tokens} tok")
                            if (ev.seconds > 0) append(" · ${"%.1f".format(ev.seconds)} с")
                        }
                        if (ev.status != "SUCCESS") append(Kind.ERROR, "status: ${ev.status}")
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                title = {
                    Column {
                        Text("agy", style = MaterialTheme.typography.titleMedium)
                        status?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    }
                },
                actions = {
                    if (!installed) {
                        TextButton(onClick = {
                            busy = true
                            status = "Установка…"
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        AgyBinary.install(ctx, net) { status = it }
                                    }
                                }.onSuccess {
                                    version = it
                                    installed = true
                                    status = "agy $it"
                                }.onFailure {
                                    status = "Ошибка: ${it.message}"
                                }
                                busy = false
                            }
                        }) { Text("Установить") }
                    }
                    IconButton(onClick = {
                        conversationId = null
                        items.clear()
                        status = version?.let { "agy $it" }
                    }) { Icon(Icons.Default.Add, contentDescription = "Новый чат") }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .imePadding(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items) { item -> Bubble(item) }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AgentMode.entries.forEach { m ->
                    FilterChip(
                        selected = mode == m,
                        onClick = { mode = m },
                        label = { Text(m.label, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = { runner.cancel() }) { Text("Стоп") }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Что делаем?") },
                    maxLines = 6,
                    shape = RoundedCornerShape(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { send() },
                    enabled = installed && !busy && input.isNotBlank(),
                ) { Text("→", fontSize = 18.sp) }
            }
        }
    }

    if (showSettings) {
        SettingsSheet(
            initial = net,
            onDismiss = { showSettings = false },
            onSave = {
                net = it
                NetConfig.save(ctx, it)
                showSettings = false
            },
        )
    }
}

@Composable
private fun Bubble(item: ChatItem) {
    val (bg, fg) = when (item.kind) {
        Kind.USER -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) to MaterialTheme.colorScheme.onSurface
        Kind.AGENT -> MaterialTheme.colorScheme.surface to MaterialTheme.colorScheme.onSurface
        Kind.TOOL -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f) to MaterialTheme.colorScheme.onSurface
        Kind.ERROR -> MaterialTheme.colorScheme.error.copy(alpha = 0.16f) to MaterialTheme.colorScheme.error
        Kind.INFO -> MaterialTheme.colorScheme.surface.copy(alpha = 0.5f) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = bg, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            if (item.kind == Kind.TOOL || item.kind == Kind.INFO) {
                Text("⚙ ${item.text}", style = MaterialTheme.typography.labelMedium)
                if (item.extra.isNotBlank()) {
                    Text(
                        item.extra,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(item.text, color = fg, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    initial: NetConfig,
    onDismiss: () -> Unit,
    onSave: (NetConfig) -> Unit,
) {
    var cfg by remember { mutableStateOf(initial) }
    var diag by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val kinds = listOf(ProxyKind.DIRECT, ProxyKind.HTTP, ProxyKind.SOCKS5)

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сеть") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    kinds.forEachIndexed { i, k ->
                        SegmentedButton(
                            selected = cfg.kind == k,
                            onClick = { cfg = cfg.copy(kind = k) },
                            shape = SegmentedButtonDefaults.itemShape(i, kinds.size),
                        ) { Text(k.name) }
                    }
                }
                if (cfg.kind != ProxyKind.DIRECT) {
                    OutlinedTextField(
                        value = cfg.host,
                        onValueChange = { cfg = cfg.copy(host = it.trim()) },
                        label = { Text("Хост") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = if (cfg.port == 0) "" else cfg.port.toString(),
                        onValueChange = { cfg = cfg.copy(port = it.filter(Char::isDigit).toIntOrNull() ?: 0) },
                        label = { Text("Порт") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = cfg.user,
                        onValueChange = { cfg = cfg.copy(user = it) },
                        label = { Text("Логин (необязательно)") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = cfg.pass,
                        onValueChange = { cfg = cfg.copy(pass = it) },
                        label = { Text("Пароль") },
                        singleLine = true,
                    )
                }
                OutlinedTextField(
                    value = cfg.dns.joinToString(", "),
                    onValueChange = {
                        cfg = cfg.copy(dns = it.split(',', ' ').map(String::trim).filter(String::isNotEmpty))
                    },
                    label = { Text("DNS-серверы (необязательно)") },
                    supportingText = {
                        Text(
                            "Обычный резолвер. Блокировки не обходит.",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    singleLine = true,
                )
                TextButton(onClick = {
                    diag = "Проверяю…"
                    scope.launch {
                        diag = withContext(Dispatchers.IO) { NetDiag.run(cfg) }
                    }
                }) { Text("Диагностика сети") }
                diag?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(cfg) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
