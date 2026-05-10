package com.zaralynchisel.utils

import android.util.Log
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Simple file + logcat logger for ZaralynChisel.
 * Writes to /sdcard/ZaralynChisel/logs/ when file logging is enabled.
 */
object Logger {

    private const val TAG = "ZaralynChisel"
    private var logDir: File? = null
    private var fileLoggingEnabled = false

    fun init(logDirectory: File) {
        logDir = logDirectory
        if (!logDir!!.exists()) logDir!!.mkdirs()
        fileLoggingEnabled = true
    }

    fun d(message: String) {
        Log.d(TAG, message)
        writeToFile("DEBUG", message)
    }

    fun i(message: String) {
        Log.i(TAG, message)
        writeToFile("INFO", message)
    }

    fun w(message: String) {
        Log.w(TAG, message)
        writeToFile("WARN", message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
        writeToFile("ERROR", "$message${throwable?.let { "\n${it.stackTraceToString()}" } ?: ""}")
    }

    private fun writeToFile(level: String, message: String) {
        if (!fileLoggingEnabled || logDir == null) return
        try {
            val date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
            val logFile = File(logDir, "zaralyn_$date.log")
            logFile.appendText("[$time] [$level] $message\n")
        } catch (_: Exception) {
            // Silently fail — don't crash the app over logging
        }
    }
}