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

    /**
     * 재억 요청(2026-11-08) - 앱이 뒤에 있을 때: 다른 앱 위에 같은 모양의 큰 팝업을 5초 띄운다.
     * "다른 앱 위에 표시" 권한이 없으면 소리·진동 알림으로 대신한다. 띄웠으면 true.
     */
    fun showOverlay(ctx: android.content.Context, text: String, sound: Boolean): Boolean {
        val d = ctx.resources.displayMetrics.density
        var shown = false
        if (android.provider.Settings.canDrawOverlays(ctx)) {
            try {
                val wm = ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
                val tv = TextView(ctx).apply {
                    this.text = text
                    textSize = 40f
                    setTextColor(Color.WHITE)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding((32 * d).toInt(), (20 * d).toInt(), (32 * d).toInt(), (20 * d).toInt())
                    background = GradientDrawable().apply { setColor(if (sound) 0xEEE24B4A.toInt() else 0xEEBA7517.toInt()); cornerRadius = 20 * d }
                }
                val lp = android.view.WindowManager.LayoutParams(
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                    android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    android.graphics.PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.CENTER }
                wm.addView(tv, lp)
                shown = true
                main.postDelayed({ try { wm.removeView(tv) } catch (_: Exception) { } }, 5000L)
            } catch (e: Exception) {
                NavLogger.d(ctx, "[돌발백그라운드] 오버레이 실패: ${e.message}")
            }
        }
        if (!shown) {
            try {
                val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    nm.createNotificationChannel(android.app.NotificationChannel("incident_alert", "사고 경고", android.app.NotificationManager.IMPORTANCE_HIGH))
                }
                nm.notify(4713, androidx.core.app.NotificationCompat.Builder(ctx, "incident_alert")
                    .setSmallIcon(R.mipmap.ic_launcher).setContentTitle(text)
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true).setTimeoutAfter(15000L).build())
            } catch (e: Exception) {
                NavLogger.d(ctx, "[돌발백그라운드] 알림 실패: ${e.message}")
            }
        }
        if (sound) {
            try {
                val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
                tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 5000)
                main.postDelayed({ try { tone.release() } catch (_: Exception) { } }, 5500L)
            } catch (_: Exception) { }
        }
        return shown
    }

    /** [sound]가 false(공사)면 경고음 없이 주황색 글씨 상자만 띄운다. 사고·재난은 빨간 상자 + 경고음. */
    fun show(activity: Activity, root: ViewGroup, text: String, sound: Boolean = true) {
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
            background = GradientDrawable().apply { setColor(if (sound) 0xEEE24B4A.toInt() else 0xEEBA7517.toInt()); cornerRadius = 20 * d }
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
        if (!sound) return
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 5000)
            main.postDelayed({ try { tone.release() } catch (_: Exception) { } }, 5500L)
        } catch (e: Exception) {
            NavLogger.d(activity, "[돌발] 경고음 실패: ${e.message}")
        }
    }
}
