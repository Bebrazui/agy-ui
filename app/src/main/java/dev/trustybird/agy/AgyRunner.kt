package dev.trustybird.agy

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.concurrent.TimeUnit

sealed interface AgyEvent {
    data class Ready(val conversationId: String, val cwd: String, val tools: Int) : AgyEvent
    data class Text(val delta: String) : AgyEvent
    data class Step(val type: String, val state: String, val detail: String) : AgyEvent
    data class TurnDone(
        val conversationId: String,
        val status: String,
        val response: String,
        val seconds: Double,
        val tokens: Long,
    ) : AgyEvent
    data class Comment(val text: String) : AgyEvent
    data class Failed(val message: String) : AgyEvent
}

enum class AgentMode(val flag: String, val label: String) {
    PLAN("plan", "Plan"),
    AUTO("auto", "Auto"),
    ACCEPT_EDITS("accept-edits", "Правки"),
}

/**
 * Headless-режим: agy -p <prompt> --output-format stream-json.
 *
 * Формат (подтверждён живым прогоном 1.5.10):
 *   {"event":"init","conversation_id":"…","init":{"cwd":"…","tools":[…],"permission_mode":"…"}}
 *   {"event":"step_update","step_update":{"step_index":1,"state":"ACTIVE",
 *        "step_type":"agent_response","text_delta":"…"}}
 *   {"event":"result","result":{"status":"SUCCESS","response":"…","usage":{"total_tokens":…}}}
 *
 * Память между сообщениями держим через --conversation <id> из init/result.
 */
class AgyRunner(private val ctx: Context) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var process: Process? = null

    fun cancel() {
        process?.let {
            it.destroy()
            if (!it.waitFor(2, TimeUnit.SECONDS)) it.destroyForcibly()
        }
        process = null
    }

    fun stream(
        prompt: String,
        cwd: String,
        net: NetConfig,
        conversationId: String? = null,
        mode: AgentMode = AgentMode.AUTO,
        model: String? = null,
        apiKey: String = "",
    ): Flow<AgyEvent> = flow {
        val bin = AgyBinary.binary(ctx)
        if (!bin.isFile) {
            emit(AgyEvent.Failed("agy не установлен"))
            return@flow
        }
        File(cwd).mkdirs()

        val args = mutableListOf(
            bin.absolutePath,
            "-p", prompt,
            "--output-format", "stream-json",
            "--mode", mode.flag,
        )
        conversationId?.let { args += listOf("--conversation", it) }
        model?.let { args += listOf("--model", it) }

        val pb = ProcessBuilder(args).apply {
            directory(File(cwd))
            redirectErrorStream(true)
            environment().apply {
                putAll(net.toEnv())
                put("HOME", File(ctx.filesDir, "home").apply { mkdirs() }.absolutePath)
                put("TMPDIR", File(ctx.cacheDir, "tmp").apply { mkdirs() }.absolutePath)
                put("TERM", "dumb")
                put("NO_COLOR", "1")
                if (apiKey.isNotBlank()) put("GEMINI_API_KEY", apiKey)
            }
        }

        val proc = pb.start()
        process = proc
        proc.outputStream.close()

        var sawResult = false
        proc.inputStream.bufferedReader().use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                parse(line)?.let {
                    if (it is AgyEvent.TurnDone) sawResult = true
                    emit(it)
                }
            }
        }

        val code = proc.waitFor()
        process = null
        if (!sawResult) {
            emit(AgyEvent.Failed("agy завершился с кодом $code без результата"))
        }
    }.flowOn(Dispatchers.IO)

    private fun parse(line: String): AgyEvent? {
        // Go-логгер пишет служебные строки прямо в stdout.
        if (line.startsWith("ERROR: logging before google.Init")) return null

        val root = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
            ?: return AgyEvent.Comment(line.take(400))

        return when (root.str("event")) {
            "init" -> {
                val init = root["init"] as? JsonObject
                AgyEvent.Ready(
                    conversationId = root.str("conversation_id") ?: "",
                    cwd = init?.str("cwd") ?: "",
                    tools = (init?.get("tools") as? kotlinx.serialization.json.JsonArray)?.size ?: 0,
                )
            }

            "step_update" -> {
                val step = root["step_update"] as? JsonObject ?: return null
                val type = step.str("step_type") ?: ""
                val state = step.str("state") ?: ""
                val delta = step.str("text_delta")
                when {
                    delta != null -> AgyEvent.Text(delta)
                    type == "user_input" -> null
                    else -> AgyEvent.Step(type, state, step.toString().take(300))
                }
            }

            "result" -> {
                val res = root["result"] as? JsonObject ?: return null
                val status = res.str("status") ?: ""
                val usage = res["usage"] as? JsonObject
                val tokens = usage?.get("total_tokens")?.let {
                    (it as? JsonPrimitive)?.content?.toLongOrNull()
                } ?: 0L
                AgyEvent.TurnDone(
                    conversationId = res.str("conversation_id") ?: "",
                    status = status,
                    response = res.str("response") ?: "",
                    seconds = res.str("duration_seconds")?.toDoubleOrNull() ?: 0.0,
                    tokens = tokens,
                )
            }

            else -> AgyEvent.Comment(line.take(300))
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.let { if (it.isString) it.content else null }
}
