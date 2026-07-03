package com.hchee.pokepicker

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 설정 화면: 권한 3개 허용 -> 감시 시작 -> 게임으로 */
class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        root.addView(TextView(this).apply {
            text = "포켓 선발 도우미"
            textSize = 22f
        })
        root.addView(TextView(this).apply {
            text = "게임 팀 프리뷰에서 스크린샷을 찍으면, 상대 6마리를 인식해 게임 위에 선택지와 3마리 추천을 띄워줘요. 게임을 벗어나지 않아 통신이 끊기지 않아요."
            textSize = 14f
            setPadding(0, dp(8), 0, dp(16))
        })

        status = TextView(this).apply { textSize = 13f; setPadding(0, 0, 0, dp(16)) }
        root.addView(status)

        root.addView(bigButton("1. 다른 앱 위에 표시 허용") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(bigButton("2. 사진(스크린샷) 접근 허용") {
            val perms = if (Build.VERSION.SDK_INT >= 33)
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.POST_NOTIFICATIONS)
            else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            requestPermissions(perms, 1)
        })
        root.addView(bigButton("3. 감시 시작 ▶") {
            if (!Settings.canDrawOverlays(this)) { toast("먼저 1번 오버레이 권한을 허용해 주세요"); return@bigButton }
            if (!hasMedia()) { toast("먼저 2번 사진 접근을 허용해 주세요"); return@bigButton }
            startForegroundService(Intent(this, OverlayService::class.java))
            toast("시작! 게임에서 팀 프리뷰 스크린샷을 찍어보세요")
        })
        root.addView(bigButton("감시 중지 ■") {
            stopService(Intent(this, OverlayService::class.java))
        })
        root.addView(bigButton("계산기 열기 (내 팀·데이터 설정)") {
            startActivity(Intent(this, CalcActivity::class.java))
        })

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        val overlay = if (Settings.canDrawOverlays(this)) "✅" else "❌"
        val media = if (hasMedia()) "✅" else "❌"
        status.text = "권한 상태:  오버레이 $overlay   사진 접근 $media"
    }

    private fun hasMedia(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    }

    private fun bigButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(8)
        layoutParams = lp
        setOnClickListener { onClick() }
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
