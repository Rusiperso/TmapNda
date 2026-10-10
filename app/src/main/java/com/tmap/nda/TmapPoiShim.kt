package com.tmap.nda

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 앱 곳곳의 카카오 장소검색(dapi.kakao.com) 요청을 티맵 Open API 요청으로 바꿔 부르고,
 * 응답을 카카오 모양으로 되돌려준다. 그래서 검색 화면·주변 검색·음성비서 코드는 그대로 두고 카카오 키 없이 동작한다.
 * 티맵 키는 이미 앱에 입력해 둔 TMAP 키를 그대로 쓴다.
 *
 *  - keyword.json  → /tmap/pois                  (이름·주소 통합검색)
 *  - category.json → /tmap/pois/search/around     (반경 안 카테고리 검색, 가까운 순)
 *  - address.json  → /tmap/geo/fullAddrGeo        (주소 → 좌표)
 *  - coord2regioncode.json → /tmap/geo/reversegeocoding (좌표 → 동네 이름)
 */
object TmapPoiConverter {

    /** 카카오 카테고리 코드 → 티맵 카테고리 이름. */
    private val CATEGORY = mapOf(
        "CS2" to "편의점", "OL7" to "주유소", "EV" to "충전소", "PK6" to "주차장",
        "CE7" to "카페", "PM9" to "약국", "BK9" to "은행", "HP8" to "병원",
        "MT1" to "마트", "SW8" to "지하철", "SC4" to "학교", "AD5" to "숙박",
        "FD6" to "음식점", "CT1" to "문화시설", "AT4" to "관광"
    )

    fun tmapCategory(kakaoCode: String): String? = CATEGORY[kakaoCode]

    /** 장소 목록(통합검색/주변검색 공통) → 카카오 형식 JSON 문자열. */
    fun poisToKakao(
        body: String,
        userLat: Double?,
        userLon: Double?,
        page: Int,
        count: Int,
        keepParkingLots: Boolean
    ): String {
        val info = parseObj(body)?.get("searchPoiInfo")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return emptyDocs()
        val total = info.str("totalCount").toIntOrNull() ?: 0
        val poiArr = info.get("pois")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("poi")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()

        val docs = JsonArray()
        for (el in poiArr) {
            val p = el.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val name = p.str("name")
            if (name.isBlank()) continue
            // 카페 같은 업소의 "OO주차장"이 별도 장소로 섞여 나오는 것을 걸러낸다(주차장을 찾는 게 아닐 때만)
            if (!keepParkingLots && name.endsWith("주차장")) continue
            val lat = p.str("noorLat").ifBlank { p.str("frontLat") }.toDoubleOrNull() ?: continue
            val lon = p.str("noorLon").ifBlank { p.str("frontLon") }.toDoubleOrNull() ?: continue

            val distance = if (userLat != null && userLon != null) {
                haversineMeters(userLat, userLon, lat, lon).toInt().toString()
            } else {
                val km = p.str("radius").toDoubleOrNull() ?: 0.0
                if (km > 0.0) (km * 1000).toInt().toString() else ""
            }

            val doc = JsonObject()
            doc.addProperty("id", p.str("id"))
            doc.addProperty("place_name", name)
            doc.addProperty("address_name", jibunAddress(p))
            doc.addProperty("road_address_name", roadAddress(p))
            doc.addProperty("x", lon.toString())
            doc.addProperty("y", lat.toString())
            doc.addProperty("distance", distance)
            doc.addProperty("phone", p.str("telNo"))
            doc.addProperty("category_name", listOf(p.str("upperBizName"), p.str("middleBizName"), p.str("lowerBizName"))
                .filter { it.isNotBlank() }.joinToString(" > "))
            docs.add(doc)
        }
        return wrap(docs, total, isEnd = page * count >= total)
    }

    /** 주소 → 좌표(fullAddrGeo) → 카카오 address.json 형식. */
    fun addressToKakao(body: String): String {
        val coords = parseObj(body)?.get("coordinateInfo")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("coordinate")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyDocs()
        val docs = JsonArray()
        for (el in coords) {
            val c = el.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val lat = c.str("lat").ifBlank { c.str("newLat") }
            val lon = c.str("lon").ifBlank { c.str("newLon") }
            if (lat.isBlank() || lon.isBlank()) continue
            val addr = listOf(c.str("city_do"), c.str("gu_gun"), c.str("eup_myun"), c.str("ri"), c.str("bunji"))
                .filter { it.isNotBlank() }.joinToString(" ")
            val doc = JsonObject()
            doc.addProperty("address_name", addr)
            doc.addProperty("x", lon)
            doc.addProperty("y", lat)
            docs.add(doc)
        }
        return wrap(docs, docs.size(), isEnd = true)
    }

    /** 좌표 → 동네 이름(reversegeocoding) → 카카오 coord2regioncode 형식. */
    fun regionToKakao(body: String): String {
        val a = parseObj(body)?.get("addressInfo")?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyDocs()
        val docs = JsonArray()
        val doc = JsonObject()
        doc.addProperty("region_type", "H")
        doc.addProperty("region_1depth_name", a.str("city_do"))
        doc.addProperty("region_2depth_name", a.str("gu_gun"))
        doc.addProperty("region_3depth_name", a.str("adminDong").ifBlank { a.str("legalDong") })
        docs.add(doc)
        return wrap(docs, 1, isEnd = true)
    }

    // ===== 내부 도우미 =====
    private fun jibunAddress(p: JsonObject): String {
        val area = listOf(p.str("upperAddrName"), p.str("middleAddrName"), p.str("lowerAddrName"), p.str("detailAddrName"))
            .filter { it.isNotBlank() }.joinToString(" ")
        return (area + " " + numberPart(p.str("firstNo"), p.str("secondNo"))).trim()
    }

    private fun roadAddress(p: JsonObject): String {
        val road = p.str("roadName")
        if (road.isBlank()) return ""
        val area = listOf(p.str("upperAddrName"), p.str("middleAddrName")).filter { it.isNotBlank() }.joinToString(" ")
        return "$area $road ${numberPart(p.str("firstBuildNo"), p.str("secondBuildNo"))}".trim()
    }

    private fun numberPart(first: String, second: String): String = when {
        first.isBlank() -> ""
        second.isBlank() || second == "0" -> first
        else -> "$first-$second"
    }

    private fun wrap(docs: JsonArray, total: Int, isEnd: Boolean): String {
        val meta = JsonObject()
        meta.addProperty("total_count", total)
        meta.addProperty("pageable_count", total)
        meta.addProperty("is_end", isEnd)
        val root = JsonObject()
        root.add("documents", docs)
        root.add("meta", meta)
        return root.toString()
    }

    private fun emptyDocs() = wrap(JsonArray(), 0, isEnd = true)

    private fun parseObj(body: String): JsonObject? = try {
        if (body.isBlank()) null else JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
    } catch (e: Exception) {
        null
    }

    private fun JsonObject.str(k: String): String {
        val e: JsonElement = get(k) ?: return ""
        return if (e.isJsonPrimitive) e.asString else ""
    }

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(a))
    }
}

/** OkHttp 가로채기: 카카오 장소검색 주소로 가는 요청을 티맵 요청으로 바꿔서 보내고 응답을 카카오 모양으로 돌려준다. */
class KakaoToTmapInterceptor(private val context: Context) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.url.host != "dapi.kakao.com") return chain.proceed(req)

        // 장소 검색은 길안내 엔진과 상관없이 항상 티맵 검색을 쓴다(카카오 REST 키는 쓰지 않는다).
        val key = context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).getString("APP_KEY", "").orEmpty()
        val q = req.url
        val page = q.queryParameter("page")?.toIntOrNull() ?: 1
        val userLon = q.queryParameter("x")?.toDoubleOrNull()
        val userLat = q.queryParameter("y")?.toDoubleOrNull()

        val tmapUrl = when (q.encodedPath) {
            "/v2/local/search/keyword.json" -> {
                val query = q.queryParameter("query").orEmpty()
                val b = "https://apis.openapi.sk.com/tmap/pois".toHttpUrl().newBuilder()
                    .addQueryParameter("version", "1")
                    .addQueryParameter("searchKeyword", query)
                    .addQueryParameter("page", page.toString())
                    .addQueryParameter("count", PAGE_SIZE.toString())
                    .addQueryParameter("resCoordType", "WGS84GEO")
                    .addQueryParameter("format", "json")
                // 카카오 요청에 반경(radius)이 있으면 "내 주변 이 안에서" 찾으라는 뜻이다(주변 검색의 이름 검색 흐름).
                // 티맵도 중심·반경·거리순을 같이 주면 가까운 순으로 준다. 반경이 없는 일반 목적지 검색은 전국에서 찾는다.
                val radiusM = q.queryParameter("radius")?.toIntOrNull()
                if (radiusM != null && userLon != null && userLat != null) {
                    b.addQueryParameter("centerLon", userLon.toString())
                        .addQueryParameter("centerLat", userLat.toString())
                        .addQueryParameter("radius", Math.ceil(radiusM / 1000.0).toInt().coerceIn(1, 33).toString())
                        .addQueryParameter("searchtypCd", if (q.queryParameter("sort") == "distance") "R" else "A")
                }
                b.build()
            }
            "/v2/local/search/category.json" -> {
                val code = q.queryParameter("category_group_code").orEmpty()
                val category = TmapPoiConverter.tmapCategory(code) ?: return emptyResponse(req)
                val radiusM = q.queryParameter("radius")?.toIntOrNull() ?: 20000
                val radiusKm = Math.ceil(radiusM / 1000.0).toInt().coerceIn(1, 33)
                if (userLon == null || userLat == null) return emptyResponse(req)
                "https://apis.openapi.sk.com/tmap/pois/search/around".toHttpUrl().newBuilder()
                    .addQueryParameter("version", "1")
                    .addQueryParameter("categories", category)
                    .addQueryParameter("centerLon", userLon.toString())
                    .addQueryParameter("centerLat", userLat.toString())
                    .addQueryParameter("radius", radiusKm.toString())
                    .addQueryParameter("page", page.toString())
                    .addQueryParameter("count", PAGE_SIZE.toString())
                    .addQueryParameter("format", "json")
                    .build()
            }
            "/v2/local/search/address.json" -> {
                "https://apis.openapi.sk.com/tmap/geo/fullAddrGeo".toHttpUrl().newBuilder()
                    .addQueryParameter("version", "1")
                    .addQueryParameter("coordType", "WGS84GEO")
                    .addQueryParameter("addressFlag", "F00")
                    .addQueryParameter("fullAddr", q.queryParameter("query").orEmpty())
                    .addQueryParameter("format", "json")
                    .build()
            }
            "/v2/local/geo/coord2regioncode.json" -> {
                "https://apis.openapi.sk.com/tmap/geo/reversegeocoding".toHttpUrl().newBuilder()
                    .addQueryParameter("version", "1")
                    .addQueryParameter("lat", (userLat ?: 0.0).toString())
                    .addQueryParameter("lon", (userLon ?: 0.0).toString())
                    .addQueryParameter("coordType", "WGS84GEO")
                    .addQueryParameter("addressType", "A10")
                    .build()
            }
            else -> return emptyResponse(req)
        }

        val tmapReq = req.newBuilder().url(tmapUrl).removeHeader("Authorization").header("appKey", key).build()
        val resp = chain.proceed(tmapReq)
        if (resp.code == 204) return resp.newBuilder().code(200).message("OK").body(jsonBody(TmapPoiConverter.poisToKakao("", null, null, 1, PAGE_SIZE, true))).build()
        if (!resp.isSuccessful) return resp   // 키 오류·한도 초과 등은 그대로 알려준다

        val raw = resp.body?.string().orEmpty()
        val keepParking = q.queryParameter("category_group_code") == "PK6" ||
            q.queryParameter("query").orEmpty().contains("주차")
        val converted = when (q.encodedPath) {
            "/v2/local/search/address.json" -> TmapPoiConverter.addressToKakao(raw)
            "/v2/local/geo/coord2regioncode.json" -> TmapPoiConverter.regionToKakao(raw)
            "/v2/local/search/keyword.json" -> TmapPoiConverter.poisToKakao(raw, userLat, userLon, page, PAGE_SIZE, keepParking)
            else -> TmapPoiConverter.poisToKakao(raw, userLat, userLon, page, PAGE_SIZE, keepParking)
        }
        return resp.newBuilder().body(jsonBody(converted)).build()
    }

    private fun jsonBody(s: String) = s.toResponseBody("application/json".toMediaType())

    private fun emptyResponse(req: okhttp3.Request): Response = Response.Builder()
        .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .body(jsonBody(TmapPoiConverter.poisToKakao("", null, null, 1, PAGE_SIZE, true)))
        .build()

    private companion object {
        const val PAGE_SIZE = 15   // 카카오 한 쪽 크기와 맞춘다(기존 4쪽까지 이어받기 로직 그대로 동작)
    }
}

/** 카카오 REST 키 칸을 없앴지만, 곳곳의 "키가 비었나?" 검사가 통과되도록 자리표시 값을 넣어둔다(값 자체는 쓰이지 않음). */
object TmapSearchKey {
    fun ensure(context: Context) {
        val p = context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
        if (p.getString("kakao_rest_api_key", "").isNullOrBlank()) {
            p.edit().putString("kakao_rest_api_key", "tmap").apply()
        }
    }
}
