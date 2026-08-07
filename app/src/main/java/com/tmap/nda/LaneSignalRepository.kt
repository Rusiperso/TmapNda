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

    // 신호등 잔여시간(초), 색상("RED"/"GREEN"/"YELLOW"/"")
    @Volatile var trafficLightRemainSec: Int = -1
    @Volatile var trafficLightColor: String = ""

    // v4.11: 카카오 안내 콜백이 매 초마다 오는 게 아니라 상황 바뀔 때만 오는 경우가
    // 있어서, 5초로는 너무 짧아서 실제로 유효한 차선인데도 금방 사라져 보였음(사용자
    // 지적: "잠깐 나왔다 사라진다"). 15초로 늘림. #문제시 원복
    fun resetIfStale(maxAgeMs: Long = 15000L) {
        if (System.currentTimeMillis() - lastUpdateTime > maxAgeMs) {
            lanes = emptyList()
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
    countdownText: android.widget.TextView?
) {
    // v4.17: "설정에서 항상표시로 했는데 15초 후 사라진다"(사용자 지적) - lane_overlay_enabled는
    // 지금까지 "위젯을 보여줄지 말지"만 결정했고, resetIfStale()의 15초 데이터 소멸 로직은
    // 이 설정과 무관하게 항상 실행되고 있었음. "항상표시"를 켰다는 건 "새 데이터가 올 때까지
    // 마지막 값을 계속 보여달라"는 뜻으로 해석해서, 오버레이가 켜져 있을 땐 훨씬 긴 시간
    // (2분)으로 완화 - 실제 주행 중 카카오 콜백 텀 정도로는 안 사라지되, 길안내가 완전히
    // 끝난 뒤에는 결국 정리되게 함(무한정 옛날 값이 남는 것 방지). #문제시 원복
    val overlayEnabled = context.getSharedPreferences("TmapNdaPrefs", android.content.Context.MODE_PRIVATE)
        .getBoolean("lane_overlay_enabled", true)
    if (!overlayEnabled) {
        LaneSignalRepository.resetIfStale()
        bar?.visibility = android.view.View.GONE
        return
    }
    LaneSignalRepository.resetIfStale(maxAgeMs = 120000L)
    if (bar == null || laneBoxContainer == null || countdownText == null) return

    val hasLanes = LaneSignalRepository.lanes.isNotEmpty()
    val hasCountdown = LaneSignalRepository.trafficLightRemainSec >= 0

    if (!hasLanes && !hasCountdown) {
        bar.visibility = android.view.View.GONE
        return
    }
    bar.visibility = android.view.View.VISIBLE

    laneBoxContainer.removeAllViews()
    LaneSignalRepository.lanes.forEach { info ->
        val tv = android.widget.TextView(context).apply {
            // v4.23: 버스전용차로(busType!=0)는 "B"로 구분 표시(사용자 10번). #문제시 원복
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
            textSize = 28f
            gravity = android.view.Gravity.CENTER
            setPadding(20, 12, 20, 12)
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
