package com.tmap.nda.nmirror

import org.json.JSONObject

/**
 * 카카오 길안내 한 순간의 정보. 화면에 그릴 쪽(nMirror / 나브디)에 넘기기 전 단계.
 */
data class GuidanceSnapshot(
    val turnDistanceMeters: Int,
    val turnMainText: String,
    val roadName: String,
    val rgCodeName: String,
    val directionAngle: Int,
    val remainDistanceMeters: Int,
    val remainTimeSeconds: Int,
    val destinationName: String,
    val hasNext: Boolean,
    val nextTurnDistanceMeters: Int,
    val nextTurnMainText: String,
    val nextRgCodeName: String,
    val nextDirectionAngle: Int
)

/**
 * 길안내 정보를 순정 티맵 내비 엔진이 쓰는 RGData JSON 형태로 만든다.
 *
 * v: 재억 요청(2026-09-04) - 같은 JSON을 두 곳이 쓰게 돼서 만드는 곳을 하나로 모음:
 *  - nMirror가 깔린 폰: [NMirrorSender]가 AIDL로 nMirror에 넘김
 *  - nMirror가 없는 폰: [com.tmap.nda.navdy.NavdySender]가 블루투스로 나브디에 직접 보냄
 * 두 경로가 같은 형식을 요구하는 이유는, 나브디로 보내는 규격 자체가 nMirror가 쓰던
 * 것과 동일하기 때문(nMirror 4.8.0 분석으로 확인). #문제시 원복
 */
object RgDataJson {

    fun build(s: GuidanceSnapshot): String {
        val guidePoint = JSONObject()
            .put("nTBTDist", s.turnDistanceMeters)
            .put("nTBTTime", 0)
            .put("nTBTTurnType", KakaoToTmapTurn.from(s.rgCodeName, s.directionAngle))
            .put("szTBTMainText", s.turnMainText)
            .put("szCrossName", s.roadName)
            .put("szRoadName", s.roadName)

        val rgData = JSONObject()
            .put("nTotalDist", s.remainDistanceMeters)
            .put("nTotalTime", s.remainTimeSeconds)
            .put("szGoPosName", s.destinationName)
            .put("stGuidePoint", guidePoint)

        if (s.hasNext) {
            rgData.put(
                "stGuidePointNext",
                JSONObject()
                    .put("nTBTDist", s.nextTurnDistanceMeters)
                    .put("nTBTTime", 0)
                    .put("nTBTTurnType", KakaoToTmapTurn.from(s.nextRgCodeName, s.nextDirectionAngle))
                    .put("szTBTMainText", s.nextTurnMainText)
                    .put("szCrossName", s.nextTurnMainText)
            )
        }

        // v: 나브디 쪽 코드가 안내 중인지 판단할 때 이 두 값을 본다(RGData.isRouting() =
        // nGoPosDist > 0 && eRgStatus > 0). 비워두면 "안내 중이 아님"으로 보고 제한속도
        // 표시를 다른 방식으로 해석하므로 채워준다. #문제시 원복
        rgData.put("nGoPosDist", s.remainDistanceMeters)
        rgData.put("nGoPosTime", s.remainTimeSeconds)
        rgData.put("eRgStatus", 1)

        return rgData.toString()
    }
}
