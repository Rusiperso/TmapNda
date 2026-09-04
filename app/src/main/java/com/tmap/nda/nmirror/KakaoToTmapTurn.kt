package com.tmap.nda.nmirror

/**
 * 카카오 길안내의 회전 종류를 **티맵 회전 번호**로 바꾼다.
 *
 * v: nMirror는 순정 티맵에서 빼낸 정보를 쓰기 때문에 회전 종류도 티맵 번호로 들어온다.
 * 우리는 카카오 안내를 쓰므로 번호를 맞춰줘야 한다. 아래 번호는 추측이 아니라 nMirror
 * 4.8.0의 변환표(service/aa/a.i())를 그대로 뒤집어 얻은 것이다 - 그 함수가 티맵 번호를
 * 안드로이드 오토 화살표 종류로 바꾸고 있어서, 어느 번호가 무슨 회전인지 확정할 수 있었다:
 *   12→좌회전, 13→우회전, 14→유턴, 16→급좌회전, 17→좌측완만, 18→우측완만, 19→급우회전,
 *   43→우측방향, 44→좌측방향, 101/102→우/좌 진입, 104/105→우/좌 진출,
 *   31~42→로터리, 200→출발, 201→도착, 표에 없는 번호→직진
 *
 * v: 2026-09-04 - 티맵 내비 SDK 원본 소스(RGConstant.TbtCode)를 직접 확보해서, 위 표에
 * 없던 시설물 번호(톨게이트 153, 고가/지하도로 119~124, 터널 121, 페리 170/171,
 * 도시고속도로 111~116)를 정의 그대로 채웠다. 그 전에는 이것들이 전부 "직진"으로
 * 떨어져서 톨게이트 앞인데도 직진 화살표가 떴다.
 * #문제시 원복: 이 파일과 NMirrorSender만 지우면 됨
 */
internal object KakaoToTmapTurn {

    private const val STRAIGHT = 11
    private const val LEFT = 12
    private const val RIGHT = 13
    private const val U_TURN = 14
    private const val SHARP_LEFT = 16
    private const val SLIGHT_LEFT = 17
    private const val SLIGHT_RIGHT = 18
    private const val SHARP_RIGHT = 19
    private const val ROTARY = 31
    private const val KEEP_RIGHT = 43
    private const val KEEP_LEFT = 44
    private const val IN_RIGHT = 101
    private const val IN_LEFT = 102
    private const val OUT_RIGHT = 104
    private const val OUT_LEFT = 105
    private const val DEPART = 200
    private const val ARRIVE = 201

    // v: 재억 제보(2026-09-04, 나브디 사진 비교) - 톨게이트("대동TG") 앞인데 우리 쪽은
    // 직진 화살표가 떴다. 표에 없는 종류가 전부 직진으로 떨어지고 있었기 때문.
    // 아래 번호는 추측이 아니라 티맵 내비 SDK 원본(RGConstant.TbtCode)의 정의 그대로다.
    // #문제시 원복: 이 아래 상수와 그걸 쓰는 when 가지들만 지우면 예전처럼 직진으로 나간다.
    private const val IN_STR = 103          // 직진방향에 고속도로 입구
    private const val OUT_STR = 106         // 직진방향에 고속도로 출구
    private const val CITY_IN_RIGHT = 111   // 도시고속도로 입구(오른쪽)
    private const val CITY_IN_LEFT = 112
    private const val CITY_IN_STR = 113
    private const val CITY_OUT_RIGHT = 114  // 도시고속도로 출구(오른쪽)
    private const val CITY_OUT_LEFT = 115
    private const val CITY_OUT_STR = 116
    private const val UNDER_IN = 119        // 지하도로 진입
    private const val OVER_IN = 120         // 고가도로 진입
    private const val TUNNEL_IN = 121       // 터널
    private const val UNDER_OUT = 123       // 지하도로 옆
    private const val OVER_OUT = 124        // 고가도로 옆
    private const val TOLLGATE = 153        // 톨게이트(고속)
    private const val FERRY_IN = 170
    private const val FERRY_OUT = 171

    fun from(rgCodeName: String, directionAngle: Int): Int {
        if (rgCodeName.startsWith("KNRGCode_RotaryDirection_") ||
            rgCodeName.startsWith("KNRGCode_RoundaboutDirection_")
        ) {
            return ROTARY
        }
        if (rgCodeName.startsWith("KNRGCode_Direction_")) {
            return fromAngle(directionAngle)
        }
        return when (rgCodeName) {
            "KNRGCode_Start" -> DEPART
            "KNRGCode_Goal" -> ARRIVE
            "KNRGCode_LeftTurn", "KNRGCode_UnprotectedLeftTurn" -> LEFT
            "KNRGCode_RightTurn" -> RIGHT
            "KNRGCode_UTurn" -> U_TURN
            "KNRGCode_LeftDirection", "KNRGCode_LeftStraight", "KNRGCode_ChangeLeftHighway",
            "KNRGCode_LeftTunnelSide" -> KEEP_LEFT
            "KNRGCode_RightDirection", "KNRGCode_RightStraight", "KNRGCode_ChangeRightHighway",
            "KNRGCode_RightTunnelSide" -> KEEP_RIGHT
            "KNRGCode_Tollgate", "KNRGCode_NonstopTollgate" -> TOLLGATE
            "KNRGCode_Tunnel", "KNRGCode_LeftTunnel", "KNRGCode_RightTunnel" -> TUNNEL_IN
            "KNRGCode_OverPath", "KNRGCode_LeftOverPath", "KNRGCode_RightOverPath" -> OVER_IN
            "KNRGCode_OverPathSide", "KNRGCode_LeftOverPathSide",
            "KNRGCode_RightOverPathSide" -> OVER_OUT
            "KNRGCode_UnderPath", "KNRGCode_LeftUnderPath", "KNRGCode_RightUnderPath" -> UNDER_IN
            "KNRGCode_UnderPathSide", "KNRGCode_LeftUnderPathSide",
            "KNRGCode_RightUnderPathSide" -> UNDER_OUT
            "KNRGCode_InFerry" -> FERRY_IN
            "KNRGCode_OutFerry" -> FERRY_OUT
            "KNRGCode_InHighway" -> IN_STR
            "KNRGCode_OutHighway" -> OUT_STR
            "KNRGCode_LeftInHighway" -> IN_LEFT
            "KNRGCode_RightInHighway" -> IN_RIGHT
            "KNRGCode_LeftOutHighway" -> OUT_LEFT
            "KNRGCode_RightOutHighway" -> OUT_RIGHT
            "KNRGCode_InCityway" -> CITY_IN_STR
            "KNRGCode_LeftInCityway" -> CITY_IN_LEFT
            "KNRGCode_RightInCityway" -> CITY_IN_RIGHT
            "KNRGCode_OutCityway" -> CITY_OUT_STR
            "KNRGCode_LeftOutCityway" -> CITY_OUT_LEFT
            "KNRGCode_RightOutCityway" -> CITY_OUT_RIGHT
            else -> STRAIGHT
        }
    }

    private fun fromAngle(rawAngle: Int): Int {
        val angle = ((rawAngle % 360) + 360) % 360
        return when (angle) {
            in 0..20, in 340..359 -> STRAIGHT
            in 21..60 -> SLIGHT_RIGHT
            in 61..120 -> RIGHT
            in 121..179 -> SHARP_RIGHT
            180 -> U_TURN
            in 181..239 -> SHARP_LEFT
            in 240..299 -> LEFT
            else -> SLIGHT_LEFT
        }
    }
}
