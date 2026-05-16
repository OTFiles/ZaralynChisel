package com.zaralynchisel.utils

import android.util.Log
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Logger for ZaralynChisel.
 * Writes to logcat + file + in-memory ring buffer (for in-app viewer).
 */
object Logger {

    private const val TAG = "ZaralynChisel"
    private const val MAX_BUFFER_LINES = 500

    private var logDir: File? = null
    private var fileLoggingEnabled = false

    // In-memory ring buffer for in-app log viewer
    private val buffer = ArrayDeque<LogEntry>(MAX_BUFFER_LINES)

    data class LogEntry(
        val timestamp: String,
        val level: String,
        val message: String,
        val formatted: String
    )

    fun init(logDirectory: File) {
        logDir = logDirectory
        if (!logDir!!.exists()) logDir!!.mkdirs()
        fileLoggingEnabled = true
    }

    fun d(message: String) {
        Log.d(TAG, message)
        val entry = buildEntry("DEBUG", message)
        addToBuffer(entry)
        writeToFile(entry)
    }

    fun i(message: String) {
        Log.i(TAG, message)
        val entry = buildEntry("INFO", message)
        addToBuffer(entry)
        writeToFile(entry)
    }

    fun w(message: String) {
        Log.w(TAG, message)
        val entry = buildEntry("WARN", message)
        addToBuffer(entry)
        writeToFile(entry)
    }

    fun e(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
        val fullMsg = "$message${throwable?.let { "\n${it.stackTraceToString()}" } ?: ""}"
        val entry = buildEntry("ERROR", fullMsg)
        addToBuffer(entry)
        writeToFile(entry)
    }

    // ── In-app viewer API ─────────────────────────────────────────────

    /** Get all buffered log entries (newest last). */
    fun getBufferedLogs(): List<LogEntry> = buffer.toList()

    /** Get all buffered logs as a single plain-text string (for copy/share). */
    fun getBufferedLogsText(): String =
        buffer.joinToString("\n") { it.formatted }

    /** Get log files from disk. */
    fun getLogFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        return dir.listFiles { f -> f.name.startsWith("zaralyn_") && f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /** Read a log file content. */
    fun readLogFile(file: File): String? =
        try { file.readText() } catch (_: Exception) { null }

    /** Clear in-memory buffer. */
    fun clearBuffer() {
        buffer.clear()
    }

    // ── Private ───────────────────────────────────────────────────────

    private fun buildEntry(level: String, message: String): LogEntry {
        val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
        val date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val formatted = "[$date $time] [$level] $message"
        return LogEntry(timestamp = time, level = level, message = message, formatted = formatted)
    }

    private fun addToBuffer(entry: LogEntry) {
        synchronized(buffer) {
            if (buffer.size >= MAX_BUFFER_LINES) {
                buffer.removeFirst()
            }
            buffer.addLast(entry)
        }
    }

    private fun writeToFile(entry: LogEntry) {
        if (!fileLoggingEnabled || logDir == null) return
        try {
            val date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            val logFile = File(logDir, "zaralyn_$date.log")
            logFile.appendText("${entry.formatted}\n")
        } catch (_: Exception) { }
    }
}