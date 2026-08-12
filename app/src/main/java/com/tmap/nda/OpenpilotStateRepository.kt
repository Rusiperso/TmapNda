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
    // v: 사용자 요청(2026-08-12) - "다들 유선으로 Android Auto 쓰는 거 아니냐"는 질문에
    // 추측 대신 실제 로그로 확인하기 위해 추가. androidx.car.app.connection.CarConnection로
    // 감지 가능한 값: 0=연결안됨, 1=네이티브(차량 자체 안드로이드, 헤드유닛에 직접 설치된
    // 우리 케이스), 2=프로젝션(진짜 Android Auto로 폰이 차에 투사되는 경우, 유선/무선
    // 둘 다 이 값으로 잡힘 - AA 자체는 유선/무선을 구분해서 알려주는 공개 API가 없음).
    // #문제시 원복
    @Volatile var carConnectionType: Int = -1
    @Volatile var carConnectionTypeLabel: String = "미확인"
    private val _state = MutableLiveData(OpenpilotState())
    val state: LiveData<OpenpilotState> get() = _state

    private val _ndaGpsState = MutableLiveData(NdaGpsState())
    val ndaGpsState: LiveData<NdaGpsState> get() = _ndaGpsState

    // v: 사용자 제보(2026-08-08) - 옛날 프로토콜(NDA/2899)만 지원하는 openpilot 포크를 쓰는
    // 사용자는 실제로 NDA 채널로 잘 연결돼있는데도, 화면의 "콤마 연결됨/대기중" 표시가
    // 메인 채널(7705, carrotMan 프로토콜)만 보고 판단해서 계속 "대기중"으로만 떴음.
    // NDA 채널 연결 여부도 별도로 노출해서, 화면 쪽에서 "둘 중 하나라도 연결되면 연결됨"으로
    // 판단할 수 있게 함. #문제시 원복
    private val _ndaConnected = MutableLiveData(false)
    val ndaConnected: LiveData<Boolean> get() = _ndaConnected

    fun updateNdaConnected(connected: Boolean) {
        if (_ndaConnected.value != connected) {
            _ndaConnected.postValue(connected)
        }
    }

    // v4.23: displayActive 안정화용 상태. #문제시 원복
    // v: 실사용 로그로 확인됨(2026-08-08) - 12분 동안 active가 397번 바뀌고 그중 94%가
    // 1초 이내 재전환. 크루즈를 직접 몇 번 껐다 켠 것도 있었지만, 그것만으론 설명 안 되는
    // 훨씬 잦은 잔물결이 같이 섞여있었음.
    // v: 사용자 재지적(2026-08-10) - 위 이유로 유지시간을 1초->2.5초로 늘렸었는데, 오히려
    // 역효과였음. 신호가 1초 미만 간격으로 계속 흔들리는 구간에서는 2.5초짜리 "안 흔들리는
    // 창"을 아예 못 채워서 stableDisplayActive가 영영 못 바뀌고, 그 사이 "불안정" 판정은
    // 여전히 쉽게 걸려서 오히려 "불안정"에 갇혀있는 시간이 더 길어짐 - "UI 변경 전엔
    // ON/OFF가 그래도 자주 바뀌었는데 지금은 계속 불안정으로만 뜬다"는 정확한 지적이었음.
    // 1초로 되돌림. #문제시 원복
    private var pendingActive: Boolean = false
    private var pendingActiveSince: Long = 0L
    private var stableDisplayActive: Boolean = false
    private val recentTransitionTimes = ArrayDeque<Long>()
    private const val STABLE_HOLD_MS = 1000L
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
        }
        // v: 사용자 제보(2026-08-12) - 전환이 몰려서 "불안정"이 한 번 뜨면, 그 뒤로 더 이상
        // 전환이 안 일어나도(=실제로는 안정된 상태인데도) 화면엔 "불안정"이 계속 남아있는
        // 버그가 있었음. 원인은 recentTransitionTimes의 오래된 타임스탬프 정리(prune)가
        // "새 전환이 들어올 때만" 실행돼서, 다음 전환이 한참 뒤에 와야만 큐가 비워졌기
        // 때문. 전환 여부와 무관하게 매 업데이트마다(=매 UDP 수신마다) 3초 지난 타임스탬프를
        // 지우도록 밖으로 빼서, 실제로 흔들림이 멈추면 최대 FLICKER_WINDOW_MS 안에 "불안정"
        // 표시가 자동으로 풀리게 함. On/Off 판정(pendingActive/STABLE_HOLD_MS)에는 영향 없음. #문제시 원복
        while (recentTransitionTimes.isNotEmpty() && now - recentTransitionTimes.first() > FLICKER_WINDOW_MS) {
            recentTransitionTimes.removeFirst()
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
