package com.tmap.nda

import android.content.Context
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs

/**
 * v: 재억 제보(2026-09-03, 사진) - 분할화면을 쓰다가 NDA를 다시 전체화면으로 되돌리면
 * 지도 화면 안의 글자/아이콘/표지판이 통째로 확대돼 서로 겹쳐 보이는 문제.
 *
 * 원인 - 지도(티맵/카카오 SDK)는 화면을 SurfaceView라는 "별도 그림판"에 그림. 분할화면일
 * 때 만들어진 작은 그림판이 전체화면으로 돌아온 뒤에도 그대로 남아 있으면, 안드로이드가
 * 그 작은 그림을 큰 화면 크기로 늘려서 보여주기 때문에 그림 안의 모든 것이 확대돼 보임.
 * 액티비티는 configChanges 선언(분할화면 중 콤마 연결이 끊기지 않게 하려고 넣은 것,
 * AndroidManifest.xml 참고) 때문에 다시 만들어지지 않아서, SDK가 스스로 새 크기로
 * 다시 그리는 계기가 없음.
 *
 * 대응 - 화면 크기가 바뀐 뒤 그림판 크기와 실제 뷰 크기가 다르면, 지도 뷰를 아주 잠깐
 * 숨겼다 다시 보여줌. 그러면 그림판이 지금 크기로 새로 만들어지면서 SDK가 다시 그림.
 * #문제시 원복: 액티비티의 onConfigurationChanged/onMultiWindowModeChanged 호출부만 지우면 됨.
 */
object MapSurfaceRefresher {

    // 분할화면 해제 애니메이션이 끝나고 최종 크기로 자리잡을 때까지 기다리는 시간.
    // 기기마다 애니메이션 길이가 달라서 두 번(빠른 기기/느린 기기) 확인함 - 첫 번째에
    // 이미 고쳐졌으면 두 번째는 크기가 맞으니 아무것도 안 함
    private val SETTLE_DELAYS_MS = longArrayOf(400L, 1200L)
    private const val REDRAW_DELAY_MS = 120L
    private const val SIZE_TOLERANCE_PX = 4

    fun onWindowResized(context: Context, mapView: View?, tag: String) {
        if (mapView == null) return
        for (delay in SETTLE_DELAYS_MS) {
            mapView.postDelayed({ refreshIfStretched(context, mapView, tag) }, delay)
        }
    }

    private fun refreshIfStretched(context: Context, mapView: View, tag: String) {
        // 지금 화면에 안 보이는 지도(예: 티맵 화면 안의 카카오 지도 오버레이)는 건드리지 않음 -
        // 잘못 건드리면 숨겨둔 화면이 켜져버림
        if (!mapView.isShown) return
        if (!findStretchedSurface(context, mapView, tag)) return

        NavLogger.d(context, "[화면크기:$tag] 그림판이 화면 크기와 달라 지도를 다시 그림")
        mapView.visibility = View.INVISIBLE
        mapView.postDelayed({
            mapView.visibility = View.VISIBLE
            mapView.requestLayout()
        }, REDRAW_DELAY_MS)
    }

    private fun findStretchedSurface(context: Context, view: View, tag: String): Boolean {
        if (view is SurfaceView) {
            val frame = view.holder?.surfaceFrame ?: return false
            if (frame.width() <= 0 || view.width <= 0) return false
            val mismatch = abs(frame.width() - view.width) > SIZE_TOLERANCE_PX ||
                abs(frame.height() - view.height) > SIZE_TOLERANCE_PX
            NavLogger.d(
                context,
                "[화면크기:$tag] 그림판 ${frame.width()}x${frame.height()} / 뷰 ${view.width}x${view.height} 불일치=$mismatch"
            )
            return mismatch
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (findStretchedSurface(context, view.getChildAt(i), tag)) return true
            }
        }
        return false
    }
}
