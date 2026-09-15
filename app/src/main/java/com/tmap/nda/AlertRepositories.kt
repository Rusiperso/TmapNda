package com.tmap.nda

/**
 * v: 재억 요청(2026-09-15) - 사고/공사구간 알림, 긴급차량 접근 알림을 자동 팝업으로 추가.
 * 사고/공사는 이미 받고 있던 KNSafetyCode(_TrafficAccidentPos 등) 데이터를 화면에도
 * 보여주는 것뿐이라 새 데이터 연결이 필요 없음. 긴급차량은 C-ITS 채널(KNCits_Emergency)로
 * 오는데, 이 채널은 지금까지 실기기 로그 117건 전부 빈 값이었음(신호등 잔여시간과 동일
 * 채널) - "언젠가 데이터가 오면 그때 바로 뜨게" UI만 먼저 준비해두고, didUpdateCitsGuide에서
 * 실제로 Emergency 타입 항목이 들어오면 바로 반영되게 연결만 해둠. #문제시 원복
 */
object AccidentAlertRepository {
    @Volatile var visible: Boolean = false
    @Volatile var title: String = ""
    @Volatile var distanceM: Int = -1
    @Volatile var lastUpdateTime: Long = 0
    @Volatile var activeRenderer: (() -> Unit)? = null

    fun notifyChanged() {
        try { activeRenderer?.invoke() } catch (e: Exception) { }
    }

    fun update(title: String, distanceM: Int) {
        this.title = title
        this.distanceM = distanceM
        this.visible = true
        this.lastUpdateTime = System.currentTimeMillis()
        notifyChanged()
    }

    fun clear() {
        if (!visible) return
        visible = false
        notifyChanged()
    }

    fun resetIfStale(maxAgeMs: Long = 20000L) {
        if (visible && System.currentTimeMillis() - lastUpdateTime > maxAgeMs) clear()
    }
}

object EmergencyAlertRepository {
    @Volatile var visible: Boolean = false
    @Volatile var message: String = "119차량 접근중"
    @Volatile var lastUpdateTime: Long = 0
    @Volatile var activeRenderer: (() -> Unit)? = null

    fun notifyChanged() {
        try { activeRenderer?.invoke() } catch (e: Exception) { }
    }

    fun update(message: String) {
        this.message = message
        this.visible = true
        this.lastUpdateTime = System.currentTimeMillis()
        notifyChanged()
    }

    fun clear() {
        if (!visible) return
        visible = false
        notifyChanged()
    }

    fun resetIfStale(maxAgeMs: Long = 20000L) {
        if (visible && System.currentTimeMillis() - lastUpdateTime > maxAgeMs) clear()
    }
}

/**
 * MapActivity/KakaoNaviActivity 공통 렌더 함수 - LaneSignalRepository의 renderLaneSignalBar()와
 * 동일한 패턴(ViewBinding 클래스가 달라서 뷰를 직접 파라미터로 받음). 메뉴-설정의 개별
 * 토글(accident_alert_enabled / emergency_alert_enabled)로 표시 여부를 끌 수 있게 함. #문제시 원복
 */
fun renderAlertBanners(
    context: android.content.Context,
    accidentBanner: android.view.View?,
    accidentText: android.widget.TextView?,
    emergencyBanner: android.view.View?,
    emergencyText: android.widget.TextView?
) {
    val prefs = context.getSharedPreferences("TmapNdaPrefs", android.content.Context.MODE_PRIVATE)
    AccidentAlertRepository.resetIfStale()
    EmergencyAlertRepository.resetIfStale()

    val accidentEnabled = prefs.getBoolean("accident_alert_enabled", true)
    if (accidentBanner != null && accidentText != null) {
        if (accidentEnabled && AccidentAlertRepository.visible) {
            val distText = if (AccidentAlertRepository.distanceM in 0..9999) "${AccidentAlertRepository.distanceM}m 앞" else ""
            accidentText.text = if (distText.isNotEmpty()) "${AccidentAlertRepository.title} · $distText" else AccidentAlertRepository.title
            accidentBanner.visibility = android.view.View.VISIBLE
        } else {
            accidentBanner.visibility = android.view.View.GONE
        }
    }

    val emergencyEnabled = prefs.getBoolean("emergency_alert_enabled", true)
    if (emergencyBanner != null && emergencyText != null) {
        if (emergencyEnabled && EmergencyAlertRepository.visible) {
            emergencyText.text = EmergencyAlertRepository.message
            emergencyBanner.visibility = android.view.View.VISIBLE
        } else {
            emergencyBanner.visibility = android.view.View.GONE
        }
    }
}
