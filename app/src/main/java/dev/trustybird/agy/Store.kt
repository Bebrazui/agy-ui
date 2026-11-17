package dev.trustybird.agy

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class Message(
    val role: String,          // user | agent | tool | error | info
    val text: String,
    val extra: String = "",
    val ts: Long = System.currentTimeMillis(),
)

@Serializable
data class Session(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Новый чат",
    val conversationId: String? = null,
    val updated: Long = System.currentTimeMillis(),
    val messages: List<Message> = emptyList(),
)

/**
 * Всё состояние приложения — внутри его песочницы: сессии, авторизация,
 * ключ API. Termux не нужен.
 */
object Store {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)

    fun isAuthed(ctx: Context): Boolean = prefs(ctx).getBoolean("authed", false)
    fun setAuthed(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean("authed", value).apply()
    }

    fun apiKey(ctx: Context): String = prefs(ctx).getString("apiKey", "") ?: ""
    fun setApiKey(ctx: Context, key: String) {
        prefs(ctx).edit().putString("apiKey", key).apply()
    }

    fun authMethod(ctx: Context): String = prefs(ctx).getString("authMethod", "") ?: ""
    fun setAuthMethod(ctx: Context, method: String) {
        prefs(ctx).edit().putString("authMethod", method).apply()
    }

    private fun file(ctx: Context) = File(ctx.filesDir, "sessions.json")

    fun sessions(ctx: Context): List<Session> {
        val f = file(ctx)
        if (!f.isFile) return emptyList()
        return runCatching {
            json.decodeFromString<List<Session>>(f.readText())
        }.getOrDefault(emptyList()).sortedByDescending { it.updated }
    }

    fun save(ctx: Context, list: List<Session>) {
        runCatching { file(ctx).writeText(json.encodeToString(list)) }
    }

    fun upsert(ctx: Context, session: Session) {
        val list = sessions(ctx).toMutableList()
        val idx = list.indexOfFirst { it.id == session.id }
        if (idx >= 0) list[idx] = session else list.add(session)
        save(ctx, list)
    }

    fun delete(ctx: Context, id: String) {
        save(ctx, sessions(ctx).filterNot { it.id == id })
    }

    fun get(ctx: Context, id: String): Session? = sessions(ctx).firstOrNull { it.id == id }

    fun titleOf(text: String): String =
        text.trim().replace('\n', ' ').take(48).ifBlank { "Новый чат" }
}
