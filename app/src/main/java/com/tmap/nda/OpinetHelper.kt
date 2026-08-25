package com.tmap.nda

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.math.*

/**
 * 오피넷(한국석유공사) 유가정보 API 연동. 카카오 category.json으로는 안 되는
 * "브랜드별 필터"와 "실시간 유가"를 제공. 각자 발급받은 API 키(opinet_api_key)가
 * 필요 - 한 사람 키를 공유하면 하루 1500건 한도가 금방 소진되므로 개인별 발급 필수.
 */
object OpinetHelper {
    data class GasStation(
        val name: String,
        val brandCode: String,
        val brandName: String,
        val distanceMeters: Double,
        val gasolinePrice: Int?,
        val dieselPrice: Int?,
        val lat: Double,
        val lon: Double
    )

    val BRANDS = listOf(
        "전체" to null,
        "SK에너지" to "SKE",
        "GS칼텍스" to "GSC",
        "현대오일뱅크" to "HDO",
        "S-OIL" to "SOL",
        "알뜰주유소" to "RTX",
        "자가상표" to "NHO"
    )

    // v: 신규기능(유종 선택) - 오피넷 prodcd 파라미터. 재억 요청 - 한 번 고르면 계속
    // 그 유종으로 검색되고, 필요하면 다시 바꿀 수 있게. #문제시 원복
    val FUEL_TYPES = listOf(
        "휘발유" to "B027",
        "경유" to "D047",
        "LPG" to "K015"
    )
    private const val PREF_FUEL_TYPE = "opinet_fuel_type_prodcd"

    fun savedFuelType(context: Context): String? =
        context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).getString(PREF_FUEL_TYPE, null)

    fun saveFuelType(context: Context, prodcd: String) {
        context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).edit().putString(PREF_FUEL_TYPE, prodcd).apply()
    }

    fun clearFuelType(context: Context) {
        context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).edit().remove(PREF_FUEL_TYPE).apply()
    }

    /**
     * WGS84(위경도) -> TM128(오피넷 좌표계) 변환. 오피넷은 KATEC이 아니라 TM128을 쓰기
     * 때문에 카카오 SDK의 convertWGS84ToKATEC()을 그대로 쓰면 안 됨(수백m 오차 가능) -
     * 표준 TM128 투영 공식으로 직접 계산.
     */
    fun wgs84ToTm128(lon: Double, lat: Double): Pair<Double, Double> {
        val re = 6371.00877
        val grid = 5.0
        val slat1 = 30.0
        val slat2 = 60.0
        val olon = 126.0
        val olat = 38.0
        val xo = 210.0 / grid
        val yo = 675.0 / grid

        val degrad = PI / 180.0
        val re2 = re / grid
        val slat1r = slat1 * degrad
        val slat2r = slat2 * degrad
        val olonr = olon * degrad
        val olatr = olat * degrad

        var sn = tan(PI * 0.25 + slat2r * 0.5) / tan(PI * 0.25 + slat1r * 0.5)
        sn = ln(cos(slat1r) / cos(slat2r)) / ln(sn)
        var sf = tan(PI * 0.25 + slat1r * 0.5)
        sf = sf.pow(sn) * cos(slat1r) / sn
        var ro = tan(PI * 0.25 + olatr * 0.5)
        ro = re2 * sf / ro.pow(sn)

        val latr = lat * degrad
        val lonr = lon * degrad
        var ra = tan(PI * 0.25 + latr * 0.5)
        ra = re2 * sf / ra.pow(sn)
        var theta = lonr - olonr
        if (theta > PI) theta -= 2.0 * PI
        if (theta < -PI) theta += 2.0 * PI
        theta *= sn

        val x = (ra * sin(theta) + xo) * grid
        val y = (ro - ra * cos(theta) + yo) * grid
        return Pair(x, y)
    }

    /** TM128 -> WGS84 역변환. 오피넷 응답 좌표(GIS_X_COOR/GIS_Y_COOR)를 카카오 길안내에 쓰려면 필요. */
    fun tm128ToWgs84(x: Double, y: Double): Pair<Double, Double> {
        val re = 6371.00877
        val grid = 5.0
        val slat1 = 30.0
        val slat2 = 60.0
        val olon = 126.0
        val olat = 38.0
        val xo = 210.0 / grid
        val yo = 675.0 / grid

        val degrad = PI / 180.0
        val raddeg = 180.0 / PI
        val re2 = re / grid
        val slat1r = slat1 * degrad
        val slat2r = slat2 * degrad
        val olonr = olon * degrad
        val olatr = olat * degrad

        var sn = tan(PI * 0.25 + slat2r * 0.5) / tan(PI * 0.25 + slat1r * 0.5)
        sn = ln(cos(slat1r) / cos(slat2r)) / ln(sn)
        var sf = tan(PI * 0.25 + slat1r * 0.5)
        sf = sf.pow(sn) * cos(slat1r) / sn
        var ro = tan(PI * 0.25 + olatr * 0.5)
        ro = re2 * sf / ro.pow(sn)

        val xn = x / grid - xo
        val yn = ro - y / grid + yo
        var ra = sqrt(xn * xn + yn * yn)
        if (sn < 0.0) ra = -ra
        var alat = (re2 * sf / ra).pow(1.0 / sn)
        alat = 2.0 * atan(alat) - PI * 0.5

        val theta = if (abs(xn) <= 0.0) 0.0 else atan2(xn, yn)
        val alon = theta / sn + olonr

        return Pair(alon * raddeg, alat * raddeg)
    }

    fun fetchNearby(
        context: Context,
        httpClient: OkHttpClient,
        apiKey: String,
        lat: Double,
        lon: Double,
        brandCode: String?,
        prodcd: String,
        onResult: (List<GasStation>) -> Unit
    ) {
        val (x, y) = wgs84ToTm128(lon, lat)
        val urlBuilder = StringBuilder("https://www.opinet.co.kr/api/aroundAll.do?out=json")
            .append("&x=").append(x)
            .append("&y=").append(y)
            .append("&radius=5000")
            .append("&sort=1") // 거리순(오피넷 자체 정렬 - 최종 정렬은 앱에서 가격+거리로 재계산)
            .append("&prodcd=").append(prodcd)
            .append("&code=").append(apiKey)
        if (brandCode != null) {
            urlBuilder.append("&pooltype=").append(brandCode)
        }
        val request = Request.Builder().url(urlBuilder.toString()).build()
        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                NavLogger.e(context, "[오피넷] 요청 실패: ${e.message}")
                onResult(emptyList())
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        NavLogger.e(context, "[오피넷] 실패 code=${it.code} (API키 확인 필요 - 개인별 발급 필요)")
                        onResult(emptyList())
                        return@use
                    }
                    try {
                        val json = JSONObject(it.body?.string() ?: "{}")
                        val result = json.optJSONObject("RESULT") ?: json.optJSONObject("result")
                        val oil = result?.optJSONArray("OIL") ?: result?.optJSONArray("oil")
                        if (oil == null) {
                            onResult(emptyList())
                            return@use
                        }
                        val stations = (0 until oil.length()).map { idx ->
                            val o = oil.getJSONObject(idx)
                            val rawX = o.optDouble("GIS_X_COOR", o.optDouble("gis_x_coor", 0.0))
                            val rawY = o.optDouble("GIS_Y_COOR", o.optDouble("gis_y_coor", 0.0))
                            val (realLon, realLat) = tm128ToWgs84(rawX, rawY)
                            GasStation(
                                name = o.optString("OS_NM", o.optString("os_nm", "이름 없음")),
                                brandCode = o.optString("POLL_DIV_CD", o.optString("poll_div_cd", "")),
                                brandName = o.optString("POLL_DIV_CD_NM", o.optString("poll_div_cd_nm", "")),
                                distanceMeters = o.optDouble("DISTANCE", o.optDouble("distance", 0.0)),
                                gasolinePrice = o.optInt("PRICE", -1).takeIf { p -> p > 0 },
                                dieselPrice = null,
                                lat = realLat,
                                lon = realLon
                            )
                        }
                        onResult(stations)
                    } catch (e: Exception) {
                        NavLogger.e(context, "[오피넷] 파싱 실패: ${e.message}")
                        onResult(emptyList())
                    }
                }
            }
        })
    }
}
