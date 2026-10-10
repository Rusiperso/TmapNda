package com.tmap.nda.naver

import android.content.Context
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class NaverPlace(val name: String, val address: String, val point: LonLat?)

/**
 * 네이버 개발자센터 지역 검색 API로 장소를 찾는다(하루 한도가 넉넉한 별도 키).
 * 응답 좌표(mapx, mapy)가 위경도×10^7 형식이면 그대로 쓰고, 옛 방식(KATECH)이면 주소로 지오코딩해서 좌표를 얻는다.
 */
object NaverPlaceSearch {

    private const val PREFS = "TndaNaverPrefs"
    private const val KEY_ID = "search_key_id"
    private const val KEY_SECRET = "search_key_secret"

    fun saveKeys(context: Context, id: String, secret: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ID, id.trim()).putString(KEY_SECRET, secret.trim()).apply()
    }

    fun hasKeys(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return !p.getString(KEY_ID, "").isNullOrBlank() && !p.getString(KEY_SECRET, "").isNullOrBlank()
    }

    /** 검색 결과(최대 5개)와 오류 메시지(성공이면 null). 네트워크를 쓰므로 메인 스레드에서 부르면 안 된다. */
    fun search(context: Context, query: String): Pair<List<NaverPlace>, String?> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = p.getString(KEY_ID, "").orEmpty()
        val secret = p.getString(KEY_SECRET, "").orEmpty()
        if (id.isBlank() || secret.isBlank()) return emptyList<NaverPlace>() to "검색용 키가 입력되지 않았어요"
        val url = "https://openapi.naver.com/v1/search/local.json?display=5&query=" + URLEncoder.encode(query, "UTF-8")
        val body = try {
            httpGet(url, mapOf("X-Naver-Client-Id" to id, "X-Naver-Client-Secret" to secret))
        } catch (e: Exception) {
            return emptyList<NaverPlace>() to "검색 실패: ${e.message}"
        }
        val places = ArrayList<NaverPlace>()
        try {
            val items = JsonParser.parseString(body).asJsonObject.getAsJsonArray("items") ?: return emptyList<NaverPlace>() to "결과 없음"
            for (el in items) {
                val o = el.asJsonObject
                val name = o.get("title")?.asString.orEmpty().replace(Regex("<[^>]*>"), "").trim()
                val road = o.get("roadAddress")?.asString.orEmpty()
                val addr = road.ifBlank { o.get("address")?.asString.orEmpty() }
                val point = parseCoord(o.get("mapx")?.asString, o.get("mapy")?.asString)
                    ?: geocode(context, addr)
                places.add(NaverPlace(name, addr, point))
            }
        } catch (e: Exception) {
            return emptyList<NaverPlace>() to "검색 결과 해석 실패: ${e.message}"
        }
        return places to if (places.isEmpty()) "결과 없음" else null
    }

    /** mapx/mapy가 위경도×10^7(예: 1269784147)이면 변환, 아니면 null. */
    internal fun parseCoord(mapx: String?, mapy: String?): LonLat? {
        val x = mapx?.toLongOrNull() ?: return null
        val y = mapy?.toLongOrNull() ?: return null
        if (x < 100_000_000L || y < 100_000_000L) return null
        return LonLat(x / 1e7, y / 1e7)
    }

    /** 주소 → 좌표(NCP Geocode). 길찾기와 같은 NCP 키를 쓰고, 키가 없거나 실패하면 null. */
    private fun geocode(context: Context, address: String): LonLat? {
        if (address.isBlank()) return null
        val p = context.getSharedPreferences("TndaNaverPrefs", Context.MODE_PRIVATE)
        val id = p.getString("ncp_key_id", "").orEmpty()
        val secret = p.getString("ncp_key_secret", "").orEmpty()
        if (id.isBlank() || secret.isBlank()) return null
        return try {
            val body = httpGet(
                "https://maps.apigw.ntruss.com/map-geocode/v2/geocode?query=" + URLEncoder.encode(address, "UTF-8"),
                mapOf("x-ncp-apigw-api-key-id" to id, "x-ncp-apigw-api-key" to secret)
            )
            val first = JsonParser.parseString(body).asJsonObject.getAsJsonArray("addresses")?.firstOrNull()?.asJsonObject
                ?: return null
            LonLat(first.get("x").asString.toDouble(), first.get("y").asString.toDouble())
        } catch (e: Exception) {
            null
        }
    }

    private fun httpGet(url: String, headers: Map<String, String>): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw RuntimeException("HTTP $status ${text.take(120)}")
            return text
        } finally {
            conn.disconnect()
        }
    }
}
