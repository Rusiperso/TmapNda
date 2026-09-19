package com.tmap.nda

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

// v19.3.79: 재억 요청 - 목적지 정보 카드/경유지 검색방법/주변탐색/"경유지로 추가할까요?"를
// 전부 같은 반투명 카드 스타일로 통일하면서, 카드 만들기·끌어서 옮기기·위치 자동저장·
// 바깥 눌러 닫기를 한 곳에 모음(카카오/티맵 화면이 같이 씀). #문제시 원복
object PopupCard {
    class Option(val label: String, val primary: Boolean = false, val onClick: () -> Unit)

    fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    fun newCardBackground(context: Context): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor("#B328282C"))
        cornerRadius = dp(context, 20).toFloat()
    }

    fun roundedFill(context: Context, colorHex: String): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor(colorHex))
        cornerRadius = dp(context, 12).toFloat()
    }

    fun makeButton(context: Context, label: String, primary: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 14f
            setPadding(0, dp(context, 13), 0, dp(context, 13))
            if (primary) {
                setTextColor(Color.parseColor("#212121"))
                setTypeface(null, Typeface.BOLD)
                background = roundedFill(context, "#FFD54F")
            } else {
                setTextColor(Color.parseColor("#DDDDDD"))
                background = roundedFill(context, "#1AFFFFFF")
            }
            isClickable = true
            setOnClickListener { onClick() }
        }

    /**
     * 카드의 빈 배경을 잡고 끌면 옮겨지고, 손을 떼면 위치를 저장해서 다음에도 그 자리에 뜨게 함.
     * prefKey가 같은 카드끼리는 같은 자리를 공유(화면 방향별로 따로 저장). 저장값은 화면 안으로
     * 보정해서 복원하고, 자리잡기 전 깜빡임을 막으려고 그동안 투명하게 둠.
     */
    fun attachDrag(context: Context, card: View, root: ViewGroup, prefKey: String) {
        val prefs = context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
        val suffix = if (context.resources.configuration.orientation ==
            Configuration.ORIENTATION_LANDSCAPE) "land" else "port"
        val keyX = "${prefKey}_x_$suffix"
        val keyY = "${prefKey}_y_$suffix"

        if (prefs.contains(keyX) && prefs.contains(keyY)) {
            card.alpha = 0f
            card.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    card.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    val maxX = (root.width - card.width).coerceAtLeast(0).toFloat()
                    val maxY = (root.height - card.height).coerceAtLeast(0).toFloat()
                    card.x = prefs.getFloat(keyX, card.x).coerceIn(0f, maxX)
                    card.y = prefs.getFloat(keyY, card.y).coerceIn(0f, maxY)
                    card.alpha = 1f
                }
            })
        }

        var dragDX = 0f
        var dragDY = 0f
        var downRawX = 0f
        var downRawY = 0f
        var moved = false
        // 손가락이 살짝 떨린 정도로는 "끌었다"고 치지 않음(그것만으로 위치가 저장돼버리던 문제). #문제시 원복
        val slop = dp(context, 10)
        card.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragDX = card.x - event.rawX
                    dragDY = card.y - event.rawY
                    downRawX = event.rawX
                    downRawY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && Math.hypot((event.rawX - downRawX).toDouble(), (event.rawY - downRawY).toDouble()) < slop) {
                        return@setOnTouchListener true
                    }
                    val maxX = (root.width - card.width).coerceAtLeast(0).toFloat()
                    val maxY = (root.height - card.height).coerceAtLeast(0).toFloat()
                    card.x = (event.rawX + dragDX).coerceIn(0f, maxX)
                    card.y = (event.rawY + dragDY).coerceIn(0f, maxY)
                    moved = true
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) {
                        prefs.edit().putFloat(keyX, card.x).putFloat(keyY, card.y).apply()
                    }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 카드를 root 위에 띄우고(뒤에 투명 방패 뷰를 깔아 바깥을 누르면 닫힘), 닫는 함수를 돌려줌.
     * defaultGravityCenter가 true면 화면 가운데, false면 왼쪽 위(경유지 버튼 오른쪽)에 뜸.
     */
    fun present(
        activity: Activity,
        root: ViewGroup,
        card: View,
        widthPx: Int,
        prefKey: String,
        defaultGravityCenter: Boolean,
        topRight: Boolean = false,
        onOutsideTap: () -> Unit
    ): () -> Unit {
        val scrim = View(activity).apply { isClickable = true }
        root.addView(scrim, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        val params = FrameLayout.LayoutParams(widthPx, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            if (topRight) {
                gravity = Gravity.TOP or Gravity.END
                marginEnd = dp(activity, 12)
                topMargin = dp(activity, 60)
            } else if (defaultGravityCenter) {
                gravity = Gravity.CENTER
            } else {
                gravity = Gravity.TOP or Gravity.START
                marginStart = dp(activity, 170)
                topMargin = dp(activity, 76)
            }
        }
        root.addView(card, params)
        attachDrag(activity, card, root, prefKey)
        var closed = false
        val close = {
            if (!closed) {
                closed = true
                root.removeView(card)
                root.removeView(scrim)
            }
        }
        scrim.setOnClickListener {
            close()
            onOutsideTap()
        }
        return close
    }

    /**
     * AlertDialog(제목 + 목록 + 아래 버튼들) 대신 쓰는 카드형 창. 검색이력/검색결과 목록처럼
     * 기존 코드가 AlertDialog의 show/dismiss/getButton/setOnDismissListener를 그대로
     * 쓰던 곳을 최소한만 고쳐서 바꿀 수 있게 같은 이름·같은 버튼 상수(-1/-2/-3)로 만듦.
     * 바깥을 누르면 닫히고, 끌어서 옮길 수 있고, 위치가 저장됨.
     */
    class CardDialog(private val activity: Activity, private val root: ViewGroup) {
        companion object {
            const val BUTTON_POSITIVE = -1
            const val BUTTON_NEGATIVE = -2
            const val BUTTON_NEUTRAL = -3
        }

        private var customTitle: View? = null
        private var titleText: CharSequence? = null
        private var titleView: TextView? = null
        private var content: View? = null
        private val buttons = HashMap<Int, TextView>()
        private var dismissListener: (() -> Unit)? = null
        private var closeFn: (() -> Unit)? = null
        private var finished = false

        fun setCustomTitle(v: View) { customTitle = v }

        fun setTitle(t: CharSequence) {
            titleText = t
            titleView?.text = t
        }

        private var contentMaxDp = 360
        private var contentReserveDp = 200

        /** maxDp: 목록 영역 최대 높이, reserveDp: 제목/버튼/여백용으로 화면 높이에서 빼둘 값. */
        fun setContent(v: View, maxDp: Int = 360, reserveDp: Int = 200) {
            content = v
            contentMaxDp = maxDp
            contentReserveDp = reserveDp
        }

        fun setOnDismissListener(l: () -> Unit) { dismissListener = l }

        fun getButton(which: Int): TextView? = buttons[which]

        /** autoClose=false면 눌러도 안 닫힘(이전/다음 같은 페이지 넘김용). */
        fun setButton(
            which: Int,
            label: String,
            destructive: Boolean = false,
            autoClose: Boolean = true,
            primary: Boolean = false,
            onClick: (() -> Unit)? = null
        ) {
            val btn = object : TextView(activity) {
                override fun setEnabled(enabled: Boolean) {
                    super.setEnabled(enabled)
                    alpha = if (enabled) 1f else 0.4f
                }
            }.apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 14f
                setPadding(0, dp(activity, 12), 0, dp(activity, 12))
                if (primary) {
                    setTextColor(Color.parseColor("#212121"))
                    setTypeface(null, Typeface.BOLD)
                    background = roundedFill(activity, "#FFD54F")
                } else {
                    setTextColor(Color.parseColor(if (destructive) "#FF8A80" else "#DDDDDD"))
                    background = roundedFill(activity, "#1AFFFFFF")
                }
                isClickable = true
                setOnClickListener {
                    onClick?.invoke()
                    if (autoClose) dismiss()
                }
            }
            buttons[which] = btn
        }

        private fun finish() {
            if (!finished) {
                finished = true
                dismissListener?.invoke()
            }
        }

        fun dismiss() {
            closeFn?.invoke()
            finish()
        }

        fun show() {
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = newCardBackground(activity)
                setPadding(dp(activity, 18), dp(activity, 16), dp(activity, 18), dp(activity, 12))
            }
            val custom = customTitle
            if (custom != null) {
                (custom.parent as? ViewGroup)?.removeView(custom)
                card.addView(custom, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(activity, 8) })
            } else if (titleText != null) {
                titleView = TextView(activity).apply {
                    text = titleText
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setPadding(0, 0, 0, dp(activity, 10))
                }
                card.addView(titleView)
            } else {
                titleView = TextView(activity).apply {
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setPadding(0, 0, 0, dp(activity, 10))
                    visibility = View.GONE
                }
                card.addView(titleView)
            }
            content?.let { c ->
                (c.parent as? ViewGroup)?.removeView(c)
                val h = minOf(dp(activity, contentMaxDp), activity.resources.displayMetrics.heightPixels - dp(activity, contentReserveDp))
                    .coerceAtLeast(dp(activity, 140))
                card.addView(c, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h))
            }
            val order = listOf(BUTTON_NEUTRAL, BUTTON_NEGATIVE, BUTTON_POSITIVE).filter { buttons.containsKey(it) }
            if (order.isNotEmpty()) {
                val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                order.forEachIndexed { i, which ->
                    row.addView(buttons[which]!!, LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    ).apply { if (i > 0) marginStart = dp(activity, 8) })
                }
                card.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(activity, 12) })
            }
            val width = minOf(dp(activity, 600), (activity.resources.displayMetrics.widthPixels * 0.94).toInt())
            closeFn = present(activity, root, card, width, "listCard", true) { finish() }
        }
    }

    /** 메뉴 카드 안에서 "설정" 같은 하위 화면으로 바뀔 때 아래 버튼 한 개. */
    class SubButton(val label: String, val primary: Boolean = false, val onClick: () -> Unit)

    /**
     * 메뉴 카드 안에서 "설정"을 열 때, 설정 창을 여는 코드가 별도 창 대신 이 카드 안에 그리도록
     * 메뉴에서 "설정"을 누르는 순간에만 잠깐 세팅해두는 값. #문제시 원복
     */
    var pendingEmbeddedHost: MenuHost? = null

    /**
     * 메뉴 카드를 담는 틀. 메뉴 항목 격자를 보여주다가, "설정"처럼 하위 화면이 필요한 항목이
     * 눌리면 같은 카드 안에서 내용을 갈아끼우고 왼쪽 위에 ← 버튼을 보여줌(누르면 메뉴로 복귀).
     */
    class MenuHost(private val activity: Activity, private val root: ViewGroup) {
        private val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = newCardBackground(activity)
            setPadding(dp(activity, 18), dp(activity, 16), dp(activity, 18), dp(activity, 14))
        }
        private val backBtn = TextView(activity).apply {
            text = "←"
            setTextColor(Color.WHITE)
            textSize = 22f
            setPadding(0, 0, dp(activity, 12), 0)
            visibility = View.GONE
            isClickable = true
        }
        private val titleHolder = FrameLayout(activity)
        private val versionView = TextView(activity).apply {
            setTextColor(Color.parseColor("#8E8E93"))
            textSize = 11f
        }
        private val body = FrameLayout(activity)
        private var menuTitle: View? = null
        private var menuBody: View? = null
        private var closeFn: (() -> Unit)? = null
        private val menuWidth = minOf(dp(activity, 460), (activity.resources.displayMetrics.widthPixels * 0.9).toInt())

        init {
            val header = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(activity, 12))
                addView(backBtn)
                addView(titleHolder, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(versionView)
            }
            card.addView(header)
            card.addView(body)
            backBtn.setOnClickListener { back() }
        }

        fun setMenu(version: String?, menuContent: View) {
            versionView.text = version ?: ""
            menuTitle = TextView(activity).apply {
                text = "메뉴"
                setTextColor(Color.WHITE)
                textSize = 17f
                setTypeface(null, Typeface.BOLD)
            }
            menuBody = menuContent
            titleHolder.removeAllViews()
            titleHolder.addView(menuTitle)
            body.removeAllViews()
            body.addView(menuContent)
        }

        fun show() {
            closeFn = present(activity, root, card, menuWidth, "menuCard", false, topRight = true) {}
        }

        fun close() {
            closeFn?.invoke()
            closeFn = null
        }

        private fun resizeCard(widthPx: Int) {
            val lp = card.layoutParams
            if (lp != null && lp.width != widthPx) {
                lp.width = widthPx
                card.layoutParams = lp
            }
            card.post {
                val maxX = (root.width - card.width).coerceAtLeast(0).toFloat()
                val maxY = (root.height - card.height).coerceAtLeast(0).toFloat()
                if (card.x > maxX) card.x = maxX
                if (card.y > maxY) card.y = maxY
            }
        }

        /** 카드 안을 하위 화면으로 바꿈. 제목 자리에는 titleView, 아래에는 content와 버튼들이 들어감. */
        fun showSubPage(
            titleView: View,
            content: View,
            maxDp: Int,
            reserveDp: Int,
            widthDp: Int,
            buttons: List<SubButton>
        ) {
            (titleView.parent as? ViewGroup)?.removeView(titleView)
            titleHolder.removeAllViews()
            titleHolder.addView(titleView)
            backBtn.visibility = View.VISIBLE
            versionView.visibility = View.GONE

            val page = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            (content.parent as? ViewGroup)?.removeView(content)
            val h = minOf(dp(activity, maxDp), activity.resources.displayMetrics.heightPixels - dp(activity, reserveDp))
                .coerceAtLeast(dp(activity, 140))
            page.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h))
            if (buttons.isNotEmpty()) {
                val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                buttons.forEachIndexed { i, b ->
                    row.addView(makeButton(activity, b.label, b.primary) { b.onClick() },
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (i > 0) marginStart = dp(activity, 8)
                        })
                }
                page.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(activity, 12) })
            }
            body.removeAllViews()
            body.addView(page)
            resizeCard(minOf(dp(activity, widthDp), (activity.resources.displayMetrics.widthPixels * 0.94).toInt()))
        }

        fun back() {
            backBtn.visibility = View.GONE
            versionView.visibility = View.VISIBLE
            titleHolder.removeAllViews()
            titleHolder.addView(menuTitle)
            body.removeAllViews()
            body.addView(menuBody)
            resizeCard(menuWidth)
        }
    }

    /**
     * "메뉴"(≡) 버튼을 눌렀을 때 뜨던 세로 목록 패널(svSecondaryPanel)을 카드로 대신 보여줌.
     * 기존 패널의 버튼들을 그대로 읽어서(글자·빨간 글자 여부) 3칸 격자로 늘어놓고, 눌렀을 때는
     * 원래 버튼의 클릭(길게 누르기도)을 대신 실행하므로 각 화면의 기존 동작은 그대로임.
     * "설정" 버튼만은 카드를 닫지 않고, 같은 카드 안에서 설정 화면으로 바뀜(← 로 복귀).
     * 패널 자체는 계속 숨겨둔 채로 씀. #문제시 원복
     */
    fun showMenuFromPanel(activity: Activity, root: ViewGroup, panel: ViewGroup): () -> Unit {
        val normal = ArrayList<Pair<TextView, String>>()
        val danger = ArrayList<Pair<TextView, String>>()
        var versionText: String? = null
        fun collect(v: View) {
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) collect(v.getChildAt(i))
            } else if (v is TextView && v.visibility != View.GONE) {
                val idName = try { activity.resources.getResourceEntryName(v.id) } catch (e: Exception) { "" }
                if (idName == "tvAppVersion") {
                    if (v.text.isNotBlank()) versionText = v.text.toString()
                } else if (v.isClickable && v.text.isNotBlank()) {
                    if (v.currentTextColor == Color.parseColor("#FF453A")) danger.add(v to idName) else normal.add(v to idName)
                }
            }
        }
        collect(panel)

        val host = MenuHost(activity, root)
        val menuContent = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        fun addGrid(items: List<Pair<TextView, String>>, cols: Int, isDanger: Boolean) {
            items.chunked(cols).forEach { rowItems ->
                val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                // 마지막 줄처럼 항목이 3개보다 적은 줄은 빈 칸을 남기지 않고 그 줄 항목들이 카드
                // 폭 전체를 나눠 갖게 함(재억 요청). #문제시 원복
                for (i in rowItems.indices) {
                    val entry = rowItems[i]
                    val cell: View = run {
                        val src = entry.first
                        TextView(activity).apply {
                            text = src.text
                            gravity = Gravity.CENTER
                            textSize = 14f
                            setPadding(dp(activity, 6), dp(activity, 13), dp(activity, 6), dp(activity, 13))
                            setTextColor(Color.parseColor(if (isDanger) "#FF453A" else "#F2F2F7"))
                            background = roundedFill(activity, if (isDanger) "#26FF453A" else "#1AFFFFFF")
                            isClickable = true
                            setOnClickListener {
                                if (entry.second == "btnEditKey") {
                                    pendingEmbeddedHost = host
                                    src.performClick()
                                    if (pendingEmbeddedHost != null) {
                                        pendingEmbeddedHost = null
                                        host.close()
                                    }
                                } else {
                                    host.close()
                                    src.performClick()
                                }
                            }
                            if (src.isLongClickable) {
                                setOnLongClickListener {
                                    host.close()
                                    src.performLongClick()
                                    true
                                }
                            }
                        }
                    }
                    row.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (i > 0) marginStart = dp(activity, 8)
                    })
                }
                menuContent.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(activity, 8) })
            }
        }
        addGrid(normal, 3, false)
        addGrid(danger, 3, true)

        host.setMenu(versionText, menuContent)
        host.show()
        return { host.close() }
    }

    /** 위쪽 라벨/제목/설명 + 세로 선택 버튼들 + 취소 버튼으로 된 확인 카드. */
    fun showChoice(
        activity: Activity,
        root: ViewGroup,
        topLabel: String?,
        title: String,
        message: String?,
        options: List<Option>,
        cancelLabel: String = "취소",
        onCancel: (() -> Unit)? = null
    ): () -> Unit {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = newCardBackground(activity)
            setPadding(dp(activity, 20), dp(activity, 18), dp(activity, 20), dp(activity, 16))
        }
        if (topLabel != null) {
            card.addView(TextView(activity).apply {
                text = topLabel
                setTextColor(Color.parseColor("#FFD54F"))
                textSize = 12f
                setPadding(0, 0, 0, dp(activity, 4))
            })
        }
        card.addView(TextView(activity).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(activity, if (message != null) 6 else 14))
        })
        if (message != null) {
            card.addView(TextView(activity).apply {
                text = message
                setTextColor(Color.parseColor("#BBBBBB"))
                textSize = 13f
                setPadding(0, 0, 0, dp(activity, 14))
            })
        }

        lateinit var close: () -> Unit
        options.forEach { opt ->
            card.addView(makeButton(activity, opt.label, opt.primary) {
                close()
                opt.onClick()
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(activity, 8) })
        }
        card.addView(makeButton(activity, cancelLabel, false) {
            close()
            onCancel?.invoke()
        })

        val width = minOf(dp(activity, 360), (activity.resources.displayMetrics.widthPixels * 0.42).toInt())
            .coerceAtLeast(minOf(dp(activity, 280), activity.resources.displayMetrics.widthPixels - dp(activity, 24)))
        close = present(activity, root, card, width, "kakaoPopupCard", false) { onCancel?.invoke() }
        return close
    }
}
