package com.tmap.nda

/**
 * 차선 안내(직진/좌회전 등 차선별 방향)와 신호등 잔여시간을, 소스가 Tmap이든
 * Kakao든 상관없이 담아두는 공유 저장소. MapActivity/KakaoNaviActivity 양쪽
 * 레이아웃에 동일한 상단바를 두고 이 값을 그대로 읽게 해서, 화면이 어느 쪽이든
 * 항상 표시되게 함(별도 오버레이 권한 없이). 정확한 SDK 필드명이 아직 확정 전이라
 * 값 자체는 진단 로그로 먼저 확인 중 - 확정되면 실제 파싱 로직을 여기 채움. #문제시 원복
 */
object LaneSignalRepository {
    @Volatile var source: String = ""       // "tmap" | "kakao" | ""(없음)
    @Volatile var lastUpdateTime: Long = 0

    // 차선 안내: 예) ["직진","직진","직진","좌회전"] 왼쪽부터 순서대로
    @Volatile var lanes: List<String> = emptyList()

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

    val hasLanes = LaneSignalRepository.lanes.isNotEmpty()
    val hasCountdown = LaneSignalRepository.trafficLightRemainSec >= 0

    if (!hasLanes && !hasCountdown) {
        bar.visibility = android.view.View.GONE
        return
    }
    bar.visibility = android.view.View.VISIBLE

    laneBoxContainer.removeAllViews()
    LaneSignalRepository.lanes.forEach { laneText ->
        val tv = android.widget.TextView(context).apply {
            text = laneText
            setTextColor(android.graphics.Color.WHITE)
            textSize = 12f
            setPadding(16, 6, 16, 6)
            setBackgroundColor(android.graphics.Color.parseColor("#333333"))
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
