package com.tmap.nda.naver

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * 네이버 지도 앱의 안내 중 메뉴판을 따라 만든 아래 메뉴.
 * 첫 화면: 안내음성 크기 / 다른 경로 · 지도설정 · 주행설정 · 상세설정 / 안내 종료.
 * 지도설정, 주행설정은 같은 자리에서 내용만 바뀌는 판으로 열린다.
 */
class NaverMenuSheet(
    private val activity: Activity,
    private val root: FrameLayout,
    private val actions: Actions
) {
    interface Actions {
        fun onOtherRoute()
        fun onVoiceSettings()
        fun onDetailSettings()
        fun onEndGuidance()
        /** 지도·주행 옵션이 바뀌었을 때(저장은 이미 끝남). key는 설정 이름. */
        fun onOptionChanged(key: String)
        fun guideVolumePercent(): Int
        fun setGuideVolumePercent(p: Int)
    }

    companion object {
        const val KEY_SAT = "naver_satellite_view_enabled"
        const val KEY_TRAFFIC = "naver_traffic_enabled"
        const val KEY_FLAT = "naver_flat_view_enabled"
        const val KEY_TRAFFIC_REROUTE = "naver_traffic_reroute_enabled"
        const val KEY_ROUTE_TRAFFIC_COLOR = "naver_route_traffic_color"
        const val KEY_TURN_SIGN = "naver_turn_sign_enabled"
        const val KEY_MINIMAL = "naver_minimal_guide_enabled"
        const val KEY_ROUTE_LINE = "route_line_display_enabled"
        const val KEY_DAYNIGHT = "map_daynight_mode"
    }

    private val d = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    // 앱 전체 팝업 카드와 같은 스타일: 반투명 회색 바탕, 둥근 모서리, 흰 글자, 앱 강조색
    private val ink = Color.WHITE
    private val sub = 0xFF8E8E93.toInt()
    private val blue = com.tmap.nda.AppAccent.color(activity)
    private val prefs = activity.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)

    /** 메뉴판이 열리고 닫힐 때 알려준다(아래 바를 잠시 가리기 위해). */
    var onShowingChanged: ((Boolean) -> Unit)? = null

    private var layer: FrameLayout? = null
    private var sheet: LinearLayout? = null
    val isShowing: Boolean get() = layer != null

    fun toggle() { if (isShowing) dismiss() else showMain() }

    fun dismiss() {
        val was = layer != null
        layer?.let { root.removeView(it) }
        layer = null; sheet = null
        if (was) onShowingChanged?.invoke(false)
    }

    // ---------- 판 틀 ----------
    private fun open(): LinearLayout {
        val s = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = com.tmap.nda.PopupCard.newCardBackground(activity)
            setPadding(dp(22), dp(18), dp(22), dp(18))
            isClickable = true
        }
        if (layer == null) {
            val l = FrameLayout(activity).apply {
                setBackgroundColor(0x66000000)
                isClickable = true
                setOnClickListener { dismiss() }
                elevation = dp(40).toFloat()
            }
            root.addView(l, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            layer = l
            onShowingChanged?.invoke(true)
        }
        val maxW = dp(520)
        layer!!.removeAllViews()
        layer!!.addView(s, FrameLayout.LayoutParams(
            if (root.width > maxW) maxW else ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { setMargins(dp(10), 0, dp(10), dp(10)) })
        sheet = s
        return s
    }

    private fun title(t: String) = TextView(activity).apply {
        text = t; textSize = 17f; setTextColor(ink); setPadding(0, 0, 0, dp(10))
    }

    private fun label(t: String) = TextView(activity).apply { text = t; textSize = 15f; setTextColor(ink) }

    private fun divider() = View(activity).apply { setBackgroundColor(0x22FFFFFF) }

    private fun styledSwitch(key: String, def: Boolean, onChange: (Boolean) -> Unit) = android.widget.Switch(activity).apply {
        isChecked = prefs.getBoolean(key, def)
        // 설정 창의 스위치와 같은 모양: 켬=초록 알약 / 끔=회색 알약, 흰 손잡이
        fun pill(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = 13 * d; setSize((46 * d).toInt(), (26 * d).toInt()) }
        val dot: android.graphics.drawable.Drawable = android.graphics.drawable.InsetDrawable(
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setSize((22 * d).toInt(), (22 * d).toInt()) }, (2 * d).toInt())
        trackTintList = null; thumbTintList = null
        trackDrawable = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), pill(0xFF34C759.toInt()))
            addState(intArrayOf(), pill(0xFF636366.toInt()))
        }
        thumbDrawable = dot
        setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(key, on).apply()
            onChange(on)
        }
    }

    /** 제목 + 설명 + 오른쪽 스위치. 한 줄을 누르면 스위치가 눌린다. */
    private fun switchRow(titleText: String, desc: String, key: String, def: Boolean): View {
        val sw = styledSwitch(key, def) { actions.onOptionChanged(key) }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            val col = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(titleText))
                if (desc.isNotEmpty()) addView(TextView(activity).apply { text = desc; textSize = 13f; setTextColor(sub) })
            }
            addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sw)
            setOnClickListener { sw.toggle() }
        }
    }

    /** 회색 바탕에 고른 칸만 흰 알약으로 보이는 선택 막대. */
    private fun segmented(options: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { setColor(0x1AFFFFFF); cornerRadius = dp(22).toFloat() }
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val cells = ArrayList<TextView>()
        fun paint(sel: Int) {
            cells.forEachIndexed { i, tv ->
                tv.background = if (i == sel) GradientDrawable().apply { setColor(0x40FFFFFF); cornerRadius = dp(18).toFloat() } else null
                tv.setTextColor(if (i == sel) ink else sub)
            }
        }
        options.forEachIndexed { i, o ->
            val tv = TextView(activity).apply {
                text = o; textSize = 15f; gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { paint(i); onPick(i) }
            }
            cells.add(tv)
            row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        paint(selected)
        return row
    }

    private fun slider(percent: Int, onChange: (Int) -> Unit): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(activity).apply { setImageBitmap(NaverMapIcons.speaker(dp(22), sub, 0)) }, LinearLayout.LayoutParams(dp(24), dp(24)))
        addView(SeekBar(activity).apply {
            max = 100; progress = percent.coerceIn(0, 100)
            progressTintList = ColorStateList.valueOf(blue)
            progressBackgroundTintList = ColorStateList.valueOf(0x44FFFFFF)
            thumb = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setSize(dp(24), dp(24)) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(8), 0, dp(8), 0) })
        addView(ImageView(activity).apply { setImageBitmap(NaverMapIcons.speaker(dp(22), sub, 2)) }, LinearLayout.LayoutParams(dp(24), dp(24)))
    }

    // ---------- 첫 화면 ----------
    fun showMain() {
        val s = open()
        s.addView(TextView(activity).apply { text = "안내음성 크기"; textSize = 16f; setTextColor(ink) })
        s.addView(slider(actions.guideVolumePercent()) { actions.setGuideVolumePercent(it) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(6), 0, dp(14)) })
        s.addView(divider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 4f; setPadding(0, dp(14), 0, dp(14)) }
        fun item(text: String, icon: android.graphics.Bitmap, onClick: () -> Unit) {
            row.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                addView(ImageView(activity).apply { setImageBitmap(icon) }, LinearLayout.LayoutParams(dp(30), dp(30)))
                addView(TextView(activity).apply { this.text = text; textSize = 14f; setTextColor(ink); setPadding(0, dp(6), 0, 0); gravity = Gravity.CENTER })
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val optionsHere = prefs.getBoolean("naver_options_in_menu", true)
        item("다른 경로", NaverMapIcons.menuRoute(dp(30), ink)) { dismiss(); actions.onOtherRoute() }
        if (optionsHere) {
            item("지도설정", NaverMapIcons.menuLayers(dp(30), ink)) { showMapSettings() }
            item("주행설정", NaverMapIcons.menuNav(dp(30), ink)) { showDriveSettings() }
            item("상세설정", NaverMapIcons.menuGear(dp(30), ink)) { showDetail() }
        }
        row.weightSum = if (optionsHere) 4f else 1f
        s.addView(row)

        s.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(0xFFE5483D.toInt()); cornerRadius = dp(14).toFloat() }
            setPadding(0, dp(15), 0, dp(15))
            addView(ImageView(activity).apply { setImageBitmap(NaverMapIcons.menuNav(dp(22), Color.WHITE, filled = true)) }, LinearLayout.LayoutParams(dp(22), dp(22)))
            addView(TextView(activity).apply {
                text = "안내 종료"; textSize = 17f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); setPadding(dp(8), 0, 0, 0)
            })
            setOnClickListener { dismiss(); actions.onEndGuidance() }
        })
    }

    // ---------- 지도설정 ----------
    private fun showMapSettings() {
        val s = open()
        s.addView(title("내비게이션 지도설정"))
        s.addView(segmented(listOf("일반지도", "위성지도"), if (prefs.getBoolean(KEY_SAT, false)) 1 else 0) { i ->
            prefs.edit().putBoolean(KEY_SAT, i == 1).apply(); actions.onOptionChanged(KEY_SAT)
        })
        s.addView(divider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(12) })
        s.addView(switchRow("교통정보", "도로 위에 교통정보를 표시합니다.", KEY_TRAFFIC, false))
        s.addView(divider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        s.addView(switchRow("평면 보기", "지도를 위에서 내려다보는 모양으로 봅니다.", KEY_FLAT, false))
        s.addView(divider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        s.addView(TextView(activity).apply { text = "야간모드"; textSize = 15f; setTextColor(ink); setPadding(0, dp(12), 0, dp(8)) })
        val modes = listOf("auto", "night", "day")
        val cur = modes.indexOf(prefs.getString(KEY_DAYNIGHT, "auto")).coerceAtLeast(0)
        s.addView(segmented(listOf("자동", "항상", "사용 안함"), cur) { i ->
            prefs.edit().putString(KEY_DAYNIGHT, modes[i]).apply(); actions.onOptionChanged(KEY_DAYNIGHT)
        })
    }

    // ---------- 주행설정 ----------
    private fun showDriveSettings() {
        val s = open()
        s.addView(switchRow("실시간 교통상황에 따라 경로 변경", "실시간 교통정보를 반영하여 최적의 경로로 변경합니다.", KEY_TRAFFIC_REROUTE, true))
        s.addView(divider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        s.addView(switchRow("최소 안내", "안내 말하는 횟수를 줄여요.", KEY_MINIMAL, false))
        s.addView(divider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        s.addView(switchRow("경로선 콤마 표시", "경로를 콤마 화면에 그려줘요.", KEY_ROUTE_LINE, false))
        s.addView(divider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        s.addView(switchRow("주행선에 교통정보 표시", "경로안내선에 교통정보 색상을 표시합니다.", KEY_ROUTE_TRAFFIC_COLOR, false))
        s.addView(divider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        s.addView(switchRow("지도 위에 차로정보 표시", "경로안내선 위에 중요한 회전 표지를 표시합니다.", KEY_TURN_SIGN, true))
        s.addView(divider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        s.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))
            addView(label("안내 목소리 선택"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(activity).apply { text = "›"; textSize = 24f; setTextColor(sub) })
            setOnClickListener { dismiss(); actions.onVoiceSettings() }
        })
    }

    // ---------- 상세설정 (네이버 앱의 "내비게이션" 설정 화면처럼 한 페이지에 모아서) ----------
    private fun section(name: String, vararg rows: View): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(activity).apply { text = name; textSize = 14f; setTextColor(sub); setPadding(dp(4), dp(14), 0, dp(6)) })
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0x1AFFFFFF); cornerRadius = dp(16).toFloat() }
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        rows.forEachIndexed { i, v ->
            if (i > 0) card.addView(divider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            card.addView(v)
        }
        addView(card)
    }

    private fun linkRow(text: String, onClick: () -> Unit) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(14), 0, dp(14))
        addView(label(text), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(activity).apply { this.text = "\u203A"; textSize = 24f; setTextColor(sub) })
        setOnClickListener { onClick() }
    }

    private fun labeledBlock(name: String, content: View) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(12), 0, dp(12))
        addView(label(name))
        addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    private fun showDetail() {
        val s = open()
        s.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply { text = "\u2039"; textSize = 30f; setTextColor(ink); setPadding(0, 0, dp(14), dp(4)); setOnClickListener { showMain() } })
            addView(TextView(activity).apply { text = "내비게이션"; textSize = 18f; setTextColor(ink) })
        })
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        body.addView(section("음성",
            linkRow("안내음성 선택") { dismiss(); actions.onVoiceSettings() },
            labeledBlock("안내음성 크기", slider(actions.guideVolumePercent()) { actions.setGuideVolumePercent(it) })
        ))
        val modes = listOf("auto", "night", "day")
        body.addView(section("지도 설정",
            labeledBlock("지도", segmented(listOf("일반지도", "위성지도"), if (prefs.getBoolean(KEY_SAT, false)) 1 else 0) { i ->
                prefs.edit().putBoolean(KEY_SAT, i == 1).apply(); actions.onOptionChanged(KEY_SAT)
            }),
            labeledBlock("야간모드", segmented(listOf("자동", "항상", "사용 안함"), modes.indexOf(prefs.getString(KEY_DAYNIGHT, "auto")).coerceAtLeast(0)) { i ->
                prefs.edit().putString(KEY_DAYNIGHT, modes[i]).apply(); actions.onOptionChanged(KEY_DAYNIGHT)
            }),
            switchRow("교통정보", "도로 위에 교통정보를 표시합니다.", KEY_TRAFFIC, false),
            switchRow("평면 보기", "지도를 위에서 내려다보는 모양으로 봅니다.", KEY_FLAT, false),
            switchRow("주행선에 교통정보 표시", "경로안내선에 교통정보 색상을 표시합니다.", KEY_ROUTE_TRAFFIC_COLOR, false),
            switchRow("지도 위에 차로정보 표시", "경로안내선 위에 중요한 회전 표지를 표시합니다.", KEY_TURN_SIGN, true)
        ))
        body.addView(section("길안내",
            switchRow("실시간 교통상황에 따라 경로 변경", "실시간 교통정보를 반영하여 최적의 경로로 변경합니다.", KEY_TRAFFIC_REROUTE, true),
            switchRow("최소 안내", "안내 말하는 횟수를 줄여요.", KEY_MINIMAL, false),
            switchRow("경로선 콤마 표시", "경로를 콤마 화면에 그려줘요.", KEY_ROUTE_LINE, false)
        ))
        body.addView(section("앱",
            linkRow("앱 전체 설정 열기") { dismiss(); actions.onDetailSettings() }
        ))
        s.addView(android.widget.ScrollView(activity).apply { addView(body); isVerticalScrollBarEnabled = false },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (root.height * 0.72f).toInt().coerceAtLeast(dp(300))))
    }
}
