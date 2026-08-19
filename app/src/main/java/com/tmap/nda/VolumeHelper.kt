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
    fun saveExplicitVolumePercent(context: Context, percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        // v14.1: 시스템 볼륨을 바꾸기 직전에 보호 시간을 먼저 걸어둠 - applySavedSystemVolume()이
        // 발생시키는 시스템 신호가 이 보호 시간 안에 들어오면 무시됨. #문제시 원복
        ignoreCaptureUntilMillis = System.currentTimeMillis() + 1500L
        context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
            .edit().putInt(PREF_KEY, clamped).apply()
        try {
            TmapUISDK.setVolume(context, clamped)
        } catch (e: Exception) {
            NavLogger.e(context, "VolumeHelper 티맵볼륨 즉시적용 예외: ${e.message}")
        }
        applySavedSystemVolume(context)
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
}
