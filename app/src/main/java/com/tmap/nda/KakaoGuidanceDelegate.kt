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

        // 현재 카카오 경로와 현재 위치를 기준으로 목적지까지 남은 거리/시간 계산
        try {
            val currentRoute = guidance.routesOnGuide?.firstOrNull()
            val currentLocation = locationGuide.location

            if (currentRoute != null && currentLocation != null) {
                val remainDist = currentRoute.remainDistFromLocation(currentLocation)
                val remainTime = currentRoute.remainTimeFromLocation(currentLocation)

                if (remainDist > 0 && remainTime > 0) {
                    KakaoRouteDataRepository.isActive = true
                    KakaoRouteDataRepository.lastUpdateTime = System.currentTimeMillis()
                    KakaoRouteDataRepository.remainDist = remainDist
                    KakaoRouteDataRepository.remainTime = remainTime

                    NavLogger.d(
                        context,
                        "[카카오 ETA] remainDist=${remainDist}m remainTime=${remainTime}s"
                    )
                }
            }
        } catch (e: Exception) {
            NavLogger.e(
                context,
                "[카카오 ETA] 남은 거리/시간 계산 실패: ${e.message}"
            )
        }

        // v2.2: 공식 KNSDK API 기반으로 HUD/UDP용 값을 다시 한 번 정확하게 채움.
        // 위 리플렉션 코드보다 뒤에 있어야 함 - 리플렉션이 잘못된 값을 넣었어도 여기서 교정됨.
        try {
            KakaoHudBridge.publish(guidance, locationGuide, guidance.routeGuide)
        } catch (e: Exception) {
            NavLogger.e(context, "[HUD 브릿지] guidanceDidUpdateLocation 반영 실패: ${e.message}")
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

        // v4.7: 공식 문서(developers.kakaomobility.com)에서 진짜 구조를 확인함 -
        // KNLane(linkIdx: Int, location: KNLocation, laneCode: List<Number>). 리플렉션
        // 대신 실제 타입 API로 정확하게 가져옴. laneCode 숫자 하나하나가 차선 하나의
        // 방향 코드인데, 정확한 코드→방향 매핑표(1=좌회전인지 2=직진인지 등)를 문서에서
        // 못 가져와서, 화면 표시는 아직 안 함(잘못 추측해서 틀린 화살표 보여주면 운전 중
        // 위험할 수 있음) - 우선 정확한 값을 로그로 남겨서 코드 표를 확정한 뒤 표시 로직
        // 완성 예정. 신호등 잔여시간은 KNGuide_Route 안에는 아예 없는 걸로 보임(공식
        // 생성자에 curDirection/nextDirection/imgDirection/lane/safetyZones/hipassInfo/
        // hwInfo/multiRouteInfo/roadEvents뿐) - 다른 델리게이트(예: KNGuide_Safety)
        // 쪽을 다음에 조사해야 함. #문제시 원복
        // v4.10: 컴파일 에러로 확인됨 - 실제 설치된 KNSDK 버전(1.12.8-hotfix02)에서는
        // linkIdx가 internal(모듈 밖에서 접근 불가)이고 laneCode는 아예 없는 필드였음.
        // developers.kakaomobility.com 문서가 다른(더 최신) SDK 버전 기준이었던 것으로
        // 보임. 리플렉션으로 되돌려서 이 버전에 실제로 공개된 getter 이름을 다시 확인. #문제시 원복
        try {
            val lane = routeGuide.lane
            if (lane != null) {
                val laneInfoList = lane.javaClass.methods
                    .firstOrNull { it.name == "getLaneInfos" && it.parameterTypes.isEmpty() }
                    ?.invoke(lane) as? List<*>
                if (laneInfoList != null) {
                    // v4.11: getHighlightType으로 추천 차선 여부를 실제로 판단해서 표시.
                    // internal 접근 제한 때문에 타입 API가 아니라 리플렉션으로 메서드를
                    // 직접 호출함(컴파일 에러로 linkIdx가 internal인 걸 확인했음 - 같은
                    // 클래스의 다른 getter들도 마찬가지일 가능성이 높아 안전하게 리플렉션
                    // 유지). #문제시 원복
                    val recommendedFlags = laneInfoList.mapNotNull { info ->
                        if (info == null) return@mapNotNull null
                        try {
                            val highlightType = info.javaClass.methods
                                .firstOrNull { it.name == "getHighlightType" && it.parameterTypes.isEmpty() }
                                ?.invoke(info) as? Int
                            (highlightType ?: 0) != 0
                        } catch (e: Exception) { false }
                    }
                    LaneSignalRepository.lanes = recommendedFlags
                    LaneSignalRepository.source = "kakao"
                    LaneSignalRepository.lastUpdateTime = System.currentTimeMillis()
                }

                val dump = lane.javaClass.methods
                    .filter { m -> m.parameterTypes.isEmpty() && m.name.startsWith("get") }
                    .joinToString(", ") { m -> try { "${m.name}=${m.invoke(lane)}" } catch (e: Exception) { "${m.name}=<실패>" } }
                NavLogger.d(context, "[차선정보] KNLane 전체덤프: $dump")
            } else {
                NavLogger.d(context, "[차선정보] lane=null (이 구간엔 차선 안내 데이터 없음)")
            }
        } catch (e: Exception) {
            NavLogger.e(context, "차선정보 조회 예외: ${e.message}")
        }

        // v1.1.00: 카카오 안내 실제 데이터를 openpilot road_limit UDP로 내보내기 위해
        // KakaoRouteDataRepository에 반영. 정확한 getter 이름을 컴파일 타임에 확정할 수
        // 없어서(SDK 문서/AAR 소스가 없음) 이름 패턴 매칭 리플렉션으로 최대한 안전하게
        // 추출함 - 실제 값이 맞게 들어오는지는 로그(KakaoRouteDataRepository 갱신 로그)로
        // 확인 필요. #문제시 원복
        try {
            val curDirection = findGetter(routeGuide, "getCurDirection")
            val nextTbtDist = curDirection?.let { findGetterInt(it, "Dist") } ?: 0
            // v4.12: 이름에 "Type"이 들어간 첫 getter를 찾다 보니 진짜 회전방향 필드
            // (getRgCode: KNRGCode_LeftTurn/RightTurn 등, 로그로 확인함)가 아니라 엉뚱한
            // getDirNameType(도로명이 도로명인지/장소명인지 구분하는 필드, 회전방향과 무관)을
            // 잘못 잡고 있었음(재억 지적: "좌회전 우회전 갈 때 화살표 보여주고 싶다"). 정확히
            // getRgCode를 지정해서 가져오도록 수정. #문제시 원복
            val turnTypeRaw = curDirection?.let { findGetter(it, "getRgCode") }
            val roadNameNow = curDirection?.let { findGetterString(it, "Name") }
                ?: findGetterString(routeGuide, "Name")

            val multiRouteInfo = findGetter(routeGuide, "getMultiRouteInfo")
            var remainDistNow = multiRouteInfo?.let { findGetterInt(it, "Dist") } ?: 0
            var remainTimeNow = multiRouteInfo?.let { findGetterInt(it, "Time") } ?: 0

            // multiRouteInfo가 null인 경우가 많아서(로그로 확인됨), guidance의 curTrip에서
            // 남은거리/시간을 폴백으로 시도. #문제시 원복
            if (remainDistNow == 0) {
                try {
                    val curTrip = guidance.javaClass.methods.firstOrNull {
                        it.name.equals("getCurTrip", true) || it.name.equals("getTrip", true)
                    }?.invoke(guidance)
                    if (curTrip != null) {
                        remainDistNow = findGetterInt(curTrip, "Dist")
                        remainTimeNow = findGetterInt(curTrip, "Time")
                        NavLogger.d(context, "[카카오->openpilot] curTrip 폴백 시도: remainDist=$remainDistNow remainTime=$remainTimeNow")
                    }
                } catch (e: Exception) { /* 무시 */ }
            }

            KakaoRouteDataRepository.isActive = true
            KakaoRouteDataRepository.lastUpdateTime = System.currentTimeMillis()
            KakaoRouteDataRepository.tbtDist = nextTbtDist
            KakaoRouteDataRepository.tbtTurnType = mapKakaoTurnTypeToOpenpilot(turnTypeRaw)
            KakaoRouteDataRepository.tbtMainText = roadNameNow ?: ""
            // 리플렉션으로 값을 찾지 못해 0이 나온 경우,
            // guidanceDidUpdateLocation()에서 계산한 정상값을 덮어쓰지 않음
            if (remainDistNow > 0 && remainTimeNow > 0) {
                KakaoRouteDataRepository.remainDist = remainDistNow
                KakaoRouteDataRepository.remainTime = remainTimeNow
            }
            KakaoRouteDataRepository.roadName = roadNameNow ?: ""

            NavLogger.d(
                context,
                "[카카오->openpilot] tbtDist=$nextTbtDist turnTypeRaw=$turnTypeRaw(->${KakaoRouteDataRepository.tbtTurnType}) " +
                    "road=$roadNameNow remainDist=$remainDistNow remainTime=$remainTimeNow"
            )
        } catch (e: Exception) {
            NavLogger.e(context, "KakaoRouteDataRepository 갱신 예외: ${e.message}")
        }

        // v2.2: 공식 KNSDK API 기반으로 다시 한 번 정확하게 교정 (리플렉션 저장 코드보다 뒤).
        try {
            KakaoHudBridge.publish(guidance, guidance.locationGuide, routeGuide)
        } catch (e: Exception) {
            NavLogger.e(context, "[HUD 브릿지] guidanceDidUpdateRouteGuide 반영 실패: ${e.message}")
        }

        naviView?.guidanceDidUpdateRouteGuide(guidance, routeGuide)
    }

    // v4.12: openpilot(carrot 계열 fork)의 실제 소스(selfdrive/road_speed_limiter.py)에서
    // nTBTTurnType 코드표를 확인함 - 12/16=좌회전, 13/19=우회전, 7계열=좌측차선변경,
    // 6계열=우측차선변경, 14/131~142=감속, 그 외=51(일반 알림). 카카오의 rgCode는 문자열
    // enum(KNRGCode_LeftTurn/KNRGCode_RightTurn 등, 로그로 확인)이라 이름으로 매칭.
    // 확실한 좌/우회전만 매핑하고, 나머지(유턴 등 확실치 않은 코드)는 안전하게 기본값
    // 유지 (재억 요청: "좌회전 우회전 갈 때 화살표 표시"). #문제시 원복
    private fun mapKakaoTurnTypeToOpenpilot(kakaoTurnType: Any?): Int {
        val name = kakaoTurnType?.toString() ?: return 51
        return when {
            name.contains("LeftTurn", ignoreCase = true) -> 12
            name.contains("RightTurn", ignoreCase = true) -> 13
            else -> 51
        }
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
            // "get$nameContains" 정확히 일치하는 getter 우선 시도(예: getName()).
            // 정확 일치가 없거나 String이 아니면(enum 등) 이름에 nameContains가 포함된
            // getter들을 전부 순회해서 그중 실제 String을 반환하는 것만 채택 - 예전엔
            // 이름에 nameContains만 포함되면 무조건 첫 번째 getter를 잡아서 .toString()을
            // 불러버려서, getDirNameType()(enum) 같은 엉뚱한 getter가 먼저 걸리면
            // "KNDirNameType_DirName"처럼 enum을 통째로 문자열화한 쓰레기 값이
            // 도로명/방향명 자리에 들어가는 버그가 있었음. #문제시 원복
            val exact = findGetter(obj, "get$nameContains", null)
            if (exact is String) return exact
            obj.javaClass.methods.filter {
                it.parameterTypes.isEmpty() && it.name.startsWith("get") && it.name.contains(nameContains)
            }.firstNotNullOfOrNull { m ->
                (runCatching { m.invoke(obj) }.getOrNull()) as? String
            }
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
