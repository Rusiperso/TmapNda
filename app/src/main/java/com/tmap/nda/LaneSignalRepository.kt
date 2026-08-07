package com.tmap.nda

/**
 * 차선 안내(직진/좌회전 등 차선별 방향)와 신호등 잔여시간을, 소스가 Tmap이든
 * Kakao든 상관없이 담아두는 공유 저장소. MapActivity/KakaoNaviActivity 양쪽
 * 레이아웃에 동일한 상단바를 두고 이 값을 그대로 읽게 해서, 화면이 어느 쪽이든
 * 항상 표시되게 함(별도 오버레이 권한 없이).
 *
 * v4.11: 실제 로그로 KNLane_LaneInfo의 진짜 필드를 확인함 - getHighlightType이
 * "이 경로로 가려면 이 차선을 타야 한다"는 추천 차선 여부를 정확히 알려줌
 * (2=추천, 0=아님, 실제 로그에서 경로상 필요한 차선만 2로 찍힘). getTurnType(방향
 * 코드: 2/8/10/40 등)은 정확한 코드→화살표 매핑표를 못 구해서, 잘못된 화살표를
 * 보여줄 위험을 피하려고 우선 "추천 차선인지 아닌지"만 표시. #문제시 원복
 */
/**
 * TmapNda 자체 빌드 APK를 바이트코드로 까봐서 KNLane_LaneInfo에 getBusType()도
 * 실제로 존재하는 걸 확인함(사용자 10번: 버스전용차로 표시 가능?) - 근데 어떤 byte
 * 값이 "버스전용차로"를 뜻하는지는 아직 실주행 로그로 확정 못 함. 안전 문제가 되는
 * 회전방향(getTurnType)과 달리 버스차로 표시는 틀려도 위험하지 않아서, "0이 아니면
 * 버스전용차로"로 우선 표시하고 실제 raw 값을 로그로 남겨서 다음 세션에 검증. #문제시 원복
 */
data class LaneDisplayInfo(val recommended: Boolean, val busType: Int = 0)

object LaneSignalRepository {
    @Volatile var source: String = ""       // "tmap" | "kakao" | ""(없음)
    @Volatile var lastUpdateTime: Long = 0

    // 차선 안내: 차선 개수만큼 - recommended면 "이 경로엔 이 차선을 타야 함"(추천 차선),
    // busType!=0이면 버스전용차로로 추정
    @Volatile var lanes: List<LaneDisplayInfo> = emptyList()

    // v5.4: "차선 화살표(좌회전/우회전/직진/유턴/대각선)를 제대로 표시해달라"(사용자) -
    // getTurnType() 바이트값을 추측해서 매핑표를 만드는 대신, 카카오 SDK가 이미 만들어둔
    // 공식 렌더링 컴포넌트(KNDriveLaneView)를 그대로 씀. APK 바이트코드로 확인: setLane()
    // 하나만 호출하면 추천차선/버스차로/회전방향 화살표까지 카카오가 알아서 정확하게
    // 그려줌 - 우리가 방향 코드를 해석할 필요가 아예 없어짐. 원본 KNLane 객체를 그대로
    // 들고 있다가 화면 쪽에서 이 View에 넘겨줌. 타입을 Any로 둔 이유: KNDriveLaneView
    // 직접 타입 사용 시 빌드가 실패해서(internal 가시성 의심, 이 파일에 이미 있던 전례와
    // 동일 패턴) 리플렉션으로 우회했는데, 혹시 KNLane 타입 자체도 이 파일(다른 모듈)에서
    // 프로퍼티 타입으로 직접 쓰면 문제가 될 수 있어 안전하게 Any로 보관. #문제시 원복
    @Volatile var kakaoLane: Any? = null

    // 신호등 잔여시간(초), 색상("RED"/"GREEN"/"YELLOW"/"")
    @Volatile var trafficLightRemainSec: Int = -1
    @Volatile var trafficLightColor: String = ""

    // v4.11: 카카오 안내 콜백이 매 초마다 오는 게 아니라 상황 바뀔 때만 오는 경우가
    // 있어서, 5초로는 너무 짧아서 실제로 유효한 차선인데도 금방 사라져 보였음(사용자
    // 지적: "잠깐 나왔다 사라진다"). 15초로 늘림. #문제시 원복
    fun resetIfStale(maxAgeMs: Long = 15000L) {
        if (System.currentTimeMillis() - lastUpdateTime > maxAgeMs) {
            lanes = emptyList()
            kakaoLane = null
            trafficLightRemainSec = -1
            trafficLightColor = ""
            source = ""
        }
    }

    fun isFresh(maxAgeMs: Long = 15000L) = (System.currentTimeMillis() - lastUpdateTime) < maxAgeMs
}

/**
 * MapActivity/KakaoNaviActivity 양쪽에서 공통으로 쓰는 렌더링 함수. 두 Activity의
 * ViewBinding 클래스가 달라서 뷰를 직접 파라미터로 받음. #문제시 원복
 */
fun renderLaneSignalBar(
    context: android.content.Context,
    bar: android.widget.LinearLayout?,
    laneBoxContainer: android.widget.LinearLayout?,
    countdownText: android.widget.TextView?,
    screenName: String
) {
    // v4.17: "설정에서 항상표시로 했는데 15초 후 사라진다"(사용자 지적) - lane_overlay_enabled는
    // 지금까지 "위젯을 보여줄지 말지"만 결정했고, resetIfStale()의 15초 데이터 소멸 로직은
    // 이 설정과 무관하게 항상 실행되고 있었음. "항상표시"를 켰다는 건 "새 데이터가 올 때까지
    // 마지막 값을 계속 보여달라"는 뜻으로 해석해서, 오버레이가 켜져 있을 땐 훨씬 긴 시간
    // (2분)으로 완화 - 실제 주행 중 카카오 콜백 텀 정도로는 안 사라지되, 길안내가 완전히
    // 끝난 뒤에는 결국 정리되게 함(무한정 옛날 값이 남는 것 방지). #문제시 원복
    // v5.4: 처음에 "데이터 출처(LaneSignalRepository.source)"로 잘못 게이팅했었음.
    // 이 기능의 원래 취지는 "티맵 화면엔 차선정보가 없으니 카카오가 만든 차선정보를
    // 티맵 화면 위에도 오버레이로 띄우자"였음 - 즉 데이터가 어디서 왔는지가 아니라
    // "지금 어느 화면을 보고 있는지"로 켜고 꺼야 함. screenName("tmap"/"kakao")은
    // 실제로 이 함수를 부르는 화면(MapActivity/KakaoNaviActivity)을 나타내고, 데이터
    // 자체는 거의 항상 카카오산이어도 티맵 화면에서 그대로 보여줌. #문제시 원복
    val prefs = context.getSharedPreferences("TmapNdaPrefs", android.content.Context.MODE_PRIVATE)
    val overlayEnabled = when (screenName) {
        "tmap" -> prefs.getBoolean("lane_overlay_tmap_enabled", true)
        "kakao" -> prefs.getBoolean("lane_overlay_kakao_enabled", true)
        else -> false
    }
    if (!overlayEnabled) {
        LaneSignalRepository.resetIfStale()
        bar?.visibility = android.view.View.GONE
        return
    }
    LaneSignalRepository.resetIfStale(maxAgeMs = 120000L)
    if (bar == null || laneBoxContainer == null || countdownText == null) return

    val kakaoLane = LaneSignalRepository.kakaoLane
    val hasLanes = kakaoLane != null || LaneSignalRepository.lanes.isNotEmpty()
    val hasCountdown = LaneSignalRepository.trafficLightRemainSec >= 0

    if (!hasLanes && !hasCountdown) {
        bar.visibility = android.view.View.GONE
        return
    }
    bar.visibility = android.view.View.VISIBLE

    if (kakaoLane != null) {
        // v5.4: 카카오 공식 컴포넌트로 그리기 - 추천차선/버스차로/회전화살표까지 전부
        // 카카오가 알아서 정확하게 렌더링. 직접 타입으로 썼다가 빌드가 실패함(exit code 1,
        // 로그 원문은 Azure blob 도메인이 네트워크 허용목록 밖이라 못 봄) - 이 코드베이스에
        // 이미 있던 전례(linkIdx가 internal이라 컴파일 실패했던 것)와 같은 종류일 가능성이
        // 높아서, KNDriveLaneView 자체도 리플렉션으로 안전하게 우회. #문제시 원복
        try {
            val existing = laneBoxContainer.getChildAt(0)
            val driveLaneView: android.view.View
            if (existing != null && existing.javaClass.name == "com.kakaomobility.knsdk.ui.component.KNDriveLaneView") {
                driveLaneView = existing
            } else {
                laneBoxContainer.removeAllViews()
                val cls = Class.forName("com.kakaomobility.knsdk.ui.component.KNDriveLaneView")
                val ctor = cls.getConstructor(android.content.Context::class.java)
                driveLaneView = ctor.newInstance(context) as android.view.View
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                laneBoxContainer.addView(driveLaneView, lp)
            }
            val laneClass = Class.forName("com.kakaomobility.knsdk.guidance.knguidance.routeguide.objects.KNLane")
            val setLaneMethod = driveLaneView.javaClass.methods.firstOrNull { it.name == "setLane" && it.parameterTypes.size == 1 && it.parameterTypes[0].isAssignableFrom(laneClass) }
            setLaneMethod?.invoke(driveLaneView, kakaoLane)
            laneBoxContainer.visibility = android.view.View.VISIBLE
        } catch (e: Exception) {
            NavLogger.e(context, "[차선정보] KNDriveLaneView 렌더링 예외: ${e.javaClass.simpleName}: ${e.message} - 예전 방식으로 폴백")
            renderLaneBoxesFallback(context, laneBoxContainer)
        }
    } else {
        renderLaneBoxesFallback(context, laneBoxContainer)
    }

    if (hasCountdown) {
        countdownText.text = "${LaneSignalRepository.trafficLightRemainSec}s"
        countdownText.setTextColor(
            when (LaneSignalRepository.trafficLightColor) {
                "RED" -> android.graphics.Color.parseColor("#FF5252")
                "GREEN" -> android.graphics.Color.parseColor("#4CAF50")
                "YELLOW" -> android.graphics.Color.parseColor("#FFC107")
                else -> android.graphics.Color.WHITE
            }
        )
        countdownText.visibility = android.view.View.VISIBLE
    } else {
        countdownText.visibility = android.view.View.GONE
    }
}

// v5.4: KNDriveLaneView를 못 쓰는 경우(Tmap 엔진 자체 데이터 등 KNLane 객체가 없는 경우)를
// 위한 예전 방식 - 추천/버스차로만 박스로 표시, 방향 화살표는 여전히 없음. #문제시 원복
private fun renderLaneBoxesFallback(context: android.content.Context, laneBoxContainer: android.widget.LinearLayout) {
    val hasLanes = LaneSignalRepository.lanes.isNotEmpty()
    laneBoxContainer.removeAllViews()
    LaneSignalRepository.lanes.forEach { info ->
        val tv = android.widget.TextView(context).apply {
            text = when {
                info.busType != 0 -> "B"
                info.recommended -> "▲"
                else -> "|"
            }
            setTextColor(
                when {
                    info.busType != 0 -> android.graphics.Color.parseColor("#42A5F5")
                    info.recommended -> android.graphics.Color.parseColor("#4CAF50")
                    else -> android.graphics.Color.parseColor("#888888")
                }
            )
            textSize = 22f
            gravity = android.view.Gravity.CENTER
            setPadding(20, 6, 20, 6)
            minWidth = 72
            setBackgroundColor(
                when {
                    info.busType != 0 -> android.graphics.Color.parseColor("#1A3A5C")
                    info.recommended -> android.graphics.Color.parseColor("#1B4D2C")
                    else -> android.graphics.Color.parseColor("#333333")
                }
            )
        }
        val lp = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.marginEnd = 8
        laneBoxContainer.addView(tv, lp)
    }
    laneBoxContainer.visibility = if (hasLanes) android.view.View.VISIBLE else android.view.View.GONE
}
