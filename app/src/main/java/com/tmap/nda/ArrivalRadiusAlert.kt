package com.tmap.nda

import android.content.Context
import android.content.SharedPreferences

/**
 * 목적지까지 남은거리(remainDist)가 설정된 반경(기본 500m) 안으로 "처음" 들어오는 순간
 * 한 번만 소리/진동으로 알려주는 기능. 같은 안내 도중 반경 근처를 왔다갔다해도 중복 알림
 * 안 뜨게 "이번 안내에서 이미 울렸는지" 플래그로 관리.
 */
object ArrivalRadiusAlert {
    private const val PREF_NAME = "TmapNdaPrefs"
    private const val KEY_ENABLED = "arrival_radius_alert_enabled"
    private const val KEY_RADIUS_M = "arrival_radius_alert_meters"
    private const val DEFAULT_RADIUS_M = 500

    @Volatile private var alreadyWarnedThisGuide = false

    // v: 새 안내 시작 지점(여러 화면/경로)마다 일일이 리셋 호출을 심는 대신, "남은거리가
    // 반경의 3배 이상으로 다시 멀어지면 새 구간/새 목적지로 간주해 자동 리셋"하는 방식으로
    // 안전하게 처리. 목적지 변경, 경유지 추가, 재탐색 등 어떤 경로로 새 안내가 시작되든
    // 놓치지 않음. #문제시 원복
    private fun maybeResetForNewTrip(context: Context, remainDistMeters: Int) {
        if (alreadyWarnedThisGuide && remainDistMeters > radiusMeters(context) * 3) {
            alreadyWarnedThisGuide = false
        }
    }

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun radiusMeters(context: Context): Int =
        prefs(context).getInt(KEY_RADIUS_M, DEFAULT_RADIUS_M)

    fun setRadiusMeters(context: Context, meters: Int) {
        prefs(context).edit().putInt(KEY_RADIUS_M, meters).apply()
    }

    /**
     * 매 위치 갱신마다 호출. remainDist(미터)가 반경 안으로 처음 진입한 순간에만 true 반환
     * (호출부에서 true 받으면 소리/진동 재생).
     */
    fun checkShouldWarn(context: Context, remainDistMeters: Int): Boolean {
        if (!isEnabled(context)) return false
        maybeResetForNewTrip(context, remainDistMeters)
        if (alreadyWarnedThisGuide) return false
        if (remainDistMeters <= 0) return false
        if (remainDistMeters > radiusMeters(context)) return false
        alreadyWarnedThisGuide = true
        return true
    }

    fun playAlert(context: Context) {
        try {
            // v: 신규기능(목적지 반경 도착알림) - 소리+진동 둘 다. 소리는 기존 과속경고음과
            // 같은 방식(ToneGenerator, 저장된 안내음량 사용)으로 일관성 유지. #문제시 원복
            val volumePercent = VolumeHelper.savedVolumePercent(context).coerceIn(1, 100)
            val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, volumePercent)
            tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 300)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tone.release() }, 400)

            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            if (vibrator?.hasVibrator() == true) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(300, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(300)
                }
            }
            NavLogger.d(context, "[반경도착알림] 재생됨")
        } catch (e: Exception) {
            NavLogger.e(context, "[반경도착알림] 재생 실패: ${e.message}")
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
