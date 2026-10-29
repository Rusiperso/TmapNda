package com.tmap.nda

import android.content.Context
import android.graphics.Canvas
import android.view.View
import com.kakaomobility.knsdk.KNSDK
import com.kakaomobility.knsdk.common.util.FloatPoint
import com.kakaomobility.knsdk.map.knmapview.KNMapView

/**
 * 카카오 지도 위에 돌발상황 표시를 덧그리는 덮개 화면. 터치는 아래 지도로 그대로 통과한다.
 * ParkedCarOverlayView와 같은 방식(지도의 좌표->화면 변환을 0.2초마다 다시 그림)이라 지도를 밀거나 확대해도 따라간다. #문제시 원복
 */
class IncidentOverlayView(
    context: Context,
    private val mapProvider: () -> KNMapView?
) : View(context), IncidentRepository.Listener {
    private class Pt(val p: FloatPoint, val type: String)
    @Volatile private var pts: List<Pt> = emptyList()
    private val d = resources.displayMetrics.density
    private val locA = IntArray(2)
    private val locB = IntArray(2)

    private val ticker = object : Runnable {
        override fun run() {
            if (pts.isNotEmpty()) invalidate()
            postDelayed(this, 200L)
        }
    }

    override fun onIncidentsChanged(list: List<IncidentRepository.Incident>) {
        pts = list.mapNotNull {
            try {
                val k = KNSDK.convertWGS84ToKATEC(it.lon, it.lat)
                Pt(FloatPoint(k.x.toFloat(), k.y.toFloat()), it.type)
            } catch (e: Exception) { null }
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        IncidentRepository.addListener(this)
        onIncidentsChanged(IncidentRepository.items)
        post(ticker)
    }

    override fun onDetachedFromWindow() {
        IncidentRepository.removeListener(this)
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val list = pts
        if (list.isEmpty()) return
        val mv = mapProvider() ?: return
        if (mv.width < 10) return
        getLocationOnScreen(locA)
        mv.getLocationOnScreen(locB)
        val dx = (locB[0] - locA[0]).toFloat()
        val dy = (locB[1] - locA[1]).toFloat()
        val size = 36f * d
        for (it in list) {
            val s = mv.katecToScreen(it.p)
            val x = s.x + dx; val y = s.y + dy
            if (x < -size || y < -size || x > width + size || y > height + size) continue
            IncidentIcons.draw(canvas, x, y, size, it.type)
        }
    }
}
