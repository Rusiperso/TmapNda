package com.tmap.nda

import android.content.Context
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.WeakHashMap

/**
 * v19.3.81: 재억 요청 - 왼쪽 위 빠른 아이콘(즐겨찾기/주변/경유지/경유지 취소)을 콤마 연결상태 칩과
 * 같은 가로·세로 크기로 2줄 x 2칸에 세움(크기 선택은 없앰).
 *  - 살짝 끌면 바로 옮겨짐(팝업 카드와 같은 방식), 그냥 톡 누르면 원래 기능
 *  - 끌어서 옮긴 아이콘만 그 자리에 남고, 나머지는 격자 자리를 그대로 따라감
 *  - 아이콘 그림/이모티콘/글자 크기를 네 칸 모두 같게 맞춤
 * #문제시 원복
 */
object QuickIconGrid {
    private const val FALLBACK_W_DP = 100f
    private const val FALLBACK_H_DP = 50f
    private const val ICON_DP = 27f
    private const val EMOJI_SP = 15f
    private const val LABEL_SP = 12f
    private const val GAP_DP = 4f
    // 재억 요청(2026-09-28): 즐겨찾기/주변/경유지/경유취소/메뉴 아이콘은 위의 연결상태 칩과 폭·높이를
    // 똑같이 맞춤(칩 폭 + 여분 없음). 실측 칩 133x61px → 아이콘도 133x61px. #문제시 원복
    private const val ORIGIN_X_DP = 10f
    private const val ORIGIN_Y_DP = 76f

    class Item(
        val view: View,
        val key: String,
        /** 0=왼쪽위, 1=오른쪽위, 2=왼쪽아래, 3=오른쪽아래 */
        val slot: Int,
        val onTap: () -> Unit
    ) {
        lateinit var group: List<Item>
        /** 칸 크기의 기준이 되는 뷰(콤마 연결상태 칩). null이면 100x50dp. */
        var sizeRef: View? = null
        var dragging = false
        /** false면 이 아이콘은 격자에서 빠진 상태(메뉴 버튼을 상단바로 되돌린 경우). */
        var active = true
        var wPx = 0
        var hPx = 0
    }

    private val registry = WeakHashMap<View, Item>()

    /**
     * 재억 요청(2026-09-28): 끌어놓을 때 자석처럼 줄이 맞도록 기준이 될 화면 위 박스들(속도 박스, 콤마 칩 등).
     * 첫 번째 박스의 왼쪽 끝이 아이콘 격자의 기본 시작점도 됨. 각 화면에서 setup 전에 지정. #문제시 원복
     */
    var snapTargets: () -> List<View> = { emptyList() }

    private const val SNAP_DP = 6f

    private fun boundsInParent(target: View, parent: View): FloatArray? {
        if (target.visibility != View.VISIBLE || target.width <= 0) return null
        val pl = IntArray(2); parent.getLocationInWindow(pl)
        val tl = IntArray(2); target.getLocationInWindow(tl)
        val l = (tl[0] - pl[0]).toFloat()
        val t = (tl[1] - pl[1]).toFloat()
        return floatArrayOf(l, t, l + target.width, t + target.height)
    }

    private fun originX(context: Context, parent: View?): Float {
        if (parent != null) {
            for (t in snapTargets()) {
                val b = boundsInParent(t, parent) ?: continue
                return b[0]
            }
        }
        return dpPx(context, ORIGIN_X_DP)
    }

    private fun snapPosition(context: Context, item: Item, x: Float, y: Float): Pair<Float, Float> {
        val v = item.view
        val parent = v.parent as? View ?: return x to y
        val th = dpPx(context, SNAP_DP)
        val gap = dpPx(context, GAP_DP)
        val w = v.width.toFloat()
        val h = v.height.toFloat()
        val rects = ArrayList<FloatArray>()
        registry.values.forEach { o ->
            if (o !== item && o.active && o.view.visibility == View.VISIBLE && o.view.parent === v.parent)
                rects.add(floatArrayOf(o.view.x, o.view.y, o.view.x + o.view.width, o.view.y + o.view.height))
        }
        snapTargets().forEach { t -> boundsInParent(t, parent)?.let { rects.add(it) } }
        var bx = x
        var by = y
        var bestDx = th + 1f
        var bestDy = th + 1f
        for (r in rects) {
            // 옆에 붙임은 같은 줄(위아래로 겹칠 때)일 때만, 아래/위에 붙임은 같은 칸(좌우로 겹칠 때)일 때만.
            // 다른 줄에 있는 박스의 "옆"에 붙어버리는 엉뚱한 자석 방지. 끝 맞춤(왼쪽/오른쪽/위/아래)은 항상 허용.
            val sameRow = y < r[3] && y + h > r[1]
            val sameCol = x < r[2] && x + w > r[0]
            val xs = ArrayList<Float>(4)
            xs.add(r[0]); xs.add(r[2] - w)
            if (sameRow) { xs.add(r[2] + gap); xs.add(r[0] - w - gap) }
            for (c in xs) {
                val d = kotlin.math.abs(x - c)
                if (d <= th && d < bestDx) { bestDx = d; bx = c }
            }
            val ys = ArrayList<Float>(4)
            ys.add(r[1]); ys.add(r[3] - h)
            if (sameCol) { ys.add(r[3] + gap); ys.add(r[1] - h - gap) }
            for (c in ys) {
                val d = kotlin.math.abs(y - c)
                if (d <= th && d < bestDy) { bestDy = d; by = c }
            }
        }
        return bx to by
    }

    /** 재억 요청(2026-09-28): 설정의 "아이콘 위치 잠금"이 켜져 있으면 아이콘을 눌러도 안 움직이고 탭만 됨. #문제시 원복 */
    const val LOCK_PREF_KEY = "lock_quick_icons"
    fun isLocked(c: Context): Boolean =
        c.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).getBoolean(LOCK_PREF_KEY, false)

    private fun prefs(c: Context) = c.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)

    private fun suffix(c: Context) =
        if (c.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) "land" else "port"

    private fun dpPx(c: Context, dp: Float) = dp * c.resources.displayMetrics.density

    private fun keyX(c: Context, item: Item) = "qi_${item.key}_x_${suffix(c)}"
    private fun keyY(c: Context, item: Item) = "qi_${item.key}_y_${suffix(c)}"

    fun setup(context: Context, items: List<Item>, sizeRef: View? = null) {
        items.forEach { it.group = items; it.sizeRef = sizeRef; registry[it.view] = it }
        // 상태 칩 글자가 바뀌어 칩 크기가 달라지면 아이콘 칸도 같이 다시 맞춤
        sizeRef?.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if ((r - l) != (or - ol) || (b - t) != (ob - ot)) sizeRef.post { layoutAll(context, items) }
        }
        items.forEach { attachTouch(context, it) }
        // 숨김(GONE)이었다가 다시 보일 때 뷰의 기준 위치(left/top)가 바뀌므로, 배치가 끝날 때마다 저장된 자리로 다시 맞춤
        items.forEach { item ->
            item.view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                if (!item.dragging && v.visibility == View.VISIBLE && v.width > 0 && item.wPx > 0) applyPosition(context, item)
            }
        }
        items.first().view.post { layoutAll(context, items) }
    }

    /** 격자에서 빼고 터치 리스너도 걷어냄(메뉴 버튼을 상단바로 되돌릴 때). */
    fun release(view: View) {
        val item = registry.remove(view) ?: return
        item.active = false
        item.dragging = false
        view.setOnTouchListener(null)
    }

    /** 표시 여부가 바뀐 뒤처럼 한 개만 자리를 다시 잡고 싶을 때. */
    fun restore(context: Context, view: View) {
        val item = registry[view] ?: return
        view.post { layoutOne(context, item) }
    }

    private fun layoutAll(context: Context, items: List<Item>) {
        items.forEach { layoutOne(context, it) }
    }

    private fun layoutOne(context: Context, item: Item) {
        val v = item.view
        val ref = item.sizeRef
        if (!item.active) return
        val wPx = (if (ref != null && ref.width > 0) ref.width else dpPx(context, FALLBACK_W_DP).toInt())
        val hPx = if (ref != null && ref.height > 0) ref.height else dpPx(context, FALLBACK_H_DP).toInt()

        val lp = v.layoutParams
        if (lp.width != wPx || lp.height != hPx) {
            lp.width = wPx
            lp.height = hPx
            v.layoutParams = lp
        }
        // 가로로 긴 칸이라 그림과 글자를 옆으로 나란히 놓음
        if (v is LinearLayout) {
            v.orientation = LinearLayout.HORIZONTAL
            v.gravity = android.view.Gravity.CENTER
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val child = v.getChildAt(i)
                when (child) {
                    is ImageView -> {
                        val iconPx = dpPx(context, ICON_DP).toInt()
                        val cp = child.layoutParams
                        if (cp.width != iconPx || cp.height != iconPx) {
                            cp.width = iconPx
                            cp.height = iconPx
                            child.layoutParams = cp
                        }
                    }
                    is TextView -> {
                        // 이모티콘(❤️)은 칸을 꽉 채워 그려지고 나머지 그림은 안쪽 여백이 있어서, 눈에 보이는 크기가 같아지게 이모티콘을 더 작게 지정. 나머지 글자는 모두 같은 크기
                        val isEmoji = child.text.any { it.code in 0x2000..0x2BFF || it.code == 0xFE0F }
                        child.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isEmoji) EMOJI_SP else LABEL_SP)
                        (child.layoutParams as? LinearLayout.LayoutParams)?.let { cp ->
                            val wantStart = if (isEmoji) 0 else dpPx(context, 5f).toInt()
                            if (cp.topMargin != 0 || cp.marginStart != wantStart) {
                                cp.topMargin = 0
                                cp.marginStart = wantStart
                                child.layoutParams = cp
                            }
                        }
                    }
                }
            }
        }

        item.wPx = wPx
        item.hPx = hPx
        applyPosition(context, item)
        PanelDragHelper.forceToFront(v)
    }

    private fun applyPosition(context: Context, item: Item) {
        if (!item.active) return
        val v = item.view
        val wPx = item.wPx
        val hPx = item.hPx
        val p = prefs(context)
        val parent = v.parent as? View
        if (p.contains(keyX(context, item)) && p.contains(keyY(context, item))) {
            var x = p.getFloat(keyX(context, item), 0f)
            var y = p.getFloat(keyY(context, item), 0f)
            if (parent != null && parent.width > 0 && parent.height > 0) {
                x = x.coerceIn(0f, (parent.width - wPx).toFloat().coerceAtLeast(0f))
                y = y.coerceIn(0f, (parent.height - hPx).toFloat().coerceAtLeast(0f))
            }
            v.x = x
            v.y = y
        } else {
            val gap = dpPx(context, GAP_DP)
            v.x = originX(context, parent) + (item.slot % 2) * (wPx + gap)
            v.y = dpPx(context, ORIGIN_Y_DP) + (item.slot / 2) * (hPx + gap)
        }
    }

    private fun attachTouch(context: Context, item: Item) {
        val v = item.view
        val slop = dpPx(context, 10f)
        var downX = 0f
        var downY = 0f
        var dX = 0f
        var dY = 0f
        var lockedMoved = false

        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    PanelDragHelper.forceToFront(v)
                    item.dragging = false
                    lockedMoved = false
                    downX = e.rawX
                    downY = e.rawY
                    dX = v.x - e.rawX
                    dY = v.y - e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isLocked(context)) {
                        // 잠금 중: 위치는 절대 안 바뀜. 손가락이 많이 움직였으면 탭도 안 침(잘못 스친 경우).
                        if (kotlin.math.hypot((e.rawX - downX).toDouble(), (e.rawY - downY).toDouble()) >= slop) lockedMoved = true
                        return@setOnTouchListener true
                    }
                    if (!item.dragging &&
                        kotlin.math.hypot((e.rawX - downX).toDouble(), (e.rawY - downY).toDouble()) < slop
                    ) return@setOnTouchListener true
                    item.dragging = true
                    val parent = v.parent as? View
                    val maxX = ((parent?.width ?: 0) - v.width).coerceAtLeast(0).toFloat()
                    val maxY = ((parent?.height ?: 0) - v.height).coerceAtLeast(0).toFloat()
                    val (snapX, snapY) = snapPosition(context, item, (e.rawX + dX).coerceIn(0f, maxX), (e.rawY + dY).coerceIn(0f, maxY))
                    v.x = snapX.coerceIn(0f, maxX)
                    v.y = snapY.coerceIn(0f, maxY)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (item.dragging) {
                        prefs(context).edit()
                            .putFloat(keyX(context, item), v.x)
                            .putFloat(keyY(context, item), v.y)
                            .apply()
                    } else if (e.action == MotionEvent.ACTION_UP && !lockedMoved) {
                        item.onTap()
                    }
                    item.dragging = false
                    true
                }
                else -> false
            }
        }
    }
}
