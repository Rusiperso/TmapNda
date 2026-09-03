package com.tmap.nda.navdy

/**
 * 회전 종류 구분값.
 *
 * v: 원래는 나브디 정품 규격(protobuf)의 NavigationTurn 값이었고 NavdySender에 있었다.
 * v19.2.98에서 나브디 통신이 RGData JSON 방식으로 바뀌면서 전송용으로는 안 쓰게 됐지만,
 * 오버레이 화살표(NavOverlayManager)와 차선 안내(KakaoGuidanceDelegate)가 "이 회전이
 * 좌회전 계열인지 우회전 계열인지"를 판단할 때 계속 쓰고 있어 그대로 남긴다. #문제시 원복
 */
enum class NavdyTurn {
    START,
    EASY_LEFT,
    EASY_RIGHT,
    END,
    KEEP_LEFT,
    KEEP_RIGHT,
    LEFT,
    OUT_OF_ROUTE,
    RIGHT,
    SHARP_LEFT,
    SHARP_RIGHT,
    STRAIGHT,
    UTURN_LEFT,
    UTURN_RIGHT,
    ROUNDABOUT_SE,
    ROUNDABOUT_E,
    ROUNDABOUT_NE,
    ROUNDABOUT_N,
    ROUNDABOUT_NW,
    ROUNDABOUT_W,
    ROUNDABOUT_SW,
    ROUNDABOUT_S,
    FERRY,
    STATE_BOUNDARY,
    FOLLOW_ROUTE,
    EXIT_RIGHT,
    EXIT_LEFT,
    MOTORWAY,
    EXIT_ROUNDABOUT,
    UNKNOWN,
    MERGE_LEFT,
    MERGE_RIGHT
}

/**
 * 카카오내비 SDK의 KNRGCode(방향 안내 코드 문자열)를 [NavdyTurn]으로 변환.
 *
 * app/src/main/java/com/tmap/nda/hud/TmapNdaCarAppService.kt 의
 * maneuverType()/maneuverFromAngle() 매핑을 그대로 따름.
 */
object KakaoToNavdyTurn {

    fun from(rgCodeName: String, directionAngle: Int): NavdyTurn {
        if (rgCodeName.startsWith("KNRGCode_RotaryDirection_") ||
            rgCodeName.startsWith("KNRGCode_RoundaboutDirection_")
        ) {
            return roundaboutFromAngle(directionAngle)
        }
        if (rgCodeName.startsWith("KNRGCode_Direction_")) {
            return fromAngle(directionAngle)
        }
        return when (rgCodeName) {
            "KNRGCode_Start" -> NavdyTurn.START
            "KNRGCode_Goal" -> NavdyTurn.END
            "KNRGCode_LeftTurn", "KNRGCode_UnprotectedLeftTurn" -> NavdyTurn.LEFT
            "KNRGCode_RightTurn" -> NavdyTurn.RIGHT
            "KNRGCode_UTurn" -> NavdyTurn.UTURN_LEFT
            "KNRGCode_LeftDirection", "KNRGCode_LeftStraight", "KNRGCode_ChangeLeftHighway",
            "KNRGCode_LeftTunnel", "KNRGCode_LeftTunnelSide", "KNRGCode_LeftOverPath",
            "KNRGCode_LeftOverPathSide", "KNRGCode_LeftUnderPath", "KNRGCode_LeftUnderPathSide" ->
                NavdyTurn.KEEP_LEFT
            "KNRGCode_RightDirection", "KNRGCode_RightStraight", "KNRGCode_ChangeRightHighway",
            "KNRGCode_RightTunnel", "KNRGCode_RightTunnelSide", "KNRGCode_RightOverPath",
            "KNRGCode_RightOverPathSide", "KNRGCode_RightUnderPath", "KNRGCode_RightUnderPathSide" ->
                NavdyTurn.KEEP_RIGHT
            "KNRGCode_LeftInHighway", "KNRGCode_LeftInCityway" -> NavdyTurn.MERGE_LEFT
            "KNRGCode_RightInHighway", "KNRGCode_RightInCityway" -> NavdyTurn.MERGE_RIGHT
            "KNRGCode_LeftOutHighway", "KNRGCode_LeftOutCityway" -> NavdyTurn.EXIT_LEFT
            "KNRGCode_RightOutHighway", "KNRGCode_RightOutCityway" -> NavdyTurn.EXIT_RIGHT
            "KNRGCode_InFerry", "KNRGCode_OutFerry" -> NavdyTurn.FERRY
            else -> NavdyTurn.STRAIGHT
        }
    }

    private fun fromAngle(rawAngle: Int): NavdyTurn {
        val angle = ((rawAngle % 360) + 360) % 360
        return when {
            angle < 20 || angle >= 340 -> NavdyTurn.STRAIGHT
            angle < 60 -> NavdyTurn.EASY_RIGHT
            angle < 120 -> NavdyTurn.RIGHT
            angle < 160 -> NavdyTurn.SHARP_RIGHT
            angle < 200 -> NavdyTurn.UTURN_RIGHT
            angle < 240 -> NavdyTurn.SHARP_LEFT
            angle < 300 -> NavdyTurn.LEFT
            else -> NavdyTurn.EASY_LEFT
        }
    }

    private fun roundaboutFromAngle(rawAngle: Int): NavdyTurn {
        val angle = ((rawAngle % 360) + 360) % 360
        return when {
            angle < 45 -> NavdyTurn.ROUNDABOUT_N
            angle < 90 -> NavdyTurn.ROUNDABOUT_NE
            angle < 135 -> NavdyTurn.ROUNDABOUT_E
            angle < 180 -> NavdyTurn.ROUNDABOUT_SE
            angle < 225 -> NavdyTurn.ROUNDABOUT_S
            angle < 270 -> NavdyTurn.ROUNDABOUT_SW
            angle < 315 -> NavdyTurn.ROUNDABOUT_W
            else -> NavdyTurn.ROUNDABOUT_NW
        }
    }
}
