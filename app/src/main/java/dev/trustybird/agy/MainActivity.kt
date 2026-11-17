package dev.trustybird.agy

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AgyTheme { AgyRoot() } }
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
    val ctx = LocalContext.current
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(ctx)
    } else {
        AgyColors
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private sealed interface Screen {
    data object Login : Screen
    data object Chats : Screen
    data class Chat(val id: String?) : Screen
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgyRoot() {
    val ctx = LocalContext.current

    var net by remember { mutableStateOf(NetConfig.load(ctx)) }
    var authed by remember { mutableStateOf(Store.isAuthed(ctx)) }
    var installed by remember { mutableStateOf(AgyBinary.isInstalled(ctx)) }
    var showSettings by remember { mutableStateOf(false) }
    var screen by remember { mutableStateOf<Screen>(if (authed) Screen.Chats else Screen.Login) }

    if (showSettings) {
        SettingsDialog(
            initial = net,
            onDismiss = { showSettings = false },
            onSave = {
                net = it
                NetConfig.save(ctx, it)
                showSettings = false
            },
        )
    }

    when (val s = screen) {
        Screen.Login -> LoginScreen(
            net = net,
            installed = installed,
            onInstalled = { installed = true },
            onSettings = { showSettings = true },
            onAuthed = {
                authed = true
                Store.setAuthed(ctx, true)
                screen = Screen.Chats
            },
        )

        Screen.Chats -> ChatsScreen(
            net = net,
            installed = installed,
            onOpen = { screen = Screen.Chat(it) },
            onNew = { screen = Screen.Chat(null) },
            onSettings = { showSettings = true },
            onInstalled = { installed = it },
            onLogout = {
                Store.setAuthed(ctx, false)
                authed = false
                screen = Screen.Login
            },
        )

        is Screen.Chat -> ChatScreen(
            sessionId = s.id,
            net = net,
            installed = installed,
            onBack = { screen = Screen.Chats },
            onInstalled = { installed = it },
            onSettings = { showSettings = true },
        )
    }
}

/* ---------------------------------- Вход ---------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginScreen(
    net: NetConfig,
    installed: Boolean,
    onInstalled: () -> Unit,
    onSettings: () -> Unit,
    onAuthed: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var apiKey by remember { mutableStateOf(Store.apiKey(ctx)) }
    var job by remember { mutableStateOf<Job?>(null) }
    var session by remember { mutableStateOf<AgyLogin?>(null) }
    var code by remember { mutableStateOf("") }
    var needCode by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { session?.cancel(); job?.cancel() } }

    fun openUrl(url: String) {
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun login(useKey: Boolean) {
        if (busy) return
        busy = true
        needCode = false
        code = ""
        status = if (useKey) "Вхожу по ключу…" else "Запрашиваю ссылку…"
        Store.setApiKey(ctx, apiKey)

        val s = AgyLogin(ctx, net, if (useKey) apiKey else "")
        session = s
        job = scope.launch {
            try {
                s.run().collect { ev ->
                    when (ev) {
                        is AgyLogin.Event.Url -> {
                            needCode = true
                            status = "Войди в Google и вставь код сюда"
                            openUrl(ev.url)
                        }
                        is AgyLogin.Event.Status -> status = ev.text
                        is AgyLogin.Event.Done -> {
                            busy = false
                            needCode = false
                            if (ev.ok) {
                                Store.setAuthMethod(ctx, if (useKey) "api-key" else "google")
                                onAuthed()
                            } else {
                                status = ev.detail
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                busy = false
                status = "Ошибка входа: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                title = { Text("agy") },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Сеть")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Antigravity", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Всё внутри приложения: бинарь, вход, чаты. Termux не нужен.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))

            if (!installed) {
                OutlinedButton(
                    enabled = !installing,
                    onClick = {
                        installing = true
                        progress = null
                        status = "Скачиваю agy…"
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    AgyBinary.install(ctx, net) { msg, frac ->
                                        status = msg
                                        progress = frac
                                    }
                                }
                            }.onSuccess {
                                onInstalled()
                                status = "agy $it готов"
                                progress = 1f
                            }.onFailure {
                                status = "Ошибка загрузки: ${it.message}"
                                progress = null
                            }
                            installing = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (installing) "Скачиваю…" else "Скачать agy") }
                if (installing) {
                    Spacer(Modifier.height(8.dp))
                    if (progress == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { progress!! }, modifier = Modifier.fillMaxWidth())
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            Button(
                enabled = installed && !busy,
                onClick = { login(useKey = false) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Войти через Google") }

            if (needCode) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Код или ссылка из браузера") },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    enabled = code.isNotBlank(),
                    onClick = { session?.submit(code) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Отправить код") }
                TextButton(onClick = { login(useKey = false) }) { Text("Новая ссылка") }
                Text(
                    "agy ждёт код 60 секунд. Не успел — нажми «Новая ссылка».",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("GEMINI_API_KEY") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            TextButton(
                enabled = installed && !busy && apiKey.isNotBlank(),
                onClick = { login(useKey = true) },
            ) { Text("Войти по ключу") }

            status?.let {
                Spacer(Modifier.height(16.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (busy) {
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/* ---------------------------------- Чаты ---------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatsScreen(
    net: NetConfig,
    installed: Boolean,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit,
    onInstalled: (Boolean) -> Unit,
    onLogout: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf(Store.sessions(ctx)) }
    var status by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                title = { Text("Чаты") },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                    IconButton(onClick = onLogout) {
                        Icon(Icons.Default.Logout, contentDescription = "Выйти")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNew) {
                Icon(Icons.Default.Add, contentDescription = "Новый чат")
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!installed) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("agy не установлен", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        Button(
                            enabled = !downloading,
                            onClick = {
                                downloading = true
                                progress = null
                                status = "Скачиваю…"
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            AgyBinary.install(ctx, net) { msg, frac ->
                                                status = msg
                                                progress = frac
                                            }
                                        }
                                    }.onSuccess { onInstalled(true); progress = 1f }
                                        .onFailure { status = "Ошибка: ${it.message}"; progress = null }
                                    downloading = false
                                }
                            },
                        ) { Text(if (downloading) "Скачиваю…" else "Установить") }
                        if (downloading) {
                            Spacer(Modifier.height(8.dp))
                            if (progress == null) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            } else {
                                LinearProgressIndicator(
                                    progress = { progress!! },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        status?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(it, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            if (sessions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Пока пусто", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Нажми +, чтобы начать",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                    items(sessions, key = { it.id }) { s ->
                        SessionRow(
                            session = s,
                            onClick = { onOpen(s.id) },
                            onDelete = {
                                Store.delete(ctx, s.id)
                                sessions = Store.sessions(ctx)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(session: Session, onClick: () -> Unit, onDelete: () -> Unit) {
    val fmt = remember { SimpleDateFormat("d MMM, HH:mm", Locale("ru")) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                session.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                session.messages.lastOrNull()?.text?.take(70) ?: "пусто",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                fmt.format(Date(session.updated)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Удалить")
        }
    }
}

/* ---------------------------------- Чат ----------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(
    sessionId: String?,
    net: NetConfig,
    installed: Boolean,
    onBack: () -> Unit,
    onInstalled: (Boolean) -> Unit,
    onSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var session by remember {
        mutableStateOf(sessionId?.let { Store.get(ctx, it) } ?: Session())
    }
    val messages = remember {
        mutableStateListOf<Message>().also { list -> list.addAll(session.messages) }
    }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var mode by remember { mutableStateOf(AgentMode.AUTO) }
    val listState = rememberLazyListState()
    val runner = remember { AgyRunner(ctx) }

    DisposableEffect(Unit) { onDispose { runner.cancel() } }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    fun persist() {
        session = session.copy(
            messages = messages.toList(),
            updated = System.currentTimeMillis(),
            title = if (session.title == "Новый чат" && messages.any { it.role == "user" }) {
                Store.titleOf(messages.first { it.role == "user" }.text)
            } else {
                session.title
            },
        )
        Store.upsert(ctx, session)
    }

    fun send() {
        val prompt = input.trim()
        if (prompt.isEmpty() || busy) return
        input = ""
        messages.add(Message("user", prompt))
        persist()
        busy = true
        scope.launch {
            runner.stream(
                prompt = prompt,
                cwd = java.io.File(ctx.filesDir, "workspace").apply { mkdirs() }.absolutePath,
                net = net,
                conversationId = session.conversationId,
                mode = mode,
                apiKey = Store.apiKey(ctx),
            ).collect { ev ->
                when (ev) {
                    is AgyEvent.Ready -> status = "инструментов: ${ev.tools}"
                    is AgyEvent.Text -> {
                        val last = messages.lastOrNull()
                        if (last?.role == "agent") {
                            messages[messages.lastIndex] = last.copy(text = last.text + ev.delta)
                        } else {
                            messages.add(Message("agent", ev.delta))
                        }
                    }
                    is AgyEvent.Step -> {
                        messages.add(Message("tool", ev.type, "(${ev.state}) ${ev.detail}"))
                    }
                    is AgyEvent.Comment -> messages.add(Message("info", ev.text))
                    is AgyEvent.Failed -> {
                        messages.add(Message("error", ev.message))
                        busy = false
                    }
                    is AgyEvent.TurnDone -> {
                        busy = false
                        session = session.copy(conversationId = ev.conversationId)
                        status = buildString {
                            append(ev.status)
                            append(" · ${ev.tokens} tok")
                            if (ev.seconds > 0) append(" · ${"%.1f".format(ev.seconds)} с")
                        }
                        if (ev.status != "SUCCESS") {
                            messages.add(Message("error", "status: ${ev.status}"))
                        }
                        persist()
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                    }
                },
                title = {
                    Column {
                        Text(session.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        status?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    }
                },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier.padding(pad).fillMaxSize().imePadding(),
        ) {
            if (!installed) {
                Surface(
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("agy не установлен", modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        AgyBinary.install(ctx, net) { msg, frac ->
                                            status = msg
                                            progress = frac
                                        }
                                    }
                                }.onSuccess { onInstalled(true); progress = 1f }
                                    .onFailure { status = "Ошибка: ${it.message}"; progress = null }
                            }
                        }) { Text("Установить") }
                    }
                    progress?.let {
                        LinearProgressIndicator(
                            progress = { it },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        )
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages) { m -> MessageBubble(m) }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModeChips(mode = mode, enabled = !busy, onChange = { mode = it })
                Spacer(Modifier.weight(1f))
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
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
}
