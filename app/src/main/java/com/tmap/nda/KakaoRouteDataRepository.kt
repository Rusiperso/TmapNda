package com.tmap.nda

/**
 * KakaoGuidanceDelegate가 실제 카카오 안내 데이터(방향/거리/ETA/안전정보)를 채워두면,
 * UdpSenderService가 화면(Tmap/Kakao)과 무관하게 이 값을 읽어 road_limit UDP 스키마로
 * openpilot에 보낼 수 있게 하는 다리 역할. 특정 fork(당근파일럿 등)에 종속되지 않고,
 * 어떤 openpilot fork든 UDP를 받기만 하면 값 자체는 항상 나가도록 하는 게 목적 -
 * 실제로 화면에 표시되는지는 그 fork의 UI 코드에 달려있음. #문제시 원복
 */
object KakaoRouteDataRepository {
    @Volatile var isActive: Boolean = false
    @Volatile var lastUpdateTime: Long = 0

    // 다음 안내지점(회전 등)까지 거리(m), openpilot nTBTTurnType 코드로 변환된 회전타입
    @Volatile var tbtDist: Int = 0
    @Volatile var tbtTurnType: Int = 0
    @Volatile var tbtMainText: String = ""

    // 목적지까지 총 남은거리(m)/남은시간(초)
    @Volatile var remainDist: Int = 0
    @Volatile var remainTime: Int = 0

    @Volatile var roadName: String = ""

    // 안전정보(스쿨존/구간단속 등) - openpilot cam_type 체계와 최대한 맞춤(추정치, 검증 필요)
    @Volatile var safetyType: Int = 0
    @Volatile var safetySpeedLimit: Int = 0
    @Volatile var safetyDist: Int = 0

    fun reset() {
        isActive = false
        tbtDist = 0
        tbtTurnType = 0
        tbtMainText = ""
        remainDist = 0
        remainTime = 0
        roadName = ""
        safetyType = 0
        safetySpeedLimit = 0
        safetyDist = 0
    }

    /** 마지막 갱신이 너무 오래됐으면(연결 끊김/화면 전환 중) 신뢰 안 함 */
    fun isFresh(maxAgeMs: Long = 5000L): Boolean {
        return isActive && (System.currentTimeMillis() - lastUpdateTime) < maxAgeMs
    }
}
