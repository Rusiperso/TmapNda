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
            "KNRGCode_LeftTunnel", "KNRGCode_LeftTunnelSide", "KNRGCode_LeftOverPath",
            "KNRGCode_LeftOverPathSide", "KNRGCode_LeftUnderPath", "KNRGCode_LeftUnderPathSide" ->
                KEEP_LEFT
            "KNRGCode_RightDirection", "KNRGCode_RightStraight", "KNRGCode_ChangeRightHighway",
            "KNRGCode_RightTunnel", "KNRGCode_RightTunnelSide", "KNRGCode_RightOverPath",
            "KNRGCode_RightOverPathSide", "KNRGCode_RightUnderPath", "KNRGCode_RightUnderPathSide" ->
                KEEP_RIGHT
            "KNRGCode_LeftInHighway", "KNRGCode_LeftInCityway" -> IN_LEFT
            "KNRGCode_RightInHighway", "KNRGCode_RightInCityway" -> IN_RIGHT
            "KNRGCode_LeftOutHighway", "KNRGCode_LeftOutCityway" -> OUT_LEFT
            "KNRGCode_RightOutHighway", "KNRGCode_RightOutCityway" -> OUT_RIGHT
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
