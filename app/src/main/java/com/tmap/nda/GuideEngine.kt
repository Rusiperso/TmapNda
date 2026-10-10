package com.tmap.nda

import android.content.Context

/**
 * 길안내를 어느 엔진으로 할지(카카오 / 네이버). 초기 화면에서 고르고 새 안내를 시작할 때마다 이 값을 따른다.
 * 처음 값은 이미 저장된 키로 정한다: 네이버 키만 있으면 네이버, 그 외엔 카카오.
 */
object GuideEngine {
    const val KAKAO = "kakao"
    const val NAVER = "naver"
    private const val PREFS = "TmapNdaPrefs"
    private const val KEY = "guide_engine"

    fun get(context: Context): String {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString(KEY, null)?.let { if (it == KAKAO || it == NAVER) return it }
        val hasKakao = !p.getString("kakao_native_app_key", "").isNullOrBlank()
        val hasNaver = com.tmap.nda.naver.NaverDirectionsClient.hasKeys(context)
        return if (hasNaver && !hasKakao) NAVER else KAKAO
    }

    fun set(context: Context, engine: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, engine).apply()
    }

    fun isNaver(context: Context) = get(context) == NAVER
}
