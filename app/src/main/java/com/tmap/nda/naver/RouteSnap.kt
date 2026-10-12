package com.tmap.nda.naver

import android.location.Location

/**
 * 폰 GPS 좌표를 파란 경로선 위의 가장 가까운 점으로 붙여준다(지도에서 차 아이콘이 경로선에서 옆으로 벗어나 보이는 것 방지).
 * 경로선에서 [maxMeters]보다 멀면(진짜 이탈 중) 붙이지 않고 원래 좌표를 그대로 쓴다.
 */
class RouteSnap {
    private var routeRef: List<LonLat>? = null
    private var lastIdx = 0

    fun snap(loc: Location, route: NaverRoute?, maxMeters: Double): Location {
        val path = route?.path ?: return loc
        if (path.size < 2) return loc
        if (routeRef !== path) { routeRef = path; lastIdx = 0 }
        val mPerLat = 111_000.0
        val mPerLon = 111_000.0 * Math.cos(Math.toRadians(loc.latitude))
        fun scan(from: Int, to: Int): Triple<Int, Double, DoubleArray>? {
            var best: Triple<Int, Double, DoubleArray>? = null
            val a = from.coerceAtLeast(0)
            val b = to.coerceAtMost(path.size - 2)
            for (i in a..b) {
                val ax = (path[i].lon - loc.longitude) * mPerLon
                val ay = (path[i].lat - loc.latitude) * mPerLat
                val bx = (path[i + 1].lon - loc.longitude) * mPerLon
                val by = (path[i + 1].lat - loc.latitude) * mPerLat
                val dx = bx - ax; val dy = by - ay
                val len2 = dx * dx + dy * dy
                val t = if (len2 < 1e-9) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
                val px = ax + dx * t; val py = ay + dy * t
                val d = Math.hypot(px, py)
                if (best == null || d < best.second) best = Triple(i, d, doubleArrayOf(px, py, dx, dy))
            }
            return best
        }
        // 먼저 직전 위치 근처만 보고(평행한 다른 길로 튀는 것 방지), 안 맞으면 전체를 훑는다
        var r = scan(lastIdx - 5, lastIdx + 400)
        if (r == null || r.second > maxMeters) r = scan(0, path.size - 2)
        if (r == null || r.second > maxMeters) return loc
        lastIdx = r.first
        val out = Location(loc)
        out.latitude = loc.latitude + r.third[1] / mPerLat
        out.longitude = loc.longitude + r.third[0] / mPerLon
        if (loc.hasSpeed() && loc.speed > 1.5f) {
            val brg = Math.toDegrees(Math.atan2(r.third[2], r.third[3]))
            out.bearing = ((brg + 360.0) % 360.0).toFloat()
        }
        return out
    }
}
