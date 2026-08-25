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

    // v: 신규기능(주유소 브랜드 다중선택 필터) - 재억 요청 - 여러 브랜드(GS,현대오일뱅크,
    // S-OIL 등)를 동시에 골라 저장해두면, 다음부터 계속 그 브랜드들만 걸러서 보여줌.
    // 콤마로 구분된 브랜드 코드 문자열로 저장, 비어있으면(null) 전체. #문제시 원복
    private const val PREF_BRAND_FILTER = "opinet_brand_filter_codes"
    // v: 재억 지적(2026-08-25) - 브랜드 필터를 "전체"로 확정해도 저장값이 비어있어서
    // savedBrandFilter()가 null을 반환 -> "아직 한 번도 안 골랐다"와 구분이 안 돼서
    // 매번 팝업이 다시 떴음. 확정 여부를 별도 플래그로 기억. #문제시 원복
    private const val PREF_BRAND_FILTER_CONFIGURED = "opinet_brand_filter_configured"

    fun isBrandFilterConfigured(context: Context): Boolean =
        context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).getBoolean(PREF_BRAND_FILTER_CONFIGURED, false)

    fun savedBrandFilter(context: Context): Set<String>? {
        val raw = context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).getString(PREF_BRAND_FILTER, null)
        if (raw.isNullOrBlank()) return null
        return raw.split(",").toSet()
    }

    fun saveBrandFilter(context: Context, codes: Set<String>) {
        val prefs = context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
        val editor = prefs.edit().putBoolean(PREF_BRAND_FILTER_CONFIGURED, true)
        if (codes.isEmpty()) {
            editor.remove(PREF_BRAND_FILTER)
        } else {
            editor.putString(PREF_BRAND_FILTER, codes.joinToString(","))
        }
        editor.apply()
    }

    /**
     * WGS84(위경도) -> TM128(오피넷 좌표계) 변환.
     *
     * v: 재억 제보(2026-08-25) - 이전 구현은 람베르트 정각원추도법(기상청/네이버 격자용
     * KATEC-LCC 공식)을 썼는데, 오피넷 TM128은 그것과 전혀 다른 "베셀 타원체 기반
     * 횡단 메르카토르(TM) 도법 + 좌표계 원점 이동(datum shift)"이라 좌표가 완전히
     * 어긋나서 반경 검색 결과가 0건으로 나왔음. 검증된 공식(WGS84 지리좌표 -> WGS84
     * 지심좌표 -> 베셀 지심좌표로 원점이동 -> 베셀 지리좌표 -> TM128 도법 투영)으로
     * 교체. 파라미터: lat_0=38N, lon_0=128E, k=0.9999, x_0=400000, y_0=600000,
     * ellps=bessel, towgs84=-146.43,507.89,681.46. #문제시 원복
     */
    private const val WGS84_A = 6378137.0
    private const val WGS84_F = 1.0 / 298.257223563
    private const val BESSEL_A = 6377397.155
    private const val BESSEL_F = 1.0 / 299.1528128
    private const val DX = 146.43
    private const val DY = -507.89
    private const val DZ = -681.46
    private const val TM128_LAT0 = 38.0
    private const val TM128_LON0 = 128.0
    private const val TM128_K0 = 0.9999
    private const val TM128_X0 = 400000.0
    private const val TM128_Y0 = 600000.0

    private fun geographicToGeocentric(latDeg: Double, lonDeg: Double, a: Double, f: Double): Triple<Double, Double, Double> {
        val e2 = 2 * f - f * f
        val lat = Math.toRadians(latDeg)
        val lon = Math.toRadians(lonDeg)
        val n = a / sqrt(1 - e2 * sin(lat) * sin(lat))
        val x = n * cos(lat) * cos(lon)
        val y = n * cos(lat) * sin(lon)
        val z = n * (1 - e2) * sin(lat)
        return Triple(x, y, z)
    }

    private fun geocentricToGeographic(x: Double, y: Double, z: Double, a: Double, f: Double): Pair<Double, Double> {
        val e2 = 2 * f - f * f
        val lon = atan2(y, x)
        val p = sqrt(x * x + y * y)
        var lat = atan2(z, p * (1 - e2))
        repeat(5) {
            val n = a / sqrt(1 - e2 * sin(lat) * sin(lat))
            val h = p / cos(lat) - n
            lat = atan2(z, p * (1 - e2 * n / (n + h)))
        }
        return Pair(Math.toDegrees(lat), Math.toDegrees(lon))
    }

    /** 베셀 타원체 위경도 -> TM128 평면좌표 (Gauss-Krüger 횡단 메르카토르 투영). */
    private fun besselLatLonToTm(latDeg: Double, lonDeg: Double): Pair<Double, Double> {
        val a = BESSEL_A
        val f = BESSEL_F
        val e2 = 2 * f - f * f
        val ep2 = e2 / (1 - e2)
        val lat0 = Math.toRadians(TM128_LAT0)
        val lon0 = Math.toRadians(TM128_LON0)
        val lat = Math.toRadians(latDeg)
        val lon = Math.toRadians(lonDeg)

        fun meridianArc(phi: Double): Double {
            val n = f / (2 - f)
            val n2 = n * n
            val n3 = n2 * n
            val a0 = a / (1 + n) * (1 + n2 / 4 + n2 * n2 / 64)
            return a0 * (phi - 1.5 * n * sin(2 * phi) + (15.0 / 16) * n2 * sin(4 * phi) - (35.0 / 48) * n3 * sin(6 * phi))
        }

        val m = meridianArc(lat)
        val m0 = meridianArc(lat0)
        val nu = a / sqrt(1 - e2 * sin(lat) * sin(lat))
        val t = tan(lat)
        val c = ep2 * cos(lat) * cos(lat)
        val aTerm = (lon - lon0) * cos(lat)

        val x = TM128_K0 * nu * (aTerm + (1 - t * t + c) * aTerm.pow(3) / 6 +
            (5 - 18 * t * t + t.pow(4)) * aTerm.pow(5) / 120) + TM128_X0
        val y = TM128_K0 * (m - m0 + nu * t * (aTerm.pow(2) / 2 +
            (5 - t * t + 9 * c) * aTerm.pow(4) / 24)) + TM128_Y0
        return Pair(x, y)
    }

    /** TM128 평면좌표 -> 베셀 타원체 위경도 (역투영, 반복 계산). */
    private fun tmToBesselLatLon(x: Double, y: Double): Pair<Double, Double> {
        val a = BESSEL_A
        val f = BESSEL_F
        val e2 = 2 * f - f * f
        val ep2 = e2 / (1 - e2)
        val lat0 = Math.toRadians(TM128_LAT0)
        val lon0 = Math.toRadians(TM128_LON0)

        fun meridianArc(phi: Double): Double {
            val n = f / (2 - f)
            val n2 = n * n
            val n3 = n2 * n
            val a0 = a / (1 + n) * (1 + n2 / 4 + n2 * n2 / 64)
            return a0 * (phi - 1.5 * n * sin(2 * phi) + (15.0 / 16) * n2 * sin(4 * phi) - (35.0 / 48) * n3 * sin(6 * phi))
        }

        val m0 = meridianArc(lat0)
        val m = m0 + (y - TM128_Y0) / TM128_K0
        var phi1 = m / (a * (1 - e2 / 4 - 3 * e2 * e2 / 64))
        repeat(5) {
            val mCalc = meridianArc(phi1)
            phi1 += (m - mCalc) / (a * (1 - e2 * sin(phi1) * sin(phi1)))
        }
        val nu1 = a / sqrt(1 - e2 * sin(phi1) * sin(phi1))
        val t1 = tan(phi1)
        val c1 = ep2 * cos(phi1) * cos(phi1)
        val r1 = a * (1 - e2) / (1 - e2 * sin(phi1) * sin(phi1)).pow(1.5)
        val d = (x - TM128_X0) / (nu1 * TM128_K0)

        val lat = phi1 - (nu1 * t1 / r1) * (d * d / 2 -
            (5 + 3 * t1 * t1 + 10 * c1 - 4 * c1 * c1) * d.pow(4) / 24)
        val lon = lon0 + (d - (1 + 2 * t1 * t1 + c1) * d.pow(3) / 6) / cos(phi1)
        return Pair(Math.toDegrees(lat), Math.toDegrees(lon))
    }

    fun wgs84ToTm128(lon: Double, lat: Double): Pair<Double, Double> {
        val (gx, gy, gz) = geographicToGeocentric(lat, lon, WGS84_A, WGS84_F)
        // WGS84 -> 베셀 지심좌표 원점 이동
        val bx = gx + DX
        val by = gy + DY
        val bz = gz + DZ
        val (bLat, bLon) = geocentricToGeographic(bx, by, bz, BESSEL_A, BESSEL_F)
        return besselLatLonToTm(bLat, bLon)
    }

    /** TM128 -> WGS84 역변환. 오피넷 응답 좌표(GIS_X_COOR/GIS_Y_COOR)를 카카오 길안내에 쓰려면 필요. */
    fun tm128ToWgs84(x: Double, y: Double): Pair<Double, Double> {
        val (bLat, bLon) = tmToBesselLatLon(x, y)
        val (bx, by, bz) = geographicToGeocentric(bLat, bLon, BESSEL_A, BESSEL_F)
        // 베셀 -> WGS84 지심좌표 원점 역이동
        val gx = bx - DX
        val gy = by - DY
        val gz = bz - DZ
        val (wLat, wLon) = geocentricToGeographic(gx, gy, gz, WGS84_A, WGS84_F)
        return Pair(wLon, wLat)
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
        NavLogger.d(context, "[오피넷] 좌표변환 wgs84(lat=$lat,lon=$lon) -> tm128(x=$x,y=$y)")
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
