package com.tmap.nda

import android.content.Context
import com.kakaomobility.knsdk.common.objects.KNError
import com.kakaomobility.knsdk.guidance.knguidance.*
import com.kakaomobility.knsdk.guidance.knguidance.citsguide.KNGuide_Cits
import com.kakaomobility.knsdk.guidance.knguidance.common.KNLocation
import com.kakaomobility.knsdk.guidance.knguidance.locationguide.KNGuide_Location
import com.kakaomobility.knsdk.guidance.knguidance.routeguide.KNGuide_Route
import com.kakaomobility.knsdk.guidance.knguidance.routeguide.objects.KNMultiRouteInfo
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.KNGuide_Safety
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.objects.KNSafety
import com.kakaomobility.knsdk.guidance.knguidance.voiceguide.KNGuide_Voice
import com.kakaomobility.knsdk.trip.kntrip.knroute.KNRoute

/**
 * 카카오내비 SDK(KNSDK) 실시간 안내 콜백 모음.
 *
 * 카카오 공식 iOS 가이드("주행 설정하기")에 명시된 필수 패턴: guidance의 각 델리게이트
 * 콜백은 앱이 받은 뒤 반드시 naviView에도 그대로 릴레이해줘야 함
 * (예: `[naviView guidance:aGuidance didUpdateLocation:aLocationGuide]`).
 * guidance는 델리게이트를 프로토콜당 1개만 가질 수 있어서, 우리 앱 델리게이트가 그
 * 자리를 차지하면 naviView 자체의 내부 렌더링(현재위치 마커 이동, 경로 갱신 등)은
 * 이 콜백을 전혀 못 받게 됨. 지금까지 naviViewGuideState(OnRouteGuide) 등 로그는
 * 정상으로 찍히는데 실제 화면(위치/경로)은 안내 시작 지점에서 고정돼 있던 증상이
 * 정확히 이 릴레이 누락과 일치함. KNNaviView가 이 인터페이스들을 직접 구현하고
 * 있다는 전제로 safe-cast 후 동일 메서드를 그대로 호출해서 릴레이함.
 * naviView는 검색/재검색 때마다 재사용될 수 있어 var로 나중에 갱신 가능하게 함. #문제시 원복
 *
 * 지금은 NavLogger로만 남김 - 다음 단계에서 UdpSenderService의 road_limit 스키마에
 * 맞춰 rgData 필드(제한속도/카메라/차선 등)로 변환해서 UDP로 내보내는 작업이 남아있음. #TODO
 *
 * onGuideEnded: 경로안내가 자연 종료(도착)됐을 때 오버레이를 걷어내기 위한 콜백.
 */
class KakaoGuidanceDelegate(
    private val context: Context,
    private val onGuideEnded: () -> Unit,
    private val onGuideStarted: () -> Unit = {},
    // 세이프티(경로없음) 모드에선 false를 리턴해서 카카오 음성을 무음 처리하고,
    // 티맵 자체 카메라/방지턱/구간단속 안내음이 대신 나오게 하는 하이브리드 스위치. #문제시 원복
    private val isRouteGuideActive: () -> Boolean = { true }
) : KNGuidance_GuideStateDelegate,
    KNGuidance_LocationGuideDelegate,
    KNGuidance_RouteGuideDelegate,
    KNGuidance_SafetyGuideDelegate,
    KNGuidance_VoiceGuideDelegate,
    KNGuidance_CitsGuideDelegate {

    // startKakaoOverlayGuidance()/showKakaoIdleMap()에서 naviView가 만들어지거나
    // 재사용될 때마다 갱신해줌. #문제시 원복
    var naviView: com.kakaomobility.knsdk.ui.view.KNNaviView? = null
    private var lastLocationLogAt = 0L

    // ===== GuideStateDelegate =====
    override fun guidanceGuideStarted(guidance: KNGuidance) {
        NavLogger.d(context, "[카카오안내] 시작됨")
        naviView?.guidanceGuideStarted(guidance)
        onGuideStarted()
    }

    override fun guidanceGuideEnded(guidance: KNGuidance) {
        NavLogger.d(context, "[카카오안내] 종료됨(도착) - Tmap으로 복귀")
        KakaoRouteDataRepository.reset()
        naviView?.guidanceGuideEnded(guidance)
        onGuideEnded()
    }

    override fun guidanceOutOfRoute(guidance: KNGuidance) {
        NavLogger.d(context, "[카카오안내] 경로이탈 감지, 재탐색 위임")
        naviView?.guidanceOutOfRoute(guidance)
    }

    override fun guidanceCheckingRouteChange(guidance: KNGuidance) {
        NavLogger.d(context, "[카카오안내] 경로변경 확인 중")
        naviView?.guidanceCheckingRouteChange(guidance)
    }

    override fun guidanceRouteUnchanged(guidance: KNGuidance) {
        NavLogger.d(context, "[카카오안내] 경로 변경 없음")
        naviView?.guidanceRouteUnchanged(guidance)
    }

    override fun guidanceRouteUnchangedWithError(guidance: KNGuidance, error: KNError) {
        NavLogger.e(context, "[카카오안내] 경로 재탐색 실패: ${error.msg}")
        naviView?.guidanceRouteUnchangedWithError(guidance, error)
    }

    override fun guidanceRouteChanged(
        guidance: KNGuidance,
        fromRoute: KNRoute,
        fromLocation: KNLocation,
        toRoute: KNRoute,
        toLocation: KNLocation,
        changeReason: KNGuideRouteChangeReason
    ) {
        NavLogger.d(context, "[카카오안내] 경로 변경됨: 사유=$changeReason")
        naviView?.guidanceRouteChanged(guidance)
    }

    override fun guidanceDidUpdateRoutes(
        guidance: KNGuidance,
        routes: List<KNRoute>,
        multiRouteInfo: KNMultiRouteInfo?
    ) {
        naviView?.guidanceDidUpdateRoutes(guidance, routes, multiRouteInfo)
    }

    override fun guidanceDidUpdateIndoorRoute(guidance: KNGuidance, route: KNRoute?) {
        // CarrotNavi 실제 코드에서 naviView.guidanceDidUpdateIndoorRoute() 직접호출이
        // 확인 안 돼서(존재 여부 불확실), 컴파일 안전하게 리플렉션으로만 시도. #문제시 원복
        try {
            naviView?.let { nv ->
                val m = nv.javaClass.methods.firstOrNull {
                    it.name == "guidanceDidUpdateIndoorRoute" && it.parameterTypes.size == 2
                }
                m?.invoke(nv, guidance, route)
            }
        } catch (e: Exception) { /* 메서드 없으면 무시 */ }
    }

    // ===== LocationGuideDelegate =====
    // 화면이 안내 시작 지점에서 안 움직이던 핵심 원인으로 의심되는 지점 - naviView가 실제
    // 위치 갱신을 받아 현재위치 마커/카메라를 옮기려면 이 콜백을 받아야 함. #문제시 원복
    override fun guidanceDidUpdateLocation(guidance: KNGuidance, locationGuide: KNGuide_Location) {
        // 이 콜백이 실제로 호출되는지, 좌표가 시간에 따라 바뀌는지 직접 확인하기 위한 로그.
        // KNGuide_Location의 정확한 프로퍼티명이 확실치 않아 컴파일 안전하게 toString()과
        // 리플렉션 getter 덤프로 남김. 매번 찍으면 시끄러워서 2초 스로틀. #문제시 원복
        val now = System.currentTimeMillis()
        if (now - lastLocationLogAt >= 2000) {
            lastLocationLogAt = now
            val dump = try {
                locationGuide.javaClass.methods
                    .filter { it.parameterTypes.isEmpty() && (it.name.startsWith("get") || it.name.startsWith("is")) }
                    .joinToString(", ") { m ->
                        try {
                            "${m.name}=${m.invoke(locationGuide)}"
                        } catch (e: Exception) {
                            "${m.name}=<err>"
                        }
                    }
            } catch (e: Exception) {
                "덤프 실패: ${e.message}"
            }
            NavLogger.d(context, "guidanceDidUpdateLocation 호출됨: $locationGuide | $dump")
            val summary = try {
                val roadName = locationGuide.javaClass.methods.firstOrNull { it.name == "getRoadName" }?.invoke(locationGuide)
                val distToDest = locationGuide.javaClass.methods.firstOrNull { it.name == "getDist" }?.invoke(locationGuide)
                "[경로추적] 도로=$roadName 남은거리=$distToDest (경로선 위에서 매칭된 위치를 계속 받고 있으면 정상 추종 중)"
            } catch (e: Exception) { "[경로추적] 요약 실패: ${e.message}" }
            NavLogger.d(context, summary)
        }
        naviView?.guidanceDidUpdateLocation(guidance, locationGuide)
    }

    // ===== RouteGuideDelegate =====
    override fun guidanceDidUpdateRouteGuide(guidance: KNGuidance, routeGuide: KNGuide_Route) {
        val fieldDump = try {
            routeGuide.javaClass.methods
                .filter { it.parameterTypes.isEmpty() && it.name.startsWith("get") }
                .joinToString(", ") { m ->
                    try { "${m.name}=${m.invoke(routeGuide)}" } catch (e: Exception) { "${m.name}=<실패>" }
                }
        } catch (e: Exception) { "덤프 실패: ${e.message}" }
        NavLogger.d(context, "guidanceDidUpdateRouteGuide 호출됨: $routeGuide | $fieldDump")

        // v1.3: 차선 정보(직진/좌회전 등 몇 차선인지) 관련 getter가 있는지 별도로 찾아서
        // 눈에 띄게 로그로 남김 - curDirection/nextDirection 안에 중첩돼 있을 수도 있어서
        // 그쪽도 같이 훑어봄. #문제시 원복
        try {
            val laneGetters = routeGuide.javaClass.methods.filter {
                it.parameterTypes.isEmpty() && (it.name.contains("Lane", true))
            }
            val curDirectionForLane = findGetter(routeGuide, "getCurDirection")
            val laneGettersInDirection = curDirectionForLane?.javaClass?.methods?.filter {
                it.parameterTypes.isEmpty() && it.name.contains("Lane", true)
            } ?: emptyList()
            if (laneGetters.isNotEmpty() || laneGettersInDirection.isNotEmpty()) {
                val laneDump = (laneGetters.map { "routeGuide.${it.name}=${try { it.invoke(routeGuide) } catch (e: Exception) { "<실패>" }}" } +
                    laneGettersInDirection.map { "curDirection.${it.name}=${try { it.invoke(curDirectionForLane) } catch (e: Exception) { "<실패>" }}" })
                    .joinToString(", ")
                NavLogger.d(context, "[차선정보?] Lane 관련 getter 발견: $laneDump")
            } else {
                NavLogger.d(context, "[차선정보?] Lane 관련 getter 없음(routeGuide/curDirection 기준)")
            }
        } catch (e: Exception) {
            NavLogger.e(context, "차선정보 탐색 예외: ${e.message}")
        }

        // v1.1.00: 카카오 안내 실제 데이터를 openpilot road_limit UDP로 내보내기 위해
        // KakaoRouteDataRepository에 반영. 정확한 getter 이름을 컴파일 타임에 확정할 수
        // 없어서(SDK 문서/AAR 소스가 없음) 이름 패턴 매칭 리플렉션으로 최대한 안전하게
        // 추출함 - 실제 값이 맞게 들어오는지는 로그(KakaoRouteDataRepository 갱신 로그)로
        // 확인 필요. #문제시 원복
        try {
            val curDirection = findGetter(routeGuide, "getCurDirection")
            val nextTbtDist = curDirection?.let { findGetterInt(it, "Dist") } ?: 0
            val turnTypeRaw = curDirection?.let { findGetter(it, null, "TurnType") }
            val roadNameNow = curDirection?.let { findGetterString(it, "RoadName") }
                ?: findGetterString(routeGuide, "RoadName")

            val multiRouteInfo = findGetter(routeGuide, "getMultiRouteInfo")
            val remainDistNow = multiRouteInfo?.let { findGetterInt(it, "RemainDist") }
                ?: multiRouteInfo?.let { findGetterInt(it, "Distance") } ?: 0
            val remainTimeNow = multiRouteInfo?.let { findGetterInt(it, "RemainTime") }
                ?: multiRouteInfo?.let { findGetterInt(it, "Time") } ?: 0

            KakaoRouteDataRepository.isActive = true
            KakaoRouteDataRepository.lastUpdateTime = System.currentTimeMillis()
            KakaoRouteDataRepository.tbtDist = nextTbtDist
            KakaoRouteDataRepository.tbtTurnType = mapKakaoTurnTypeToOpenpilot(turnTypeRaw)
            KakaoRouteDataRepository.tbtMainText = roadNameNow ?: ""
            KakaoRouteDataRepository.remainDist = remainDistNow
            KakaoRouteDataRepository.remainTime = remainTimeNow
            KakaoRouteDataRepository.roadName = roadNameNow ?: ""

            NavLogger.d(
                context,
                "[카카오->openpilot] tbtDist=$nextTbtDist turnTypeRaw=$turnTypeRaw(->${KakaoRouteDataRepository.tbtTurnType}) " +
                    "road=$roadNameNow remainDist=$remainDistNow remainTime=$remainTimeNow"
            )
        } catch (e: Exception) {
            NavLogger.e(context, "KakaoRouteDataRepository 갱신 예외: ${e.message}")
        }

        naviView?.guidanceDidUpdateRouteGuide(guidance, routeGuide)
    }

    // 카카오의 회전타입 코드(enum/정수, 정확한 값 체계 미확인)를 openpilot이 기대하는
    // nTBTTurnType 코드(51=직진/알림 기본값)로 매핑. 지금은 안전하게 기본값(51, 알림)만
    // 리턴 - 실제 로그로 카카오 turnType 원본값들을 수집한 뒤 표를 채워야 정확해짐. #문제시 원복
    private fun mapKakaoTurnTypeToOpenpilot(kakaoTurnType: Any?): Int {
        return 51
    }

    private fun findGetter(obj: Any, exactName: String? = null, nameContains: String? = null): Any? {
        return try {
            val m = obj.javaClass.methods.firstOrNull {
                it.parameterTypes.isEmpty() && (
                    (exactName != null && it.name == exactName) ||
                        (nameContains != null && it.name.startsWith("get") && it.name.contains(nameContains))
                    )
            }
            m?.invoke(obj)
        } catch (e: Exception) { null }
    }

    private fun findGetterInt(obj: Any, nameContains: String): Int {
        return try {
            (findGetter(obj, null, nameContains) as? Number)?.toInt() ?: 0
        } catch (e: Exception) { 0 }
    }

    private fun findGetterString(obj: Any, nameContains: String): String? {
        return try {
            findGetter(obj, null, nameContains)?.toString()
        } catch (e: Exception) { null }
    }

    // ===== SafetyGuideDelegate =====
    override fun guidanceDidUpdateSafetyGuide(guidance: KNGuidance, safetyGuide: KNGuide_Safety?) {
        try {
            if (safetyGuide != null) {
                val typeRaw = findGetterInt(safetyGuide, "Type")
                val speedLimitRaw = findGetterInt(safetyGuide, "SpeedLimit")
                val distRaw = findGetterInt(safetyGuide, "Dist")
                KakaoRouteDataRepository.safetyType = typeRaw
                KakaoRouteDataRepository.safetySpeedLimit = speedLimitRaw
                KakaoRouteDataRepository.safetyDist = distRaw
                NavLogger.d(context, "[카카오->openpilot] 안전정보: type=$typeRaw speedLimit=$speedLimitRaw dist=$distRaw")
            } else {
                KakaoRouteDataRepository.safetyType = 0
                KakaoRouteDataRepository.safetyDist = 0
            }
        } catch (e: Exception) {
            NavLogger.e(context, "안전정보 반영 예외: ${e.message}")
        }
        naviView?.guidanceDidUpdateSafetyGuide(guidance, safetyGuide)
    }

    override fun guidanceDidUpdateAroundSafeties(guidance: KNGuidance, safeties: List<KNSafety>?) {
        naviView?.guidanceDidUpdateAroundSafeties(guidance, safeties)
    }

    // ===== VoiceGuideDelegate =====
    // "카카오 음성 중 티맵 음성이 안 나온다/겹친다"를 로그로 검증하기 위해, 카카오 음성이
    // 재생되는 시점마다 티맵 쪽 음소거 상태(SharedPreferences tmap_muted)를 같이 찍음.
    // 이게 재생 시점에 tmap_muted=true가 아니면 티맵 음성이 같이 나올 수 있다는 뜻. #문제시 원복
    override fun shouldPlayVoiceGuide(
        guidance: KNGuidance,
        voiceGuide: KNGuide_Voice,
        newData: MutableList<ByteArray>
    ): Boolean {
        val allow = isRouteGuideActive()
        NavLogger.d(context, "[음성] shouldPlayVoiceGuide 호출됨 allow=$allow ${tmapMuteStateSnapshot()}")
        // v1.0.99: naviView.shouldPlayVoiceGuide()를 relay하면 naviView가 재생 여부를
        // 자체적으로 다시 판단해서(우리 kakaoMuted 값과 무관하게) 소리가 계속 나던 것으로
        // 의심됨 - CarrotNavi도 이 메서드는 naviView로 relay 안 함. 우리 델리게이트가
        // guidance에 직접 리턴하는 allow 값만으로 음소거를 제어하도록 relay 제거.
        // newData가 mutable list라, boolean 리턴만으로 재생이 안 막힐 경우를 대비해
        // 음소거 상태면 오디오 바이트 자체도 비워버림(이중 방어). #문제시 원복
        if (!allow) {
            newData.clear()
        }
        return allow
    }

    override fun willPlayVoiceGuide(guidance: KNGuidance, voiceGuide: KNGuide_Voice) {
        NavLogger.d(context, "[음성] willPlayVoiceGuide(카카오 음성 재생 시작) ${tmapMuteStateSnapshot()}")
        if (!isRouteGuideActive()) return
        naviView?.willPlayVoiceGuide(guidance, voiceGuide)
    }

    override fun didFinishPlayVoiceGuide(guidance: KNGuidance, voiceGuide: KNGuide_Voice) {
        NavLogger.d(context, "[음성] didFinishPlayVoiceGuide(카카오 음성 재생 끝) ${tmapMuteStateSnapshot()}")
        naviView?.didFinishPlayVoiceGuide(guidance, voiceGuide)
    }

    private fun tmapMuteStateSnapshot(): String {
        return "lastAppliedTmapVolume=${KakaoSdkState.lastAppliedTmapVolume}"
    }

    // ===== CitsGuideDelegate =====
    override fun didUpdateCitsGuide(guidance: KNGuidance, citsGuide: KNGuide_Cits) {
        // v1.3: Cits = C-ITS(협력 지능형 교통체계) - 한국에서 신호등 잔여시간(SPaT) 정보를
        // 이 프로토콜로 주고받는 경우가 많아서, 실제로 그런 필드가 있는지 확인하려고
        // 전체 getter를 덤프함. #문제시 원복
        val fieldDump = try {
            citsGuide.javaClass.methods
                .filter { it.parameterTypes.isEmpty() && it.name.startsWith("get") }
                .joinToString(", ") { m ->
                    try { "${m.name}=${m.invoke(citsGuide)}" } catch (e: Exception) { "${m.name}=<실패>" }
                }
        } catch (e: Exception) { "덤프 실패: ${e.message}" }
        NavLogger.d(context, "[신호등?] didUpdateCitsGuide 호출됨: $citsGuide | $fieldDump")
        naviView?.didUpdateCitsGuide(guidance, citsGuide)
    }
}
