package com.tmap.nda

import android.content.Context
import com.kakaomobility.knsdk.KNSDK
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
    private val isRouteGuideActive: () -> Boolean = { true },
    // 재억 요청(2026-08-20) - 재안내(경로 변경) 시 화면에 짧은 문구를 띄우기 위한 콜백. #문제시 원복
    private val onRouteChanged: () -> Unit = {}
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
        // v: 재억 요청(2026-08-28) - "임의로 몇 초 늦추지 말고, 카카오 길안내가 실제로
        // 시작될 때 나브디 연결을 시도해야 하지 않냐"는 지적. 실제로 안내 데이터를 보낼
        // 시점이 바로 이때이므로, 백그라운드 15초 루프를 기다리지 않고 이 순간 바로
        // 1번 더 시도(이미 연결돼있으면 조용히 무시됨, 연결 안 돼있으면 여기서 바로 시도). #문제시 원복
        com.tmap.nda.navdy.NavdyAutoConnect.tryConnect(context)
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
        onRouteChanged()
    }

    override fun guidanceDidUpdateRoutes(
        guidance: KNGuidance,
        routes: List<KNRoute>,
        multiRouteInfo: KNMultiRouteInfo?
    ) {
        naviView?.guidanceDidUpdateRoutes(guidance, routes, multiRouteInfo)

        // v: 신규기능(콤마 화면에 카카오 경로선 표시) - 새 경로가 확정될 때마다 좌표 목록을
        // 뽑아서 저장해둠. UdpSenderService가 이 값을 주기적으로 콤마 7713번 문으로 같이
        // 실어보냄(기존 rgdata HTTP 전송에 vrtx 키만 추가하는 방식이라 새 연결/새 포트를
        // 안 열어도 됨). #문제시 원복
        try {
            val firstRoute = routes.firstOrNull()
            if (firstRoute != null) {
                val coords = extractRouteLineCoordinates(firstRoute)
                if (coords.isNotEmpty()) {
                    KakaoRouteDataRepository.routeCoordinates = coords
                    KakaoRouteDataRepository.routeCoordinatesUpdatedAt = System.currentTimeMillis()
                    NavLogger.d(context, "[경로선 조사] 좌표 ${coords.size}개 추출 성공")
                } else {
                    NavLogger.d(context, "[경로선 조사] 후보 게터 전부 실패 - KNRoute 구조 재조사 필요")
                    dumpRouteStructureOnce(firstRoute)
                }
            }
        } catch (e: Exception) {
            NavLogger.e(context, "[경로선 조사] 추출 실패: ${e.message}")
        }
    }

    // v: 재억 요청(2026-08-22) - 지금까지 시도한 후보 이름(RoutePoints/RouteLine/Points/
    // Line/Coords/Coordinates)이 전부 틀려서 경로선이 한 번도 추출된 적이 없었음. 추측을
    // 더 늘리는 대신, KNRoute 객체가 실제로 갖고 있는 메서드 전부를 딱 한 번 로그로
    // 통째로 남겨서, 다음 로그에서 진짜 이름을 확정할 수 있게 함(카메라알림음조사/
    // 교통정보조사와 동일한 패턴). #문제시 원복
    private var routeStructureDumped = false
    private fun dumpRouteStructureOnce(route: KNRoute) {
        if (routeStructureDumped) return
        routeStructureDumped = true
        try {
            val methodNames = route.javaClass.methods
                .filter { it.parameterTypes.isEmpty() && (it.name.startsWith("get") || it.name.startsWith("is")) }
                .map { it.name }
                .sorted()
            NavLogger.e(context, "[경로선 조사][전체구조] KNRoute 메서드 목록: ${methodNames.joinToString(", ")}")
            // 이름에 point/coord/line/path/geometry/shape가 들어간 후보는 실제 반환값까지 같이 찍음
            val keywords = listOf("point", "coord", "line", "path", "geometry", "shape", "vertex", "poly")
            for (m in route.javaClass.methods) {
                if (m.parameterTypes.isNotEmpty()) continue
                val lower = m.name.lowercase()
                if (keywords.none { lower.contains(it) }) continue
                try {
                    val result = m.invoke(route)
                    val preview = when (result) {
                        is List<*> -> "List(size=${result.size}) 첫항목=${result.firstOrNull()}"
                        else -> result?.toString()?.take(200)
                    }
                    NavLogger.e(context, "[경로선 조사][후보값] ${m.name}() = $preview")
                } catch (e: Exception) {
                    NavLogger.e(context, "[경로선 조사][후보값] ${m.name}() 호출 실패: ${e.message}")
                }
            }
            // v: 재억 요청(2026-08-28) - "여러 교차로를 거쳐가는 회전(예: 5km 앞 우회전까지
            // 직진-직진-우회전)일 때, 지금 당장 눈앞 교차로 말고 그 다음다음 교차로의
            // 차선 정보까지 미리 볼 수 있냐"는 질문 - KNRoute가 "안내지점 전체 목록"을
            // 통째로 주는 게터가 있다면, 그 목록의 각 항목마다 차선 정보가 붙어있는지까지
            // 확인 가능. guide/turn/section/lane/tbt 키워드가 들어간 후보를 전부 찾아서
            // 반환값까지 같이 찍음. #문제시 원복
            val guideKeywords = listOf("guide", "turn", "section", "lane", "tbt", "direction")
            for (m in route.javaClass.methods) {
                if (m.parameterTypes.isNotEmpty()) continue
                val lower = m.name.lowercase()
                if (guideKeywords.none { lower.contains(it) }) continue
                try {
                    val result = m.invoke(route)
                    val preview = when (result) {
                        is List<*> -> "List(size=${result.size}) 첫항목=${result.firstOrNull()}"
                        else -> result?.toString()?.take(300)
                    }
                    NavLogger.e(context, "[경로선 조사][안내지점후보값] ${m.name}() = $preview")
                } catch (e: Exception) {
                    NavLogger.e(context, "[경로선 조사][안내지점후보값] ${m.name}() 호출 실패: ${e.message}")
                }
            }
        } catch (e: Exception) {
            NavLogger.e(context, "[경로선 조사][전체구조] 덤프 실패: ${e.message}")
        }
    }

    // v: 신규기능(경로선) - KNRoute 안에서 "경로를 이루는 좌표 목록"을 주는 게터 이름이
    // 공식 문서에 명시 안 돼있어서, 자주 쓰이는 이름 후보들을 순서대로 시도. 후보 하나가
    // 성공하면 그 결과(List<KNPoint 유사 객체>)에서 다시 위도/경도 게터를 찾아 (경도,위도)
    // 쌍으로 변환. 전부 실패하면 빈 리스트 반환(호출부에서 안전하게 무시됨). 실주행 로그로
    // 어떤 후보가 맞았는지 확인 후 나머지 후보는 정리할 예정. #문제시 원복
    private fun extractRouteLineCoordinates(route: KNRoute): List<Pair<Double, Double>> {
        // v: 재억 제보(2026-08-22) - 로그 덤프로 실제 이름 확정됨: routePolylineWGS84().
        // "get"+후보이름 패턴이 아니라 진짜 이름이 그대로 이거였음. 결과 각 항목은
        // x(경도)/y(위도)/trfSt(교통상황) 필드를 가진 좌표 객체. #문제시 원복
        try {
            val exactGetter = route.javaClass.methods.firstOrNull {
                it.parameterTypes.isEmpty() && it.name == "routePolylineWGS84"
            }
            if (exactGetter == null) {
                NavLogger.e(context, "[경로선 조사] routePolylineWGS84 게터를 못 찾음(이름이 또 바뀐 듯)")
            } else {
                val result = exactGetter.invoke(route) as? List<*>
                if (result == null) {
                    NavLogger.e(context, "[경로선 조사] routePolylineWGS84() 반환값이 List가 아님")
                } else if (result.isEmpty()) {
                    NavLogger.e(context, "[경로선 조사] routePolylineWGS84() 반환 리스트가 비어있음")
                } else {
                    val out = ArrayList<Pair<Double, Double>>(result.size)
                    var xFailCount = 0
                    var yFailCount = 0
                    for (point in result) {
                        if (point == null) continue
                        // v: 재억 제보(2026-08-22) - 로그로 확정됨: 각 항목은 객체가 아니라
                        // {x=..., y=..., trfSt=...} 형태의 LinkedHashMap(사실상 파싱된
                        // JSON)이었음. getX()/getY() 함수를 찾던 리플렉션은 애초에 Map엔
                        // 그런 함수가 없어서 매번 실패. map["x"]/map["y"]로 직접 꺼냄. #문제시 원복
                        val lon: Double?
                        val lat: Double?
                        if (point is Map<*, *>) {
                            lon = (point["x"] as? Number)?.toDouble()
                            lat = (point["y"] as? Number)?.toDouble()
                        } else {
                            lon = findGetterDouble(point, "X").takeIf { it != 0.0 }
                            lat = findGetterDouble(point, "Y").takeIf { it != 0.0 }
                        }
                        if (lon == null) xFailCount++
                        if (lat == null) yFailCount++
                        if (lon != null && lat != null) out.add(lon to lat)
                    }
                    if (out.isNotEmpty()) {
                        NavLogger.d(context, "[경로선 조사] routePolylineWGS84() 개수=${out.size}")
                        return out
                    } else {
                        // v: 재억 제보(2026-08-22) - 리스트는 있는데 좌표 추출이 실패하는 진짜
                        // 이유(X/Y 게터를 못 찾는 건지 값이 전부 0인 건지)를 정확히 남김. #문제시 원복
                        val firstPointClass = result.firstOrNull { it != null }?.javaClass?.name
                        NavLogger.e(context, "[경로선 조사] routePolylineWGS84() 리스트(${result.size}개)는 받았지만 좌표 추출 0개. xFail=$xFailCount yFail=$yFailCount 항목클래스=$firstPointClass")
                    }
                }
            }
        } catch (e: Exception) {
            NavLogger.e(context, "[경로선 조사] routePolylineWGS84() 예외: ${e.javaClass.simpleName}: ${e.message}")
        }

        val lineCandidates = listOf("RoutePoints", "RouteLine", "Points", "Line", "Coords", "Coordinates")
        for (candidateName in lineCandidates) {
            try {
                val getter = route.javaClass.methods.firstOrNull {
                    it.parameterTypes.isEmpty() && it.name.equals("get$candidateName", ignoreCase = true)
                } ?: continue
                val result = getter.invoke(route) as? List<*> ?: continue
                if (result.isEmpty()) continue

                val out = ArrayList<Pair<Double, Double>>(result.size)
                for (point in result) {
                    if (point == null) continue
                    val lon = findGetterDouble(point, "Longitude").takeIf { it != 0.0 }
                        ?: findGetterDouble(point, "X").takeIf { it != 0.0 } ?: continue
                    val lat = findGetterDouble(point, "Latitude").takeIf { it != 0.0 }
                        ?: findGetterDouble(point, "Y").takeIf { it != 0.0 } ?: continue
                    out.add(lon to lat)
                }
                if (out.isNotEmpty()) {
                    NavLogger.d(context, "[경로선 조사] 매칭게터=get$candidateName 개수=${out.size}")
                    return out
                }
            } catch (e: Exception) {
                // 이 후보 실패 - 다음 후보 시도
            }
        }
        return emptyList()
    }

    // v: 재억 요청(2026-08-22) - 경유지를 하나 이상 지나서 최종목적지로 가는 경우,
    // "지금 향하고 있는 정차지"(다음 경유지 또는 그게 없으면 최종목적지)와 거기까지
    // 남은 거리를 알아냄. KNRoute.getLocationsOfPois()가 출발/경유/도착 각 지점의
    // 누적거리(DistFromS)를 담고 있을 것으로 추정(확실친 않음, 로그 없이 시도하는
    // 부분이라 방어적으로 짬 - 실패하면 null 반환해서 기존 동작 그대로 유지). #문제시 원복
    private fun resolveNextStopInfo(guidance: KNGuidance, myDistFromS: Int): Triple<String, Int, Boolean>? {
        return try {
            val trip = guidance.trip ?: return null
            val route = guidance.routesOnGuide?.firstOrNull() ?: return null
            val poisMethod = route.javaClass.methods.firstOrNull {
                it.name == "getLocationsOfPois" && it.parameterTypes.isEmpty()
            } ?: return null
            val pois = poisMethod.invoke(route) as? List<*> ?: return null
            if (pois.size < 3) return null // 출발/도착 2개뿐이면 경유지 없음 - 기존 동작 유지

            // 경유지 이름 목록: trip 객체에서 "via"가 들어간 게터를 찾아 KNPOI 리스트를
            // 얻고, 각 항목의 getName()을 시도. 순서가 요청한 순서와 같다고 가정. #문제시 원복
            val viaNames = try {
                val viaGetter = trip.javaClass.methods.firstOrNull {
                    it.parameterTypes.isEmpty() && it.name.lowercase().contains("via")
                }
                (viaGetter?.invoke(trip) as? List<*>)?.map { via ->
                    try {
                        via?.javaClass?.methods?.firstOrNull { it.name == "getName" }?.invoke(via) as? String
                    } catch (e: Exception) { null }
                }
            } catch (e: Exception) { null }

            for (idx in 1 until pois.size) {
                val poi = pois[idx] ?: continue
                val dist = findGetterInt(poi, "DistFromS")
                if (dist <= 0 || dist <= myDistFromS) continue
                val isFinal = idx == pois.size - 1
                val name = if (isFinal) {
                    trip.goal?.name.orEmpty().ifBlank { "목적지" }
                } else {
                    viaNames?.getOrNull(idx - 1)?.takeIf { !it.isNullOrBlank() } ?: "경유지"
                }
                return Triple(name, dist - myDistFromS, isFinal)
            }
            null
        } catch (e: Exception) {
            NavLogger.e(context, "[다음정차지] 조사 실패(기존 동작 유지): ${e.message}")
            null
        }
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
        // 이 콜백이 실제로 호출되는지, 좌표가 시간에 따라 바뀌는지 직접 확인하기 위한 로그였음.
        // 이미 오래전에 확인 끝났고(정상 호출됨), 바로 아래 [경로추적] 요약 한 줄이면 충분한데
        // 여기서 원본 객체 전체를 리플렉션으로 통째로 덤프하고 있어서 로그 한 줄이 수천 자에
        // 달했음(용량 낭비 1순위, 재억 요청으로 정리). 스로틀(2초)과 요약 로그는 유지. #문제시 원복
        val now = System.currentTimeMillis()
        if (now - lastLocationLogAt >= 2000) {
            lastLocationLogAt = now
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

                    // v: 재억 요청(2026-08-22) - 이 로그가 GPS 갱신마다(보통 초당 1회) 매번
                    // 찍혀서 로그 파일에서 가장 큰 비중(4천줄 이상)을 차지하고 있었음.
                    // 값 자체는 1초마다 봐야 할 이유가 없어서(남은 거리/시간은 서서히만
                    // 바뀜) 다른 주기성 로그들과 동일하게 10초 간격으로 줄임. #문제시 원복
                    if (System.currentTimeMillis() - lastEtaLogTime > 10_000L) {
                        lastEtaLogTime = System.currentTimeMillis()
                        NavLogger.d(
                            context,
                            "[카카오 ETA] remainDist=${remainDist}m remainTime=${remainTime}s"
                        )
                    }
                }
            }

            // v: 지피티 분석 반영 - 현재 위치의 DistFromS(경로 시작점부터 누적거리)를
            // 매번 저장해둠. 안전정보 이벤트의 DistFromS에서 이 값을 빼면 실제 남은
            // 거리를 곡선 경로 그대로 정확히 계산할 수 있음(아래 카메라/방지턱 검색
            // 로직에서 사용). #문제시 원복
            if (currentLocation != null) {
                // v: 버그수정(재억 제보 - "10% 이하로 달렸는데도 경고음 남") 원인 조사 -
                // 카카오 화면 과속경고음이 지금까지 Tmap 전용 SdiDataRepository.roadLimitSpeed를
                // 그대로 갖다 썼는데, 카카오 화면에선 이 값을 채워주는 코드가 아예 없어서
                // Tmap 화면 마지막 값(또는 기본값 80)이 실제 도로와 무관하게 고정되어 있었음.
                // KNGuide_Location에 "지금 도로의 기본 제한속도"에 해당하는 게터가 있는지
                // 확실치 않아서(공식 문서에 명시 안 됨), 후보 이름들을 순서대로 리플렉션으로
                // 찔러보고 처음 유효한(>=30) 값을 채택. 게터 이름이 로그로 남으므로, 다음 실주행
                // 로그를 보고 진짜 맞는 값인지 검증 후 이 후보 목록을 정리할 예정. #문제시 원복
                val roadLimitCandidates = listOf("RoadSpeedLimit", "CurRoadSpeedLimit", "CurSpeedLimit", "LinkSpeedLimit", "RoadLimitSpeed")
                var kakaoLimit = 0
                var matchedGetterName: String? = null
                for (candidate in roadLimitCandidates) {
                    val v = findGetterInt(currentLocation, candidate)
                    if (v in 30..150) {
                        kakaoLimit = v
                        matchedGetterName = candidate
                        break
                    }
                }
                if (kakaoLimit > 0) {
                    SdiDataRepository.kakaoRoadLimitSpeed = kakaoLimit
                    SdiDataRepository.kakaoRoadLimitSpeedUpdatedAt = System.currentTimeMillis()
                }
                if (now - lastLocationLogAt < 50) {
                    // 위 2초 스로틀 로그와 같은 타이밍에 한 번만 같이 남김
                    NavLogger.d(context, "[카카오 도로제한속도 조사] 매칭게터=$matchedGetterName 값=$kakaoLimit (30~150 범위 밖이면 0으로 무시됨)")
                }

                val myDistFromS = findGetterInt(currentLocation, "DistFromS")
                if (myDistFromS > 0) {
                    KakaoRouteDataRepository.currentDistFromS = myDistFromS

                    // v: 재억 요청(2026-08-22) - 경유지가 있을 땐 최종목적지 정보 대신
                    // "지금 향하고 있는 다음 정차지"(경유지 or 최종목적지) 정보를 보여주고,
                    // 그 경유지를 지나면 자동으로 다음 걸로 넘어가게 함. 로그 없이 바로
                    // 시도하는 거라 방어적으로 - 뭔가 안 맞으면 조용히 원래 동작(최종목적지
                    // 표시)으로 돌아감. #문제시 원복
                    resolveNextStopInfo(guidance, myDistFromS)?.let { (name, remainDist, isFinal) ->
                        KakaoRouteDataRepository.destinationName = name
                        if (!isFinal) {
                            // 경유지 구간은 전체 남은시간을 거리 비율로 나눠 대략 추정
                            // (카카오 SDK가 경유지 단위 시간을 따로 안 줘서 근사치). #문제시 원복
                            val totalRemainDist = KakaoRouteDataRepository.remainDist
                            val totalRemainTime = KakaoRouteDataRepository.remainTime
                            if (totalRemainDist > 0 && totalRemainTime > 0 && remainDist <= totalRemainDist) {
                                KakaoRouteDataRepository.remainDist = remainDist
                                KakaoRouteDataRepository.remainTime =
                                    (totalRemainTime.toLong() * remainDist / totalRemainDist).toInt()
                            }
                        }
                    }

                    // v10.2: 실주행 로그로 확인된 버그 수정 - 안전정보 거리는 새 이벤트가
                    // 잡힐 때만(뜸하게) 갱신됐어서, 그 사이엔 UdpSenderService가 계속 옛날
                    // 값을 그대로 반복 전송함(카메라 앞에서 dist가 18초간 안 줄어듦 ->
                    // openpilot 감속 안 함). 여기서 위치 갱신마다(훨씬 자주) 저장해둔 이벤트의
                    // 절대거리에서 현재 위치를 빼서 실시간으로 다시 계산해줌. #문제시 원복
                    val eventDistFromS = KakaoRouteDataRepository.safetyEventDistFromS
                    if (eventDistFromS > 0 && KakaoRouteDataRepository.safetyDistTrusted) {
                        val liveDist = eventDistFromS - myDistFromS
                        if (liveDist >= 0) {
                            KakaoRouteDataRepository.safetyDist = liveDist
                        } else {
                            // 이미 지나침 - 정리해서 openpilot이 옛날 카메라를 계속
                            // "감속 대상"으로 오인하지 않게 함
                            KakaoRouteDataRepository.safetyType = -1
                            KakaoRouteDataRepository.safetyDist = 0
                            KakaoRouteDataRepository.safetyDistTrusted = false
                            KakaoRouteDataRepository.safetyEventDistFromS = -1
                        }
                    }
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
            KakaoHudBridge.publish(context, guidance, locationGuide, guidance.routeGuide)
        } catch (e: Exception) {
            NavLogger.e(context, "[HUD 브릿지] guidanceDidUpdateLocation 반영 실패: ${e.message}")
        }

        naviView?.guidanceDidUpdateLocation(guidance, locationGuide)
    }

    // ===== RouteGuideDelegate =====
    override fun guidanceDidUpdateRouteGuide(guidance: KNGuidance, routeGuide: KNGuide_Route) {
        // v: 재억 제보(2026-08-22) - "티맵이 한 번씩 멈춰서 초기화면까지 나갔다 들어와야
        // 한다"는 문제의 진짜 원인으로 확정됨. 이 콜백은 회전 안내가 바뀔 때마다(교차로
        // 근처 등에서 꽤 잦음) 불리는데, 매번 아무 제한 없이 메인 스레드에서 객체 전체를
        // 리플렉션으로 통째로 훑고 문자열로 풀어쓰고 있었음(수백~수천 자). 이게 반복되면서
        // 화면이 순간순간 멈추다가, 안드로이드가 "응답 없음"으로 판단해서 화면 자체를
        // 강제 종료시킨 것으로 보임. 필드 구조 확인은 이미 오래전에 끝났고 실제로 쓰는 값은
        // 아래(KNLane 등)에서 정식 API로 따로 가져오고 있어서, 이 디버그용 통째 덤프는
        // 완전히 제거함(용도 다함). #문제시 원복

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
            // v5.4: 이 토글은 "화면 표시 여부"만 결정해야 함(재확인) - 카카오 데이터는
            // 티맵 화면에도 오버레이로 띄우는 게 이 기능의 원래 취지라, 여기서 수집 자체를
            // 막으면 안 됨. 계속 수집하고, 실제로 보여줄지는 renderLaneSignalBar에서
            // 화면별로 결정. #문제시 원복
            if (lane != null) {
                val laneInfoList = lane.javaClass.methods
                    .firstOrNull { it.name == "getLaneInfos" && it.parameterTypes.isEmpty() }
                    ?.invoke(lane) as? List<*>
                if (laneInfoList != null) {
                    // v: 재억 요청(2026-08-28) - "색깔 유도선" 조사용, 앱 켜있는 동안 1번만
                    // 차선 정보 객체가 실제로 어떤 getter들을 가지고 있는지 전부 남김.
                    // color/유도선 관련 이름이 있는지 확인용. #문제시 원복
                    if (!laneInfoApiScanDone && laneInfoList.isNotEmpty()) {
                        laneInfoApiScanDone = true
                        try {
                            val first = laneInfoList.firstOrNull { it != null }
                            if (first != null) {
                                NavLogger.e(context, "===== [차선API스캔] ${first.javaClass.name} 메서드 목록 =====")
                                for (m in first.javaClass.methods) {
                                    if (m.parameterTypes.isEmpty() && (m.name.startsWith("get") || m.name.startsWith("is"))) {
                                        val value = try { m.invoke(first) } catch (e: Exception) { "호출실패: ${e.message}" }
                                        NavLogger.e(context, "[차선API스캔] ${m.name}() = $value")
                                    }
                                }
                                NavLogger.e(context, "===== [차선API스캔] 끝 =====")
                            }
                        } catch (e: Exception) {
                            NavLogger.e(context, "[차선API스캔] 실패: ${e.message}")
                        }
                    }
                    // v4.11: getHighlightType으로 추천 차선 여부를 실제로 판단해서 표시.
                    // internal 접근 제한 때문에 타입 API가 아니라 리플렉션으로 메서드를
                    // 직접 호출함(컴파일 에러로 linkIdx가 internal인 걸 확인했음 - 같은
                    // 클래스의 다른 getter들도 마찬가지일 가능성이 높아 안전하게 리플렉션
                    // 유지). #문제시 원복
                    // v: 재억 요청(2026-08-28) - "카카오 화면엔 노란색으로 정확하게 추천 차선이
                    // 뜨는데, getHighlightType()은 항상 false"라는 제보 - highlightType이
                    // 진짜 필드가 아니었음. 실기기 로그로 대조한 결과 getSuggest()가 노란색
                    // 강조와 정확히 일치해서 이걸로 교체함. #문제시 원복
                    // v: 재억 제보(2026-08-28, 실기기 로그로 확인) - getHighlightType()은 이번
                    // 세션 내내 항상 0(false)만 찍혔는데, 그 순간마다 카카오 화면엔 실제로
                    // 노란색 추천 차선이 정확히 표시되고 있었음. 반면 getSuggest()는 매번
                    // 정확히 차선 한두 개만 1로 찍히고 나머지는 0 - 화면의 노란색 강조와
                    // 패턴이 일치함. highlightType 대신 getSuggest를 진짜 "추천 차선" 필드로
                    // 채택. #문제시 원복
                    // v: 재억 요청(2026-08-28) - "색깔 유도선" 값이 진짜 실주행에서 어떻게
                    // 나오는지 확인하기 위해, getSuggest와 동일하게 매번(1회성 스캔이 아니라)
                    // 로그로 남김. #문제시 원복
                    val colorTypeValues = laneInfoList.map { info ->
                        if (info == null) return@map "null"
                        try {
                            val v = info.javaClass.methods
                                .firstOrNull { it.name == "getColorType" && it.parameterTypes.isEmpty() }
                                ?.invoke(info)
                            v?.toString() ?: "null"
                        } catch (e: Exception) { "실패" }
                    }
                    // v: 재억 요청(2026-08-28, 실기기 로그로 확인) - getSuggest가 이번 세션
                    // 80번 갱신 중 8번(10%)만 true를 줬고, 특히 "좌회전해야 하는 차로" 같은
                    // 상황(74초 정지 구간)에서는 계속 전부 false였음 - getSuggest는 일반적인
                    // "이 차로가 낫다" 추천용일 뿐, "이 차로에서 좌/우회전 가능한지"는 다른
                    // 필드(getTurnType, 예전 API스캔에서 이미 존재 확인됨)일 가능성이 높아서
                    // 비교용으로 같이 로그에 남김. #문제시 원복
                    val turnTypeValues = laneInfoList.map { info ->
                        if (info == null) return@map "null"
                        try {
                            val v = info.javaClass.methods
                                .firstOrNull { it.name == "getTurnType" && it.parameterTypes.isEmpty() }
                                ?.invoke(info)
                            v?.toString() ?: "null"
                        } catch (e: Exception) { "실패" }
                    }
                    // v: 재억 재제보(2026-08-29, 실기기 로그로 확인) - getSuggest는 여전히
                    // 대부분(90%) false만 줘서 "1,2차로" 배지가 거의 안 뜬다는 문제가 계속
                    // 됐음. 대신 getTurnType 값을 실제 회전방향과 대조해보니 비트마스크로
                    // 보임: 1=유턴, 2=좌회전, 8=직진, 32=우회전 (10=2+8=좌회전+직진 겸용
                    // 차로, 40=8+32=직진+우회전 겸용 차로 - 실제 좌/우회전 상황에서 정확히
                    // 해당 방향 비트를 가진 차로만 추천되는 게 로그로 확인됨). 지금 안내
                    // 방향에 맞는 비트를 가진 차로만 추천 처리. 이 매핑은 이번 로그(제한된
                    // 표본)로 추정한 것이라 확실하진 않음 - 다음 로그로 더 검증 예정. #문제시 원복
                    val curDirectionForLane = findGetter(routeGuide, "getCurDirection")
                    val turnCodeForLane = curDirectionForLane?.let { findGetter(it, "getRgCode") }?.toString()
                    // v: 재억 재지적(2026-08-29) - 로터리를 무조건 우회전(32)으로 고정하면
                    // 안 되고, 진출 각도에 따라 좌/우/직진이 다 될 수 있음. 위 아이콘 매핑과
                    // 동일한 각도 기반 판단(KakaoToNavdyTurn)을 재사용해서 일관되게 처리. #문제시 원복
                    val directionAngleForLane = curDirectionForLane?.let { findGetterInt(it, "DirectionAng") } ?: 0
                    val requiredBit = when {
                        turnCodeForLane == "KNRGCode_LeftTurn" || turnCodeForLane == "KNRGCode_UnprotectedLeftTurn" || turnCodeForLane == "KNRGCode_LeftOutHighway" -> 2
                        turnCodeForLane == "KNRGCode_RightTurn" || turnCodeForLane == "KNRGCode_RightOutHighway" || turnCodeForLane == "KNRGCode_OutHighway" -> 32
                        turnCodeForLane == "KNRGCode_UTurn" -> 1
                        turnCodeForLane == "KNRGCode_OverPath" || turnCodeForLane == "KNRGCode_UnderPath" -> 8
                        // v: 재억 재지적(2026-08-29) - 대안경로 오감지 수정됐다고 해서
                        // 좌/우측 분기도 같이 방향 매핑에 포함. #문제시 원복
                        turnCodeForLane == "KNRGCode_LeftDirection" -> 2
                        turnCodeForLane == "KNRGCode_RightDirection" -> 32
                        turnCodeForLane != null && (turnCodeForLane.startsWith("KNRGCode_RoundaboutDirection") || turnCodeForLane.startsWith("KNRGCode_RotaryDirection")) ->
                            when (com.tmap.nda.navdy.KakaoToNavdyTurn.from(turnCodeForLane, directionAngleForLane)) {
                                com.tmap.nda.navdy.NavdyTurn.ROUNDABOUT_NE, com.tmap.nda.navdy.NavdyTurn.ROUNDABOUT_E, com.tmap.nda.navdy.NavdyTurn.ROUNDABOUT_SE -> 32
                                com.tmap.nda.navdy.NavdyTurn.ROUNDABOUT_S, com.tmap.nda.navdy.NavdyTurn.ROUNDABOUT_SW, com.tmap.nda.navdy.NavdyTurn.ROUNDABOUT_W -> 2
                                else -> 8
                            }
                        else -> null
                    }
                    val recommendedFlags = laneInfoList.mapNotNull { info ->
                        if (info == null) return@mapNotNull null
                        try {
                            val turnTypeRaw = info.javaClass.methods
                                .firstOrNull { it.name == "getTurnType" && it.parameterTypes.isEmpty() }
                                ?.invoke(info)
                            val turnTypeBits = (turnTypeRaw as? Number)?.toInt() ?: 0
                            val recommended = if (requiredBit != null) {
                                (turnTypeBits and requiredBit) != 0
                            } else {
                                // 직진 등 매핑 안 한 방향은 기존 getSuggest로 폴백
                                val suggestRaw = info.javaClass.methods
                                    .firstOrNull { it.name == "getSuggest" && it.parameterTypes.isEmpty() }
                                    ?.invoke(info)
                                ((suggestRaw as? Number)?.toInt() ?: 0) != 0
                            }
                            // v4.23: getBusType도 실제로 존재함(APK 바이트코드로 확인) - 버스전용차로
                            // 표시(사용자 10번)용으로 같이 읽음. Byte로 반환되니 Number로 넓게 받아서 처리. #문제시 원복
                            val busTypeRaw = info.javaClass.methods
                                .firstOrNull { it.name == "getBusType" && it.parameterTypes.isEmpty() }
                                ?.invoke(info)
                            val busType = (busTypeRaw as? Number)?.toInt() ?: 0
                            if (busType != 0) {
                                NavLogger.d(context, "[버스차로 수집] getBusType=$busType (0이 아닌 값 실제 관측)")
                            }
                            LaneDisplayInfo(recommended = recommended, busType = busType)
                        } catch (e: Exception) { LaneDisplayInfo(recommended = false) }
                    }
                    // v: 재억 재제보(2026-08-30, 실기기로 확인) - "우회전/좌회전인데 정확히
                    // 맞는 비트가 있는 차로가 하나도 없는 경우"(우회전 전용차로가 따로 없는
                    // 흔한 교차로)를 확인함. 가장자리 차로를 폴백으로 추천하는 방법도
                    // 검토했으나, 그 차로가 버스전용차로거나 다른 도로로 빠지는 차로일 수도
                    // 있어 틀린 정보를 확신하듯 보여줄 위험이 있음("엄하게 할거면 넣지마"
                    // 재억님 판단) - 폴백 없이 정확히 맞는 차로가 있을 때만 추천 표시. #문제시 원복
                    LaneSignalRepository.lanes = recommendedFlags
                    LaneSignalRepository.source = "kakao"
                    LaneSignalRepository.lastUpdateTime = System.currentTimeMillis()
                    // v: 재억 제보(2026-08-28) - "오버레이에 추천 차선 배지가 안 뜬다" 원인
                    // 조사용. lanes 개수만으론 "추천 차선이 실제로 있었는지"를 알 수 없어서,
                    // 차선별 recommended 값을 그대로 남김(전부 false면 SDK가 이 구간에서
                    // 추천 차선 자체를 안 준 것 - 배지가 안 뜨는 게 정상 동작). #문제시 원복
                    NavLogger.d(context, "[차선진단][추천여부] ${recommendedFlags.mapIndexed { i, f -> "${i + 1}번=${f.recommended}" }}")
                    NavLogger.d(context, "[차선진단][getTurnType] ${turnTypeValues.mapIndexed { i, v -> "${i + 1}번=$v" }}")
                    NavLogger.d(context, "[차선진단][getColorType] ${colorTypeValues.mapIndexed { i, v -> "${i + 1}번=$v" }}")
                    // v13.6: 재억 지적 - "평소엔 안뜨고 카카오 안내 끝내야 뜬다" 원인을
                    // 다음 재현 때 정확히 잡기 위한 진단 로그. 카카오가 실제로 차선을
                    // 갱신하는 매 순간을 15초 간격으로 남김. #문제시 원복
                    if (System.currentTimeMillis() - lastLaneUpdateDiagLogTime > 15000L) {
                        lastLaneUpdateDiagLogTime = System.currentTimeMillis()
                        NavLogger.d(context, "[차선진단][카카오] 갱신됨 lanes=${recommendedFlags.size}개")
                    }
                }
                // v5.4: KNDriveLaneView(카카오 공식 렌더링 컴포넌트)에 그대로 넘겨줄 원본
                // KNLane 객체 보관. #문제시 원복
                LaneSignalRepository.kakaoLane = lane

                // v: 재억 제보(2026-08-22) - 위 guidanceDidUpdateRouteGuide 덤프와 똑같은
                // 문제(메인 스레드에서 스로틀 없이 매번 리플렉션 통째 덤프 + 곧바로 화면
                // 갱신 트리거) - 티맵 화면이 멈추는 원인 중 하나로 보여서 제거. 실제 차선
                // 렌더링은 이미 아래 KNDriveLaneView 정식 API로 처리 중이라 이 덤프는
                // 용도 다함. #문제시 원복
                LaneSignalRepository.notifyChanged()
            } else if (lane == null) {
                LaneSignalRepository.kakaoLane = null
                // v: 재억 재요청(2026-08-28) - "나오다 말다 한다, 안 나오는 경우가 더 많다"는
                // 제보로 다시 확인. 지난 수정(v19.2.31)은 카카오가 lane=null을 보낼 때마다
                // 즉시 lanes를 비웠는데, 카카오 SDK 자체가 안내 도중에도 이 콜백을 간헐적으로
                // null로 보내는 걸로 보임(추측이 아니라 "나오다 말다"라는 실사용 증상과
                // 맞음) - 그때마다 지워버리면 깜빡거리게 됨. 올바른 기준은 "카카오가 이번엔
                // null을 줬는지"가 아니라 "실제로 그 회전 지점을 지났는지(tbtDist가 0에
                // 가까워졌는지)"여야 함. 그래서 lane=null이어도 곧바로 지우지 않고, 회전
                // 지점에 다 왔을 때(또는 완전히 오래돼서 안내 자체가 끝난 걸로 보일 때)만
                // 지우도록 바꿈. #문제시 원복
                // v: 재억 재제보(2026-08-29, 실기기 로그로 확인) - "여전히 잠깐 뜨고
                // 사라진다"는 재발 제보. 원인: 위 30초 타임아웃(guidanceLikelyEnded)이
                // 신호대기 같은 정상적인 정차 상황까지 "안내 끝남"으로 착각해서 지워버리고
                // 있었음(로그로 확인 - 신호대기로 nTBTDist가 1분 넘게 안 바뀌는 동안 카카오가
                // 차선 정보를 다시 안 줬을 뿐인데, 30초가 지났다고 지워버림). "안내가 실제로
                // 끝났는지"는 오버레이 전체를 껐다 켜는 별도 로직(NavOverlayManager의 신선도
                // 체크, v19.2.36)이 이미 판단하고 있어서 여기선 필요 없음 - 회전 지점 도달
                // 여부로만 판단. #문제시 원복
                val nearOrPastManeuver = KakaoRouteDataRepository.tbtDist in 0..15
                if (nearOrPastManeuver) {
                    LaneSignalRepository.lanes = emptyList()
                    LaneSignalRepository.source = ""
                }
                NavLogger.d(context, "[차선정보] lane=null (이 구간엔 차선 안내 데이터 없음, tbtDist=${KakaoRouteDataRepository.tbtDist}, 지워짐=$nearOrPastManeuver)")
                LaneSignalRepository.notifyChanged()
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
            // 잘못 잡고 있었음(사용자 지적: "좌회전 우회전 갈 때 화살표 보여주고 싶다"). 정확히
            // getRgCode를 지정해서 가져오도록 수정. #문제시 원복
            val turnTypeRaw = curDirection?.let { findGetter(it, "getRgCode") }
            // v: 재억 재지적(2026-08-29) - 로터리 등 각도 기반 판단에 필요한 실제 회전각도.
            // KakaoHudBridge 쪽은 공식 API(direction.directionAng)로 이미 얻고 있지만, 그건
            // 이 함수(mapKakaoTurnTypeToOpenpilot) 호출보다 뒤에 실행돼서 여기선 아직 최신값이
            // 아님 - 실행 순서를 안 건드리고 여기서도 리플렉션으로 직접 얻음. #문제시 원복
            val directionAngleNow = curDirection?.let { findGetterInt(it, "DirectionAng") } ?: 0
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
            KakaoRouteDataRepository.tbtTurnType = mapKakaoTurnTypeToOpenpilot(turnTypeRaw, directionAngleNow)
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
            KakaoHudBridge.publish(context, guidance, guidance.locationGuide, routeGuide)
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
    // 유지 (사용자 요청: "좌회전 우회전 갈 때 화살표 표시"). #문제시 원복
    // v4.13: openpilot(carrot_serv.py의 turn_type_mapping) 쪽 코드표를 확인해보니
    // 12=좌회전, 13=우회전 외에 14=유턴이 있는데 여태 안 쓰고 있었음 - 유턴 안내가
    // 항상 51(무안내)로 나가서 화살표가 안 뜨던 원인 중 하나(사용자 5번). U-turn 매핑 추가.
    // 그리고 "RightTurn"/"LeftTurn"이 아닌 나머지 KNRGCode(예: 최근 로그에서 실제로 관측된
    // KNRGCode_RightDirection처럼 우측 분기/차선변경성 코드)는 잘못된 화살표를 보여줄
    // 위험이 있어 매핑하지 않고 51로 두되, 어떤 코드가 안 걸렸는지 15초 간격으로 로그를
    // 남겨서 다음 세션에서 매핑표를 확장할 수 있게 함. #문제시 원복
    private var lastUnmappedTurnTypeLogTime = 0L
    private var lastLaneUpdateDiagLogTime = 0L
    // v4.16: TmapNda 자체 APK를 디컴파일해서 KNRGCode enum을 바이트코드 레벨에서 직접
    // 추출 - 전체 92개 값을 정확히 확인함(사용자가 준 CarrotNavi 참고로 openpilot이 기대하는
    // 코드체계도 확인됨: carrot_serv.py의 turn_type_mapping). 이 둘을 대조해서 확실한
    // 것만 매핑하고, 애매한 건(Direction_1~12, InHighway 계열 등) 잘못된 화살표를 보여줄
    // 위험이 있어 그냥 51(무안내)로 둠 - 예전에 사용자가 지적한 것과 같은 이유. #문제시 원복
    //
    // openpilot(carrot_serv.py) 쪽 코드체계 참고:
    //   12=좌회전 13=우회전 14=유턴 51~55=무안내(직진) 201=도착
    //   6/43/73/74/117/123/124=우측분기(fork right)  7/17/44/75/76/118=좌측분기(fork left)
    //   101/104/111/114=우측 완만한 램프(off-ramp 우) 102/105/112/115=좌측 완만한 램프
    //   153/154/249=톨게이트(TG)
    private fun mapKakaoTurnTypeToOpenpilot(kakaoTurnType: Any?, directionAngleForMapping: Int = 0): Int {
        val name = kakaoTurnType?.toString() ?: return 51
        return when (name) {
            // 확실한 매핑 (좌/우회전, 유턴 - 기존에 이미 검증됨)
            "KNRGCode_LeftTurn", "KNRGCode_UnprotectedLeftTurn" -> 12
            "KNRGCode_RightTurn" -> 13
            "KNRGCode_UTurn" -> 14
            // 도착
            "KNRGCode_Goal" -> 201
            // v: 재억 재지적(2026-08-29) - "대안경로 오감지는 내가 수정했으니까 없애버려"
            // - v13.6에서 대안경로 합류 지점 오감속 방지로 51(무안내)에 고정해뒀던 걸
            // 되돌림. openpilot(carrot_serv.py) 코드표대로 우측분기=6, 좌측분기=7로 복원. #문제시 원복
            "KNRGCode_LeftDirection" -> 7
            "KNRGCode_RightDirection" -> 6
            // 고속도로 진출 램프
            "KNRGCode_LeftOutHighway" -> 102
            "KNRGCode_RightOutHighway", "KNRGCode_OutHighway" -> 101
            // 톨게이트
            "KNRGCode_Tollgate", "KNRGCode_NonstopTollgate" -> 153
            else -> {
                // v: 재억 재지적(2026-08-29) - "로터리를 무조건 우회전으로 박으면 안 되고,
                // 실제 경로(진출 각도)를 따라 판단해야 한다"는 지적이 맞음. 로터리는 진출구
                // 위치에 따라 좌회전/우회전/직진 다 될 수 있어서 고정 매핑은 위험함. 이미
                // Navdy HUD용으로 만들어뒀던 각도 기반 판단(KakaoToNavdyTurn.roundaboutFromAngle,
                // direction.directionAng 실측값 사용)을 재사용해서 좌/우/직진을 구분함.
                // RightDirection/LeftDirection(v13.6에서 대안경로 합류 지점 오감속 방지로
                // 일부러 51 고정해둔 것)은 오늘 문제와 원인이 달라서 그대로 안 건드림. #문제시 원복
                if (name.startsWith("KNRGCode_RoundaboutDirection") || name.startsWith("KNRGCode_RotaryDirection")) {
                    return when (com.tmap.nda.navdy.KakaoToNavdyTurn.from(name, directionAngleForMapping)) {
                        com.tmap.nda.navdy.NavdyTurn.ROUNDABOUT_NE, com.tmap.nda.navdy.NavdyTurn.ROUNDABOUT_E, com.tmap.nda.navdy.NavdyTurn.ROUNDABOUT_SE -> 13
                        com.tmap.nda.navdy.NavdyTurn.ROUNDABOUT_S, com.tmap.nda.navdy.NavdyTurn.ROUNDABOUT_SW, com.tmap.nda.navdy.NavdyTurn.ROUNDABOUT_W -> 12
                        else -> 51 // N, NW 근처(거의 직진)
                    }
                }
                if (System.currentTimeMillis() - lastUnmappedTurnTypeLogTime > 15000L) {
                    lastUnmappedTurnTypeLogTime = System.currentTimeMillis()
                    NavLogger.d(context, "[카카오 회전코드 수집] 미매핑 KNRGCode=$name -> 51(무안내)로 전송됨")
                }
                51
            }
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

    // v5.2: DoublePoint.getX()/getY() 같은 Double 게터용. #문제시 원복
    private fun findGetterDouble(obj: Any, nameContains: String): Double {
        return try {
            (findGetter(obj, null, nameContains) as? Number)?.toDouble() ?: 0.0
        } catch (e: Exception) { 0.0 }
    }

    // v5.1: getPassed() 같은 Boolean 게터용 - findGetterInt와 동일 패턴. #문제시 원복
    private fun findGetterBool(obj: Any, nameContains: String): Boolean? {
        return try {
            findGetter(obj, null, nameContains) as? Boolean
        } catch (e: Exception) { null }
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

    // v4.21: 사용자 지적으로 다시 파보니 - 지금까지 findGetterInt(safetyGuide, "Type"/"SpeedLimit"/
    // "Dist")가 KNGuide_Safety 컨테이너 자체에서 그 이름의 게터를 찾고 있었는데, 실제로 이
    // 클래스엔 getSafetiesOnGuide(): List<KNSafety> 하나만 있고 Type/SpeedLimit/Dist는
    // 전부 그 리스트 안의 개별 KNSafety 항목(그리고 그 하위타입 KNSafety_Camera/
    // KNSafety_Caution)에 있었음(APK 바이트코드로 직접 확인). 즉 지금까지 이 기능은 매번
    // 0(찾는 게터 없음 → 기본값)만 반환해서 사실상 한 번도 제대로 동작한 적이 없었을
    // 가능성이 높음. getCode()(KNSafetyCode enum)/getLocation().getDistFromS()(경로상
    // 거리)로 정확한 경로로 다시 짬 + KNSafetyCode → Tmap/openpilot 고유 nSdiType 스킴으로
    // 번역(carrot_serv.py의 _get_sdi_descr 표 기준, 확실한 것만 매핑). #문제시 원복
    private fun mapKakaoSafetyCodeToSdiType(kakaoCodeName: String?): Int {
        if (kakaoCodeName == null) return -1
        return when (kakaoCodeName) {
            "KNSafetyCode_SpeedViolationCamera" -> 1          // 과속(고정식)
            "KNSafetyCode_SpeedViolationSectionInCamera" -> 2 // 구간단속 시작
            "KNSafetyCode_SpeedViolationSectionOutCamera" -> 3 // 구간단속 끝
            "KNSafetyCode_SpeedViolationSection" -> 4         // 구간단속중
            "KNSafetyCode_SpeedViolationSectionHalf" -> 4
            "KNSafetyCode_MovableSpeedViolationCamera" -> 7   // 과속(이동식)
            "KNSafetyCode_BoxedSpeedViolationCamera" -> 8     // 고정식 과속위험구간(박스형)
            // v10.1: "신호과속" 카메라를 nSdiType=0으로 매핑해뒀었는데, openpilot 쪽에서
            // 0은 "카메라 없음"으로 해석됨(UdpSenderService.kt의 Tmap측 "sdiType==0인데
            // speedLimit/dist는 있으면 1로 강제" 로직이 그 증거 - 이미 알려진 문제였음).
            // 근데 그 강제보정은 Tmap 분기에만 있고 카카오 우선채택 분기엔 없어서, 신호+과속
            // 동시단속 카메라를 만날 때만 조용히 감속이 안 됐음(재억 로그로 확인:
            // KNSafetyCode_SignalAndSpeedViolationCamera -> nSdiType=0). 속도단속이 핵심이니
            // 고정식 과속카메라(1)와 동일하게 매핑. #문제시 원복
            "KNSafetyCode_SignalAndSpeedViolationCamera",
            "KNSafetyCode_SignalAndSpeedViolationBackwardCamera" -> 1 // 신호과속(속도단속 기준 적용)
            "KNSafetyCode_SignalViolationCamera" -> 6         // 신호단속
            "KNSafetyCode_BuslaneViolationCamera",
            "KNSafetyCode_BuslaneAndSpeedViolationCamera" -> 9 // 버스전용차로
            "KNSafetyCode_ShoulderLaneViolationCamera" -> 11  // 갓길감시
            "KNSafetyCode_CutInViolationCamera" -> 12         // 끼어들기금지
            "KNSafetyCode_TrafficCollectionCamera" -> 13      // 교통정보수집
            "KNSafetyCode_OverloadViolationCamera" -> 15      // 과적차량
            "KNSafetyCode_CargoViolationCamera" -> 16         // 적재불량
            "KNSafetyCode_ParkingViolationCamera" -> 17       // 주차단속
            "KNSafetyCode_RailroadCrossing" -> 19             // 철길건널목
            "KNSafetyCode_ChildrenProtectionZone" -> 20       // 어린이보호구역
            "KNSafetyCode_Hump" -> 22                         // 과속방지턱
            "KNSafetyCode_RestArea" -> 25                     // 휴게소
            "KNSafetyCode_OneTollingGate" -> 26                // 톨게이트
            "KNSafetyCode_FogArea", "KNSafetyCode_FogAreaLive" -> 27 // 안개주의
            "KNSafetyCode_TrafficAccidentPos", "KNSafetyCode_CarAccidentPos",
            "KNSafetyCode_PedestrianAccidentPos", "KNSafetyCode_ChildrenAccidentPos",
            "KNSafetyCode_DrowsyDrivingAccidentPos", "KNSafetyCode_IntentTrafficAccident" -> 29 // 사고다발
            "KNSafetyCode_SharpTurnSection" -> 30              // 급커브지역
            "KNSafetyCode_SteepDownhillSection" -> 32          // 급경사구간
            "KNSafetyCode_AnimalsAppearingCaution" -> 33       // 야생동물 사고 잦은 구간
            else -> -1 // 확신 없는 코드는 억지로 안 채움(잘못된 라벨/감속 유발 위험) - 로그로 수집
        }
    }

    // ===== SafetyGuideDelegate =====
    private var lastUnmappedSafetyCodeLogTime = 0L
    override fun guidanceDidUpdateSafetyGuide(guidance: KNGuidance, safetyGuide: KNGuide_Safety?) {
        try {
            val safetyList = safetyGuide?.let { findGetter(it, "getSafetiesOnGuide") } as? List<*>
            // 경로상 여러 안전정보 중, 남은 거리(getDistFromS)가 0 이상이면서 가장 가까운
            // 것 하나를 "현재 가장 시급한 이벤트"로 선택. #문제시 원복
            var nearest: Any? = null
            var nearestDist = Int.MAX_VALUE
            var nearestIsTrusted = false
            var nearestGeoDist = -1
            // v10.2: routeBasedDist로 채택된 경우, 이벤트의 "절대" DistFromS도 같이
            // 저장해둠 - guidanceDidUpdateLocation()이 매번 재계산할 때 씀(위 버그 수정 참고)
            var nearestEventDistFromS = -1

            // v5.2: 사용자 요청("2번 경우도 완성") - 카메라형(단일지점)은 SDK에 "남은 거리"
            // getter가 없어서, 안전정보 위치(getPos())와 현재 GPS 위치로 직접 유클리드
            // 거리를 계산함. 카카오 좌표계(DoublePoint.x/y)가 KATEC(미터 단위 평면좌표)일
            // 것으로 추정하고 계산하지만, 100% 확신은 아니라서 - 이번엔 이 값도 아직
            // "미검증"으로 두고 로그로만 남김(구간단속 getRemainDist() 검증 때와 동일한
            // 절차). 다음 로그에서 실제로 접근할수록 이 값이 줄어드는 게 확인되면 그때
            // 신뢰(trusted) 처리. #문제시 원복
            val gpsPos = try {
                KNSDK.sharedGpsManager()?.recentGpsData?.pos
            } catch (e: Exception) { null }

            safetyList?.forEach { item ->
                if (item == null) return@forEach
                // v5.1: [카카오 안전정보 검증용] 로그로 확정된 버그 - getDistFromS()는
                // "경로 시작점부터의 거리"라 주행할수록 계속 증가하기만 했음(사용자 실주행
                // 검증으로 발견: 1015→2169→...→43621처럼 계속 늘어남, 이벤트에 가까워질수록
                // 줄어들어야 하는데 정반대). APK 바이트코드 재조사 결과 KNSafety_Section/
                // KNSafety_SectionSegment엔 진짜 "남은 거리" getRemainDist()가 따로 있었음 -
                // 이걸 우선 시도. getPassed()로 이미 지나친 이벤트는 아예 후보에서 제외. #문제시 원복
                val alreadyPassed = (findGetterBool(item, "Passed")) == true
                if (alreadyPassed) return@forEach
                val remainDist = findGetterInt(item, "RemainDist").takeIf { it > 0 }

                // 카메라형 등 remainDist가 없는 타입은 좌표 기반 직선거리를 후보로 계산
                var geoDist = -1
                if (remainDist == null && gpsPos != null) {
                    try {
                        val evLocation = findGetter(item, "getLocation")
                        val evPos = evLocation?.let { findGetter(it, "getPos") }
                        if (evPos != null) {
                            val ex = findGetterDouble(evPos, "X")
                            val ey = findGetterDouble(evPos, "Y")
                            val gx = findGetterDouble(gpsPos, "X")
                            val gy = findGetterDouble(gpsPos, "Y")
                            val dx = ex - gx
                            val dy = ey - gy
                            geoDist = kotlin.math.sqrt(dx * dx + dy * dy).toInt()
                        }
                    } catch (e: Exception) { /* 무시 - 검증용 계산이라 실패해도 안전 */ }
                }

                val location = findGetter(item, "getLocation")
                val distFromS = location?.let { findGetterInt(it, "DistFromS") } ?: -1

                // v: 지피티 분석 반영(2026-08-08) - getRemainDist()가 없어도, 이벤트의
                // DistFromS(경로 시작점~이벤트 누적거리)에서 현재 위치의 DistFromS(경로
                // 시작점~현재위치 누적거리)를 빼면 실제 남은 거리를 곡선 경로 그대로 정확히
                // 계산할 수 있음 - 직선거리(geoDist)보다 정확하고, 좌표 계산 실패 시에도
                // 안 끊김. 둘 다 유효하고(양수) 뺄셈 결과가 0 이상일 때만 채택, 이것도
                // trusted로 인정. #문제시 원복
                val myDistFromS = KakaoRouteDataRepository.currentDistFromS
                val routeBasedDist = if (distFromS > 0 && myDistFromS > 0) {
                    (distFromS - myDistFromS).takeIf { it >= 0 }
                } else null

                val dist = remainDist ?: routeBasedDist ?: geoDist.takeIf { it > 0 } ?: distFromS
                if (dist in 0 until nearestDist) {
                    nearestDist = dist
                    nearest = item
                    nearestIsTrusted = remainDist != null || routeBasedDist != null
                    nearestGeoDist = geoDist
                    nearestEventDistFromS = if (routeBasedDist != null) distFromS else -1
                }
            }
            if (nearest != null) {
                val codeObj = findGetter(nearest!!, "getCode")
                val codeName = codeObj?.toString()
                val codeValue = codeObj?.let { findGetterInt(it, "Value") } ?: -1
                // 카메라형이면 getSpeedLimit(), 주의구간형이면 getLimit() - 클래스가 다르므로
                // 이름에 맞는 게터를 그때그때 찾음. #문제시 원복
                val speedLimit = findGetterInt(nearest!!, "SpeedLimit").takeIf { it > 0 }
                    ?: findGetterInt(nearest!!, "Limit")
                val sdiType = mapKakaoSafetyCodeToSdiType(codeName)
                KakaoRouteDataRepository.safetyType = sdiType
                KakaoRouteDataRepository.safetySpeedLimit = speedLimit
                KakaoRouteDataRepository.safetyDist = nearestDist
                KakaoRouteDataRepository.safetyDistTrusted = nearestIsTrusted
                KakaoRouteDataRepository.safetyEventDistFromS = nearestEventDistFromS
                if (sdiType == -1 && System.currentTimeMillis() - lastUnmappedSafetyCodeLogTime > 15000L) {
                    lastUnmappedSafetyCodeLogTime = System.currentTimeMillis()
                    NavLogger.d(context, "[카카오 안전정보코드 수집] 미매핑 code=$codeName(value=$codeValue) speedLimit=$speedLimit dist=$nearestDist")
                }
                NavLogger.d(context, "[카카오->openpilot] 안전정보: kakaoCode=$codeName(value=$codeValue) -> nSdiType=$sdiType speedLimit=$speedLimit dist=$nearestDist trusted=$nearestIsTrusted geoDist=$nearestGeoDist")
            } else {
                KakaoRouteDataRepository.safetyType = -1
                KakaoRouteDataRepository.safetyDist = 0
                KakaoRouteDataRepository.safetyDistTrusted = false
                KakaoRouteDataRepository.safetyEventDistFromS = -1
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

    // v4.13: "볼륨 50/60으로 낮춰도 카카오 안내는 항상 100%처럼 들린다"는 제보(사용자 7번) -
    // 지금까지 STREAM_MUSIC만 조절해왔는데, 이 헤드유닛에서 카카오 TTS가 실제로
    // STREAM_MUSIC이 아닌 다른 스트림(STREAM_SYSTEM 등)이나 USAGE_ASSISTANCE_NAVIGATION_
    // GUIDANCE 전용 볼륨 그룹으로 나갈 가능성을 확인하기 위한 진단 로그. 음성이 "실제로
    // 재생되는 그 순간"의 모든 스트림 볼륨 + (API 26+) 활성 재생 세션의 AudioAttributes를
    // 같이 찍어서, 다음 로그로 어떤 스트림이 진짜인지 확정할 수 있게 함. #문제시 원복
    // v4.15: [볼륨진단] 로그로 확인됨 - 카카오 음성 재생 시점에 STREAM_MUSIC=69/150(46%)로
    // 최대가 아닌데도 사용자 체감은 계속 100%. 즉 STREAM_MUSIC을 만지는 건 애초에 방향이
    // 틀렸다는 게 실측으로 확정됨. v3.13때 "카카오 SDK엔 공개 볼륨 API가 없다"고
    // 결론지었던 걸 재검증 - Tmap의 rgData 리플렉션과 같은 방식으로, KNGuidance/KNSDK
    // 객체의 메서드 중 volume/sound/audio가 들어간 게 실제로 있는지 1회 스캔해서 로그로
    // 남김(SDK 버전이 바뀌었을 수도 있어서). #문제시 원복
    // v: 재억 요청(2026-08-22) - 이 스캔은 SDK 버전이 안 바뀌면 결과가 항상 똑같은데,
    // 인스턴스 필드(private var)라서 카카오 안내를 새로 시작할 때마다(델리게이트가
    // 새로 만들어질 때마다) 매번 다시 찍히고 있었음 - 불필요한 로그 낭비. 앱이 켜져
    // 있는 동안(companion object) 딱 1번만 찍히게 함. #문제시 원복
    companion object {
        @Volatile private var audioApiScanDone = false
        // v: 재억 요청(2026-08-28) - "색깔 유도선"(나들목/분기점 도로에 실제로 칠해진
        // 녹색/분홍색과 매칭되는 안내) 기능 조사용. KNLane_LaneInfo에 색상 관련 getter가
        // 있는지 앱 켜있는 동안 1번만 전체 메서드 목록을 스캔해서 남김. #문제시 원복
        @Volatile private var laneInfoApiScanDone = false
    }
    private var lastEtaLogTime = 0L
    private fun scanForVolumeApiOnce(guidance: KNGuidance) {
        if (audioApiScanDone) return
        audioApiScanDone = true
        try {
            NavLogger.e(context, "===== [볼륨API스캔] KNGuidance 메서드 목록 =====")
            for (m in guidance.javaClass.methods) {
                val n = m.name.lowercase()
                if (n.contains("volume") || n.contains("sound") || n.contains("audio") || n.contains("mute")) {
                    NavLogger.e(context, "[볼륨API스캔] KNGuidance.${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
                }
            }
            NavLogger.e(context, "===== [볼륨API스캔] KNSDK 클래스 메서드 목록 =====")
            for (m in KNSDK.javaClass.methods) {
                val n = m.name.lowercase()
                if (n.contains("volume") || n.contains("sound") || n.contains("audio") || n.contains("mute")) {
                    NavLogger.e(context, "[볼륨API스캔] KNSDK.${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
                }
            }
            if (naviView != null) {
                NavLogger.e(context, "===== [볼륨API스캔] naviView 메서드 목록 =====")
                for (m in naviView!!.javaClass.methods) {
                    val n = m.name.lowercase()
                    if (n.contains("volume") || n.contains("sound") || n.contains("audio") || n.contains("mute")) {
                        NavLogger.e(context, "[볼륨API스캔] naviView.${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
                    }
                }
            }
        } catch (e: Exception) {
            NavLogger.e(context, "[볼륨API스캔] 예외: ${e.message}")
        }
    }

    override fun willPlayVoiceGuide(guidance: KNGuidance, voiceGuide: KNGuide_Voice) {
        NavLogger.d(context, "[음성] willPlayVoiceGuide(카카오 음성 재생 시작) ${tmapMuteStateSnapshot()}")
        AudioStreamDiagnostics.log(context, "카카오음성시작")
        // v: 재억 제보(2026-08-23) - 안내음량을 30%로 낮춰놔도 경로 중간에 카카오 음성이
        // 나올 때 갑자기 소리가 커지는 증상. [볼륨진단] 로그로 확인해보니 카카오 음성이
        // 시작되는 순간 정체불명의 두 번째 재생 세션(contentType=0)이 같이 뜨는데, 이건
        // 카카오 SDK 내부에서 지가 알아서 트는 소리라 우리가 직접 볼륨을 지정해서 못 건드림
        // (scanForVolumeApiOnce로 확인해도 KNGuidance/KNSDK/naviView 어디에도 볼륨 관련
        // 공개 API가 없음). 대신 재억이 요청한 대로 "카카오 길안내 음량이 그 값 그대로
        // 나오게" - 음성이 시작되는 바로 그 순간에 우리가 저장해둔 음량%를 시스템 볼륨에
        // 즉시 다시 강제 적용해서, 카카오가 자기 마음대로 볼륨을 건드렸더라도 재생 시작
        // 시점엔 항상 재억이 설정한 값으로 맞춰지게 함. #문제시 원복
        VolumeHelper.applySavedSystemVolume(context)
        scanForVolumeApiOnce(guidance)
        if (!isRouteGuideActive()) return
        naviView?.willPlayVoiceGuide(guidance, voiceGuide)
    }

    override fun didFinishPlayVoiceGuide(guidance: KNGuidance, voiceGuide: KNGuide_Voice) {
        NavLogger.d(context, "[음성] didFinishPlayVoiceGuide(카카오 음성 재생 끝) ${tmapMuteStateSnapshot()}")
        // v: 재억 제보(2026-08-22) - "소리가 들락날락한다"는 원인을 잡으려면 재생 시작
        // 순간뿐 아니라 끝나는 순간의 볼륨도 같이 봐야 비교가 됨(시작=X, 끝=Y처럼).
        // 기존엔 시작 순간만 찍었음. #문제시 원복
        AudioStreamDiagnostics.log(context, "카카오음성끝")
        // v: 재억 제보(2026-08-23) - 음성이 끝난 뒤에도 카카오가 건드려놓은 볼륨이 그대로
        // 남아있을 수 있어서, 끝나는 시점에도 한 번 더 저장된 음량%로 재적용. #문제시 원복
        VolumeHelper.applySavedSystemVolume(context)
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
