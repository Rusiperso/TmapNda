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
object LaneSignalRepository {
    @Volatile var source: String = ""       // "tmap" | "kakao" | ""(없음)
    @Volatile var lastUpdateTime: Long = 0

    // 차선 안내: 차선 개수만큼 true/false - true면 "이 경로엔 이 차선을 타야 함"(추천 차선)
    @Volatile var lanes: List<Boolean> = emptyList()

    // 신호등 잔여시간(초), 색상("RED"/"GREEN"/"YELLOW"/"")
    @Volatile var trafficLightRemainSec: Int = -1
    @Volatile var trafficLightColor: String = ""

    fun resetIfStale(maxAgeMs: Long = 5000L) {
        if (System.currentTimeMillis() - lastUpdateTime > maxAgeMs) {
            lanes = emptyList()
            trafficLightRemainSec = -1
            trafficLightColor = ""
            source = ""
        }
    }

    fun isFresh(maxAgeMs: Long = 5000L) = (System.currentTimeMillis() - lastUpdateTime) < maxAgeMs
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
    LaneSignalRepository.resetIfStale()
    if (bar == null || laneBoxContainer == null || countdownText == null) return

    // v4.13: 설정에서 끄면 오버레이 자체를 안 띄움 (사용자 요청 3번). #문제시 원복
    val overlayEnabled = context.getSharedPreferences("TmapNdaPrefs", android.content.Context.MODE_PRIVATE)
        .getBoolean("lane_overlay_enabled", true)
    if (!overlayEnabled) {
        bar.visibility = android.view.View.GONE
        return
    }

    val hasLanes = LaneSignalRepository.lanes.isNotEmpty()
    val hasCountdown = LaneSignalRepository.trafficLightRemainSec >= 0

    if (!hasLanes && !hasCountdown) {
        bar.visibility = android.view.View.GONE
        return
    }
    bar.visibility = android.view.View.VISIBLE

    laneBoxContainer.removeAllViews()
    LaneSignalRepository.lanes.forEach { isRecommended ->
        val tv = android.widget.TextView(context).apply {
            text = if (isRecommended) "▲" else "|"
            setTextColor(if (isRecommended) android.graphics.Color.parseColor("#4CAF50") else android.graphics.Color.parseColor("#888888"))
            textSize = 14f
            setPadding(14, 4, 14, 4)
            setBackgroundColor(
                if (isRecommended) android.graphics.Color.parseColor("#1B4D2C") else android.graphics.Color.parseColor("#333333")
            )
        }
        val lp = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.marginEnd = 4
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
