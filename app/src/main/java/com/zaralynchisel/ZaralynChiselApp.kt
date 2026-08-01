package com.zaralynchisel

import android.app.Application
import com.zaralynchisel.fileaccess.SafFileAccess
import com.zaralynchisel.utils.Logger
import com.zaralynchisel.utils.PreferenceManager
import java.io.File

class ZaralynChiselApp : Application() {

    lateinit var preferenceManager: PreferenceManager
        private set
    val safAccess: SafFileAccess by lazy { SafFileAccess(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        preferenceManager = PreferenceManager(this)

        // Initialize logger — try external storage, fall back to internal
        val externalLogDir = try {
            File(android.os.Environment.getExternalStorageDirectory(), "ZaralynChisel/logs")
        } catch (e: Exception) {
            null
        }
        val logDir = if (externalLogDir?.parentFile?.canWrite() == true) {
            externalLogDir
        } else {
            File(filesDir, "logs")
        }
        Logger.init(logDir)
        // Catch everything uncaught so crashes land in the in-app log instead of
        // vanishing (the previous handler is preserved and still receives it).
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Logger.e("UNCAUGHT on ${thread.name}: ${throwable.javaClass.name}: ${throwable.message}", throwable)
            } catch (_: Throwable) { }
            prev?.uncaughtException(thread, throwable)
        }
        Logger.i("ZaralynChiselApp.onCreate — v${com.zaralynchisel.BuildConfig.VERSION_NAME}, logDir=$logDir")
    }

    companion object {
        lateinit var instance: ZaralynChiselApp
            private set
    }
}