package com.hchee.pokepicker

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 파일 로그: 문제 진단용. MainActivity의 '로그 공유'로 내보낼 수 있다. */
object AppLog {
    private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "applog.txt")
    }

    @Synchronized
    fun log(msg: String) {
        Log.d("PokePicker", msg)
        val f = file ?: return
        try {
            if (f.exists() && f.length() > 300_000) {
                f.writeText(f.readText().takeLast(150_000))
            }
            f.appendText("${fmt.format(Date())}  $msg\n")
        } catch (_: Exception) { }
    }

    fun log(msg: String, e: Throwable) = log("$msg :: ${Log.getStackTraceString(e)}")

    fun read(): String = file?.takeIf { it.exists() }?.readText() ?: "(로그 없음)"

    fun clear() { runCatching { file?.writeText("") } }
}
