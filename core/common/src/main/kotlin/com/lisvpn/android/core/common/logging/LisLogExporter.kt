package com.lisvpn.android.core.common.logging

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class LisLogExporter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val logFile: File
        get() = context.filesDir.resolve("logs/lisvpn.log")

    fun append(priority: Int, tag: String?, message: String, throwable: Throwable?) {
        runCatching {
            val file = logFile
            file.parentFile?.mkdirs()
            if (file.length() > MAX_LOG_BYTES) rotate(file)
            file.appendText(formatLine(priority, tag, message, throwable))
        }
    }

    fun readText(): String = runCatching {
        val file = logFile
        if (!file.exists()) "Логи пока пустые" else file.readText().takeLast(MAX_EXPORT_CHARS)
    }.getOrElse { "Не удалось прочитать логи: ${it.message}" }

    fun clear() {
        runCatching { logFile.delete() }
    }

    private fun rotate(file: File) {
        val old = file.parentFile?.resolve("lisvpn.old.log") ?: return
        runCatching { if (old.exists()) old.delete() }
        runCatching { file.renameTo(old) }
    }

    private fun formatLine(priority: Int, tag: String?, message: String, throwable: Throwable?): String {
        val level = when (priority) {
            android.util.Log.VERBOSE -> "V"
            android.util.Log.DEBUG -> "D"
            android.util.Log.INFO -> "I"
            android.util.Log.WARN -> "W"
            android.util.Log.ERROR -> "E"
            else -> priority.toString()
        }
        val safe = maskSensitive(message)
        val stack = throwable?.let { "\n${maskSensitive(android.util.Log.getStackTraceString(it))}" }.orEmpty()
        return "${System.currentTimeMillis()} $level/${tag ?: "LisVPN"}: $safe$stack\n"
    }

    private fun maskSensitive(message: String): String = message
        .replace(UUID_REGEX, "<uuid>")
        .replace(VLESS_URI_REGEX, "vless://<masked>")
        .replace(TOKEN_REGEX, "<token>")
        .replace(IPV4_REGEX, "<ip>")
        .replace(SUB_URL_REGEX) { match -> "${match.value.substringBefore("/sub/")}/sub/<masked>" }

    private companion object {
        const val MAX_LOG_BYTES = 1_000_000L
        const val MAX_EXPORT_CHARS = 200_000
        val UUID_REGEX = Regex("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b")
        val VLESS_URI_REGEX = Regex("vless://[^\\s]+", RegexOption.IGNORE_CASE)
        val TOKEN_REGEX = Regex("\\b[A-Za-z0-9_-]{24,}\\b")
        val IPV4_REGEX = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
        val SUB_URL_REGEX = Regex("https?://[^\\s]+/sub/[^\\s]+")
    }
}

class LisFileLogTree(
    private val exporter: LisLogExporter,
    private val isDebug: Boolean,
) : Timber.Tree() {
    override fun isLoggable(tag: String?, priority: Int): Boolean =
        if (isDebug) true else priority >= android.util.Log.WARN

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        exporter.append(priority, tag, message, t)
    }
}
