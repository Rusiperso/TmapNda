package com.tmap.nda

import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.min

/**
 * 차량 디스플레이용 가로 HUD 폭 자동 맞춤.
 *
 * 물리적인 "6인치/7인치" 값이나 raw pixel만 믿지 않고, 현재 Activity가 실제로 받은
 * window 크기에서 system bar/display cutout을 제외한 뒤 dp와 화면비를 계산한다.
 * 따라서 800x480 소형 내비, 1280x720 일반 내비, 초광폭 커브드 화면뿐 아니라
 * 제조사가 density를 다르게 설정한 기기에서도 지도 영역을 일정 비율 이상 보존한다.
 *
 * 세로 레이아웃에는 llLeftHudPanel이 없으므로 아무 값도 덮어쓰지 않는다. 이 조건이
 * 중요하다. 이전 구현은 세로에서도 검색창/카카오 지도에 160dp 이상의 marginStart를
 * 넣어 화면이 오른쪽으로 밀리거나 잘릴 수 있었다.
 */
object HudScale {

    internal enum class Profile { COMPACT, STANDARD, EXPANDED, ULTRAWIDE }

    internal data class Spec(
        val profile: Profile,
        val availableWidthDp: Float,
        val availableHeightDp: Float,
        val panelWidthDp: Float
    )

    /**
     * 레이아웃 완료/회전/해상도 변경 때마다 자동으로 다시 계산한다.
     * panelView가 null이면 세로 화면이므로 즉시 종료한다.
     */
    fun install(
        rootView: View,
        panelView: View?,
        marginedViews: List<View?>
    ) {
        if (panelView == null) return

        var lastSignature = ""

        fun applyNow() {
            if (rootView.width <= 0 || rootView.height <= 0) return

            val insets = currentSafeInsets(rootView)
            val availableWidthPx =
                (rootView.width - insets.left - insets.right).coerceAtLeast(1)
            val availableHeightPx =
                (rootView.height - insets.top - insets.bottom).coerceAtLeast(1)
            val density = rootView.resources.displayMetrics.density.coerceAtLeast(0.75f)
            val spec = calculateSpec(availableWidthPx, availableHeightPx, density)
            val panelWidthPx = (spec.panelWidthDp * density).toInt().coerceAtLeast(1)

            val signature = buildString {
                append(availableWidthPx).append('x').append(availableHeightPx)
                append('@').append(density)
                append(':').append(panelWidthPx)
            }
            if (signature == lastSignature) return
            lastSignature = signature

            panelView.layoutParams?.let { lp ->
                if (lp.width != panelWidthPx) {
                    lp.width = panelWidthPx
                    panelView.layoutParams = lp
                }
            }

            marginedViews.forEach { view ->
                val lp = view?.layoutParams
                if (lp is ViewGroup.MarginLayoutParams && lp.marginStart != panelWidthPx) {
                    lp.marginStart = panelWidthPx
                    view.layoutParams = lp
                }
            }

            NavLogger.d(
                rootView.context,
                "HudScale: ${spec.profile} " +
                    "${spec.availableWidthDp.toInt()}x${spec.availableHeightDp.toInt()}dp " +
                    "panel=${spec.panelWidthDp.toInt()}dp"
            )
        }

        rootView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyNow() }

        rootView.post { applyNow() }
        ViewCompat.requestApplyInsets(rootView)
    }

    /** 순수 계산 함수라 실제 차량 없이도 해상도별 단위 테스트가 가능하다. */
    internal fun calculateSpec(widthPx: Int, heightPx: Int, density: Float): Spec {
        val safeDensity = density.coerceAtLeast(0.75f)
        val widthDp = widthPx / safeDensity
        val heightDp = heightPx / safeDensity
        val aspectRatio = widthDp / heightDp.coerceAtLeast(1f)

        val profile = when {
            aspectRatio >= 2.20f -> Profile.ULTRAWIDE
            widthDp >= 840f -> Profile.EXPANDED
            widthDp >= 600f -> Profile.STANDARD
            else -> Profile.COMPACT
        }

        // 작은 화면은 정보 패널의 최소 가독성을 확보하고, 초광폭 화면은 지도에 더 많은
        // 공간을 준다. 어떤 화면에서도 패널이 전체 폭의 28%를 넘지 않게 제한한다.
        val targetShare = when {
            aspectRatio >= 2.40f -> 0.15f
            widthDp >= 1200f -> 0.17f
            widthDp >= 900f -> 0.18f
            widthDp >= 720f -> 0.20f
            else -> 0.23f
        }
        val minPanelDp = when {
            heightDp < 400f -> 140f
            heightDp < 480f -> 150f
            else -> 160f
        }
        val maxPanelDp = when {
            heightDp < 420f -> 190f
            heightDp < 600f -> 240f
            else -> 320f
        }

        val upperBound = min(maxPanelDp, widthDp * 0.28f).coerceAtLeast(1f)
        val lowerBound = min(minPanelDp, upperBound)
        val panelWidthDp = (widthDp * targetShare).coerceIn(lowerBound, upperBound)

        return Spec(profile, widthDp, heightDp, panelWidthDp)
    }

    private fun currentSafeInsets(view: View): Insets {
        val compatInsets = ViewCompat.getRootWindowInsets(view)
            ?.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            ?: return Insets.NONE
        return Insets.of(
            compatInsets.left,
            compatInsets.top,
            compatInsets.right,
            compatInsets.bottom
        )
    }
}
