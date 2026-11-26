package com.tmap.nda

import android.location.Location

/**
 * GPS가 끊겼는데 따라갈 경로선이 없을 때(경로에서 많이 벗어난 상태 등) 쓰는 "커브 이어가기".
 * 끊기기 직전 GPS 몇 개로 "1m 가는 동안 방향이 몇 도 바뀌었나"를 구해 두고, 끊긴 뒤에도
 * 그 휘어짐을 그대로 이어서 간다. 직선 길이면 휘어짐이 0이라 직선으로, 커브 길이면 커브로 간다.
 * 콤마는 속도만 주고 핸들/회전 값은 안 줘서 GPS 기록으로 구한다. 재억 요청(2026-11-26). #문제시 원복: 이 파일과 쓰는 곳 제거
 */
class DrCurve {
    private class Fix(val t: Long, val bearing: Float, val speed: Float)
    private val fixes = ArrayDeque<Fix>()

    /** 실제 GPS가 들어올 때마다 부른다. */
    fun record(l: Location, nowMs: Long = System.currentTimeMillis()) {
        if (!l.hasBearing() || l.speed < 3f) return
        fixes.addLast(Fix(nowMs, l.bearing, l.speed))
        while (fixes.isNotEmpty() && nowMs - fixes.first().t > 6_000L) fixes.removeFirst()
    }

    /** 1m 가는 동안 바뀐 방향(도). 오른쪽으로 휘면 +. 기록이 부족하면 0(직선). */
    fun degPerMeter(): Double {
        if (fixes.size < 3) return 0.0
        val a = fixes.first()
        val b = fixes.last()
        val dt = (b.t - a.t) / 1000.0
        if (dt < 1.5) return 0.0
        var d = (b.bearing - a.bearing).toDouble()
        while (d > 180) d -= 360.0
        while (d < -180) d += 360.0
        val dist = (a.speed + b.speed) / 2.0 * dt
        if (dist < 15.0) return 0.0
        // 너무 급한 값(GPS 튐)은 제한: 1m당 최대 약 1.2도(반경 약 50m)
        return (d / dist).coerceIn(-1.2, 1.2)
    }

    fun clear() = fixes.clear()

    companion object {
        /** bearing(도)을 distM만큼 가면서 curv(도/m)로 휘게 한 새 방향. */
        fun nextBearing(bearing: Double, distM: Double, curv: Double): Double =
            ((bearing + curv * distM) % 360.0 + 360.0) % 360.0

        /** (lat,lon)에서 bearing 방향으로 distM 이동하되 구간 중간 방향을 써서 휘어짐을 반영. 결과 [lat, lon, 새 방향]. */
        fun step(lat: Double, lon: Double, bearing: Double, distM: Double, curv: Double): DoubleArray {
            val nb = nextBearing(bearing, distM, curv)
            val midRad = Math.toRadians(nextBearing(bearing, distM / 2.0, curv))
            val nlat = lat + distM * Math.cos(midRad) / 111_320.0
            val nlon = lon + distM * Math.sin(midRad) / (111_320.0 * Math.cos(Math.toRadians(lat)))
            return doubleArrayOf(nlat, nlon, nb)
        }
    }
}
