package com.tmap.nda

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.math.*

/**
 * 한국환경공단 전기자동차 충전소 정보 API(공공데이터포털) 연동. 각자 발급받은 키
 * (ev_charger_api_key)가 필요. 이 API는 위경도 반경검색이 아니라 시군구코드(zcode)
 * 기준 조회라서, 먼저 카카오 좌표->행정구역 API로 시군구코드를 구한 뒤 그 지역
 * 충전소를 받아와 클라이언트에서 거리순 정렬함.
 *
 * 참고: 필드명이 실제 응답과 다를 수 있어(문서만으로 100% 확정 어려움), 파싱 실패 시
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

    /** 카카오 좌표->행정구역 API로 시군구 코드(5자리) 조회. */
    private fun resolveZcode(
        httpClient: OkHttpClient,
        kakaoRestKey: String,
        lat: Double,
        lon: Double,
        onResult: (String?) -> Unit
    ) {
        val url = "https://dapi.kakao.com/v2/local/geo/coord2regioncode.json?x=$lon&y=$lat"
        val request = Request.Builder().url(url).header("Authorization", "KakaoAK $kakaoRestKey").build()
        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) = onResult(null)
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) { onResult(null); return@use }
                    try {
                        val json = JSONObject(it.body?.string() ?: "{}")
                        val docs = json.optJSONArray("documents")
                        val region = (0 until (docs?.length() ?: 0))
                            .map { i -> docs!!.getJSONObject(i) }
                            .firstOrNull { d -> d.optString("region_type") == "H" }
                        val fullCode = region?.optString("code")
                        onResult(fullCode?.take(5))
                    } catch (e: Exception) {
                        onResult(null)
                    }
                }
            }
        })
    }

    fun fetchNearby(
        context: Context,
        httpClient: OkHttpClient,
        evApiKey: String,
        kakaoRestKey: String,
        lat: Double,
        lon: Double,
        onResult: (List<ChargerStation>) -> Unit
    ) {
        resolveZcode(httpClient, kakaoRestKey, lat, lon) { fullCode ->
            if (fullCode == null || fullCode.length < 4) {
                NavLogger.e(context, "[전기차충전소] 행정구역 코드 조회 실패 lat=$lat lon=$lon")
                onResult(emptyList())
                return@resolveZcode
            }
            // v: 재억 제보(2026-08-25) - zcode=41220(5자리 표준코드) 그대로 넣었더니
            // resultCode=00(정상)인데 totalCount=0으로 옴 - 이 API는 5자리 표준코드가 아니라
            // 시도코드(2자리)+시군구코드(2자리)를 따로 받는 방식으로 추정됨. 41220 ->
            // zcode=41(경기도), zscode=22(평택시)로 분리해서 시도. #문제시 원복
            val sidoCode = fullCode.take(2)
            val sigunguCode = fullCode.drop(2).take(2)
            NavLogger.d(context, "[전기차충전소] 요청 시작 sido=$sidoCode sigungu=$sigunguCode lat=$lat lon=$lon")
            // v: 재억 제보(2026-08-25) - 공공데이터포털 키를 URLEncoder로 또 인코딩해서
            // 이중 인코딩이 되어 "SERVICE_KEY_IS_NOT_REGISTERED_ERROR"가 났음. 포털이 주는
            // 키는 이미 Encoding 형태(특수문자가 %XX로 인코딩된 상태)라 그대로 붙여야 함. #문제시 원복
            // v: https로 변경 - 공공데이터포털 최신 API는 http 요청을 막거나 리다이렉트할 수 있음. #문제시 원복
            val url = "https://apis.data.go.kr/B552584/EvCharger/getChargerInfo" +
                "?serviceKey=$evApiKey&zcode=$sidoCode&zscode=$sigunguCode&numOfRows=100&pageNo=1&dataType=JSON"
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
                            // v: 재억 제보(2026-08-25) - 승인됐는데도 검색이 안 됨. 공공데이터포털
                            // 표준 REST 응답은 response->body->items->item으로 감싸져 있는데,
                            // 예전엔 최상위 items만 봐서 못 찾았을 가능성. 두 형태 다 지원하고,
                            // resultCode도 확인해서 "정상인데 결과가 0건"인 경우와 구분. #문제시 원복
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
                                // v: 재억 제보(2026-08-25) - 요청은 성공(에러 없음)했는데 결과가 0건.
                                // 파라미터명이 잘못됐는지, 진짜 그 지역에 데이터가 없는지 구분하기
                                // 위해 0건일 때도 원본 응답을 로그로 남김. #문제시 원복
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
                                .sortedBy { it.distanceMeters }
                            onResult(stations)
                        } catch (e: Exception) {
                            NavLogger.e(context, "[전기차충전소] 파싱 실패: ${e.message} body=${bodyStr.take(300)}")
                            onResult(emptyList())
                        }
                    }
                }
            })
        }
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
