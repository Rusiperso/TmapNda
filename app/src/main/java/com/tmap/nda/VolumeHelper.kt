package com.tmap.nda

import android.content.Context
import android.media.AudioManager
import com.tmapmobility.tmap.tmapsdk.ui.util.TmapUISDK

/**
 * 지금까지 티맵 음소거 해제 시 무조건 TmapUISDK.setVolume(context, 100)을 호출해서,
 * 사용자가 하드웨어 볼륨 버튼 등으로 직접 맞춰둔 값(예: 50)을 매번 100으로 덮어써버렸음
 * (사용자: "볼륨을 50으로 했는데 다음에 실행하면 다시 100이 됨"). 음소거 직전에 실제
 * 시스템 음악 스트림 볼륨을 캡처해뒀다가, 음소거 해제 시 그 값으로 복원하도록 함.
 * #문제시 원복
 */
object VolumeHelper {
    private const val PREF_KEY = "last_music_volume_percent"

    // v14.1: 재억 지적 - 설정 화면에서 슬라이더로 40%를 골라 "지금 음량으로 저장"을 눌러도
    // 다시 예전 값(예: 46%)으로 되돌아가는 문제. 원인 - saveExplicitVolumePercent()가
    // applySavedSystemVolume()으로 시스템 음량을 실제로 바꾸는데, 이때 안드로이드가 보내는
    // "음량이 바뀌었다"는 신호를 아래 volumeChangeReceiver(MapActivity)가 그대로 받아서
    // "지금 시스템 음량"을 다시 저장해버림 - 그 신호가 타이밍상 살짝 늦게(옛날 값 기준으로)
    // 들어오면 방금 저장한 40%가 옛날 46%로 도로 덮어써짐. 저장 직후 짧은 시간(1.5초) 동안은
    // 이 자동 재저장을 무시하도록 보호장치를 둠. #문제시 원복
    @Volatile
    private var ignoreCaptureUntilMillis: Long = 0L

    fun captureCurrentVolumePercent(context: Context) {
        if (System.currentTimeMillis() < ignoreCaptureUntilMillis) {
            return
        }
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max > 0 && current > 0) {
                val percent = (current * 100) / max
                context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                    .edit().putInt(PREF_KEY, percent).apply()
            }
        } catch (e: Exception) {
            NavLogger.e(context, "VolumeHelper 캡처 예외: ${e.message}")
        }
    }

    fun savedVolumePercent(context: Context): Int {
        return context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
            .getInt(PREF_KEY, 100)
    }

    // v13.8: 재억 요청 - 설정 화면에서 슬라이더로 직접 고른 값을 그대로 저장. 기존
    // captureCurrentVolumePercent()는 "지금 시스템이 들고 있는 실제 볼륨"을 읽어와서
    // 저장하는 함수라, 사용자가 슬라이더로 원하는 값을 직접 고르는 이번 기능과는 안 맞음
    // (그래서 함수를 따로 둠). 저장과 동시에 티맵/시스템 볼륨에도 바로 반영해서 설정
      // 화면에서 바로 소리로 확인 가능하게 함. #문제시 원복
    private val reapplyHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // v14.8: 재억 지적 - 슬라이더로 값 고르고 "지금 음량으로 저장"을 눌러도, 잠시 뒤(1.5초
    // 보호 시간이 끝난 뒤) 카카오/티맵 SDK 쪽에서 자기 나름대로 시스템 볼륨을 다시 건드리는
    // 경우가 있고, 그 변경이 volumeChangeReceiver에 자동 캡처되어 방금 저장한 값을 도로
    // 덮어써버림(예: 30%로 저장했는데 나중에 열어보면 예전 값 48%로 돌아가 있음). 한 번만
    // 보호하고 끝내는 대신, 저장 직후 몇 초에 걸쳐 여러 번 다시 확인해서 재억이 고른 값으로
    // 계속 고정시킴. #문제시 원복
    private val reapplyDelaysMillis = longArrayOf(500L, 1500L, 3000L, 5000L)

    fun saveExplicitVolumePercent(context: Context, percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        val appContext = context.applicationContext
        reapplyHandler.removeCallbacksAndMessages(null)
        // v14.1: 시스템 볼륨을 바꾸기 직전에 보호 시간을 먼저 걸어둠 - applySavedSystemVolume()이
        // 발생시키는 시스템 신호가 이 보호 시간 안에 들어오면 무시됨. #문제시 원복
        ignoreCaptureUntilMillis = System.currentTimeMillis() + 5500L
        appContext.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
            .edit().putInt(PREF_KEY, clamped).apply()
        try {
            TmapUISDK.setVolume(appContext, clamped)
        } catch (e: Exception) {
            NavLogger.e(appContext, "VolumeHelper 티맵볼륨 즉시적용 예외: ${e.message}")
        }
        applySavedSystemVolume(appContext)
        // v14.8: 500ms/1.5초/3초/5초 뒤에 다시 한 번씩 확인해서, 저장된 값(clamped)이랑
        // 실제 시스템 볼륨이 어긋나 있으면 재억이 고른 값으로 다시 맞춰줌. #문제시 원복
        for (delay in reapplyDelaysMillis) {
            reapplyHandler.postDelayed({
                appContext.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                    .edit().putInt(PREF_KEY, clamped).apply()
                applySavedSystemVolume(appContext)
            }, delay)
        }
    }

    // v3.13: 카카오 SDK는 자체 음성안내 볼륨을 조절하는 공개 API가 없어서(문서에도 없음),
    // TmapUISDK.setVolume()로는 카카오 음성 자체를 못 건드림. 대신 안드로이드 시스템
    // 음악 스트림 볼륨 자체를 직접 마지막 저장값으로 맞춰버림 - 카카오 SDK 내부가 뭘
    // 하든 결국 이 시스템 볼륨을 그대로 따라가서 확실하게 먹힘 (사용자: "50, 60으로
    // 조정해도 다음 안내 할 때 또 100프로"). #문제시 원복
    fun applySavedSystemVolume(context: Context) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val percent = savedVolumePercent(context)
            val target = ((percent * max) / 100).coerceIn(0, max)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        } catch (e: Exception) {
            NavLogger.e(context, "VolumeHelper 시스템볼륨 적용 예외: ${e.message}")
        }
    }

    // ===== 길안내 음량(= 음성 안내만) - 미디어(음악) 음량과 완전히 분리 =====
    //
    // v: 재억 요청(2026-09-02, 실기기 로그로 원인 확정) - "볼륨 버튼으로 카카오 길안내
    // 음량만 바꾸고 싶다".
    //
    // 로그로 확인된 사실:
    //   17:17:30~41 (앱/볼륨버튼으로 조절) 저장값 33->100->46%, 기기볼륨도 똑같이 따라감,
    //                                     카카오 안내음량은 0.33에 멈춰서 안 따라옴
    //   17:17:45~49 (카카오 메뉴로 조절)   기기볼륨 46% 고정, 카카오 안내음량만 0.7->0.2->0.9
    // 즉 "미디어 음량"과 "카카오 안내 음량"은 완전히 별개이고, 실제로 들리는 크기는 둘의
    // 곱이었음(미디어 46% x 안내 0.5 = 체감 23%). 그래서 앱에서 아무리 키워도 카카오 쪽이
    // 낮으면 계속 작게 들렸던 것.
    //
    // 이제 물리 볼륨버튼은 미디어(STREAM_MUSIC)를 건드리지 않고 길안내 음량만 조절함.
    // 음악 볼륨은 재억이 다른 앱/화면에서 따로 조절(재억 선택: A안).
    // #문제시 원복: adjustGuideVolumeByHardwareKey를 이 파일 히스토리의 예전 버전
    // (STREAM_MUSIC을 직접 조절하던 것)으로 되돌리면 됨.
    private const val PREF_KEY_GUIDE_VOLUME = "guide_volume_percent"
    private const val GUIDE_VOLUME_STEP = 5

    /** 카카오 화면이 살아있는 동안 등록해두는 적용 함수(naviView.sndVolume 설정). */
    @Volatile
    var kakaoGuideVolumeApplier: ((Float) -> Unit)? = null

    fun guideVolumePercent(context: Context): Int =
        context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
            .getInt(PREF_KEY_GUIDE_VOLUME, 100)
            .coerceIn(0, 100)

    /**
     * 길안내 음량 저장 + 즉시 적용(카카오 음성 + 티맵 음성).
     * 미디어(음악) 음량은 건드리지 않음.
     */
    fun setGuideVolumePercent(context: Context, percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
            .edit().putInt(PREF_KEY_GUIDE_VOLUME, clamped).apply()
        applyGuideVolume(context)
    }

    fun applyGuideVolume(context: Context) {
        val percent = guideVolumePercent(context)
        // 카카오 안내 음성(0.0~1.0)
        try {
            kakaoGuideVolumeApplier?.invoke(percent / 100f)
        } catch (e: Exception) {
            NavLogger.flushTrace(context, "voice", "[안내음량] 카카오 적용 예외: ${e.message}")
        }
        // 티맵 안내 음성(0~100). 티맵 SDK 자체 음성 볼륨이라 미디어 음량과는 별개.
        //
        // v: 재억 제보(2026-09-02) - "카카오 길안내 중에 티맵 음성이(음소거 사용 중인데도)
        // 섞여 들어온다". v19.2.78에서 제가 만든 버그였음. 이 함수가 티맵 음소거 설정을
        // 보지 않고 무조건 길안내 음량%를 티맵에도 적용해버려서, 음소거(볼륨 0)로 눌러둔
        // 걸 이 함수가 매번 되살리고 있었음. 게다가 이 함수는 카카오 음성이 재생될 때마다
        // (willPlayVoiceGuide/didFinishPlayVoiceGuide) 호출돼서 음소거가 계속 풀렸음.
        // 이제 음소거가 켜져 있으면 티맵 볼륨은 0으로 유지함. #문제시 원복
        try {
            val tmapMuted = context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                .getBoolean("tmap_muted", false)
            TmapUISDK.setVolume(context.applicationContext, if (tmapMuted) 0 else percent)
        } catch (e: Exception) {
            NavLogger.flushTrace(context, "voice", "[안내음량] 티맵 적용 예외: ${e.message}")
        }
    }

    /**
     * 카카오 메뉴에서 사용자가 직접 안내 음량을 바꾼 걸 감지했을 때, 앱 저장값도 같이 맞춤
     * (양방향 동기화). 우리가 방금 적용한 값과 같으면 아무것도 안 함.
     */
    fun syncGuideVolumeFromKakao(context: Context, kakaoFraction: Float) {
        val percent = (kakaoFraction * 100).toInt().coerceIn(0, 100)
        if (kotlin.math.abs(percent - guideVolumePercent(context)) <= 1) return
        context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
            .edit().putInt(PREF_KEY_GUIDE_VOLUME, percent).apply()
        NavLogger.d(context, "[안내음량] 카카오 메뉴에서 바뀐 값 반영: ${percent}%")
    }

    // v: 신규기능(물리 볼륨버튼으로 안내음량 조절) - dispatchKeyEvent로 가로챈 볼륨키를 처리.
    // v: 재억 요청(2026-09-02, A안) - 이제 미디어(STREAM_MUSIC)는 건드리지 않고 길안내
    // 음량만 조절함. 음악은 그대로 유지됨. #문제시 원복
    //
    // v: 재억 제보(2026-09-02) - "50에서 0까지 쭉 누르고 있는데 화면에는 50/45/35처럼
    // 띄엄띄엄 뜬다". 원인 두 가지:
    //   1) 토스트는 한 번에 하나씩 순서대로 뜨고 각각 최소 2초씩 머물러서, 빠르게 여러 번
    //      바뀌면 뒤늦게 밀린 값이 하나씩 나타남(값 자체는 정상적으로 5%씩 잘 바뀌고 있었음 -
    //      로그의 18:13:12.5/12.8/13.1/13.5/13.8 참고). 화면 표시만 밀렸던 것.
    //   2) 길게 누를 때 반복 입력이 300ms 간격이라 뚝뚝 끊겨 보였음.
    // 그래서 표시는 토스트 대신 액티비티가 직접 그리는 오버레이(즉시 갱신)로 넘기고,
    // 길게 누르는 중(repeat)에는 더 촘촘하게(2%) 움직이도록 함. #문제시 원복
    private const val GUIDE_VOLUME_STEP_REPEAT = 2

    /** 화면에 현재 음량을 즉시 표시하는 함수(액티비티가 등록). 없으면 표시 생략. */
    @Volatile
    var guideVolumeIndicator: ((Int) -> Unit)? = null

    fun adjustGuideVolumeByHardwareKey(context: Context, up: Boolean, isRepeat: Boolean = false) {
        try {
            val current = guideVolumePercent(context)
            val step = if (isRepeat) GUIDE_VOLUME_STEP_REPEAT else GUIDE_VOLUME_STEP
            val target = if (up) {
                (current + step).coerceAtMost(100)
            } else {
                (current - step).coerceAtLeast(0)
            }
            if (target == current) return
            setGuideVolumePercent(context, target)
            // 길게 누르는 동안 로그가 도배되지 않게 처음/끝만 남김. #문제시 원복
            if (!isRepeat || target == 0 || target == 100) {
                NavLogger.d(context, "[안내음량] 물리버튼으로 조절: ${target}% (미디어 음량은 안 건드림)")
            }
            val indicator = guideVolumeIndicator
            if (indicator != null) {
                indicator.invoke(target)
            } else {
                try {
                    android.widget.Toast.makeText(context, "길안내 음량 ${target}%", android.widget.Toast.LENGTH_SHORT).show()
                } catch (_: Exception) { /* 토스트 실패는 무시 */ }
            }
        } catch (e: Exception) {
            NavLogger.e(context, "VolumeHelper 물리버튼 조절 예외: ${e.message}")
        }
    }
}
