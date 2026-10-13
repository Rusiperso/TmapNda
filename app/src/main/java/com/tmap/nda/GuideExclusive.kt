package com.tmap.nda

import android.app.Activity
import android.content.Context
import com.kakaomobility.knsdk.KNSDK
import com.tmap.nda.naver.NaverNavigator
import java.lang.ref.WeakReference

/**
 * 카카오 길안내와 네이버 길안내는 동시에 돌지 않는다.
 * 네이버가 선택/시작되면 카카오 안내(음성·경고음·카메라 처리·콤마 전송·화면)를 멈추고,
 * 카카오가 선택/시작되면 네이버 안내(엔진·음성·경고음·GPS)를 멈춘다.
 * 티맵 기능과 카카오 SDK 자체(경로만 받는 용도 포함)는 어느 쪽이든 그대로 둔다.
 */
object GuideExclusive {
    private var kakaoActivity: WeakReference<Activity>? = null

    fun registerKakaoActivity(a: Activity) { kakaoActivity = WeakReference(a) }
    fun unregisterKakaoActivity(a: Activity) { if (kakaoActivity?.get() === a) kakaoActivity = null }

    /** 네이버 안내가 선택/시작될 때: 카카오 안내를 멈춘다(SDK는 그대로). */
    fun stopKakao(ctx: Context) {
        val act = kakaoActivity?.get()
        val active = KakaoRouteDataRepository.isActive || (act != null && !act.isFinishing)
        if (!active) return
        NavLogger.d(ctx, "[안내배타] 네이버 선택 - 카카오 안내 정지")
        try { KNSDK.sharedGuidance()?.stop() } catch (e: Exception) { NavLogger.e(ctx, "[안내배타] 카카오 정지 예외: ${e.message}") }
        KakaoRouteDataRepository.reset()
        if (act != null && !act.isFinishing) act.finish()
    }

    /** 카카오 안내가 선택/시작될 때: 네이버 안내를 멈춘다. */
    fun stopNaver(ctx: Context) {
        if (!NaverNavigator.isRunning) return
        NavLogger.d(ctx, "[안내배타] 카카오 선택 - 네이버 안내 정지")
        try {
            NaverNavigator.silence()
            NaverNavigator.stop()
        } catch (e: Exception) { NavLogger.e(ctx, "[안내배타] 네이버 정지 예외: ${e.message}") }
    }

    private const val PREFS = "TmapNdaPrefs"
    private const val KEY_SWITCH_AT = "resume_after_engine_switch_at"

    /** 엔진을 바꾸다가 안내가 끊겼으면 true(3분 안). 한 번 읽으면 지워진다. */
    fun consumeResumeAfterSwitch(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = p.getLong(KEY_SWITCH_AT, 0L)
        if (at == 0L) return false
        p.edit().remove(KEY_SWITCH_AT).apply()
        return System.currentTimeMillis() - at < 3 * 60_000L
    }

    /**
     * 초기 화면에서 엔진을 고른 순간 호출: 고르지 않은 쪽이 돌고 있으면 멈추고,
     * 달리던 목적지를 저장해 앱이 다시 시작된 뒤 새 엔진으로 자동으로 이어간다(물어보지 않고 바로).
     */
    fun onEngineSelected(ctx: Context, engine: String) {
        val saved = ResumeGuidanceStore.get(ctx)
        val savedWaypoints = ResumeGuidanceStore.getWaypointsJson(ctx)
        val kakaoRunning = KakaoRouteDataRepository.isActive && !NaverNavigator.isRunning
        val wasGuiding = if (engine == GuideEngine.NAVER) kakaoRunning else NaverNavigator.isRunning
        if (engine == GuideEngine.NAVER) stopKakao(ctx) else stopNaver(ctx)
        if (wasGuiding && saved != null) {
            ResumeGuidanceStore.save(ctx, saved, savedWaypoints)   // 정지하면서 지워졌을 수 있어 다시 저장
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_SWITCH_AT, System.currentTimeMillis()).apply()
            NavLogger.d(ctx, "[안내배타] 엔진 전환 - '${saved.name}' 안내를 새 엔진으로 이어가도록 저장")
        }
    }
}
