package com.hchee.pokepicker

import android.graphics.Bitmap
import android.graphics.Color

/**
 * 팀 프리뷰 스크린샷에서 상대 6마리의 타입 조합을 인식.
 * prototype/type_icons.py 의 검증된 알고리즘을 순수 Kotlin으로 이식.
 *
 * 원리:
 *  1) find_panels: 화면 오른쪽 띠에서 "마젠타(크림슨 패널)" 픽셀을 가로줄마다 세서
 *     패널 6개의 세로 위치를 자동 검출 (이름 배너는 얇아서 제외)
 *  2) classify: 각 패널의 타입 아이콘 영역 픽셀을 18개 타입색+제외색 중
 *     가장 가까운 색으로 분류해 개수를 셈 (RGB 유클리드 거리)
 *  3) 최다 타입 대비 35% 이상인 두 번째 타입이 있으면 복합 타입
 */
object Recognizer {

    data class SlotResult(val types: List<String>, val candidates: List<String>)

    // 게임 스샷에서 보정한 실제 아이콘색 (prototype/type_icons.py 와 동일)
    private val TYPE_RGB = linkedMapOf(
        "노말" to intArrayOf(153, 153, 153), "불꽃" to intArrayOf(208, 65, 58),
        "물" to intArrayOf(72, 122, 226), "전기" to intArrayOf(236, 190, 72),
        "풀" to intArrayOf(94, 155, 63), "얼음" to intArrayOf(121, 208, 242),
        "격투" to intArrayOf(236, 149, 79), "독" to intArrayOf(136, 77, 196),
        "땅" to intArrayOf(135, 83, 47), "비행" to intArrayOf(138, 177, 229),
        "에스퍼" to intArrayOf(216, 85, 125), "벌레" to intArrayOf(151, 161, 66),
        "바위" to intArrayOf(188, 184, 137), "고스트" to intArrayOf(103, 64, 112),
        "드래곤" to intArrayOf(85, 102, 210), "악" to intArrayOf(79, 71, 71),
        "강철" to intArrayOf(124, 162, 182), "페어리" to intArrayOf(216, 123, 223)
    )

    // 타입이 아닌 색(패널 크림슨/성별 파랑/노란 벽/흰색 등) — 여기에 가까운 픽셀은 무시
    private val REJECT_RGB = arrayOf(
        intArrayOf(99, 23, 47), intArrayOf(110, 36, 44), intArrayOf(101, 36, 58),
        intArrayOf(98, 34, 76), intArrayOf(98, 27, 73), intArrayOf(120, 40, 66),
        intArrayOf(70, 18, 38),
        intArrayOf(32, 60, 205), intArrayOf(72, 101, 198), intArrayOf(45, 70, 200),
        intArrayOf(66, 97, 200),
        intArrayOf(165, 150, 66), intArrayOf(159, 145, 68), intArrayOf(150, 129, 57),
        intArrayOf(200, 182, 83), intArrayOf(236, 215, 120),
        intArrayOf(255, 255, 255), intArrayOf(235, 235, 235), intArrayOf(150, 150, 150),
        intArrayOf(90, 88, 92), intArrayOf(60, 55, 58)
    )

    private val NAMES = TYPE_RGB.keys.toList()
    private val ALLCOLS: Array<IntArray> = (TYPE_RGB.values + REJECT_RGB).toTypedArray()
    private const val NT = 18
    private const val DIST2 = 55 * 55       // 거리 제곱 비교(sqrt 생략)
    private const val MINPIX_RATIO = 12.0 / (3120.0 * 1440.0)  // 해상도 비례 최소 픽셀

    // 좌표 비율 (3120x1440 기준으로 캘리브레이션, 비율이라 다른 해상도도 동작)
    private const val STRIP_X0 = 0.76; private const val STRIP_X1 = 0.90
    private const val ICON_X0 = 0.833; private const val ICON_X1 = 0.890
    private const val ICON_Y0 = 0.06; private const val ICON_Y1 = 0.40

    /** 스크린샷 -> 상대 슬롯별 (타입조합, 후보 목록) */
    fun recognize(shot: Bitmap): List<SlotResult> {
        val panels = findPanels(shot)
        val minPix = (MINPIX_RATIO * shot.width * shot.height).toInt().coerceAtLeast(6)
        return panels.map { (y0, y1) ->
            val bh = y1 - y0
            val iy0 = y0 + (bh * ICON_Y0).toInt()
            val iy1 = y0 + (bh * ICON_Y1).toInt()
            val ix0 = (shot.width * ICON_X0).toInt()
            val ix1 = (shot.width * ICON_X1).toInt()
            val counts = classify(shot, ix0, iy0, ix1, iy1)
            val sorted = counts.withIndex().sortedByDescending { it.value }
            val top = sorted.firstOrNull()?.value ?: 0
            val chosen = sorted
                .filter { it.value >= maxOf(minPix, (0.35 * top).toInt()) }
                .take(2)
                .map { NAMES[it.index] }
            SlotResult(chosen, if (chosen.isEmpty()) emptyList() else Dex.candidates(chosen.toSet()))
        }
    }

    /** 오른쪽 띠의 마젠타 가로줄 프로파일로 패널 세로 구간 검출 */
    private fun findPanels(shot: Bitmap): List<Pair<Int, Int>> {
        val w = shot.width; val h = shot.height
        val x0 = (w * STRIP_X0).toInt(); val x1 = (w * STRIP_X1).toInt()
        val stripW = x1 - x0
        val px = IntArray(stripW)
        val rows = IntArray(h)
        val hsv = FloatArray(3)
        for (y in 0 until h) {
            shot.getPixels(px, 0, stripW, x0, y, stripW, 1)
            var cnt = 0
            for (c in px) {
                Color.colorToHSV(c, hsv)
                val hue = hsv[0]; val s = hsv[1]; val v = hsv[2]
                // OpenCV(H 0..180) 기준 156..179 | 0..8  ->  도(0..360) 기준 312..360 | 0..16
                if ((hue >= 312f || hue <= 16f) && s > 0.196f && v > 0.118f) cnt++
            }
            rows[y] = cnt
        }
        val maxCnt = rows.max()
        if (maxCnt == 0) return emptyList()
        val thr = maxCnt * 0.18
        val minBand = (h * 0.075).toInt()
        val bands = ArrayList<Pair<Int, Int>>()
        var y = 0
        while (y < h) {
            if (rows[y] > thr) {
                var j = y
                while (j < h && rows[j] > thr) j++
                if (j - y > minBand) bands.add(y to j)
                y = j
            } else y++
        }
        return bands
    }

    /** 아이콘 영역 픽셀을 최근접 색으로 분류해 타입별 개수 반환 (제외색은 버림) */
    private fun classify(shot: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int): IntArray {
        val counts = IntArray(NT)
        val w = (x1 - x0).coerceAtLeast(1)
        val px = IntArray(w)
        for (y in y0 until y1.coerceAtMost(shot.height)) {
            shot.getPixels(px, 0, w, x0, y, w, 1)
            for (c in px) {
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                var bestIdx = -1; var bestD = Int.MAX_VALUE
                for (i in ALLCOLS.indices) {
                    val t = ALLCOLS[i]
                    val dr = r - t[0]; val dg = g - t[1]; val db = b - t[2]
                    val d = dr * dr + dg * dg + db * db
                    if (d < bestD) { bestD = d; bestIdx = i }
                }
                if (bestIdx in 0 until NT && bestD < DIST2) counts[bestIdx]++
            }
        }
        return counts
    }
}
