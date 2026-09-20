package com.tmap.nda

import android.content.Context

/**
 * 음성 보정: 폰이 자꾸 잘못 알아듣는 말을 사용자가 직접 적어두는 표. 한 줄에 하나,
 * "틀린말=원래말" 형식(예: 경비실=경유지). 띄어쓰기는 무시하고 비교함. #문제시 원복
 */
object VoiceCorrections {
    private const val KEY = "voice_corrections_text"

    fun getText(context: Context): String =
        context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).getString(KEY, "") ?: ""

    fun setText(context: Context, text: String) {
        context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).edit().putString(KEY, text).apply()
    }

    /** 저장된 줄들을 (틀린말, 원래말) 짝으로 바꿈. 형식이 안 맞는 줄은 건너뜀. */
    fun pairs(context: Context): List<Pair<String, String>> =
        getText(context).lines().mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val from = line.substring(0, idx).replace(" ", "")
            val to = line.substring(idx + 1).replace(" ", "")
            if (from.isEmpty() || to.isEmpty()) null else from to to
        }
}
