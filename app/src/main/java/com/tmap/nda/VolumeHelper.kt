package com.tmap.nda

import android.content.Context
import android.media.AudioManager

/**
 * 지금까지 티맵 음소거 해제 시 무조건 TmapUISDK.setVolume(context, 100)을 호출해서,
 * 사용자가 하드웨어 볼륨 버튼 등으로 직접 맞춰둔 값(예: 50)을 매번 100으로 덮어써버렸음
 * (사용자: "볼륨을 50으로 했는데 다음에 실행하면 다시 100이 됨"). 음소거 직전에 실제
 * 시스템 음악 스트림 볼륨을 캡처해뒀다가, 음소거 해제 시 그 값으로 복원하도록 함.
 * #문제시 원복
 */
object VolumeHelper {
    private const val PREF_KEY = "last_music_volume_percent"

    fun captureCurrentVolumePercent(context: Context) {
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
}
