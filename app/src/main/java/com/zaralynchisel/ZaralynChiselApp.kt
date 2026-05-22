package com.zaralynchisel

import android.app.Application
import com.zaralynchisel.utils.Logger
import com.zaralynchisel.utils.PreferenceManager
import java.io.File

class ZaralynChiselApp : Application() {

    lateinit var preferenceManager: PreferenceManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        preferenceManager = PreferenceManager(this)

        // Initialize logger with external logs directory
        val logDir = File(
            android.os.Environment.getExternalStorageDirectory(),
            "ZaralynChisel/logs"
        )
        Logger.init(logDir)
        Logger.i("ZaralynChiselApp onCreate — v0.1.0-alpha, logDir=$logDir")
    }

    companion object {
        lateinit var instance: ZaralynChiselApp
            private set
    }
}