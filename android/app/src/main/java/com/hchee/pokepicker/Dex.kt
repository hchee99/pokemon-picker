package com.hchee.pokepicker

import android.content.Context
import org.json.JSONObject
import java.io.File

/** 도감 항목: 이름 + 한국어 타입 집합 */
data class Mon(val name: String, val types: Set<String>)

/**
 * pokedex.json 로더.
 * 우선순위: filesDir/pokedex.json(사용자가 갱신한 것) -> assets/pokedex.json(번들)
 * 형식: {"dex":{이름:{name,types:[영문타입],...}},...} 또는 dex 맵 자체.
 */
object Dex {
    private val KO = mapOf(
        "Normal" to "노말", "Fire" to "불꽃", "Water" to "물", "Electric" to "전기",
        "Grass" to "풀", "Ice" to "얼음", "Fighting" to "격투", "Poison" to "독",
        "Ground" to "땅", "Flying" to "비행", "Psychic" to "에스퍼", "Bug" to "벌레",
        "Rock" to "바위", "Ghost" to "고스트", "Dragon" to "드래곤", "Dark" to "악",
        "Steel" to "강철", "Fairy" to "페어리"
    )

    var mons: List<Mon> = emptyList()
        private set
    var rawJson: String = "{}"
        private set

    fun load(ctx: Context) {
        val text = readSource(ctx) ?: return
        rawJson = text
        val root = JSONObject(text)
        val dex = if (root.has("dex")) root.getJSONObject("dex") else root
        val out = ArrayList<Mon>()
        for (key in dex.keys()) {
            val m = dex.optJSONObject(key) ?: continue
            val tArr = m.optJSONArray("types") ?: continue
            val types = HashSet<String>()
            for (i in 0 until tArr.length()) {
                KO[tArr.optString(i)]?.let { types.add(it) }
            }
            if (types.isNotEmpty()) out.add(Mon(m.optString("name", key), types))
        }
        mons = out
    }

    private fun readSource(ctx: Context): String? {
        val f = File(ctx.filesDir, "pokedex.json")
        if (f.exists()) return f.readText()
        return try {
            ctx.assets.open("pokedex.json").bufferedReader().readText()
        } catch (e: Exception) {
            null
        }
    }

    /** 타입 조합이 정확히 일치하는 후보들 */
    fun candidates(types: Set<String>): List<String> =
        mons.filter { it.types == types }.map { it.name }
}
