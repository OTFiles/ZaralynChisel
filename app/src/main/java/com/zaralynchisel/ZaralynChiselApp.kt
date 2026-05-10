package com.zaralynchisel

import android.app.Application
import com.zaralynchisel.utils.PreferenceManager

class ZaralynChiselApp : Application() {

    lateinit var preferenceManager: PreferenceManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        preferenceManager = PreferenceManager(this)
    }

    companion object {
        lateinit var instance: ZaralynChiselApp
            private set
    }
}