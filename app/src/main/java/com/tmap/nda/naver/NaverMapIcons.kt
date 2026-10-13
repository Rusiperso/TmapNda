package com.tmap.nda.naver

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface

/** 네이버 지도 앱처럼 보이게 하는 작은 그림들(내 차 화살표, 도착 표시, 길 위 화살표, 회전 표지)을 코드로 그린다. */
object NaverMapIcons {

    const val BLUE = 0xFF1F6FEB.toInt()

    private fun bmp(w: Int, h: Int, draw: (Canvas) -> Unit): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        draw(Canvas(b))
        return b
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }

    /** 내 위치: 흰 동그라미 + 파란 테두리 + 파란 화살표(위쪽이 진행 방향). */
    fun car(size: Int): Bitmap = bmp(size, size) { c ->
        val s = size.toFloat(); val r = s / 2f
        c.drawCircle(r, r, r * 0.92f, fill(0x33000000))
        c.drawCircle(r, r, r * 0.86f, fill(Color.WHITE))
        c.drawCircle(r, r, r * 0.86f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = s * 0.06f; color = BLUE })
        val p = Path().apply {
            moveTo(r, s * 0.20f); lineTo(s * 0.74f, s * 0.72f); lineTo(r, s * 0.60f); lineTo(s * 0.26f, s * 0.72f); close()
        }
        c.drawPath(p, fill(BLUE))
    }

    /** 내 위치로 돌아오기 버튼용 그림. */
    fun recenter(size: Int): Bitmap = bmp(size, size) { c ->
        val s = size.toFloat(); val r = s / 2f
        val st = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = s * 0.07f; color = BLUE; strokeCap = Paint.Cap.ROUND }
        c.drawCircle(r, r, s * 0.24f, st)
        c.drawCircle(r, r, s * 0.09f, fill(BLUE))
        c.drawLine(r, s * 0.10f, r, s * 0.24f, st); c.drawLine(r, s * 0.76f, r, s * 0.90f, st)
        c.drawLine(s * 0.10f, r, s * 0.24f, r, st); c.drawLine(s * 0.76f, r, s * 0.90f, r, st)
    }

    /** 도착지 표시: 네이버 지도처럼 빨간 핀 머리 안에 흰 글씨 "도착". 핀 끝(아래 가운데)이 목적지 좌표. */
    fun arrival(w: Int, h: Int): Bitmap = bmp(w, h) { c ->
        val r = w / 2f - 2f
        val cx = w / 2f; val cy = r + 2f
        val red = 0xFFF0463C.toInt()
        val path = Path().apply {
            addCircle(cx, cy, r, Path.Direction.CW)
            moveTo(cx - r * 0.62f, cy + r * 0.78f); lineTo(cx, h.toFloat() - 1f); lineTo(cx + r * 0.62f, cy + r * 0.78f); close()
        }
        c.drawPath(path, fill(red))
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f; color = Color.WHITE })
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; textSize = r * 0.82f
        }
        c.drawText("도착", cx, cy - (t.ascent() + t.descent()) / 2f, t)
    }

    /** 경로선 위에 반복되는 진행 방향 화살표(›). 위쪽이 진행 방향. */
    fun chevron(size: Int): Bitmap = bmp(size, size) { c ->
        val s = size.toFloat()
        val p = Path().apply { moveTo(s * 0.20f, s * 0.70f); lineTo(s * 0.5f, s * 0.30f); lineTo(s * 0.80f, s * 0.70f) }
        c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = s * 0.17f; color = Color.WHITE
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        })
    }

    private val SAFE_RED = 0xFFE5322D.toInt()
    private val SAFE_YELLOW = 0xFFFFC107.toInt()

    /**
     * 경로 위 안전정보 표시. 단속 카메라류는 흰 바탕+빨간 테두리(제한속도가 있으면 숫자, 없으면 카메라 그림, 구간단속은 "구간 시작/끝"),
     * 방지턱·철길·어린이보호구역·사고다발은 노란 바탕+검은 그림. [sdiType]은 티맵/openpilot nSdiType 번호.
     */
    fun safety(sdiType: Int, speedLimit: Int, size: Int): Bitmap = bmp(size, size) { c ->
        val s = size.toFloat(); val r = s / 2f
        val warn = sdiType in intArrayOf(19, 20, 22, 29)
        c.drawCircle(r, r, r * 0.95f, fill(0x33000000))
        c.drawCircle(r, r, r * 0.88f, fill(if (warn) SAFE_YELLOW else Color.WHITE))
        val ringW = s * (if (warn) 0.05f else 0.12f)
        c.drawCircle(r, r, r * 0.88f - ringW / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = ringW; color = if (warn) 0xFF222222.toInt() else SAFE_RED
        })
        val ink = 0xFF111111.toInt()
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = ink; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        fun text(t: String, cy: Float, sz: Float) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; textSize = sz }
            c.drawText(t, r, cy - (p.ascent() + p.descent()) / 2f, p)
        }
        when (sdiType) {
            22 -> { // 과속방지턱: 도로선 위의 둥근 턱
                stroke.strokeWidth = s * 0.07f
                c.drawLine(s * 0.22f, s * 0.66f, s * 0.78f, s * 0.66f, stroke)
                c.drawArc(RectF(s * 0.28f, s * 0.32f, s * 0.72f, s * 0.80f), 180f, 180f, false, stroke)
            }
            19 -> { // 철길건널목: X
                stroke.strokeWidth = s * 0.09f
                c.drawLine(s * 0.30f, s * 0.30f, s * 0.70f, s * 0.70f, stroke); c.drawLine(s * 0.70f, s * 0.30f, s * 0.30f, s * 0.70f, stroke)
            }
            20 -> text("어린이", r, s * 0.24f)
            29 -> text("!", r, s * 0.62f)
            2, 3 -> { text("구간", r - s * 0.13f, s * 0.24f); text(if (sdiType == 2) "시작" else "끝", r + s * 0.14f, s * 0.24f) }
            else -> if (speedLimit >= 30) {
                text(speedLimit.toString(), r, s * (if (speedLimit >= 100) 0.30f else 0.38f))
            } else { // 카메라 그림
                val body = RectF(s * 0.27f, s * 0.38f, s * 0.73f, s * 0.64f)
                c.drawRoundRect(body, s * 0.05f, s * 0.05f, fill(ink))
                c.drawRoundRect(RectF(s * 0.38f, s * 0.31f, s * 0.54f, s * 0.40f), s * 0.03f, s * 0.03f, fill(ink))
                c.drawCircle(r, s * 0.51f, s * 0.08f, fill(Color.WHITE))
            }
        }
    }

    /** 회전 화살표 그림. [code]는 NaverTurnMap.toOpenpilotCode 값. 투명 배경에 [color]로 그린다. */
    fun turnArrow(code: Int, size: Int, color: Int): Bitmap = bmp(size, size) { c ->
        val s = size.toFloat()
        fun pt(x: Float, y: Float) = floatArrayOf(x * s / 100f, y * s / 100f)
        val pts: List<FloatArray> = when (code) {
            12 -> listOf(pt(58f, 92f), pt(58f, 52f), pt(22f, 52f))
            13 -> listOf(pt(42f, 92f), pt(42f, 52f), pt(78f, 52f))
            14 -> listOf(pt(66f, 92f), pt(66f, 36f), pt(60f, 24f), pt(46f, 20f), pt(34f, 28f), pt(30f, 44f), pt(30f, 72f))
            7, 102 -> listOf(pt(56f, 92f), pt(56f, 62f), pt(26f, 24f))
            6, 101 -> listOf(pt(44f, 92f), pt(44f, 62f), pt(74f, 24f))
            else -> listOf(pt(50f, 92f), pt(50f, 14f))
        }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = s * 0.14f; this.color = color
            strokeCap = Paint.Cap.BUTT; strokeJoin = Paint.Join.ROUND
        }
        // 화살촉이 들어갈 자리만큼 마지막 선을 짧게 그린다
        val a = pts[pts.size - 2]; val b = pts.last()
        val dx = b[0] - a[0]; val dy = b[1] - a[1]
        val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
        val ux = dx / len; val uy = dy / len
        val headLen = s * 0.26f; val headHalf = s * 0.22f
        val path = Path().apply {
            moveTo(pts[0][0], pts[0][1])
            for (i in 1 until pts.size - 1) lineTo(pts[i][0], pts[i][1])
            lineTo(b[0] - ux * headLen * 0.6f, b[1] - uy * headLen * 0.6f)
        }
        c.drawPath(path, line)
        val head = Path().apply {
            moveTo(b[0], b[1])
            lineTo(b[0] - ux * headLen - uy * headHalf, b[1] - uy * headLen + ux * headHalf)
            lineTo(b[0] - ux * headLen + uy * headHalf, b[1] - uy * headLen - ux * headHalf)
            close()
        }
        c.drawPath(head, fill(color))
    }

    /** 지도 위 회전 표지: 흰 둥근 네모 안에 검은 회전 화살표. */
    fun turnSign(code: Int, size: Int): Bitmap = bmp(size, size) { c ->
        val s = size.toFloat()
        val box = RectF(2f, 2f, s - 2f, s - 2f)
        c.drawRoundRect(box, s * 0.2f, s * 0.2f, fill(Color.WHITE))
        c.drawRoundRect(box, s * 0.2f, s * 0.2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = 0x66000000 })
        val inner = (s * 0.72f).toInt()
        c.drawBitmap(turnArrow(code, inner, 0xFF222222.toInt()), (s - inner) / 2f, (s - inner) / 2f, null)
    }

    /**
     * 갈림길 도식: 회전 지점 [idx] 앞 70m·뒤 70m의 경로 좌표를 오는 길이 아래에서 위로 오도록 돌려서 그린다.
     * 흐린 선 = 오는 길을 그대로 이어간 직진 방향, 굵은 흰 선 = 실제로 갈 길(끝에 화살표). 좌표가 모자라면 null.
     */
    fun junction(path: List<LonLat>, idx: Int, size: Int, code: Int = 11): Bitmap? {
        if (idx !in 1 until path.size - 1) return null
        val c = path[idx]
        val cosLat = Math.cos(Math.toRadians(c.lat))
        fun xy(p: LonLat) = doubleArrayOf((p.lon - c.lon) * 111320.0 * cosLat, (p.lat - c.lat) * 111320.0)
        val before = ArrayList<DoubleArray>()
        var acc = 0.0
        var i = idx
        while (i > 0 && acc < 70.0) { acc += NaverGuidanceEngine.distance(path[i - 1], path[i]); i--; before.add(xy(path[i])) }
        val after = ArrayList<DoubleArray>()
        acc = 0.0; i = idx
        while (i < path.size - 1 && acc < 70.0) { acc += NaverGuidanceEngine.distance(path[i], path[i + 1]); i++; after.add(xy(path[i])) }
        if (before.isEmpty() || after.isEmpty()) return null
        // 오는 길의 방향(끝 쪽 20m 정도)을 위쪽으로 맞춘다
        val ref = before.firstOrNull { Math.hypot(it[0], it[1]) >= 15.0 } ?: before.last()
        val len = Math.hypot(ref[0], ref[1]).coerceAtLeast(0.001)
        val dx = -ref[0] / len; val dy = -ref[1] / len     // 오는 길의 진행 방향 (ref → 회전 지점)
        fun rot(p: DoubleArray) = doubleArrayOf(p[0] * dy - p[1] * dx, p[0] * dx + p[1] * dy) // 진행 방향이 +y(위)
        var afterR: List<DoubleArray> = after.map(::rot)
        // 실제 좌표가 거의 직진이면(예: 본선에서 갈라지는 진입로), 안내 종류에 맞춰 갈 길을 살짝 꺾어 그려서 방향이 보이게 한다
        val far = afterR.last()
        val exitDeg = Math.toDegrees(Math.atan2(far[0], far[1]))
        val want = when (code) { 7, 102 -> -35.0; 6, 101 -> 35.0; else -> 0.0 }
        if (want != 0.0 && Math.abs(exitDeg) < 25.0) {
            val a = Math.toRadians(want - exitDeg)
            afterR = afterR.map { doubleArrayOf(it[0] * Math.cos(a) + it[1] * Math.sin(a), -it[0] * Math.sin(a) + it[1] * Math.cos(a)) }
        }
        val pts = ArrayList<DoubleArray>()
        pts.addAll(before.reversed().map(::rot)); pts.add(doubleArrayOf(0.0, 0.0)); pts.addAll(afterR)
        val straightEnd = doubleArrayOf(0.0, 55.0)
        val all = pts + listOf(straightEnd)
        var minX = all.minOf { it[0] }; var maxX = all.maxOf { it[0] }
        var minY = all.minOf { it[1] }; var maxY = all.maxOf { it[1] }
        val spanX = (maxX - minX).coerceAtLeast(40.0); val spanY = (maxY - minY).coerceAtLeast(40.0)
        val pad = size * 0.14f
        val k = ((size - pad * 2) / Math.max(spanX, spanY)).toFloat()
        val ox = pad + ((size - pad * 2) - (spanX * k).toFloat()) / 2f - (minX * k).toFloat()
        val oy = size - pad - ((size - pad * 2) - (spanY * k).toFloat()) / 2f + (minY * k).toFloat()
        fun px(p: DoubleArray) = floatArrayOf(ox + (p[0] * k).toFloat(), oy - (p[1] * k).toFloat())
        return bmp(size, size) { cv ->
            val s = size.toFloat()
            cv.drawRoundRect(RectF(1f, 1f, s - 1f, s - 1f), s * 0.14f, s * 0.14f, fill(0xFF1556B8.toInt()))
            val w = s * 0.12f
            fun line(list: List<DoubleArray>, color: Int, width: Float): Path {
                val path0 = Path()
                list.forEachIndexed { n, p -> val q = px(p); if (n == 0) path0.moveTo(q[0], q[1]) else path0.lineTo(q[0], q[1]) }
                cv.drawPath(path0, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = width; this.color = color
                    strokeCap = Paint.Cap.BUTT; strokeJoin = Paint.Join.ROUND
                })
                return path0
            }
            // 오는 길을 그대로 이어간 직진 방향(흐리게)
            line(listOf(doubleArrayOf(0.0, 0.0), straightEnd), 0x66FFFFFF, w)
            // 오는 길
            val approach = before.reversed().map(::rot) + listOf(doubleArrayOf(0.0, 0.0))
            line(approach, 0xFFFFFFFF.toInt(), w)
            // 갈 길(마지막 12%는 화살촉 자리)
            val exit = listOf(doubleArrayOf(0.0, 0.0)) + afterR
            line(exit, 0xFFFFFFFF.toInt(), w)
            val e1 = px(exit[exit.size - 2]); val e2 = px(exit.last())
            val ddx = e2[0] - e1[0]; val ddy = e2[1] - e1[1]
            val dl = Math.hypot(ddx.toDouble(), ddy.toDouble()).toFloat().coerceAtLeast(1f)
            val ux = ddx / dl; val uy = ddy / dl
            val hl = s * 0.22f; val hh = s * 0.17f
            val head = Path().apply {
                moveTo(e2[0] + ux * hl * 0.2f, e2[1] + uy * hl * 0.2f)
                lineTo(e2[0] - ux * hl * 0.8f - uy * hh, e2[1] - uy * hl * 0.8f + ux * hh)
                lineTo(e2[0] - ux * hl * 0.8f + uy * hh, e2[1] - uy * hl * 0.8f - ux * hh)
                close()
            }
            cv.drawPath(head, fill(Color.WHITE))
        }
    }

    private fun stroke(color: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = w; this.color = color
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    /** 스피커 그림. [waves]가 0이면 음소거 모양, 2면 큰 소리 모양. */
    fun speaker(size: Int, color: Int, waves: Int): Bitmap = bmp(size, size) { c ->
        val s = size.toFloat()
        val body = Path().apply {
            moveTo(s * 0.14f, s * 0.38f); lineTo(s * 0.30f, s * 0.38f); lineTo(s * 0.50f, s * 0.20f)
            lineTo(s * 0.50f, s * 0.80f); lineTo(s * 0.30f, s * 0.62f); lineTo(s * 0.14f, s * 0.62f); close()
        }
        c.drawPath(body, fill(color))
        val st = stroke(color, s * 0.07f)
        if (waves >= 1) c.drawArc(RectF(s * 0.38f, s * 0.34f, s * 0.70f, s * 0.66f), -50f, 100f, false, st)
        if (waves >= 2) c.drawArc(RectF(s * 0.34f, s * 0.20f, s * 0.86f, s * 0.80f), -50f, 100f, false, st)
    }

    /** 메뉴판 아이콘들(선 그림). */
    fun menuRoute(size: Int, color: Int): Bitmap = bmp(size, size) { c ->
        val s = size.toFloat(); val st = stroke(color, s * 0.075f)
        c.drawLine(s * 0.16f, s * 0.34f, s * 0.80f, s * 0.34f, st)
        c.drawLine(s * 0.66f, s * 0.20f, s * 0.80f, s * 0.34f, st); c.drawLine(s * 0.66f, s * 0.48f, s * 0.80f, s * 0.34f, st)
        c.drawLine(s * 0.84f, s * 0.68f, s * 0.20f, s * 0.68f, st)
        c.drawLine(s * 0.34f, s * 0.54f, s * 0.20f, s * 0.68f, st); c.drawLine(s * 0.34f, s * 0.82f, s * 0.20f, s * 0.68f, st)
    }

    fun menuLayers(size: Int, color: Int): Bitmap = bmp(size, size) { c ->
        val s = size.toFloat(); val st = stroke(color, s * 0.07f)
        fun diamond(dy: Float) = Path().apply {
            moveTo(s * 0.5f, s * (0.16f + dy)); lineTo(s * 0.88f, s * (0.34f + dy)); lineTo(s * 0.5f, s * (0.52f + dy)); lineTo(s * 0.12f, s * (0.34f + dy)); close()
        }
        c.drawPath(diamond(0f), st)
        c.drawPath(Path().apply { moveTo(s * 0.12f, s * 0.52f); lineTo(s * 0.5f, s * 0.70f); lineTo(s * 0.88f, s * 0.52f) }, st)
        c.drawPath(Path().apply { moveTo(s * 0.12f, s * 0.68f); lineTo(s * 0.5f, s * 0.86f); lineTo(s * 0.88f, s * 0.68f) }, st)
    }

    fun menuNav(size: Int, color: Int, filled: Boolean = false): Bitmap = bmp(size, size) { c ->
        val s = size.toFloat()
        val p = Path().apply { moveTo(s * 0.5f, s * 0.10f); lineTo(s * 0.88f, s * 0.88f); lineTo(s * 0.5f, s * 0.68f); lineTo(s * 0.12f, s * 0.88f); close() }
        if (filled) c.drawPath(p, fill(color)) else c.drawPath(p, stroke(color, s * 0.075f))
    }

    fun menuGear(size: Int, color: Int): Bitmap = bmp(size, size) { c ->
        val s = size.toFloat(); val r = s / 2f
        val st = stroke(color, s * 0.075f)
        c.drawCircle(r, r, s * 0.20f, st)
        for (i in 0 until 8) {
            c.save(); c.rotate(i * 45f, r, r)
            c.drawLine(r, s * 0.14f, r, s * 0.25f, stroke(color, s * 0.11f).apply { strokeCap = Paint.Cap.BUTT })
            c.restore()
        }
        c.drawCircle(r, r, s * 0.34f, st)
    }
}
