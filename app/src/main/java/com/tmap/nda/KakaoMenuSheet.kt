package com.tmap.nda

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 카카오 길안내 화면 아래 바의 ☰를 누르면 먼저 뜨는 우리 옵션 판.
 * 카카오 설정 탭의 옵션을 모아 보여주고, "카카오 메뉴 열기"를 누르면 SDK의 원래 메뉴가 열린다.
 * 스타일은 앱 팝업 카드(반투명 회색, 둥근 모서리, 흰 글자)와 같다.
 */
class KakaoMenuSheet(
    private val activity: Activity,
    private val root: FrameLayout,
    private val openKakaoMenu: () -> Unit
) {
    private val d = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val prefs = activity.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
    private var layer: FrameLayout? = null
    val isShowing: Boolean get() = layer != null

    fun dismiss() {
        layer?.let { root.removeView(it) }
        layer = null
    }

    private fun switchView(checked: Boolean, onChange: (Boolean) -> Unit) = android.widget.Switch(activity).apply {
        isChecked = checked
        fun pill(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = 13 * d; setSize((46 * d).toInt(), (26 * d).toInt()) }
        trackTintList = null; thumbTintList = null
        trackDrawable = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), pill(0xFF3F78F0.toInt()))
            addState(intArrayOf(), pill(0xFFD9D9D9.toInt()))
        }
        thumbDrawable = android.graphics.drawable.InsetDrawable(
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setSize((22 * d).toInt(), (22 * d).toInt()) }, (2 * d).toInt())
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    private fun row(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val sw = switchView(checked, onChange)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply { text = title; textSize = 17f; setTextColor(0xFF222222.toInt()) })
                addView(TextView(activity).apply { text = desc; textSize = 13f; setTextColor(0xFF555555.toInt()) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sw)
            setOnClickListener { sw.toggle() }
        }
    }

    private fun divider() = View(activity).apply { setBackgroundColor(0xFFE3E3E3.toInt()) }

    fun show() {
        if (isShowing) return
        val sheet = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val r = dp(24).toFloat()
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
            setPadding(dp(28), dp(18), dp(28), dp(22))
            isClickable = true
        }
        val line = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        sheet.addView(row("스쿨존 회피", "새 경로를 찾을 때부터 적용돼요.", prefs.getBoolean("kakao_avoid_school_zone", false)) {
            prefs.edit().putBoolean("kakao_avoid_school_zone", it).apply()
        })
        sheet.addView(divider(), line)
        sheet.addView(row("최소 안내", "꼭 필요한 음성 안내만 해요.", prefs.getBoolean("kakao_minimal_guide_enabled", false)) {
            prefs.edit().putBoolean("kakao_minimal_guide_enabled", it).apply()
        })
        sheet.addView(divider(), line)
        sheet.addView(row("경로선 콤마 표시", "경로를 콤마 화면에 그려줘요.", prefs.getBoolean("route_line_display_enabled", false)) {
            prefs.edit().putBoolean("route_line_display_enabled", it).apply()
        })
        sheet.addView(divider(), line)
        sheet.addView(row("돌발상황 표시", "지도에 사고·공사·기상 정보를 표시해요.", prefs.getBoolean(IncidentRepository.PREF_KEY, true)) {
            prefs.edit().putBoolean(IncidentRepository.PREF_KEY, it).apply()
            IncidentRepository.refreshNow()
        })
        sheet.addView(divider(), line)
        sheet.addView(row("돌발정보 가운데 경고", "켜면 화면 가운데에 크게 알리고, 끄면 상단바에 주의 경고와 같이 떠요.", prefs.getBoolean(IncidentRepository.PREF_CENTER, true)) {
            prefs.edit().putBoolean(IncidentRepository.PREF_CENTER, it).apply()
            IncidentRepository.refreshNow()
        })
        sheet.addView(divider(), line)
        sheet.addView(row("돌발정보 차량에도 보내기", "계기판·HUD·콤마에도 앞쪽 사고를 알려요.", prefs.getBoolean(IncidentRepository.PREF_CAR, true)) {
            prefs.edit().putBoolean(IncidentRepository.PREF_CAR, it).apply()
            IncidentRepository.refreshNow()
        })
        sheet.addView(divider(), line)
        sheet.addView(row("하이패스 장착", "통행료 계산에 쓰여요.", CarFuelSettings.getUseHipass(activity)) {
            CarFuelSettings.save(activity, CarFuelSettings.getCarType(activity), CarFuelSettings.getCarFuel(activity), it)
        })
        sheet.addView(TextView(activity).apply {
            text = "카카오 메뉴 열기"; textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(0xFF3F78F0.toInt()); cornerRadius = dp(8).toFloat() }
            setPadding(0, dp(18), 0, dp(18))
            setOnClickListener { dismiss(); openKakaoMenu() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        val l = FrameLayout(activity).apply {
            setBackgroundColor(0x80000000.toInt())
            isClickable = true
            setOnClickListener { dismiss() }
            elevation = dp(40).toFloat()
        }
        val wide = root.width > root.height
        // 화면 맨 아래 시스템 바(제스처 바)에 판이 가려지지 않게 그 높이만큼 띄운다
        val navBottom = try {
            if (android.os.Build.VERSION.SDK_INT >= 30)
                root.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.systemBars())?.bottom ?: 0
            else 0
        } catch (e: Exception) { 0 }
        l.addView(sheet, FrameLayout.LayoutParams(
            if (wide) (root.width * 0.4f).toInt().coerceAtLeast(dp(360)) else ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, if (wide) Gravity.BOTTOM or Gravity.END else Gravity.BOTTOM).apply { bottomMargin = navBottom })
        root.addView(l, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        layer = l
    }
}
