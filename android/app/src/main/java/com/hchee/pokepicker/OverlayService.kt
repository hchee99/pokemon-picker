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
    private var reader: ImageReader? = null
    private var vdisp: android.hardware.display.VirtualDisplay? = null
    @Volatile private var wantFrame = false
    private var firstResults: List<Recognizer.SlotResult>? = null  // 2프레임 병합용 1차 결과

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.log("Service onCreate")
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        runCatching { Dex.load(this) }
            .onFailure { AppLog.log("Dex.load 실패", it) }
        AppLog.log("Dex 로드: ${Dex.mons.size}마리")
        runCatching { startAsForeground() }.onFailure { AppLog.log("startForeground 실패", it) }
        runCatching { showBubble() }.onFailure { AppLog.log("버블 표시 실패", it) }
        runCatching { watchScreenshots() }.onFailure { AppLog.log("스샷감시 등록 실패", it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra("data")
        AppLog.log("onStartCommand code=$code data=${data != null} projection=${projection != null}")
        if (code != 0 && data != null && projection == null) {
            try {
                // API 34: 프로젝션 얻기 전에 mediaProjection 타입으로 포그라운드 재선언
                startAsForeground(withProjection = true)
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projection = mpm.getMediaProjection(code, data)
                projection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        AppLog.log("projection onStop")
                        projection = null
                        runCatching { vdisp?.release() }; vdisp = null
                        runCatching { reader?.close() }; reader = null
                    }
                }, main)
                AppLog.log("프로젝션 획득 성공: ${projection != null}")
                setupCaptureSession()   // 세션은 1번만 만들고 계속 유지 (안드로이드14 1회 제한 대응)
                bubble?.text = "⚡"
                Toast.makeText(this, "⚡를 누르면 화면을 캡처해 인식해요 (저장 없음)", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                AppLog.log("프로젝션 획득 실패", e)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        observer?.let { contentResolver.unregisterContentObserver(it) }
        runCatching { vdisp?.release() }
        runCatching { reader?.close() }
        projection?.stop()
        listOf(bubble, panel, webPanel).forEach { v -> v?.let { runCatching { wm.removeView(it) } } }
        super.onDestroy()
    }

    // ---------- 저장 없는 화면 캡처 (MediaProjection, 지속 세션) ----------
    // 안드로이드14는 createVirtualDisplay를 프로젝션당 1번만 허용 → 세션을 계속 유지하고,
    // 평소엔 프레임을 그냥 버리다가(wantFrame=false) ⚡ 탭 시 다음 프레임을 비트맵으로 변환.
    private fun realMetrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return dm
    }

    private fun newReader(w: Int, h: Int): ImageReader {
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            if (!wantFrame) { img.close(); return@setOnImageAvailableListener }  // 평소엔 버림(스톨 방지)
            wantFrame = false
            try {
                val plane = img.planes[0]
                val bmpW = plane.rowStride / plane.pixelStride
                val full = Bitmap.createBitmap(bmpW, img.height, Bitmap.Config.ARGB_8888)
                full.copyPixelsFromBuffer(plane.buffer)
                val shot = if (bmpW != img.width) Bitmap.createBitmap(full, 0, 0, img.width, img.height) else full
                img.close()
                AppLog.log("프레임 수신: ${shot.width}x${shot.height}")
                Thread { handleBitmap(shot) }.start()
            } catch (e: Exception) {
                AppLog.log("프레임 변환 실패", e)
                runCatching { img.close() }
            }
        }, main)
        return r
    }

    private fun setupCaptureSession() {
        val mp = projection ?: return
        val dm = realMetrics()
        reader = newReader(dm.widthPixels, dm.heightPixels)
        vdisp = mp.createVirtualDisplay(
            "pokecap", dm.widthPixels, dm.heightPixels, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, main
        )
        AppLog.log("캡처 세션 생성: ${dm.widthPixels}x${dm.heightPixels}")
    }

    private fun captureNow() {
        val vd = vdisp
        if (vd == null) { AppLog.log("captureNow: 세션 없음"); Toast.makeText(this, "캡처 세션이 없어요 — 앱에서 다시 '시작'해 주세요", Toast.LENGTH_SHORT).show(); return }
        // 화면 회전 등으로 크기가 바뀌었으면 세션 재생성 없이 resize (1회 제한 회피)
        val dm = realMetrics()
        if (reader?.width != dm.widthPixels || reader?.height != dm.heightPixels) {
            AppLog.log("해상도 변경 감지 → reader 교체 + resize (${dm.widthPixels}x${dm.heightPixels})")
            val old = reader
            reader = newReader(dm.widthPixels, dm.heightPixels)
            vd.resize(dm.widthPixels, dm.heightPixels, dm.densityDpi)
            vd.surface = reader!!.surface
            runCatching { old?.close() }
        }
        bubble?.text = "⏳"
        main.postDelayed({ bubble?.text = "⚡" }, 1500)
        AppLog.log("captureNow: 다음 프레임 요청")
        firstResults = null
        wantFrame = true
        main.postDelayed({
            if (wantFrame) { wantFrame = false; AppLog.log("프레임 타임아웃"); Toast.makeText(this, "캡처 실패 — 한 번 더 탭해 주세요", Toast.LENGTH_SHORT).show() }
        }, 2500)
    }

    private fun handleBitmap(bmp: Bitmap) {
        val results = try {
            Recognizer.recognize(bmp)
        } catch (e: Exception) {
            AppLog.log("인식 실패", e); emptyList()
        }
        AppLog.log("인식 결과 ${results.size}칸: " + results.joinToString(" | ") {
            it.types.joinToString("/") + "→" + it.candidates.take(3).joinToString(",")
        })
        main.post {
            val first = firstResults
            if (first == null) {
                if (results.size < 3) {
                    Toast.makeText(this, "상대 패널을 못 찾았어요 (팀 프리뷰 화면인지 확인)", Toast.LENGTH_SHORT).show()
                    return@post
                }
                // 1차 성공 → 0.5초 뒤 한 프레임 더 찍어 레이저/애니메이션 가림 보완
                firstResults = results
                main.postDelayed({ if (firstResults != null) wantFrame = true }, 500)
                main.postDelayed({
                    firstResults?.let { f ->   // 2차가 안 오면 1차 결과로 진행
                        firstResults = null; wantFrame = false
                        AppLog.log("2차 프레임 없음 → 1차 결과 사용")
                        finishResults(f)
                    }
                }, 2000)
            } else {
                firstResults = null
                val merged = Recognizer.merge(first, results)
                AppLog.log("2프레임 병합: " + merged.joinToString(" | ") { it.types.joinToString("/") })
                finishResults(merged)
            }
        }
    }

    private fun finishResults(results: List<Recognizer.SlotResult>) {
        lastResults = results
        selected.clear()
        results.forEachIndexed { i, r -> r.candidates.firstOrNull()?.let { selected[i] = it } }
        showPanel()
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
        var dx = 0f; var dy = 0f; var downX = 0f; var downY = 0f; var moved = false
        val slop = dp(12)  // 이 이상 움직여야 드래그로 판정 (미세 떨림은 탭으로 인정)
        tv.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    dx = e.rawX - lp.x; dy = e.rawY - lp.y
                    downX = e.rawX; downY = e.rawY; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(e.rawX - downX) > slop || kotlin.math.abs(e.rawY - downY) > slop) {
                        moved = true
                        lp.x = (e.rawX - dx).toInt(); lp.y = (e.rawY - dy).toInt()
                        wm.updateViewLayout(v, lp)
                    }
                    true
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

        // 왼쪽에 표시: 오른쪽의 상대 포켓몬을 보면서 후보를 탭할 수 있게
        val lp = overlayParams(
            (disp.widthPixels * 0.42).toInt().coerceAtLeast(dp(300)),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
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
        // 왼쪽에 표시: 오른쪽 상대 팀 정보를 가리지 않게 (상대 인식창과 같은 쪽)
        val lp = overlayParams((disp.widthPixels * 0.55).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        lp.x = dp(8)
        wm.addView(root, lp)
        webPanel = root
    }

    // ---------- 헬퍼 ----------
    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // NOT_FOCUSABLE: 오버레이가 포커스를 뺏으면 뒤 화면(게임/계산기)이 키보드를 못 씀.
        // 터치·버튼은 포커스 없이도 동작한다.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    )

    private fun pill(color: Int, radiusDp: Int = 20) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
