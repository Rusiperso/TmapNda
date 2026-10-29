package com.tmap.nda

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/** 돌발상황 표시 모양: 사고=빨간 동그라미 느낌표, 공사=주황 네모 "공", 기상=파란 동그라미 "비", 그 밖=회색 동그라미. */
object IncidentIcons {
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private fun color(t: String) = when (t) {
        "acc", "dis" -> 0xFFE24B4A.toInt()
        "cor" -> 0xFFBA7517.toInt()
        "wea" -> 0xFF378ADD.toInt()
        else -> 0xFF888780.toInt()
    }
    private fun label(t: String) = when (t) { "cor" -> "공"; "wea" -> "비"; else -> "!" }

    /** (cx, cy)를 가운데로 size 크기의 표시를 그린다. */
    @Synchronized
    fun draw(c: Canvas, cx: Float, cy: Float, size: Float, t: String) {
        val r = size / 2f
        fillP.color = color(t)
        ringP.strokeWidth = size * 0.08f
        if (t == "cor") {
            val rect = RectF(cx - r * 0.9f, cy - r * 0.9f, cx + r * 0.9f, cy + r * 0.9f)
            c.drawRoundRect(rect, r * 0.25f, r * 0.25f, fillP)
            c.drawRoundRect(rect, r * 0.25f, r * 0.25f, ringP)
        } else {
            c.drawCircle(cx, cy, r * 0.95f, fillP)
            c.drawCircle(cx, cy, r * 0.95f, ringP)
        }
        textP.textSize = size * 0.55f
        c.drawText(label(t), cx, cy + size * 0.2f, textP)
    }

    fun bitmap(t: String, size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        draw(Canvas(b), size / 2f, size / 2f, size.toFloat(), t)
        return b
    }
}
