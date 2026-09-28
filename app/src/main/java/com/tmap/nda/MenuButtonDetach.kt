package com.tmap.nda

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.WeakHashMap

/**
 * 재억 요청(2026-09-28): 상단바의 ≡ 메뉴 버튼을 설정에서 "따로 떼어내기"를 켜면, 즐겨찾기/주변/경유지
 * 아이콘과 같은 크기의 아이콘 칸(QuickIconGrid)으로 상단바 밖에 나오고, 살짝 끌어 옮기면 그 자리가
 * 저장됨(가로/세로 따로). 끄면 원래 상단바 자리로 돌아감. 기본값 꺼짐. #문제시 원복
 */
object MenuButtonDetach {
    const val PREF_KEY = "detach_menu_button"
    private const val GRID_SLOT = 4 // 2x2 격자 아래 줄 왼쪽

    private class Saved(
        val parent: ViewGroup,
        val index: Int,
        val layoutParams: ViewGroup.LayoutParams,
        val elevation: Float,
        val orientation: Int,
        val gravity: Int,
        val childSizes: List<IntArray>, // 자식별 [width, height, topMargin, marginStart]
        val textSizesPx: List<Float>
    )

    private val saved = WeakHashMap<View, Saved>()

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).getBoolean(PREF_KEY, false)

    /**
     * 설정값에 맞춰 메뉴 버튼 위치를 맞춤(켜졌는데 아직 안 뺐으면 빼고, 꺼졌는데 빠져 있으면 되돌림).
     * 여러 번 불러도 안전. host = 아이콘 칸들이 올라가 있는 화면 전체 컨테이너(즐겨찾기 버튼의 부모).
     */
    fun sync(context: Context, btn: View, host: ViewGroup?, sizeRef: View?) {
        val want = isEnabled(context)
        val detached = saved.containsKey(btn)
        if (want && !detached) {
            if (host != null) detach(context, btn, host, sizeRef)
        } else if (!want && detached) {
            attachBack(context, btn)
        } else if (want) {
            QuickIconGrid.restore(context, btn)
        }
    }

    private fun detach(context: Context, btn: View, host: ViewGroup, sizeRef: View?) {
        val parent = btn.parent as? ViewGroup ?: return
        val childSizes = ArrayList<IntArray>()
        val textSizes = ArrayList<Float>()
        if (btn is ViewGroup) {
            for (i in 0 until btn.childCount) {
                val c = btn.getChildAt(i)
                val lp = c.layoutParams
                val m = lp as? ViewGroup.MarginLayoutParams
                childSizes.add(intArrayOf(lp.width, lp.height, m?.topMargin ?: 0, m?.marginStart ?: 0))
                textSizes.add(if (c is TextView) c.textSize else 0f)
            }
        }
        saved[btn] = Saved(
            parent, parent.indexOfChild(btn), btn.layoutParams, btn.elevation,
            (btn as? LinearLayout)?.orientation ?: LinearLayout.VERTICAL,
            (btn as? LinearLayout)?.gravity ?: Gravity.CENTER,
            childSizes, textSizes
        )
        parent.removeView(btn)
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        host.addView(btn, lp)
        btn.elevation = 20f * context.resources.displayMetrics.density
        QuickIconGrid.setup(context, listOf(QuickIconGrid.Item(btn, "btnMoreMenu", GRID_SLOT) { btn.performClick() }), sizeRef)
    }

    private fun attachBack(context: Context, btn: View) {
        val s = saved.remove(btn) ?: return
        QuickIconGrid.release(btn)
        (btn.parent as? ViewGroup)?.removeView(btn)
        btn.translationX = 0f
        btn.translationY = 0f
        btn.elevation = s.elevation
        if (btn is LinearLayout) {
            btn.orientation = s.orientation
            btn.gravity = s.gravity
        }
        if (btn is ViewGroup) {
            for (i in 0 until minOf(btn.childCount, s.childSizes.size)) {
                val c = btn.getChildAt(i)
                val sz = s.childSizes[i]
                val lp = c.layoutParams
                lp.width = sz[0]
                lp.height = sz[1]
                (lp as? ViewGroup.MarginLayoutParams)?.let { it.topMargin = sz[2]; it.marginStart = sz[3] }
                c.layoutParams = lp
                if (c is TextView && s.textSizesPx[i] > 0f) c.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, s.textSizesPx[i])
                if (c is ImageView) c.requestLayout()
            }
        }
        s.parent.addView(btn, s.index.coerceIn(0, s.parent.childCount), s.layoutParams)
    }
}
