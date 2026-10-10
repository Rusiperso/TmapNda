package com.tmap.nda.naver

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** 위치 한 번을 경로에 맞춰 본 결과. 화면·음성·콤마 전송은 모두 이 값을 읽는다. */
data class GuidanceState(
    val progressMeters: Double,
    val remainMeters: Int,
    val remainTimeSec: Int,
    /** 경로선에서 떨어진 거리(m). 0에 가까울수록 경로 위. */
    val offRouteMeters: Double,
    val nextGuide: NaverGuide?,
    val nextGuideDistMeters: Int,
    val secondGuide: NaverGuide?,
    val secondGuideDistMeters: Int,
    val roadName: String,
    val arrived: Boolean
)

/**
 * 네이버가 준 경로(점들의 선) 위에서 내 위치를 따라가며 다음 안내까지 남은 거리를 계산한다.
 * 카카오 SDK가 해주던 "위치 맞추기 + 다음 안내 계산"을 대신하는 부분이며, 안드로이드와 무관한 순수 계산이다.
 */
class NaverGuidanceEngine(val route: NaverRoute) {

    private val path = route.path
    private val n = path.size
    private val cum = DoubleArray(n)
    private val totalLen: Double
    private var lastSeg = 0

    init {
        for (i in 1 until n) cum[i] = cum[i - 1] + distance(path[i - 1], path[i])
        totalLen = cum[n - 1]
    }

    val totalMeters: Double get() = totalLen

    /** [accuracyM]는 GPS 오차 반경(m). 클수록 이탈 판단이 느슨해지도록 호출하는 쪽이 쓴다. */
    fun update(lon: Double, lat: Double): GuidanceState {
        val m = match(lon, lat)
        lastSeg = m.seg
        val progress = m.progress

        val guides = route.guides
        var nextIdx = -1
        for (i in guides.indices) {
            if (cum[guides[i].pointIndex] - progress > -PASSED_TOLERANCE_M) { nextIdx = i; break }
        }
        val next = guides.getOrNull(nextIdx)
        val second = if (nextIdx >= 0) guides.getOrNull(nextIdx + 1) else null
        val nextDist = next?.let { max(0.0, cum[it.pointIndex] - progress).toInt() } ?: 0
        val secondDist = second?.let { max(0.0, cum[it.pointIndex] - progress).toInt() } ?: 0

        val remain = max(0.0, totalLen - progress)
        val arrived = remain <= ARRIVE_M && (next == null || next.type == 88)
        return GuidanceState(
            progressMeters = progress,
            remainMeters = remain.toInt(),
            remainTimeSec = remainTimeSec(progress, nextIdx),
            offRouteMeters = m.offDist,
            nextGuide = next,
            nextGuideDistMeters = nextDist,
            secondGuide = second,
            secondGuideDistMeters = secondDist,
            roadName = roadNameAt(m.seg),
            arrived = arrived
        )
    }

    private fun remainTimeSec(progress: Double, nextIdx: Int): Int {
        val guides = route.guides
        if (guides.isEmpty() || nextIdx < 0) {
            val frac = if (totalLen > 0) max(0.0, totalLen - progress) / totalLen else 0.0
            return (route.durationMs * frac / 1000.0).toInt()
        }
        val prevPoint = if (nextIdx == 0) 0.0 else cum[guides[nextIdx - 1].pointIndex]
        val nextPoint = cum[guides[nextIdx].pointIndex]
        val span = nextPoint - prevPoint
        val frac = if (span > 1.0) ((nextPoint - progress) / span).coerceIn(0.0, 1.0) else 0.0
        var ms = guides[nextIdx].durationMs * frac
        for (i in nextIdx + 1 until guides.size) ms += guides[i].durationMs
        return (ms / 1000.0).toInt()
    }

    private fun roadNameAt(seg: Int): String {
        for (s in route.sections) {
            if (seg >= s.pointIndex && seg < s.pointIndex + max(1, s.pointCount)) return s.name
        }
        return ""
    }

    private class Match(val seg: Int, val progress: Double, val offDist: Double)

    /** 직전 위치 근처부터 앞쪽을 먼저 찾고, 너무 멀면 전체에서 다시 찾는다(경로가 겹치는 구간 오인 방지). */
    private fun match(lon: Double, lat: Double): Match {
        val from = max(0, lastSeg - 3)
        var to = from
        val limit = cum[from] + SEARCH_AHEAD_M
        while (to < n - 2 && cum[to + 1] < limit) to++
        var best = scan(lon, lat, from, min(n - 2, to + 1))
        if (best.offDist > WIDE_SEARCH_TRIGGER_M) {
            val wide = scan(lon, lat, 0, n - 2)
            if (wide.offDist < best.offDist - 20.0) best = wide
        }
        return best
    }

    private fun scan(lon: Double, lat: Double, from: Int, to: Int): Match {
        val cosLat = cos(Math.toRadians(lat))
        var bestSeg = from
        var bestOff = Double.MAX_VALUE
        var bestT = 0.0
        for (i in from..to) {
            val ax = (path[i].lon - lon) * M_PER_DEG_LAT * cosLat
            val ay = (path[i].lat - lat) * M_PER_DEG_LAT
            val bx = (path[i + 1].lon - lon) * M_PER_DEG_LAT * cosLat
            val by = (path[i + 1].lat - lat) * M_PER_DEG_LAT
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 <= 1e-9) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val px = ax + t * dx
            val py = ay + t * dy
            val off = hypot(px, py)
            if (off < bestOff) { bestOff = off; bestSeg = i; bestT = t }
        }
        val segLen = cum[bestSeg + 1] - cum[bestSeg]
        return Match(bestSeg, cum[bestSeg] + bestT * segLen, bestOff)
    }

    companion object {
        private const val M_PER_DEG_LAT = 111_320.0
        private const val SEARCH_AHEAD_M = 1500.0
        private const val WIDE_SEARCH_TRIGGER_M = 60.0
        private const val PASSED_TOLERANCE_M = 8.0
        private const val ARRIVE_M = 30.0

        fun distance(a: LonLat, b: LonLat): Double {
            val cosLat = cos(Math.toRadians((a.lat + b.lat) / 2))
            val dx = (b.lon - a.lon) * M_PER_DEG_LAT * cosLat
            val dy = (b.lat - a.lat) * M_PER_DEG_LAT
            return hypot(dx, dy)
        }
    }
}

/**
 * "경로에서 벗어났으니 다시 찾아야 하나?"를 정하는 규칙.
 * - 벗어난 상태가 [confirmMs] 이상 이어져야 하고(GPS 튐 방지)
 * - 직전 요청 후 [minIntervalMs]가 지나야 하며
 * - 한 시간에 [maxPerHour]번을 넘기지 않는다(호출 한도 보호).
 */
class RerouteGate(
    private val offRouteMeters: Double = 40.0,
    private val confirmMs: Long = 4_000,
    private val minIntervalMs: Long = 20_000,
    private val maxPerHour: Int = 15
) {
    private var offSince = -1L
    private var lastRequestAt = -1L
    private val recent = ArrayDeque<Long>()

    fun shouldReroute(nowMs: Long, state: GuidanceState): Boolean {
        if (state.arrived || state.offRouteMeters < offRouteMeters) { offSince = -1L; return false }
        if (offSince < 0) offSince = nowMs
        if (nowMs - offSince < confirmMs) return false
        if (lastRequestAt >= 0 && nowMs - lastRequestAt < minIntervalMs) return false
        while (recent.isNotEmpty() && nowMs - recent.first() > 3_600_000L) recent.removeFirst()
        return recent.size < maxPerHour
    }

    fun noteRequested(nowMs: Long) {
        lastRequestAt = nowMs
        recent.addLast(nowMs)
        offSince = -1L
    }
}

/** 안내 지점마다 "언제 말할지"를 정한다. 같은 지점의 같은 단계는 한 번만 말한다. */
class AnnouncePlanner {
    private val said = HashSet<Long>()

    /** 지금 말해야 할 문장이 있으면 돌려주고, 없으면 null. [speedKmh]가 빠르면 더 먼 거리에서 먼저 알려준다. */
    fun next(state: GuidanceState, speedKmh: Double): String? {
        val g = state.nextGuide ?: return null
        val dist = state.nextGuideDistMeters
        val thresholds = if (speedKmh >= 80) HIGHWAY else URBAN
        val label = NaverTurnMap.label(g.type)
        if (label.isEmpty()) return null
        // 가장 가까운 단계부터 확인: 이미 그 안으로 들어왔고 아직 안 말한 단계 중 제일 작은 것
        val stage = thresholds.filter { dist <= it }.minOrNull() ?: return null
        val base = g.pointIndex.toLong() * 10_000L
        // 이 단계거나 더 가까운 단계를 이미 말했다면 건너뜀
        if (thresholds.any { it <= stage && said.contains(base + it) }) return null
        // 한 번에 여러 단계를 건너뛰고 들어온 경우, 먼 단계들도 말한 것으로 쳐서 중복을 막는다
        for (t in thresholds) if (t >= stage) said.add(base + t)
        return when {
            stage <= NOW_M -> label
            stage >= 1000 -> "${stage / 1000}킬로미터 앞 $label"
            else -> "${stage}미터 앞 $label"
        }
    }

    fun reset() { said.clear() }

    companion object {
        private const val NOW_M = 40
        private val URBAN = intArrayOf(300, 100, NOW_M)
        private val HIGHWAY = intArrayOf(1000, 500, 200, NOW_M)
    }
}
