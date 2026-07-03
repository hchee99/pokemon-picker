package com.hchee.pokepicker

import android.app.Application
import android.util.Log

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.log("=== 앱 시작 (sdk=${android.os.Build.VERSION.SDK_INT}, ${android.os.Build.MODEL}) ===")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            AppLog.log("CRASH: ${Log.getStackTraceString(e)}")
            prev?.uncaughtException(t, e)
        }
    }
}
