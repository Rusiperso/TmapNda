package com.tmap.nda

import java.util.concurrent.CopyOnWriteArraySet

data class KakaoRouteSnapshot(
    val isActive: Boolean,
    val lastUpdateTime: Long,
    val tbtDist: Int,
    val tbtTurnType: Int,
    val tbtMainText: String,
    val remainDist: Int,
    val remainTime: Int,
    val roadName: String,
    val rgCodeName: String,
    val directionAngle: Int,
    val destinationName: String
)

/**
 * KakaoGuidanceDelegate가 실제 카카오 안내 데이터(방향/거리/ETA/안전정보)를 채워두면,
 * UdpSenderService가 화면(Tmap/Kakao)과 무관하게 이 값을 읽어 road_limit UDP 스키마로
 * openpilot에 보낼 수 있게 하는 다리 역할. 특정 fork(당근파일럿 등)에 종속되지 않고,
 * 어떤 openpilot fork든 UDP를 받기만 하면 값 자체는 항상 나가도록 하는 게 목적 -
 * 실제로 화면에 표시되는지는 그 fork의 UI 코드에 달려있음. #문제시 원복
 *
 * v2.0: Android Auto HUD(TmapNdaCarAppService)도 같은 데이터를 구독할 수 있도록
 * addListener/removeListener 리스너 패턴 추가. 기존 필드는 그대로 유지해서
 * UdpSenderService의 직접 필드 접근과 호환됨. #문제시 원복
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

    // Android Auto Maneuver 변환에 필요한 Kakao 원본값 (v2.0, 아직 채워주는 쪽 없음 - #TODO)
    @Volatile var rgCodeName: String = ""
    @Volatile var directionAngle: Int = 0
    @Volatile var destinationName: String = "목적지"

    // 안전정보(스쿨존/구간단속 등) - v4.21: KNSafetyCode를 Tmap/openpilot nSdiType 스킴으로
    // 정확히 번역해서 채움(KakaoGuidanceDelegate.mapKakaoSafetyCodeToSdiType 참고).
    // -1 = 유효한 이벤트 없음/매핑 안 됨 (0은 그 스킴에서 "신호과속"이라는 실제 의미가
    // 있는 값이라 기본값으로 못 씀). #문제시 원복
    @Volatile var safetyType: Int = -1
    @Volatile var safetySpeedLimit: Int = 0
    @Volatile var safetyDist: Int = 0
    // v: 사용자 제보(지피티 분석) - getRemainDist()가 없는 카메라형 안전정보도, 경로
    // 시작점부터 이벤트까지의 누적거리(getDistFromS)에서 "경로 시작점부터 현재 차량
    // 위치까지의 누적거리"를 빼면 실제 "남은 거리"를 정확히 구할 수 있음(직선거리보다
    // 정확 - 곡선 도로 경로를 그대로 따라감). guidanceDidUpdateLocation()에서 매번
    // 갱신됨. #문제시 원복
    @Volatile var currentDistFromS: Int = -1
    // v5.1: getRemainDist()(진짜 남은거리, 검증됨)로 구했는지 DistFromS()(미검증 폴백)로
    // 구했는지 표시. UdpSenderService가 "믿을 수 있는 카카오 값만 Tmap 폴백으로 쓴다"를
    // 판단하는 데 씀. #문제시 원복
    @Volatile var safetyDistTrusted: Boolean = false
    // v10.2: 실주행 로그로 확인된 버그 - safetyDist는 guidanceDidUpdateSafetyGuide()가
    // 새 안전정보 이벤트를 받을 때만 계산되는데(뜸하게 호출됨), UdpSenderService는 훨씬
    // 자주(초당 2회) 그 캐시값을 그대로 반복 전송해서 접근 중에도 거리가 하나도 안
    // 줄어드는 문제가 있었음(카메라 앞 18초 동안 dist=1053 고정 -> openpilot 감속 시작
    // 안 함). routeBasedDist로 구한 경우에 한해 이벤트의 "절대" DistFromS를 따로 저장해두고,
    // guidanceDidUpdateLocation()이 매번 currentDistFromS를 갱신할 때마다 safetyDist =
    // safetyEventDistFromS - currentDistFromS로 실시간 재계산함. #문제시 원복
    @Volatile var safetyEventDistFromS: Int = -1

    // v: 신규기능(콤마 화면에 카카오 경로선 표시용) - guidanceDidUpdateRoutes에서 채워짐.
    // (경도,위도) 순서 목록. 콤마 쪽 7713번 문(carrot_http_post -> _dispatch_obj의 "vrtx" 키)이
    // 이 형식을 그대로 받아서 화면에 선을 그려줌. KNRoute 안에서 정확히 어떤 게터가 이 좌표
    // 목록을 주는지 공식 문서에 명시가 안 돼있어서, 후보 이름들을 순서대로 리플렉션으로
    // 찔러보고 첫 성공을 채택 - 실주행 로그(matchedRouteLineGetter)로 검증 필요. #문제시 원복
    @Volatile var routeCoordinates: List<Pair<Double, Double>> = emptyList()
    @Volatile var routeCoordinatesUpdatedAt: Long = 0L

    private val listeners = CopyOnWriteArraySet<(KakaoRouteSnapshot) -> Unit>()

    fun reset() {
        isActive = false
        lastUpdateTime = 0
        tbtDist = 0
        tbtTurnType = 0
        tbtMainText = ""
        remainDist = 0
        remainTime = 0
        roadName = ""
        rgCodeName = ""
        directionAngle = 0
        destinationName = "목적지"
        safetyType = -1
        safetySpeedLimit = 0
        safetyDist = 0
        safetyDistTrusted = false
        safetyEventDistFromS = -1
        notifyListeners(snapshot())
    }

    /** 마지막 갱신이 너무 오래됐으면(연결 끊김/화면 전환 중) 신뢰 안 함 */
    fun isFresh(maxAgeMs: Long = 5000L): Boolean {
        return isActive && (System.currentTimeMillis() - lastUpdateTime) < maxAgeMs
    }

    fun snapshot(): KakaoRouteSnapshot = KakaoRouteSnapshot(
        isActive = isActive,
        lastUpdateTime = lastUpdateTime,
        tbtDist = tbtDist,
        tbtTurnType = tbtTurnType,
        tbtMainText = tbtMainText,
        remainDist = remainDist,
        remainTime = remainTime,
        roadName = roadName,
        rgCodeName = rgCodeName,
        directionAngle = directionAngle,
        destinationName = destinationName
    )

    /** v2.2: KakaoHudBridge(공식 KNSDK API 기반)가 값을 한 번에 반영하고 구독자에게 알림.
     *  UdpSenderService가 읽는 개별 필드도 같이 갱신되므로 콤마 UDP 쪽도 자동으로 정확해짐. */
    fun publishGuidance(
        tbtDist: Int,
        tbtMainText: String,
        remainDist: Int,
        remainTime: Int,
        roadName: String,
        rgCodeName: String,
        directionAngle: Int,
        destinationName: String
    ) {
        isActive = true
        lastUpdateTime = System.currentTimeMillis()

        this.tbtDist = tbtDist.coerceAtLeast(0)
        this.tbtMainText = tbtMainText
        this.remainDist = remainDist.coerceAtLeast(0)
        this.remainTime = remainTime.coerceAtLeast(0)
        this.roadName = roadName
        this.rgCodeName = rgCodeName
        this.directionAngle = directionAngle
        this.destinationName = destinationName.ifBlank { "목적지" }

        notifyListeners(snapshot())
    }

    /** v2.0: KakaoGuidanceDelegate가 필드를 갱신한 뒤 이 함수를 호출해주면 HUD 등 구독자에게 알림 */
    fun publishUpdated() {
        notifyListeners(snapshot())
    }

    fun addListener(listener: (KakaoRouteSnapshot) -> Unit) {
        listeners.add(listener)
        runCatching { listener(snapshot()) }
    }

    fun removeListener(listener: (KakaoRouteSnapshot) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners(value: KakaoRouteSnapshot) {
        listeners.forEach { listener ->
            runCatching { listener(value) }
        }
    }
}
