package dev.trustybird.agy

import android.app.Application
import java.io.File

/**
 * Ловит падения и пишет их в файл, чтобы причину можно было увидеть,
 * а не угадывать. Файл потом показывается в «Настройки → Логи».
 */
class AgyApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val file = File(filesDir, "crash.txt")
                file.writeText(
                    buildString {
                        appendLine("Время: ${java.util.Date()}")
                        appendLine("Поток: ${thread.name}")
                        appendLine(error.stackTraceToString())
                        appendLine()
                        appendLine("--- login.log ---")
                        appendLine(runCatching { File(filesDir, "login.log").readText() }.getOrDefault(""))
                    },
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
