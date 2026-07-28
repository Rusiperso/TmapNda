package com.tmap.nda

import android.app.Activity
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlin.math.abs

/**
 * 폰/태블릿 등 화면 크기(smallestScreenWidthDp)가 다른 기기에서
 * 동일한 XML(고정 dp/sp)이 서로 다르게 보이는 문제를 완화하기 위한
 * 런타임 스케일링 유틸.
 *
 * activity_map.xml / layout-land/activity_map.xml 은 특정 기준 화면(스마트폰,
 * sw ~ 360dp)에 맞춰 dp/sp가 하드코딩되어 있음. 태블릿처럼 sw 값이 큰 기기에서는
 * 뷰 트리를 순회하며 textSize/padding/margin/고정 width·height 에 비율을 곱해
 * 기준 화면 대비 상대적으로 일관된 비율로 보이게 만든다.
 *
 * #문제시 원복: apply() 호출부(MapActivity.onCreate)만 제거하면 이전 동작으로 복귀.
 */
object UiAutoScaler {

    // 레이아웃이 설계된 기준 스마트폰 smallestWidthDp
    private const val REF_SW_DP = 360f

    // 과도한 확대/축소 방지용 클램프
    private const val MIN_SCALE = 0.8f
    private const val MAX_SCALE = 1.8f

    // 기준값과 거의 같으면(스마트폰 정상 범위) 굳이 순회하지 않음
    private const val SKIP_THRESHOLD = 0.03f

    fun apply(activity: Activity, root: View) {
        val swDp = activity.resources.configuration.smallestScreenWidthDp.toFloat()
        if (swDp <= 0f) return

        val rawScale = swDp / REF_SW_DP
        val scale = rawScale.coerceIn(MIN_SCALE, MAX_SCALE)

        if (abs(scale - 1f) < SKIP_THRESHOLD) return

        NavLogger.d(activity, "UiAutoScaler: swDp=$swDp scale=$scale 적용")
        scaleView(root, scale)
    }

    private fun scaleView(view: View, scale: Float) {
        if (view is TextView) {
            val curPx = view.textSize
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, curPx * scale)
        }

        view.setPadding(
            (view.paddingLeft * scale).toInt(),
            (view.paddingTop * scale).toInt(),
            (view.paddingRight * scale).toInt(),
            (view.paddingBottom * scale).toInt()
        )

        val lp = view.layoutParams
        if (lp != null) {
            if (lp is ViewGroup.MarginLayoutParams) {
                lp.leftMargin = (lp.leftMargin * scale).toInt()
                lp.topMargin = (lp.topMargin * scale).toInt()
                lp.rightMargin = (lp.rightMargin * scale).toInt()
                lp.bottomMargin = (lp.bottomMargin * scale).toInt()
            }
            // MATCH_PARENT(-1) / WRAP_CONTENT(-2) 는 건드리지 않고,
            // 고정 dp로 지정된(0보다 큰) width/height 만 스케일링
            if (lp.width > 0) lp.width = (lp.width * scale).toInt()
            if (lp.height > 0) lp.height = (lp.height * scale).toInt()
            view.layoutParams = lp
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                scaleView(view.getChildAt(i), scale)
            }
        }
    }
}
