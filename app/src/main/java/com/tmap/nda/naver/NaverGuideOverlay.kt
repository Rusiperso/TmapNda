package com.tmap.nda.naver

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 카카오 SDK 화면이 직접 그려주던 안내 박스(다음 회전, 남은 거리·시간)를 대신 그린다.
 * 상단바·버튼 같은 기존 UI는 건드리지 않고, 지도 위에 얹는 두 개의 작은 반투명 박스만 추가한다.
 */
class NaverGuideOverlay(private val activity: Activity, private val root: FrameLayout) {

    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val glass = 0xE6202428.toInt()

    private val arrow = text(46f, Color.WHITE, true)
    private val dist = text(36f, 0xFFFFD54F.toInt(), true)
    private val instr = text(17f, Color.WHITE, false)
    private val next = text(14f, 0xFFB0BEC5.toInt(), false)
    private val eta = text(18f, Color.WHITE, true)
    private val road = text(13f, 0xFFB0BEC5.toInt(), false)

    private val banner = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = round(glass)
        setPadding(dp(16), dp(10), dp(18), dp(12))
        visibility = View.GONE
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        arrow.setPadding(0, 0, dp(14), 0)
        row.addView(arrow); row.addView(dist)
        addView(row); addView(instr); addView(next)
    }

    private val bottom = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = round(glass)
        setPadding(dp(16), dp(8), dp(18), dp(8))
        visibility = View.GONE
        addView(eta); addView(road)
    }

    init {
        root.addView(banner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(0, dp(76), dp(12), 0)
        })
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(0, 0, dp(12), dp(16))
        })
        // 기존 버튼·팝업 카드처럼 끌어서 옮길 수 있고, 놓은 자리는 저장돼 다음에도 그대로 뜬다(가로/세로 따로).
        // 설정의 "팝업 위치 잠금"이 켜져 있으면 안 움직인다. 화면에 붙인 뒤에 걸어야 저장된 위치가 복원된다.
        com.tmap.nda.PopupCard.attachDrag(activity, banner, root, "naverGuideBanner")
        com.tmap.nda.PopupCard.attachDrag(activity, bottom, root, "naverGuideBottom")
    }

    private var wanted = false
    private var obscured = false
    private fun apply() {
        val v = if (wanted && !obscured) View.VISIBLE else View.GONE
        banner.visibility = v; bottom.visibility = v
    }
    fun show() { wanted = true; apply() }
    fun hide() { wanted = false; apply() }

    /** 팝업 카드가 떠 있는 동안은 안내 박스를 잠시 숨겨서 겹쳐 보이지 않게 한다. */
    fun setObscured(b: Boolean) { obscured = b; apply() }

    fun update(s: GuidanceState, goalName: String) {
        show()
        val g = s.nextGuide
        val type = g?.type ?: 0
        arrow.text = arrowFor(NaverTurnMap.toOpenpilotCode(type))
        dist.text = distText(s.nextGuideDistMeters)
        instr.text = g?.instructions.orEmpty().ifBlank { NaverTurnMap.label(type) }.ifBlank { s.roadName }
        val sec = s.secondGuide
        if (sec != null) {
            next.text = "이후 ${NaverTurnMap.label(sec.type).ifBlank { "직진" }} ${distText(s.secondGuideDistMeters)}"
            next.visibility = View.VISIBLE
        } else {
            next.visibility = View.GONE
        }
        val arrive = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(System.currentTimeMillis() + s.remainTimeSec * 1000L))
        eta.text = "$arrive 도착 · ${distText(s.remainMeters)} · ${(s.remainTimeSec / 60.0).roundToInt()}분"
        road.text = s.roadName.ifBlank { goalName }
    }

    private fun arrowFor(code: Int) = when (code) {
        12 -> "⬅"
        13 -> "➡"
        14 -> "↩"
        7, 102 -> "↖"
        6, 101 -> "↗"
        201 -> "🏁"
        else -> "⬆"
    }

    private fun distText(m: Int) =
        if (m >= 1000) String.format(Locale.US, "%.1fkm", m / 1000.0) else "${m}m"

    private fun text(size: Float, color: Int, bold: Boolean) = TextView(activity).apply {
        textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    private fun round(color: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(14).toFloat()
        setStroke(1, 0x33FFFFFF)
    }
}
