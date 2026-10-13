package com.tmap.nda.naver

import com.kakaomobility.knsdk.KNSDK
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.objects.KNSafety_Camera
import com.kakaomobility.knsdk.trip.kntrip.knroute.KNRoute
import com.tmap.nda.mapKakaoSafetyCodeToSdiType

/**
 * 네이버 경로선 위에 있는 안전정보 1개(카메라/구간단속/방지턱 등). 카카오 경로를 길안내 없이 받아서 얻는다.
 * [sdiType]은 티맵/openpilot nSdiType 번호, [alongM]은 네이버 경로 시작점부터 이 지점까지의 길이(m).
 */
data class RouteSafetyItem(
    val lat: Double,
    val lon: Double,
    val sdiType: Int,
    val speedLimit: Int,
    val alongM: Int,
    val offM: Int
)

object RouteSafety {
    /** 지도에 표시하는 종류: 단속 카메라류, 구간단속 시작/끝, 철길건널목, 어린이보호구역, 과속방지턱, 사고다발. */
    private val SHOWN = setOf(1, 2, 3, 6, 7, 8, 9, 11, 12, 15, 16, 17, 19, 20, 22, 29)

    /** 네이버 경로선에서 이 거리(m) 안에 있는 것만 쓴다(카카오가 다른 길을 고른 구간의 카메라가 섞이지 않게). */
    const val MAX_OFF_ROUTE_M = 15.0

    /** 카카오 경로의 진행 방향과 네이버 경로 방향이 이 각도(도) 넘게 다르면 반대 차로/다른 길로 보고 버린다. */
    private const val MAX_HEADING_DIFF_DEG = 35.0

    /** 한국 안에 있는 위경도인지. */
    private fun inKorea(lat: Double, lon: Double) = lat in 32.5..39.5 && lon in 123.5..132.5

    /** 카카오 좌표(KATEC로 보이지만 위경도로 와도 깨지지 않게)를 (위도, 경도)로. */
    private fun toWgs(x: Double, y: Double): Pair<Double, Double>? {
        val w = try { KNSDK.convertKATECToWGS84(x.toInt(), y.toInt()) } catch (_: Exception) { null }
        if (w != null && inKorea(w.y, w.x)) return w.y to w.x
        if (inKorea(y, x)) return y to x
        return null
    }

    fun build(kakao: KNRoute, naver: NaverRoute): List<RouteSafetyItem> {
        val path = naver.path
        if (path.size < 2) return emptyList()
        val raw = kakao.safetyList() ?: return emptyList()
        val mPerLat = 111_000.0
        val cum = DoubleArray(path.size)
        for (i in 1 until path.size) cum[i] = cum[i - 1] + NaverGuidanceEngine.distance(path[i - 1], path[i])
        val out = ArrayList<RouteSafetyItem>()
        for (s in raw) {
            val sdi = mapKakaoSafetyCodeToSdiType(s.code.name)
            if (sdi !in SHOWN) continue
            val p = s.location.pos
            // 좌표는 카카오 자체 좌표(KATEC)로 보이지만, 위경도로 이미 오는 경우에도 깨지지 않게 둘 다 받는다.
            val here = toWgs(p.x, p.y) ?: continue
            val lat = here.first; val lon = here.second
            val mPerLon = 111_000.0 * Math.cos(Math.toRadians(lat))
            var bestD = Double.MAX_VALUE; var bestAlong = 0.0; var bestI = 0
            for (i in 0 until path.size - 1) {
                val ax = (path[i].lon - lon) * mPerLon; val ay = (path[i].lat - lat) * mPerLat
                val bx = (path[i + 1].lon - lon) * mPerLon; val by = (path[i + 1].lat - lat) * mPerLat
                val dx = bx - ax; val dy = by - ay
                val len2 = dx * dx + dy * dy
                val t = if (len2 < 1e-9) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
                val d = Math.hypot(ax + dx * t, ay + dy * t)
                if (d < bestD) { bestD = d; bestAlong = cum[i] + (cum[i + 1] - cum[i]) * t; bestI = i }
            }
            if (bestD > MAX_OFF_ROUTE_M) continue
            // 반대 차로/나란한 다른 길 거르기: 카카오 경로가 이 지점에서 가는 방향(30m 앞 지점)이
            // 네이버 경로 방향과 같아야 하고, 그 앞 지점도 네이버 경로선 위에 있어야 한다.
            val ahead = try { s.location.locationAfterDist(30)?.pos } catch (_: Exception) { null }
            val aheadW = ahead?.let { toWgs(it.x, it.y) }
            if (aheadW == null) continue
            val kdx = (aheadW.second - lon) * mPerLon; val kdy = (aheadW.first - lat) * mPerLat
            if (Math.hypot(kdx, kdy) < 5.0) continue
            val ndx = (path[bestI + 1].lon - path[bestI].lon) * mPerLon; val ndy = (path[bestI + 1].lat - path[bestI].lat) * mPerLat
            val cos = (kdx * ndx + kdy * ndy) / (Math.hypot(kdx, kdy) * Math.hypot(ndx, ndy).coerceAtLeast(1e-6))
            if (cos < Math.cos(Math.toRadians(MAX_HEADING_DIFF_DEG))) continue
            var aheadD = Double.MAX_VALUE
            val mPerLon2 = mPerLon
            for (i in (bestI - 3).coerceAtLeast(0) until minOf(path.size - 1, bestI + 40)) {
                val ax = (path[i].lon - aheadW.second) * mPerLon2; val ay = (path[i].lat - aheadW.first) * mPerLat
                val bx = (path[i + 1].lon - aheadW.second) * mPerLon2; val by = (path[i + 1].lat - aheadW.first) * mPerLat
                val dx = bx - ax; val dy = by - ay
                val len2 = dx * dx + dy * dy
                val t = if (len2 < 1e-9) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
                aheadD = minOf(aheadD, Math.hypot(ax + dx * t, ay + dy * t))
            }
            if (aheadD > MAX_OFF_ROUTE_M) continue
            val limit = (s as? KNSafety_Camera)?.speedLimit ?: 0
            out.add(RouteSafetyItem(lat, lon, sdi, limit, bestAlong.toInt(), bestD.toInt()))
        }
        // 같은 종류가 거의 같은 자리(30m 안)에 겹쳐 있으면 하나로 본다(양방향/중복 등록)
        val sorted = out.sortedBy { it.alongM }
        val dedup = ArrayList<RouteSafetyItem>()
        for (it in sorted) {
            val prev = dedup.lastOrNull { d -> d.sdiType == it.sdiType }
            if (prev != null && it.alongM - prev.alongM < 30) continue
            dedup.add(it)
        }
        return dedup
    }
}
