package com.tmap.nda

import android.content.Context

/**
 * 목적지 검색 이력 - 좌표까지 저장해서 클릭 시 재검색 없이 바로 길안내를 시작할 수 있게 함.
 *
 * v2.5: MapActivity와 KakaoNaviActivity가 각자 이력 읽기/쓰기 코드를 따로 갖고 있다가,
 * v2.1에서 MapActivity만 "검색어(String) -> 좌표포함(HistoryEntry)" 구조로 바꾸고
 * KakaoNaviActivity 쪽을 안 바꿔서 카카오 화면의 "최근 목적지" 패널에 원본 JSON이
 * 그대로("{"name":"서울역",...}") 찍히는 버그가 났음(재억 제보). 두 Activity가 완전히
 * 같은 코드를 쓰도록 여기 하나로 합쳐서, 한쪽만 바뀌고 다른 쪽이 안 바뀌는 일을 방지. #문제시 원복
 */
data class HistoryEntry(val name: String, val addr: String, val lat: Double, val lon: Double)

object SearchHistoryStore {
    private const val KEY = "search_history_json"
    private const val MAX = 15

    private fun prefs(context: Context) =
        context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)

    fun get(context: Context): List<HistoryEntry> {
        val raw = prefs(context).getString(KEY, "[]") ?: "[]"
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                HistoryEntry(
                    o.optString("name"),
                    o.optString("addr"),
                    o.optDouble("lat"),
                    o.optDouble("lon")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeAll(context: Context, entries: List<HistoryEntry>) {
        val arr = org.json.JSONArray()
        entries.forEach {
            arr.put(
                org.json.JSONObject()
                    .put("name", it.name)
                    .put("addr", it.addr)
                    .put("lat", it.lat)
                    .put("lon", it.lon)
            )
        }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    fun save(context: Context, entry: HistoryEntry) {
        if (entry.name.isBlank()) return
        val current = get(context).toMutableList()
        current.removeAll { it.name == entry.name && it.lat == entry.lat && it.lon == entry.lon }
        current.add(0, entry)
        while (current.size > MAX) current.removeAt(current.size - 1)
        writeAll(context, current)
    }

    fun delete(context: Context, entry: HistoryEntry) {
        val current = get(context).toMutableList()
        current.removeAll { it.name == entry.name && it.lat == entry.lat && it.lon == entry.lon }
        writeAll(context, current)
    }

    fun clear(context: Context) {
        prefs(context).edit().putString(KEY, "[]").apply()
    }
}
