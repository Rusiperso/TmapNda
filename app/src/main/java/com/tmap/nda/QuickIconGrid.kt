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
    }

    private val registry = WeakHashMap<View, Item>()

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
        items.first().view.post { layoutAll(context, items) }
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
        val wPx = if (ref != null && ref.width > 0) ref.width else dpPx(context, FALLBACK_W_DP).toInt()
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
            v.x = dpPx(context, ORIGIN_X_DP) + (item.slot % 2) * (wPx + gap)
            v.y = dpPx(context, ORIGIN_Y_DP) + (item.slot / 2) * (hPx + gap)
        }
        PanelDragHelper.forceToFront(v)
    }

    private fun attachTouch(context: Context, item: Item) {
        val v = item.view
        val slop = dpPx(context, 10f)
        var downX = 0f
        var downY = 0f
        var dX = 0f
        var dY = 0f
        var dragging = false

        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    PanelDragHelper.forceToFront(v)
                    dragging = false
                    downX = e.rawX
                    downY = e.rawY
                    dX = v.x - e.rawX
                    dY = v.y - e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging &&
                        kotlin.math.hypot((e.rawX - downX).toDouble(), (e.rawY - downY).toDouble()) < slop
                    ) return@setOnTouchListener true
                    dragging = true
                    val parent = v.parent as? View
                    val maxX = ((parent?.width ?: 0) - v.width).coerceAtLeast(0).toFloat()
                    val maxY = ((parent?.height ?: 0) - v.height).coerceAtLeast(0).toFloat()
                    v.x = (e.rawX + dX).coerceIn(0f, maxX)
                    v.y = (e.rawY + dY).coerceIn(0f, maxY)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        prefs(context).edit()
                            .putFloat(keyX(context, item), v.x)
                            .putFloat(keyY(context, item), v.y)
                            .apply()
                    } else if (e.action == MotionEvent.ACTION_UP) {
                        item.onTap()
                    }
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }
}
