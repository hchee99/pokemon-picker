package com.hchee.pokepicker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * 포그라운드 서비스:
 *  - MediaStore를 감시해 새 스크린샷이 생기면 자동으로 인식 실행
 *  - 게임 위 오버레이로 상대 6칸 + 후보 표시 (탭으로 확정)
 *  - "추천 보기"를 누르면 오버레이 WebView로 기존 웹 계산기를 띄워 자동 입력
 * 게임을 벗어나지 않으므로 온라인 배틀 통신이 끊기지 않는다.
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var bubble: TextView? = null
    private var panel: View? = null
    private var webPanel: View? = null
    private var observer: ContentObserver? = null
    private var lastId = -1L
    private var lastResults: List<Recognizer.SlotResult> = emptyList()
    private val selected = HashMap<Int, String>()
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var capturing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        Dex.load(this)
        startAsForeground()
        showBubble()
        watchScreenshots()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra("data")
        if (code != 0 && data != null && projection == null) {
            // API 34: 프로젝션 얻기 전에 mediaProjection 타입으로 포그라운드 재선언
            startAsForeground(withProjection = true)
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mpm.getMediaProjection(code, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { projection = null }
            }, main)
            bubble?.text = "⚡"
            Toast.makeText(this, "⚡를 누르면 화면을 캡처해 인식해요 (저장 없음)", Toast.LENGTH_SHORT).show()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        observer?.let { contentResolver.unregisterContentObserver(it) }
        projection?.stop()
        listOf(bubble, panel, webPanel).forEach { v -> v?.let { runCatching { wm.removeView(it) } } }
        super.onDestroy()
    }

    // ---------- 저장 없는 화면 캡처 (MediaProjection) ----------
    private fun captureNow() {
        val mp = projection ?: return
        if (capturing) return
        capturing = true
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        val w = dm.widthPixels; val h = dm.heightPixels
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        var vd: android.hardware.display.VirtualDisplay? = null
        var done = false
        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            if (done) { img.close(); return@setOnImageAvailableListener }
            done = true
            try {
                val plane = img.planes[0]
                val rowStride = plane.rowStride; val pixelStride = plane.pixelStride
                val bmpW = rowStride / pixelStride
                val full = Bitmap.createBitmap(bmpW, h, Bitmap.Config.ARGB_8888)
                full.copyPixelsFromBuffer(plane.buffer)
                val shot = if (bmpW != w) Bitmap.createBitmap(full, 0, 0, w, h) else full
                img.close()
                main.post { runCatching { vd?.release() }; reader.close(); capturing = false }
                Thread { handleBitmap(shot) }.start()
            } catch (e: Exception) {
                img.close()
                main.post { runCatching { vd?.release() }; reader.close(); capturing = false }
            }
        }, main)
        vd = mp.createVirtualDisplay(
            "pokecap", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, main
        )
        // 3초 안에 프레임이 안 오면 정리
        main.postDelayed({
            if (!done) { runCatching { vd?.release() }; runCatching { reader.close() }; capturing = false }
        }, 3000)
    }

    private fun handleBitmap(bmp: Bitmap) {
        val results = Recognizer.recognize(bmp)
        main.post {
            if (results.size < 3) {
                Toast.makeText(this, "상대 패널을 못 찾았어요 (팀 프리뷰 화면인지 확인)", Toast.LENGTH_SHORT).show()
            } else {
                lastResults = results
                selected.clear()
                results.forEachIndexed { i, r -> r.candidates.firstOrNull()?.let { selected[i] = it } }
                showPanel()
            }
        }
    }

    // ---------- 포그라운드 알림 ----------
    private fun startAsForeground(withProjection: Boolean = false) {
        val chId = "watch"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(chId, "화면 인식", NotificationManager.IMPORTANCE_LOW)
        )
        val n: Notification = Notification.Builder(this, chId)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("포켓 선발 도우미 실행 중")
            .setContentText(if (withProjection) "⚡ 버튼을 누르면 캡처·인식 (저장 없음)" else "팀 프리뷰 스크린샷을 찍으면 자동 인식해요")
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (withProjection) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(1, n, type)
        } else startForeground(1, n)
    }

    // ---------- 스크린샷 감시 ----------
    private fun watchScreenshots() {
        observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                checkLatestScreenshot()
            }
        }
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer!!
        )
    }

    private fun checkLatestScreenshot() {
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH
        )
        val since = System.currentTimeMillis() / 1000 - 20
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj,
            "${MediaStore.Images.Media.DATE_ADDED} >= ?", arrayOf(since.toString()),
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { c ->
            if (!c.moveToFirst()) return
            val id = c.getLong(0)
            val name = (c.getString(2) ?: "") + (c.getString(3) ?: "")
            if (id == lastId) return
            if (!name.contains("screenshot", true) && !name.contains("스크린샷")) return
            lastId = id
            val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
            Thread { process(uri) }.start()
        }
    }

    private fun process(uri: Uri) {
        val bmp: Bitmap = try {
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return
        } catch (e: Exception) { return }
        handleBitmap(bmp)
    }

    // ---------- 작은 플로팅 버블 ----------
    private fun showBubble() {
        val tv = TextView(this).apply {
            text = "⚡"
            textSize = 20f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = pill(0xEE333344.toInt())
            setTextColor(Color.WHITE)
        }
        val lp = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(8); lp.y = dp(120)
        var dx = 0f; var dy = 0f; var moved = false
        tv.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX - lp.x; dy = e.rawY - lp.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (e.rawX - dx).toInt(); lp.y = (e.rawY - dy).toInt()
                    if (kotlin.math.abs(e.rawX - dx - lp.x) > 6) moved = true
                    wm.updateViewLayout(v, lp); moved = true; true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        when {
                            panel != null -> hidePanel()
                            projection != null -> captureNow()   // 저장 없이 즉시 캡처·인식
                            lastResults.isNotEmpty() -> showPanel()
                            else -> Toast.makeText(this, "팀 프리뷰에서 스크린샷을 찍으면 자동 인식해요", Toast.LENGTH_SHORT).show()
                        }
                    }
                    true
                }
                else -> false
            }
        }
        wm.addView(tv, lp)
        bubble = tv
    }

    // ---------- 인식 결과 패널 ----------
    private fun showPanel() {
        hidePanel(); hideWeb()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pill(0xF01E1E28.toInt(), 14)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(TextView(this).apply {
            text = "상대 인식 결과 — 후보를 탭해 확정"
            setTextColor(Color.WHITE); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(6))
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lastResults.forEachIndexed { i, r ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
            row.addView(TextView(this).apply {
                text = "${i + 1}. ${if (r.types.isEmpty()) "(인식 실패)" else r.types.joinToString("/")}"
                setTextColor(0xFFAAB2FF.toInt()); textSize = 12f
            })
            val chipsWrap = HorizontalScrollView(this)
            val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            if (r.candidates.isEmpty()) {
                chips.addView(TextView(this).apply {
                    text = "후보 없음"; setTextColor(0xFF888888.toInt()); textSize = 12f
                })
            }
            r.candidates.forEach { name ->
                chips.addView(TextView(this).apply {
                    text = name; textSize = 12f
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    val sel = selected[i] == name
                    background = pill(if (sel) 0xFF4A6CD4.toInt() else 0xFF3A3A48.toInt())
                    setTextColor(Color.WHITE)
                    val p = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ); p.rightMargin = dp(6); layoutParams = p
                    setOnClickListener { selected[i] = name; showPanel() }
                })
            }
            chipsWrap.addView(chips)
            row.addView(chipsWrap)
            list.addView(row)
        }
        val scroll = ScrollView(this)
        scroll.addView(list)
        val disp = resources.displayMetrics
        scroll.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (disp.heightPixels * 0.55).toInt()
        )
        root.addView(scroll)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        btnRow.addView(Button(this).apply {
            text = "추천 보기"
            setOnClickListener { hidePanel(); showWeb() }
        })
        btnRow.addView(Button(this).apply {
            text = "닫기"
            setOnClickListener { hidePanel() }
        })
        root.addView(btnRow)

        val lp = overlayParams(
            (disp.widthPixels * 0.46).toInt().coerceAtLeast(dp(320)),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = Gravity.TOP or Gravity.END
        lp.x = dp(8); lp.y = dp(40)
        wm.addView(root, lp)
        panel = root
    }

    private fun hidePanel() { panel?.let { runCatching { wm.removeView(it) } }; panel = null }
    private fun hideWeb() { webPanel?.let { runCatching { wm.removeView(it) } }; webPanel = null }

    // ---------- WebView 계산기 패널 ----------
    private fun showWeb() {
        hideWeb()
        val disp = resources.displayMetrics
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pill(0xFF14141C.toInt(), 14)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    val foes = JSONArray()
                    for (i in 0 until 6) foes.put(selected[i] ?: "")
                    val js = """
                        (function(){
                          try {
                            if(!localStorage.getItem('pkmn_dex_v1')) { doImport(${JSONObject.quote(Dex.rawJson)}); }
                            var foes = $foes;
                            var els = document.querySelectorAll('#foe .tn');
                            for (var i = 0; i < els.length; i++) { els[i].value = foes[i] || ''; }
                            run();
                            var out = document.getElementById('out');
                            if (out) out.scrollIntoView();
                          } catch(e) {}
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(js, null)
                }
            }
            loadUrl("file:///android_asset/index.html")
        }
        web.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (disp.heightPixels * 0.78).toInt()
        )
        root.addView(web)
        root.addView(Button(this).apply {
            text = "닫기"
            setOnClickListener { hideWeb() }
        })
        val lp = overlayParams((disp.widthPixels * 0.60).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.CENTER
        wm.addView(root, lp)
        webPanel = root
    }

    // ---------- 헬퍼 ----------
    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    )

    private fun pill(color: Int, radiusDp: Int = 20) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
