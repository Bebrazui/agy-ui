package dev.trustybird.agy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MessageBubble(item: Message) {
    val (bg, fg) = when (item.role) {
        "user" -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) to MaterialTheme.colorScheme.onSurface
        "agent" -> MaterialTheme.colorScheme.surface to MaterialTheme.colorScheme.onSurface
        "tool" -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f) to MaterialTheme.colorScheme.onSurface
        "error" -> MaterialTheme.colorScheme.error.copy(alpha = 0.16f) to MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.5f) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = bg, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            if (item.role == "tool" || item.role == "info") {
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
fun ModeChips(mode: AgentMode, enabled: Boolean, onChange: (AgentMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AgentMode.entries.forEach { m ->
            FilterChip(
                selected = mode == m,
                enabled = enabled,
                onClick = { onChange(m) },
                label = { Text(m.label, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    initial: NetConfig,
    onDismiss: () -> Unit,
    onSave: (NetConfig) -> Unit,
) {
    var cfg by remember { mutableStateOf(initial) }
    var diag by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val kinds = listOf(ProxyKind.DIRECT, ProxyKind.HTTP, ProxyKind.SOCKS5)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сеть") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Прокси для agy и для скачивания", style = MaterialTheme.typography.labelMedium)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    kinds.forEachIndexed { i, k ->
                        SegmentedButton(
                            selected = cfg.kind == k,
                            onClick = { cfg = cfg.copy(kind = k, dns = cfg.dns) },
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = if (cfg.port == 0) "" else cfg.port.toString(),
                            onValueChange = {
                                cfg = cfg.copy(port = it.filter(Char::isDigit).toIntOrNull() ?: 0)
                            },
                            label = { Text("Порт") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = cfg.user,
                            onValueChange = { cfg = cfg.copy(user = it) },
                            label = { Text("Логин") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
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
                        cfg = cfg.copy(
                            dns = it.split(',', ' ', '\n').map(String::trim).filter(String::isNotEmpty),
                        )
                    },
                    label = { Text("DNS-серверы (DoT / DoH)") },
                    placeholder = { Text("dns.dns-ai.ru, 1.1.1.1, https://dns.google/dns-query") },
                    supportingText = {
                        Text(
                            "Обычное имя или IP — сначала DoT (853), потом DoH. " +
                                "tls:// — только DoT, https:// — только DoH. " +
                                "Если сервер не ответил — берётся следующий.",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    maxLines = 3,
                )

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        enabled = !checking,
                        onClick = {
                            checking = true
                            diag = "Проверяю…"
                            scope.launch {
                                diag = withContext(Dispatchers.IO) { NetDiag.run(cfg) }
                                checking = false
                            }
                        },
                    ) { Text("Проверить") }
                    TextButton(
                        enabled = !checking,
                        onClick = {
                            checking = true
                            diag = "Запускаю agy через прокси…"
                            scope.launch {
                                diag = withContext(Dispatchers.IO) { ProxyCheck.run(ctx, cfg) }
                                checking = false
                            }
                        },
                    ) { Text("agy через прокси") }
                    TextButton(onClick = {
                        diag = "Читаю логи…"
                        scope.launch {
                            diag = withContext(Dispatchers.IO) {
                                buildString {
                                    appendLine("--- login.log ---")
                                    appendLine(
                                        runCatching {
                                            java.io.File(ctx.filesDir, "login.log").readText()
                                        }.getOrDefault("нет файла"),
                                    )
                                    appendLine()
                                    appendLine("--- crash.txt ---")
                                    append(
                                        runCatching {
                                            java.io.File(ctx.filesDir, "crash.txt").readText()
                                        }.getOrDefault("падений не было"),
                                    )
                                }
                            }
                        }
                    }) { Text("Логи") }
                    TextButton(onClick = { cfg = NetConfig() }) { Text("Сбросить") }
                }

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
