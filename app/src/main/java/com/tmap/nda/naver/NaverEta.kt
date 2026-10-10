package com.tmap.nda.naver

import android.content.Context
import com.tmap.nda.TmapPoiConverter

/**
 * 최근 목적지·즐겨찾기 목록에 붙는 "약 N분" 표시용 소요시간 추정.
 * 목록 항목마다 네이버 길찾기를 부르면 호출 한도(월 3천 건)를 금방 쓰므로, 직선거리로 어림잡는다.
 * 정확한 시간은 목적지를 골라 경로 선택 카드가 뜰 때 네이버가 계산해 준다.
 */
object NaverEta {
    /** 직선거리에 도로 굽음(1.3배)을 곱하고, 거리 구간별 평균 속도로 나눈다. */
    fun estimateMinutes(startLat: Double, startLon: Double, destLat: Double, destLon: Double): Int {
        val km = TmapPoiConverter.haversineMeters(startLat, startLon, destLat, destLon) / 1000.0 * 1.3
        val speed = when {
            km < 3 -> 25.0
            km < 15 -> 35.0
            km < 60 -> 55.0
            else -> 75.0
        }
        return Math.round(km / speed * 60.0).toInt().coerceAtLeast(1)
    }

    /** 카카오 때의 KakaoSdkState.computeEta와 같은 모양(결과는 바로 돌려준다). */
    fun computeEta(
        context: Context,
        startLat: Double,
        startLon: Double,
        destLat: Double,
        destLon: Double,
        callback: (etaMinutes: Int?, distanceMeters: Int?) -> Unit
    ) {
        val meters = TmapPoiConverter.haversineMeters(startLat, startLon, destLat, destLon).toInt()
        callback(estimateMinutes(startLat, startLon, destLat, destLon), meters)
    }
}
