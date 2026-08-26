package com.tmap.nda

import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance
import com.kakaomobility.knsdk.guidance.knguidance.locationguide.KNGuide_Location
import com.kakaomobility.knsdk.guidance.knguidance.routeguide.KNGuide_Route
import com.tmap.nda.navdy.KakaoToNavdyTurn
import com.tmap.nda.navdy.NavdySender
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Kakao SDK의 공식 타입을 HUD용 공통 데이터로 변환한다.
 * v2.2: androidx.car.app이 실제로 컴파일되는 게 확인돼서(v2.0 빌드 성공),
 * 그동안 보류했던 공식 API(guidance.trip, KNDirection 등) 기반 브릿지를 실제로 적용.
 * 컴파일 안 되는 API가 있으면 CI 로그로 바로 확인 가능하니 일단 시도. #문제시 원복
 *
 * [신규] Navdy 애프터마켓 HUD/클러스터 연동 추가.
 * androidx.car.app 경로(TmapNdaCarAppService)는 nMirror가 표준 CarAppService
 * 호스트 역할을 하지 않아 실차에서 동작하지 않는 것이 실측로그(everConnected 항상
 * false)로 확인됨. 대신 Navdy 프로토콜(블루투스, alelec 공개 재구현체 기준으로
 * 직접 재구현, gitlab.com/alelec/navdy)을 통해 Navdy 호환 애프터마켓 기기로
 * 직접 방향 안내를 전송한다. NavdySender가 연결되어 있지 않으면(페어링 전이거나
 * 기기가 꺼져있으면) sendManeuver 호출은 조용히 무시됨. #문제시 원복
 */
object KakaoHudBridge {
    private var lastDestDiagLogTime = 0L
    private var lastNavdySendTime = 0L
    private val etaFormat = SimpleDateFormat("HH:mm", Locale.KOREA)

    fun publish(
        context: android.content.Context,
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

        // v: 재억 요청(2026-08-22) - "동판교로"처럼 목적지명 대신 도로명이 뜨는 문제의 진짜
        // 원인을 잡기 위해, trip.goal.name의 실제 원본값(비어있는지/뭐가 들어있는지)을
        // 15초 간격으로 남김. 이 값이 계속 비어있으면 카카오 SDK가 애초에 목적지 이름을
        // 안 채워주고 있다는 뜻이고, 가끔 채워졌다 비었다 한다면 타이밍 문제라는 뜻. #문제시 원복
        val rawGoalName = trip?.goal?.name
        if (System.currentTimeMillis() - lastDestDiagLogTime > 15000L) {
            lastDestDiagLogTime = System.currentTimeMillis()
            NavLogger.d(context, "[목적지명진단] trip?.goal?.name=\"$rawGoalName\" (trip=${trip != null}, goal=${trip?.goal != null}) currentRoad=\"$currentRoad\"")
        }

        val remainDist = trip?.remainDist() ?: KakaoRouteDataRepository.remainDist
        val remainTime = trip?.remainTime() ?: KakaoRouteDataRepository.remainTime

        KakaoRouteDataRepository.publishGuidance(
            tbtDist = turnDistance,
            tbtMainText = instruction,
            remainDist = remainDist,
            remainTime = remainTime,
            roadName = currentRoad,
            rgCodeName = direction?.rgCode?.name.orEmpty(),
            directionAngle = direction?.directionAng ?: 0,
            destinationName = trip?.goal?.name.orEmpty()
        )

        // [신규] Navdy 애프터마켓 HUD/클러스터로 전송.
        // GPS 위치 갱신 콜백이 보통 초당 여러 번 오므로, 500ms 간격으로 스로틀해서
        // 블루투스 대역폭/기기 부하를 아낀다. #문제시 원복
        val now = System.currentTimeMillis()
        if (now - lastNavdySendTime >= 500L) {
            lastNavdySendTime = now
            try {
                val turn = KakaoToNavdyTurn.from(
                    direction?.rgCode?.name.orEmpty(),
                    direction?.directionAng ?: 0
                )
                val etaText = if (remainTime > 0) {
                    etaFormat.format(Date(System.currentTimeMillis() + remainTime * 1000L))
                } else ""
                val speedText = runCatching {
                    val speedMps = currentLocation?.let { loc ->
                        loc.javaClass.methods.firstOrNull { it.name == "getSpeed" }
                            ?.invoke(loc) as? Number
                    }?.toDouble() ?: 0.0
                    "${(speedMps * 3.6).toInt()}km/h"
                }.getOrDefault("")

                NavdySender.sendManeuver(
                    currentRoad = currentRoad,
                    turn = turn,
                    distanceToTurn = "${turnDistance}m",
                    pendingStreet = instruction,
                    eta = etaText,
                    speed = speedText
                )
            } catch (e: Exception) {
                NavLogger.e(context, "[Navdy 전송] 실패: ${e.message}")
            }
        }
    }
}
