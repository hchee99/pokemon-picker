package com.hchee.pokepicker

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/** 웹 계산기 전체 화면 (초기 데이터 임포트 + 내 팀/프리셋 설정용) */
class CalcActivity : Activity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Dex.load(this)
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    val js = """
                        (function(){
                          try {
                            if(!localStorage.getItem('pkmn_dex_v1')) { doImport(${JSONObject.quote(Dex.rawJson)}); }
                          } catch(e) {}
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(js, null)
                }
            }
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(web)
    }
}
