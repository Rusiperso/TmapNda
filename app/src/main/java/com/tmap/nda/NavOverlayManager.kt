package com.tmap.nda

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.tmap.nda.navdy.KakaoToNavdyTurn
import com.tmap.nda.navdy.NavdyTurn

/**
 * 재억 요청(2026-08-27) - 순정 Tmap의 "다른 앱 위에 표시"(미니 길안내 모드)와 같은 개념.
 * TmapNda의 안내 화면(MapActivity/KakaoNaviActivity)이 전부 백그라운드로 내려가도,
 * 다음 방향/거리를 작은 오버레이 창으로 계속 볼 수 있게 함. 콤마 화면과는 별개로
 * "폰 화면"에 뜨는 창이라 SYSTEM_ALERT_WINDOW 권한이 필요.
 *
 * 안내 데이터는 KakaoRouteDataRepository(HUD/Navdy와 동일한 소스)를 그대로 구독해서 씀.
 */
object NavOverlayManager {

    private const val PREF_KEY_ENABLED = "background_overlay_enabled"

    // 앱 자신의 안내 화면이 몇 개나 "started" 상태인지 셈. 0이 되는 순간 = 완전히
    // 백그라운드로 내려간 순간. Tmap<->카카오 화면 전환 중에는 새 화면의 onStart가
    // 옛 화면의 onStop보다 먼저 불려서 0을 거치지 않으므로 깜빡임 없이 전환됨. #문제시 원복
    @Volatile private var startedScreenCount = 0

    private var windowManager: WindowManager? = null
    private var overlayView: LinearLayout? = null
    private var iconView: TextView? = null
    private var mainTextView: TextView? = null
    private var distTextView: TextView? = null
    private var guidanceListener: ((KakaoRouteSnapshot) -> Unit)? = null

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
            .getBoolean(PREF_KEY_ENABLED, false)

    fun hasPermission(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** 설정 다이얼로그에서 스위치를 켤 때 호출 - 시스템 권한 화면으로 안내 */
    fun requestPermission(activity: Activity) {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivity(intent)
        } catch (e: Exception) {
            NavLogger.e(activity, "[오버레이] 권한 설정 화면 이동 실패: ${e.message}")
        }
    }

    /** MapActivity/KakaoNaviActivity의 onStart에서 호출 */
    fun activityStarted() {
        startedScreenCount++
        if (startedScreenCount == 1) {
            hide()
        }
    }

    /** MapActivity/KakaoNaviActivity의 onStop에서 호출 */
    fun activityStopped(context: Context) {
        startedScreenCount = (startedScreenCount - 1).coerceAtLeast(0)
        if (startedScreenCount == 0) {
            maybeShow(context)
        }
    }

    private fun maybeShow(context: Context) {
        if (!isEnabled(context) || !hasPermission(context)) return
        if (!KakaoRouteDataRepository.isActive) return
        show(context)
    }

    private fun show(context: Context) {
        if (overlayView != null) return
        val appContext = context.applicationContext
        try {
            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = buildView(appContext)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (24 * appContext.resources.displayMetrics.density).toInt()
            }
            wm.addView(view, params)
            windowManager = wm
            overlayView = view
            applySnapshot(KakaoRouteDataRepository.snapshot())
            val listener: (KakaoRouteSnapshot) -> Unit = { snapshot -> applySnapshot(snapshot) }
            guidanceListener = listener
            KakaoRouteDataRepository.addListener(listener)
            NavLogger.d(appContext, "[오버레이] 표시 시작")
        } catch (e: Exception) {
            NavLogger.e(appContext, "[오버레이] 표시 실패: ${e.message}")
            windowManager = null
            overlayView = null
        }
    }

    fun hide() {
        val wm = windowManager
        val view = overlayView
        guidanceListener?.let { KakaoRouteDataRepository.removeListener(it) }
        guidanceListener = null
        if (wm != null && view != null) {
            try { wm.removeView(view) } catch (_: Exception) {}
        }
        windowManager = null
        overlayView = null
    }

    private fun applySnapshot(snapshot: KakaoRouteSnapshot) {
        if (!snapshot.isActive) {
            hide()
            return
        }
        mainTextView?.text = snapshot.tbtMainText.ifBlank { snapshot.roadName }
        distTextView?.text = "${snapshot.tbtDist}m"
        iconView?.text = arrowFor(snapshot.rgCodeName, snapshot.directionAngle)
    }

    private fun arrowFor(rgCodeName: String, directionAngle: Int): String {
        return when (KakaoToNavdyTurn.from(rgCodeName, directionAngle)) {
            NavdyTurn.LEFT, NavdyTurn.SHARP_LEFT, NavdyTurn.KEEP_LEFT,
            NavdyTurn.EASY_LEFT, NavdyTurn.MERGE_LEFT, NavdyTurn.EXIT_LEFT -> "←"
            NavdyTurn.RIGHT, NavdyTurn.SHARP_RIGHT, NavdyTurn.KEEP_RIGHT,
            NavdyTurn.EASY_RIGHT, NavdyTurn.MERGE_RIGHT, NavdyTurn.EXIT_RIGHT -> "→"
            NavdyTurn.UTURN_LEFT, NavdyTurn.UTURN_RIGHT -> "↩"
            NavdyTurn.END -> "●"
            else -> "↑"
        }
    }

    private fun buildView(context: Context): LinearLayout {
        val density = context.resources.displayMetrics.density
        val padding = (12 * density).toInt()
        val icon = TextView(context).apply {
            textSize = 22f
            setTextColor(Color.WHITE)
            text = "↑"
        }
        val main = TextView(context).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = (220 * density).toInt()
        }
        val dist = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#AAAAAA"))
        }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * density).toInt(), 0, 0, 0)
            addView(main)
            addView(dist)
        }
        iconView = icon
        mainTextView = main
        distTextView = dist
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.parseColor("#E6202020"))
            addView(icon)
            addView(texts)
        }
    }
}
