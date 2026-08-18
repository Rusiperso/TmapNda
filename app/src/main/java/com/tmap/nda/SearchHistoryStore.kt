package com.tmap.nda

import android.content.Context

/**
 * 목적지 검색 이력 - 좌표까지 저장해서 클릭 시 재검색 없이 바로 길안내를 시작할 수 있게 함.
 *
 * v2.5: MapActivity와 KakaoNaviActivity가 각자 이력 읽기/쓰기 코드를 따로 갖고 있다가,
 * v2.1에서 MapActivity만 "검색어(String) -> 좌표포함(HistoryEntry)" 구조로 바꾸고
 * KakaoNaviActivity 쪽을 안 바꿔서 카카오 화면의 "최근 목적지" 패널에 원본 JSON이
 * 그대로("{"name":"서울역",...}") 찍히는 버그가 났음(사용자 제보). 두 Activity가 완전히
 * 같은 코드를 쓰도록 여기 하나로 합쳐서, 한쪽만 바뀌고 다른 쪽이 안 바뀌는 일을 방지. #문제시 원복
 */
// v11.1: 검색 결과 목록에 거리도 같이 보여주고 싶어함(재억 요청) - 검색 이력 저장/불러오기
// 쪽은 그대로 쓰되, 화면에 표시할 때만 쓰는 값이라 기본값 null로 둬서 기존 저장 로직에는
// 영향 없게 함. #문제시 원복
// v13.2-3: 즐겨찾기/집/회사에 "이동 방식"(추천/고속도로/무료도로)도 같이 저장해서
// 짧게 눌렀을 때 팝업 없이 바로 그 방식으로 안내 시작하도록 함(재억 요청). 기존
// 생성자(4~5개 인자) 호출부는 전부 그대로 컴파일되도록 기본값으로 둠. #문제시 원복
data class HistoryEntry(
    val name: String,
    val addr: String,
    val lat: Double,
    val lon: Double,
    val distanceMeters: Double? = null,
    val routePriorityName: String? = null,
    val routeAvoidOption: Int = 0
)

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
