package com.tmap.nda

import android.content.Context
import android.view.View
import android.view.ViewGroup

// v1.9: "7인치에 맞춘 220dp 고정폭이 6인치에서는 잘리고 8인치에서는 남아돈다"는 지적 -
// 좌측 HUD 패널 폭을 고정 dp가 아니라 "실제 화면 폭의 몇 %"로 매 기기마다 런타임에 계산해서
// 적용하도록 바꿈. 너무 작은 화면(패널이 뭉개짐)/너무 큰 화면(패널만 과하게 넓어짐) 방지용으로
// min/max dp 클램프를 둠. 태블릿이나 차량 헤드유닛처럼 폭이 크게 갈리는 기기군에도 그대로 대응됨.
// #문제시 원복 (XML의 220dp 값은 뷰가 인플레이트되는 순간의 기본값/미리보기용으로만 남겨둠 -
// 아래 applyPanelWidth()가 onCreate에서 실제 값으로 즉시 덮어씀)
object HudScale {
    private const val PANEL_WIDTH_PERCENT = 0.16f
    private const val MIN_PANEL_WIDTH_DP = 160f
    private const val MAX_PANEL_WIDTH_DP = 320f

    fun computePanelWidthPx(context: Context): Int {
        val dm = context.resources.displayMetrics
        // 가로모드(HUD 화면) 기준이라 더 긴 변을 "폭"으로 취급 - 회전/기기 보고값 차이에 안전.
        val screenWidthPx = maxOf(dm.widthPixels, dm.heightPixels)
        val minPx = (MIN_PANEL_WIDTH_DP * dm.density).toInt()
        val maxPx = (MAX_PANEL_WIDTH_DP * dm.density).toInt()
        return (screenWidthPx * PANEL_WIDTH_PERCENT).toInt().coerceIn(minPx, maxPx)
    }

    // panelView: 좌측 HUD 패널 자체(폭을 바꿈). marginedViews: 그 패널 폭만큼 marginStart를
    // 맞춰줘야 지도/오버레이가 패널에 안 가리는 다른 뷰들(검색패널, 안내바, 카카오오버레이 등).
    fun applyPanelWidth(panelView: View?, marginedViews: List<View?>) {
        val ctx = panelView?.context ?: marginedViews.firstOrNull { it != null }?.context ?: return
        val panelWidthPx = computePanelWidthPx(ctx)

        panelView?.let { v ->
            val lp = v.layoutParams
            if (lp != null) {
                lp.width = panelWidthPx
                v.layoutParams = lp
            }
        }

        marginedViews.forEach { v ->
            val lp = v?.layoutParams
            if (lp is ViewGroup.MarginLayoutParams) {
                lp.marginStart = panelWidthPx
                v.layoutParams = lp
            }
        }
    }
}
