package com.tmap.nda

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

data class OpenpilotState(
    val carrot2: String = "",
    val ip: String = "",
    val trafficState: Int = 0,
    val xState: Int = 0,
    val active: Boolean = false,
    // v4.13: "연결 대기에서 변화가 없다"(사용자 6번)는 것과 "실제로 정말 안 붙었다"를
    // 화면에서 구분할 수 있게, 이 상태가 갱신된 시각을 같이 들고 있음. UI에서 마지막
    // 수신 이후 경과 시간을 표시하면, 최소한 앱이 멈춘 게 아니라 계속 재시도 중이라는 걸
    // 눈으로 확인 가능함. #문제시 원복
    val lastUpdateTime: Long = 0L,
    // v4.23: 실주행 로그로 확인됨 - openpilot이 보내는 active 값이 진짜로 초당 2회씩
    // true/false를 반복함(사용자 지적: OP ON/OFF 반복 점멸). openpilot 쪽 자체 발신
    // 데이터가 그런 것으로 보여서(같은 주기로 몇 시간 내내 지속) TmapNda 버그가 아니라
    // 판단되지만, 화면이 초당 2번씩 깜빡이면 보기에 너무 산만해서 표시용으로만 안정화된
    // 값을 별도로 둠 - 원본 active는 그대로 보존(다른 용도로 필요할 수 있으니 안 건드림). #문제시 원복
    val displayActive: Boolean = false,
    val isFlickering: Boolean = false
)

/** NDA(EON:ROAD_LIMIT_SERVICE:v1) 브릿지를 통해 openpilot으로부터 역수신한 GPS 위치. */
data class NdaGpsState(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0,
    val speed: Double = 0.0,
    val bearing: Double = 0.0,
    val accuracy: Double = 0.0,
    val timestampMs: Long = 0L,
    val hasFix: Boolean = false
)

object OpenpilotStateRepository {
    private val _state = MutableLiveData(OpenpilotState())
    val state: LiveData<OpenpilotState> get() = _state

    private val _ndaGpsState = MutableLiveData(NdaGpsState())
    val ndaGpsState: LiveData<NdaGpsState> get() = _ndaGpsState

    // v4.23: displayActive 안정화용 상태. #문제시 원복
    // v: 실사용 로그로 확인됨(2026-08-08) - 12분 동안 active가 397번 바뀌고 그중 94%가
    // 1초 이내 재전환. 크루즈를 직접 몇 번 껐다 켠 것도 있었지만, 그것만으론 설명 안 되는
    // 훨씬 잦은 잔물결이 같이 섞여있었음. 원인 자체(openpilot이 보내는 controls_active
    // 원본값)는 우리 쪽에서 못 건드리므로, 화면 표시용 안정화 유지시간을 1초→2.5초로
    // 늘려서 짧은 잔물결에는 화면 텍스트가 덜 흔들리게 함. #문제시 원복
    private var pendingActive: Boolean = false
    private var pendingActiveSince: Long = 0L
    private var stableDisplayActive: Boolean = false
    private val recentTransitionTimes = ArrayDeque<Long>()
    private const val STABLE_HOLD_MS = 2500L
    private const val FLICKER_WINDOW_MS = 3000L
    private const val FLICKER_THRESHOLD = 4

    fun updateState(
        carrot2: String,
        ip: String,
        trafficState: Int,
        xState: Int,
        active: Boolean
    ) {
        val now = System.currentTimeMillis()

        if (active != pendingActive) {
            pendingActive = active
            pendingActiveSince = now
            recentTransitionTimes.addLast(now)
            while (recentTransitionTimes.isNotEmpty() && now - recentTransitionTimes.first() > FLICKER_WINDOW_MS) {
                recentTransitionTimes.removeFirst()
            }
        }
        // 새 값이 STABLE_HOLD_MS 이상 유지됐을 때만 화면표시용 값을 실제로 갱신
        if (pendingActive != stableDisplayActive && now - pendingActiveSince >= STABLE_HOLD_MS) {
            stableDisplayActive = pendingActive
        }
        val flickering = recentTransitionTimes.size >= FLICKER_THRESHOLD

        _state.postValue(OpenpilotState(carrot2, ip, trafficState, xState, active, now, stableDisplayActive, flickering))
    }

    fun updateNdaGps(
        latitude: Double,
        longitude: Double,
        altitude: Double,
        speed: Double,
        bearing: Double,
        accuracy: Double,
        timestampMs: Long
    ) {
        _ndaGpsState.postValue(
            NdaGpsState(latitude, longitude, altitude, speed, bearing, accuracy, timestampMs, hasFix = true)
        )
    }
}
