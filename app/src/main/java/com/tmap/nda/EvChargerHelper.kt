package com.tmap.nda

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.math.*

/**
 * 한국환경공단 전기자동차 충전소 정보 API(공공데이터포털) 연동. 각자 발급받은 키
 * (ev_charger_api_key)가 필요.
 *
 * v: 재억 제보(2026-08-25) - 원래는 시군구 지역코드로 좁혀서 조회했는데(5자리 표준코드,
 * 시도+시군구 2자리씩 분리 둘 다 시도), 이 API가 실제로 쓰는 지역코드 체계를 문서 없이
 * 계속 잘못 짚었음(둘 다 resultCode=00인데 결과 0건). 지역코드 추측을 포기하고, 대신
 * 전국 데이터를 한 번에 받아와 앱에서 직접 거리(15km 이내)로 걸러내는 방식으로 전환. #문제시 원복
 *
 * 참고: 필드명이 실제 응답과 다를 수 있어(문서만으로 100% 확정 어려움), 파싱 실패/0건 시
 * 원인 파악용으로 원본 응답 일부를 로그에 남김.
 */
object EvChargerHelper {
    data class ChargerStation(
        val name: String,
        val chargerType: String,
        val statusText: String,
        val distanceMeters: Double,
        val lat: Double,
        val lon: Double
    )

    fun fetchNearby(
        context: Context,
        httpClient: OkHttpClient,
        evApiKey: String,
        kakaoRestKey: String,
        lat: Double,
        lon: Double,
        onResult: (List<ChargerStation>) -> Unit
    ) {
        // v: 재억 제보(2026-08-25) - zcode(5자리 표준코드), zcode+zscode(2자리씩 분리) 둘 다
        // resultCode=00(정상)인데 totalCount=0으로 와서, 이 API가 쓰는 지역코드 체계를
        // 계속 잘못 짚었던 것으로 보임. 더 이상 추측하지 않고 지역코드 필터 자체를 빼고,
        // 대량으로 받아온 뒤 앱에서 직접 거리(15km)로 걸러내는 방식으로 전환. #문제시 원복
        NavLogger.d(context, "[전기차충전소] 요청 시작(전국, 거리필터) lat=$lat lon=$lon")
        val url = "https://apis.data.go.kr/B552584/EvCharger/getChargerInfo" +
            "?serviceKey=$evApiKey&numOfRows=3000&pageNo=1&dataType=JSON"
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                NavLogger.e(context, "[전기차충전소] 요청 실패: ${e.message}")
                onResult(emptyList())
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    val bodyStr = it.body?.string() ?: "{}"
                    if (!it.isSuccessful) {
                        NavLogger.e(context, "[전기차충전소] 실패 code=${it.code} body=${bodyStr.take(200)}")
                        onResult(emptyList())
                        return@use
                    }
                    try {
                        val json = JSONObject(bodyStr)
                        val body = json.optJSONObject("response")?.optJSONObject("body")
                        val resultCode = json.optJSONObject("response")?.optJSONObject("header")?.optString("resultCode")
                        val itemsContainer = body?.optJSONObject("items") ?: json.optJSONObject("items")
                        val itemsRaw = itemsContainer?.opt("item")
                        val items = when (itemsRaw) {
                            is org.json.JSONArray -> itemsRaw
                            is JSONObject -> org.json.JSONArray().put(itemsRaw) // 결과 1건일 때 배열이 아니라 단일 객체로 오는 경우
                            else -> null
                        }
                        if (items == null) {
                            NavLogger.e(context, "[전기차충전소] 응답 형식 다름(resultCode=$resultCode, 필드명 확인 필요): ${bodyStr.take(500)}")
                            onResult(emptyList())
                            return@use
                        }
                        if (items.length() == 0) {
                            NavLogger.d(context, "[전기차충전소] resultCode=$resultCode 결과 0건, 원본 응답: ${bodyStr.take(500)}")
                        }
                        val stations = (0 until items.length()).map { idx ->
                            val o = items.getJSONObject(idx)
                            val slat = o.optDouble("lat", 0.0)
                            val slon = o.optDouble("lng", o.optDouble("lon", 0.0))
                            ChargerStation(
                                name = o.optString("statNm", "이름 없음"),
                                chargerType = o.optString("chgerType", ""),
                                statusText = when (o.optString("stat", "")) {
                                    "2" -> "충전대기"
                                    "3" -> "충전중"
                                    "1" -> "통신이상"
                                    "4", "5" -> "운영중지"
                                    else -> "상태 미확인"
                                },
                                distanceMeters = haversineMeters(lat, lon, slat, slon),
                                lat = slat,
                                lon = slon
                            )
                        }.distinctBy { it.name to it.lat to it.lon }
                            .filter { it.distanceMeters in 0.1..15000.0 } // 좌표 없는(0,0) 항목 제외 + 15km 이내만
                            .sortedBy { it.distanceMeters }
                        NavLogger.d(context, "[전기차충전소] 전국 ${items.length()}건 중 15km 이내 ${stations.size}건")
                        onResult(stations)
                    } catch (e: Exception) {
                        NavLogger.e(context, "[전기차충전소] 파싱 실패: ${e.message} body=${bodyStr.take(300)}")
                        onResult(emptyList())
                    }
                }
            }
        })
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
