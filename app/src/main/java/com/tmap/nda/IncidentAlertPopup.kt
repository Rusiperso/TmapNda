package com.tmap.nda

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 앞쪽에 새 사고·공사가 잡히면 화면 정가운데에 큰 글씨("1.2km 앞 사고발생!")를 5초 띄우고 경고음(5초)을 낸다.
 * 같은 돌발상황은 한 번만 알린다(IncidentRepository가 중복을 거른다). #문제시 원복
 */
object IncidentAlertPopup {
    private val main = Handler(Looper.getMainLooper())
    private var current: TextView? = null

    fun show(activity: Activity, root: ViewGroup, text: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        val d = activity.resources.displayMetrics.density
        current?.let { (it.parent as? ViewGroup)?.removeView(it) }
        val tv = TextView(activity).apply {
            this.text = text
            textSize = 44f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding((36 * d).toInt(), (22 * d).toInt(), (36 * d).toInt(), (22 * d).toInt())
            background = GradientDrawable().apply { setColor(0xEEE24B4A.toInt()); cornerRadius = 20 * d }
            elevation = 60 * d
            isClickable = false
        }
        root.addView(tv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        current = tv
        main.postDelayed({
            (tv.parent as? ViewGroup)?.removeView(tv)
            if (current === tv) current = null
        }, 5000L)
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 5000)
            main.postDelayed({ try { tone.release() } catch (_: Exception) { } }, 5500L)
        } catch (e: Exception) {
            NavLogger.d(activity, "[돌발] 경고음 실패: ${e.message}")
        }
    }
}
