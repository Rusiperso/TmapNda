package com.tmap.nda

import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance
import com.kakaomobility.knsdk.guidance.knguidance.locationguide.KNGuide_Location
import com.kakaomobility.knsdk.guidance.knguidance.routeguide.KNGuide_Route
import com.tmap.nda.navdy.NavdySender

/**
 * Kakao SDK의 안내 정보를 바깥 화면(차량 계기판/HUD, 나브디)으로 내보내는 통로.
 *
 * 안내 정보를 한 번만 만들어서 두 곳에 나눠 준다 - 둘 다 같은 형식(RGData JSON)을 받는다:
 *  - [com.tmap.nda.nmirror.NMirrorSender] : nMirror에 넘겨 차량 순정 계기판/HUD로
 *  - [com.tmap.nda.navdy.NavdySender]     : 나브디 HUD로 블루투스 직접 전송
 *
 * 각자 자기 설정 스위치가 꺼져 있거나 연결이 안 돼 있으면 호출은 조용히 무시된다.
 *
 * 참고: androidx.car.app 경로(TmapNdaCarAppService)는 nMirror가 표준 CarAppService
 * 호스트 역할을 하지 않아 실차에서 동작하지 않는 것이 실측 로그(everConnected 항상
 * false)로 확인됨. #문제시 원복
 */
object KakaoHudBridge {

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
        // v: 재억 요청(2026-09-03) - 15초마다 똑같은 내용이 계속 다시 남고 있었음
        // (실기기 로그 199줄 중 180줄이 직전 줄과 동일). 값이 실제로 바뀔 때만 남김 -
        // "채워졌다 비었다" 하는 타이밍 문제는 오히려 더 선명하게 보임. #문제시 원복
        val rawGoalName = trip?.goal?.name
        NavLogger.dIfChanged(context, "목적지명진단", "[목적지명진단] trip?.goal?.name=\"$rawGoalName\" (trip=${trip != null}, goal=${trip?.goal != null}) currentRoad=\"$currentRoad\"")

        val remainDist = trip?.remainDist() ?: KakaoRouteDataRepository.remainDist
        val remainTime = trip?.remainTime() ?: KakaoRouteDataRepository.remainTime

        // [신규] 오버레이 2번째 줄(재억 요청, 2026-08-27) - "그 다음" 회전 미리보기.
        // KNGuide_Route가 curDirection과 같은 타입(KNDirection)의 nextDirection을
        // 공식으로 제공함(실제 SDK 클래스 시그니처로 확인함, KNSDK 1.12.8-hotfix02). #문제시 원복
        val nextDirection = routeGuide?.nextDirection
        val nextTurnDistance = if (currentLocation != null && nextDirection != null) {
            runCatching {
                currentLocation.distToLocation(nextDirection.location).coerceAtLeast(0)
            }.getOrDefault(0)
        } else {
            0
        }
        // v: 오버레이(재억 요청, 2026-08-27) "OOO 방면" 둘째 줄용 - curDirection의
        // instruction과 완전히 동일한 방식으로 nextDirection에서도 도로/지점명 추출. #문제시 원복
        val nextInstruction =
            nextDirection?.directionNames?.firstOrNull().orEmpty()
                .ifBlank { nextDirection?.nodeName.orEmpty() }

        KakaoRouteDataRepository.publishGuidance(
            tbtDist = turnDistance,
            tbtMainText = instruction,
            remainDist = remainDist,
            remainTime = remainTime,
            roadName = currentRoad,
            rgCodeName = direction?.rgCode?.name.orEmpty(),
            directionAngle = direction?.directionAng ?: 0,
            destinationName = trip?.goal?.name.orEmpty(),
            hasNextDirection = nextDirection != null,
            nextTbtDist = nextTurnDistance,
            nextRgCodeName = nextDirection?.rgCode?.name.orEmpty(),
            nextDirectionAngle = nextDirection?.directionAng ?: 0,
            nextTbtMainText = nextInstruction
        )

        // v: 재억 제보(2026-09-03) - 순정 티맵으로 안내하면 차량 순정 계기판/HUD에 뜨는데
        // 이 앱의 카카오 안내는 안 뜬다는 문제. nMirror가 순정 티맵에서 정보를 빼가는 구조라
        // 우리 앱은 대상이 아니었는데, 같은 정보를 받는 창구가 외부에 열려 있어 직접 넣는다
        // (자세한 근거는 NMirrorSender 주석).
        //
        // v: 재억 요청(2026-09-04) - 나브디도 같은 형식(RGData JSON)을 받으므로 정보를 한 번만
        // 만들어서 두 곳에 나눠 준다. 어느 쪽으로 보낼지는 각자의 설정 스위치가 판단하고,
        // 보내는 간격 제한도 각자가 처리한다.
        //  - nMirror 있는 폰: nMirror가 차량 순정 계기판/HUD로 중계
        //  - nMirror 없는 폰: NavdySender가 나브디에 블루투스로 직접 전송
        // #문제시 원복: 아래 블록만 지우면 됨
        val snapshot = com.tmap.nda.nmirror.GuidanceSnapshot(
            turnDistanceMeters = turnDistance,
            turnMainText = instruction,
            turnNodeName = direction?.nodeName.orEmpty(),
            roadName = currentRoad,
            rgCodeName = direction?.rgCode?.name.orEmpty(),
            directionAngle = direction?.directionAng ?: 0,
            remainDistanceMeters = remainDist,
            remainTimeSeconds = remainTime,
            destinationName = trip?.goal?.name.orEmpty(),
            hasNext = nextDirection != null,
            nextTurnDistanceMeters = nextTurnDistance,
            nextTurnMainText = nextInstruction,
            nextTurnNodeName = nextDirection?.nodeName.orEmpty(),
            nextRgCodeName = nextDirection?.rgCode?.name.orEmpty(),
            nextDirectionAngle = nextDirection?.directionAng ?: 0,
            highwayListJson = com.tmap.nda.nmirror.TbtListJson.build(routeGuide, currentLocation)
        )

        try {
            com.tmap.nda.nmirror.NMirrorSender.send(context, snapshot)
        } catch (e: Exception) {
            NavLogger.e(context, "[nMirror 전송] 실패: ${e.message}")
        }

        try {
            NavdySender.sendGuidance(snapshot)
        } catch (e: Exception) {
            NavLogger.e(context, "[Navdy 전송] 실패: ${e.message}")
        }
    }
}
