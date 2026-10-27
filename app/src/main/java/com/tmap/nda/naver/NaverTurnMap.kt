package com.tmap.nda.naver

/**
 * 네이버 안내 종류(type) 번호를 앱 안의 다른 표현으로 바꾸는 표.
 * 번호의 뜻은 네이버 Directions 15 문서 기준이다.
 *
 * 콤마(openpilot) 회전 코드는 KakaoGuidanceDelegate.mapKakaoTurnTypeToOpenpilot 주석에 정리된 체계를 따른다:
 *   11=직진 12=좌회전 13=우회전 14=유턴 51=무안내 201=도착
 *   6=우측분기 7=좌측분기 101=우측 완만한 램프 102=좌측 완만한 램프 153=톨게이트
 * 확실한 것만 매핑하고 모르는 것은 51(무안내)로 둔다 - 틀린 화살표가 나가는 것보다 낫기 때문.
 */
object NaverTurnMap {

    const val NONE = 51

    fun toOpenpilotCode(type: Int): Int = if (isRotary(type)) rotaryPlainCode(type) else when (type) {
        1 -> 11
        2, 8, 11, 12, 13 -> 12                // 좌회전, 비보호 좌회전, 8·9·11시 방향
        3, 14, 15, 16 -> 13                   // 우회전, 1·3·4시 방향
        6 -> 14                               // 유턴
        4, 41, 81 -> 7                        // 좌측 진행/진입/좌측 본선 합류
        5, 42, 82 -> 6                        // 우측 진행/진입/우측 본선 합류
        57, 59, 62, 64, 76 -> 7               // 좌측 고속·도시고속·고가·지하·자동차전용 진입
        66, 68, 71, 73, 77 -> 6               // 우측 진입
        58, 60, 79 -> 102                     // 좌측 고속·도시고속·자동차전용 진출 = 좌측 완만한 램프
        67, 69, 80 -> 101                     // 우측 진출 = 우측 완만한 램프
        121, 122, 123 -> 153                  // 톨게이트
        87 -> NONE                            // 경유지: 화살표 없음
        88 -> 201                             // 목적지
        else -> NONE
    }

    /**
     * 콤마로 내보낼 회전 코드. 카카오(KakaoGuidanceDelegate.mapKakaoTurnTypeToOpenpilot)와 같은 규칙으로 맞춘다:
     * 좌/우회전·유턴·직진·도착, 좌/우 분기(7/6), 고속도로 진출 램프(102/101), 톨게이트(153)만 내보내고
     * 도시고속도로 진출·본선 합류는 "자동 길안내 주행(시험)"이 켜졌을 때만 갈림길로 내보낸다.
     * 카카오가 안 내보내는 고속·고가·지하 "진입"은 51(무안내)로 둔다(예전엔 분기로 나가 엉뚱한 차선변경 후보가 됐음).
     * 화면에 그리는 화살표는 [toOpenpilotCode]를 그대로 쓴다.
     */
    fun toCommaCode(type: Int, autoDrive: Boolean): Int {
        if (isRotary(type)) return rotaryCommaCode(type)
        return when (type) {
            81 -> if (autoDrive) 7 else NONE      // 좌측 본선 합류 = 카카오 ChangeLeftHighway
            82 -> if (autoDrive) 6 else NONE
            53, 60, 69 -> when (type) {           // 도시고속도로 진출 = 카카오 OutCityway 계열
                60 -> if (autoDrive) 102 else NONE
                else -> if (autoDrive) 101 else NONE
            }
            else -> when (clusterName(type)) {
                "KNRGCode_Straight" -> 11
                "KNRGCode_LeftTurn" -> 12
                "KNRGCode_RightTurn" -> 13
                "KNRGCode_UTurn" -> 14
                "KNRGCode_Goal" -> 201
                "KNRGCode_LeftDirection" -> 7
                "KNRGCode_RightDirection" -> 6
                "KNRGCode_LeftOutHighway" -> 102
                "KNRGCode_RightOutHighway", "KNRGCode_OutHighway" -> 101
                "KNRGCode_Tollgate" -> 153
                else -> NONE
            }
        }
    }

    // ===== 로터리(21~34)·회전교차로(91~104) =====
    // 두 묶음은 번호만 70 차이 나고 방향 구성이 같다. 기준 번호(base) 1=직진 2=유턴 3=7시 4=8시 ... 8=12시 9=1시 ... 14=6시.

    fun isRotary(type: Int): Boolean = type in 21..34 || type in 91..104

    private fun rotaryBase(type: Int): Int = when (type) {
        in 21..34 -> type - 20
        in 91..104 -> type - 90
        else -> 0
    }

    /** 시계 방향 시각(1~12). 직진·유턴이거나 로터리가 아니면 0. */
    fun rotaryClock(type: Int): Int {
        val base = rotaryBase(type)
        return if (base in 3..14) (base + 3) % 12 + 1 else 0
    }

    /** 폰 화면용 단순 화살표: 직진 11, 왼쪽 12, 오른쪽 13, 유턴·6시 14. */
    private fun rotaryPlainCode(type: Int): Int {
        val base = rotaryBase(type)
        if (base == 1) return 11
        if (base == 2) return 14
        return when (rotaryClock(type)) {
            12 -> 11
            6 -> 14
            in 1..5 -> 13
            else -> 12
        }
    }

    /** 콤마로 내보내는 로터리 전용 코드(131~142). 카카오 로터리 변환(KakaoGuidanceDelegate)과 같은 값을 쓴다. */
    private fun rotaryCommaCode(type: Int): Int {
        val base = rotaryBase(type)
        if (base == 1) return 142
        if (base == 2) return 14
        return when (rotaryClock(type)) {
            12 -> 142
            1, 2 -> 131
            3 -> 133
            4, 5 -> 134
            6 -> 138
            7, 8 -> 136
            9 -> 139
            else -> 140
        }
    }

    /** 로터리 방향 글자. 예: "회전교차로 오른쪽 3시 방향". 로터리가 아니면 빈 문자열. */
    fun rotaryText(type: Int): String {
        if (!isRotary(type)) return ""
        val name = if (type in 21..34) "로터리" else "회전교차로"
        val base = rotaryBase(type)
        val dir = when {
            base == 1 -> "직진 방향"
            base == 2 -> "유턴"
            else -> when (val h = rotaryClock(type)) {
                12, 6 -> "${h}시 방향"
                in 1..5 -> "오른쪽 ${h}시 방향"
                else -> "왼쪽 ${h}시 방향"
            }
        }
        return "$name $dir"
    }

    /**
     * 네이버 안내 문구에서 "방면" 앞의 이름을 뽑는다.
     * 예: "머내공원사거리에서 '서분당(고기)IC' 방면으로 우회전" → "서분당(고기)IC". 방면이 없으면 빈 문자열.
     */
    fun bangmyeonName(instructions: String): String {
        if (!instructions.contains("방면")) return ""
        val quoted = Regex("['‘’\"“”]([^'‘’\"“”]+)['‘’\"“”]\\s*방면").find(instructions)
        if (quoted != null) return quoted.groupValues[1].trim()
        return Regex("(\\S+)\\s*방면").find(instructions)?.groupValues?.get(1)?.trim().orEmpty()
    }

    /** 콤마 카드의 방면·방향 줄. 로터리는 시각 방향, 그 밖에는 "OO 방면 우회전"처럼. 보여줄 게 없으면 빈 문자열. */
    fun directionLine(type: Int, instructions: String): String {
        if (isRotary(type)) return rotaryText(type)
        val name = bangmyeonName(instructions)
        if (name.isBlank()) return ""
        val turn = label(type)
        return if (turn.isBlank()) "$name 방면" else "$name 방면 $turn"
    }

    /** 화면·음성에 쓰는 짧은 한국어 이름. */
    fun label(type: Int): String = when (type) {
        1 -> "직진"
        2 -> "좌회전"
        3 -> "우회전"
        4 -> "좌측 방향"
        5 -> "우측 방향"
        6 -> "유턴"
        8 -> "비보호 좌회전"
        11, 12, 13 -> "좌회전"
        14, 15, 16 -> "우회전"
        in 21..34, in 91..104 -> rotaryText(type)
        41 -> "왼쪽 도로 진입"
        42 -> "오른쪽 도로 진입"
        47 -> "휴게소 진입"
        50, 57, 66 -> "고속도로 진입"
        51, 58, 67 -> "고속도로 진출"
        52, 59, 68 -> "도시고속도로 진입"
        53, 60, 69 -> "도시고속도로 진출"
        54 -> "분기로 진입"
        55, 62, 71 -> "고가도로 진입"
        56, 64, 73 -> "지하차도 진입"
        75, 76, 77 -> "자동차전용도로 진입"
        78, 79, 80 -> "자동차전용도로 진출"
        81 -> "왼쪽 본선 합류"
        82 -> "오른쪽 본선 합류"
        87 -> "경유지"
        88 -> "목적지"
        121, 122, 123 -> "톨게이트"
        else -> ""
    }

    /** 카카오 오버레이/HUD가 아는 이름(KNRGCode_*)으로 바꾼 값. 모르는 것은 빈 문자열. */
    fun kakaoLikeName(type: Int): String = when (toOpenpilotCode(type)) {
        11 -> "KNRGCode_Straight"
        12 -> "KNRGCode_LeftTurn"
        13 -> "KNRGCode_RightTurn"
        14 -> "KNRGCode_UTurn"
        201 -> "KNRGCode_Goal"
        else -> ""
    }

    /**
     * 차량 클러스터/HUD(nMirror·나브디)로 보내는 이름. [kakaoLikeName]은 5종만 알아서 방향 안내·진입/진출이
     * 전부 빈 값 → 클러스터에서 직진 화살표로 떨어졌다. 여기선 KakaoToTmapTurn이 아는 이름으로 넓게 매핑한다.
     */
    fun clusterName(type: Int): String = when (type) {
        1 -> "KNRGCode_Straight"
        2, 8, 11, 12, 13 -> "KNRGCode_LeftTurn"
        3, 14, 15, 16 -> "KNRGCode_RightTurn"
        6 -> "KNRGCode_UTurn"
        4, 41, 81 -> "KNRGCode_LeftDirection"
        5, 42, 82 -> "KNRGCode_RightDirection"
        50 -> "KNRGCode_InHighway"
        51 -> "KNRGCode_OutHighway"
        57, 76 -> "KNRGCode_LeftInHighway"
        66, 77 -> "KNRGCode_RightInHighway"
        58, 79 -> "KNRGCode_LeftOutHighway"
        67, 80 -> "KNRGCode_RightOutHighway"
        52 -> "KNRGCode_InCityway"
        53 -> "KNRGCode_OutCityway"
        59 -> "KNRGCode_LeftInCityway"
        68 -> "KNRGCode_RightInCityway"
        60 -> "KNRGCode_LeftOutCityway"
        69 -> "KNRGCode_RightOutCityway"
        55 -> "KNRGCode_OverPath"
        62 -> "KNRGCode_LeftOverPath"
        71 -> "KNRGCode_RightOverPath"
        56 -> "KNRGCode_UnderPath"
        64 -> "KNRGCode_LeftUnderPath"
        73 -> "KNRGCode_RightUnderPath"
        in 21..34, in 91..104 -> "KNRGCode_RoundaboutDirection_1"
        121, 122, 123 -> "KNRGCode_Tollgate"
        88 -> "KNRGCode_Goal"
        else -> ""
    }

    /** 이 종류는 "차선·진행 방향 변경" 성격이라 시내 갈림길 자동 차선변경 후보가 된다. */
    fun isFork(type: Int): Boolean = toOpenpilotCode(type) in intArrayOf(6, 7, 101, 102)

    fun isHighwayEntryOrExit(type: Int): Boolean = type in 50..53 || type in 57..60 || type in 66..69
}
