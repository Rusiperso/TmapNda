package com.tmap.nda

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 카카오 내장형 길찾기 SDK(실시간 길 안내)의 하루 호출 수를 앱 안에서 센다.
 * 카카오 한도는 앱 키 하나당 "하루" 100,000건이다(카카오 개발자 문서의 쿼터 표 기준, 바뀔 수 있음).
 * 이 숫자는 우리 앱이 경로를 요청한 횟수이고, 카카오 서버가 실제로 세는 값과는 다를 수 있다.
 */
object KakaoUsage {
    const val DAILY_CAP = 100_000
    private const val PREFS = "TmapNdaPrefs"
    private const val KEY_DAY = "kakao_usage_day"
    private const val KEY_COUNT = "kakao_usage_count"

    private fun today(): String = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Seoul")
    }.format(Date())

    fun todayCount(context: Context): Int {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (p.getString(KEY_DAY, "") == today()) p.getInt(KEY_COUNT, 0) else 0
    }

    /** 경로를 한 번 요청할 때마다 부른다. */
    fun countCall(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val n = todayCount(context) + 1
        p.edit().putString(KEY_DAY, today()).putInt(KEY_COUNT, n).apply()
    }

    /** 화면에 띄울 문구. */
    fun label(context: Context): String {
        val n = todayCount(context)
        val pct = n * 100.0 / DAILY_CAP
        val pctText = if (pct < 0.1) "0.1% 미만" else String.format(Locale.US, "%.1f%%", pct)
        return "오늘 카카오 길안내 ${String.format(Locale.US, "%,d", DAILY_CAP)}건 중 $pctText (${n}건 사용 중)"
    }
}
