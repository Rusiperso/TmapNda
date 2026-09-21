package com.tmap.nda

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.kakaomobility.knsdk.common.util.FloatPoint
import com.kakaomobility.knsdk.map.knmapview.KNMapView

/**
 * 내 차 위치 보기용 덮개 화면 - 카카오 지도 위에 걷는 길(선), 차 표시, 내 위치 점을 직접 그림.
 * 카카오 SDK의 선 그리기는 두 점짜리 직선뿐이라, 대신 지도의 좌표->화면 변환(katecToScreen)을
 * 써서 0.07초마다 다시 그림(지도를 밀거나 확대해도 따라감). 터치는 그대로 아래 지도로 통과.
 */
class ParkedCarOverlayView(
    context: Context,
    private val mapProvider: () -> KNMapView?
) : View(context) {
    var path: List<FloatPoint> = emptyList()
    var car: FloatPoint? = null
    var me: FloatPoint? = null

    private val d = resources.displayMetrics.density
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 9f * d; color = Color.WHITE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5f * d; color = Color.parseColor("#2F6FDB")
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * d; color = Color.WHITE
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 12f * d; textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val locA = IntArray(2)
    private val locB = IntArray(2)

    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 70L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val mv = mapProvider() ?: return
        if (mv.width < 10) return
        getLocationOnScreen(locA)
        mv.getLocationOnScreen(locB)
        val dx = (locB[0] - locA[0]).toFloat()
        val dy = (locB[1] - locA[1]).toFloat()
        fun sx(p: FloatPoint) = mv.katecToScreen(p).x + dx
        fun sy(p: FloatPoint) = mv.katecToScreen(p).y + dy

        val pts = path
        if (pts.size >= 2) {
            val p = Path()
            pts.forEachIndexed { i, pt ->
                val x = sx(pt); val y = sy(pt)
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            canvas.drawPath(p, outline)
            canvas.drawPath(p, line)
        }
        me?.let {
            val x = sx(it); val y = sy(it)
            fill.color = Color.parseColor("#2F6FDB")
            canvas.drawCircle(x, y, 9f * d, fill)
            canvas.drawCircle(x, y, 9f * d, ring)
        }
        car?.let {
            val x = sx(it); val y = sy(it)
            fill.color = Color.parseColor("#E8613C")
            canvas.drawCircle(x, y, 15f * d, fill)
            canvas.drawCircle(x, y, 15f * d, ring)
            canvas.drawText("차", x, y + 4.5f * d, label)
        }
    }
}
