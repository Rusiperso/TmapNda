package com.tmap.nda

import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance
import com.kakaomobility.knsdk.guidance.knguidance.locationguide.KNGuide_Location
import com.kakaomobility.knsdk.guidance.knguidance.routeguide.KNGuide_Route

/**
 * Kakao SDK의 공식 타입을 HUD용 공통 데이터로 변환한다.
 * v2.2: androidx.car.app이 실제로 컴파일되는 게 확인돼서(v2.0 빌드 성공),
 * 그동안 보류했던 공식 API(guidance.trip, KNDirection 등) 기반 브릿지를 실제로 적용.
 * 컴파일 안 되는 API가 있으면 CI 로그로 바로 확인 가능하니 일단 시도. #문제시 원복
 */
object KakaoHudBridge {
    fun publish(
        guidance: KNGuidance,
        locationGuide: KNGuide_Location?,
        routeGuide: KNGuide_Route?
    ) {
        val currentLocation = locationGuide?.location
        val direction = routeGuide?.curDirection
        val trip = guidance.trip

        val turnDistance =
            if (currentLocation != null && direction != null) {
                runCatching {
                    currentLocation
                        .distToLocation(direction.location)
                        .coerceAtLeast(0)
                }.getOrDefault(KakaoRouteDataRepository.tbtDist)
            } else {
                KakaoRouteDataRepository.tbtDist
            }

        val currentRoad = currentLocation?.roadName.orEmpty()
            .ifBlank { KakaoRouteDataRepository.roadName }

        val instruction =
            direction?.directionNames?.firstOrNull().orEmpty()
                .ifBlank { direction?.nodeName.orEmpty() }
                .ifBlank { currentRoad }

        KakaoRouteDataRepository.publishGuidance(
            tbtDist = turnDistance,
            tbtMainText = instruction,
            remainDist = trip?.remainDist()
                ?: KakaoRouteDataRepository.remainDist,
            remainTime = trip?.remainTime()
                ?: KakaoRouteDataRepository.remainTime,
            roadName = currentRoad,
            rgCodeName = direction?.rgCode?.name.orEmpty(),
            directionAngle = direction?.directionAng ?: 0,
            destinationName = trip?.goal?.name.orEmpty()
                .ifBlank { "목적지" }
        )
    }
}
