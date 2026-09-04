package com.tmap.nda.nmirror

import com.tmap.nda.KakaoRouteDataRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * 카카오 길안내 한 순간의 정보. 화면에 그릴 쪽(nMirror / 나브디)에 넘기기 전 단계.
 */
data class GuidanceSnapshot(
    val turnDistanceMeters: Int,
    val turnMainText: String,
    // 안내지점 자체의 이름("대동TG", "서면교차로" 등). 티맵은 이걸 szCrossName에 넣는데
    // 우리는 그 칸에 현재 도로명을 넣고 있어서 나브디에 시설 이름이 안 떴다. #문제시 원복
    val turnNodeName: String,
    val roadName: String,
    val rgCodeName: String,
    val directionAngle: Int,
    val remainDistanceMeters: Int,
    val remainTimeSeconds: Int,
    val destinationName: String,
    val hasNext: Boolean,
    val nextTurnDistanceMeters: Int,
    val nextTurnMainText: String,
    val nextTurnNodeName: String,
    val nextRgCodeName: String,
    val nextDirectionAngle: Int,
    // 앞으로 지날 고속도로 시설 목록(JSON 배열 문자열). 없으면 null - [TbtListJson] 참고.
    val highwayListJson: String?
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
            .put("szCrossName", s.turnNodeName.ifBlank { s.roadName })
            .put("szRoadName", s.roadName)

        val rgData = JSONObject()
            .put("nTotalDist", s.remainDistanceMeters)
            .put("nTotalTime", s.remainTimeSeconds)
            .put("szGoPosName", s.destinationName)
            .put("szPosRoadName", s.roadName)
            .put("stGuidePoint", guidePoint)

        if (s.hasNext) {
            rgData.put(
                "stGuidePointNext",
                JSONObject()
                    .put("nTBTDist", s.nextTurnDistanceMeters)
                    .put("nTBTTime", 0)
                    .put("nTBTTurnType", KakaoToTmapTurn.from(s.nextRgCodeName, s.nextDirectionAngle))
                    .put("szTBTMainText", s.nextTurnMainText)
                    .put("szCrossName", s.nextTurnNodeName.ifBlank { s.nextTurnMainText })
            )
        }

        // v: 나브디 쪽 코드가 안내 중인지 판단할 때 이 두 값을 본다(RGData.isRouting() =
        // nGoPosDist > 0 && eRgStatus > 0). 비워두면 "안내 중이 아님"으로 보고 제한속도
        // 표시를 다른 방식으로 해석하므로 채워준다. #문제시 원복
        rgData.put("nGoPosDist", s.remainDistanceMeters)
        rgData.put("nGoPosTime", s.remainTimeSeconds)
        rgData.put("eRgStatus", 1)

        // v: 재억 제보(2026-09-04) - 나브디에 경로 좌표를 보내도 가운데 지도가 안 그려지던 문제.
        // 길 모양(좌표)만 주고 "차가 그 경로 어디에 있는지"를 안 줘서, 받는 쪽이 어디를 중심으로
        // 어느 방향으로 그릴지 정할 수가 없었다. nMirror가 순정 티맵에서 빼가는 RGData에는
        // 이 값들이 들어 있다. 못 읽으면 아예 안 넣어 예전과 같게 둔다. #문제시 원복
        TmapPositionSource.current()?.let { pos ->
            rgData.put("vpPosPointLat", pos.latitude)
            rgData.put("vpPosPointLon", pos.longitude)
            rgData.put("nPosAngle", pos.angle)
            rgData.put("nPosSpeed", pos.speed)
            if (pos.remainedLengthToEnd > 0) {
                rgData.put("remainedLengthToEnd", pos.remainedLengthToEnd)
            }
            // v: 재억 제보(2026-09-04, 나브디 사진 비교) - 순정 티맵으로 보낼 때는 나브디에
            // 제한속도가 뜨는데(고속도로 100) 우리 앱으로 보낼 때만 안 떴다. 기기는 그릴 줄
            // 아는데 우리가 이 칸을 아예 안 채우고 있었을 뿐. #문제시 원복
            if (pos.roadLimitSpeedRaw > 0) {
                rgData.put("nRoadLimitSpeed", pos.roadLimitSpeedRaw)
            }
        }

        // v: 재억 제보(2026-09-04) - 나브디 화면에 카메라/방지턱 알림이 전혀 안 뜨던 문제.
        // openpilot에는 [UdpSenderService]가 KakaoRouteDataRepository.safetyType 등을 UDP로
        // 따로 보내고 있었는데, 나브디/nMirror로 가는 이 JSON에는 sdiInfo가 아예 없었다 -
        // 나브디가 화면에 그릴 이벤트 자체를 못 받고 있었던 것. 실기기 rgData 원본 덤프
        // (`rgData.sdiInfo[0].nSdiType` 등)에서 확인한 것과 같은 필드명으로 채워준다. #문제시 원복
        if (KakaoRouteDataRepository.safetyType >= 0 && KakaoRouteDataRepository.safetyDist > 0) {
            val sdiInfo = JSONObject()
                .put("nSdiType", KakaoRouteDataRepository.safetyType)
                .put("nSdiSpeedLimit", KakaoRouteDataRepository.safetySpeedLimit)
                .put("nSdiDist", KakaoRouteDataRepository.safetyDist)
                .put("bSdiTarget", true)
            rgData.put("sdiCount", 1)
            rgData.put("sdiInfo", JSONArray().put(sdiInfo))
        }

        return rgData.toString()
    }
}
