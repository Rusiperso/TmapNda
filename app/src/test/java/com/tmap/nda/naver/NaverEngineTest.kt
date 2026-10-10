package com.tmap.nda.naver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos

class NaverEngineTest {

    private val lat0 = 37.5
    private val lon0 = 127.0
    private val mPerLon = 111_320.0 * cos(Math.toRadians(lat0))
    private val mPerLat = 111_320.0

    /** 동쪽으로 [east]m 간 뒤 북쪽으로 [north]m 가는 경로. 50m마다 점을 찍는다. 모퉁이에서 좌회전(2), 끝에 목적지(88). */
    private fun lRoute(east: Int = 1000, north: Int = 1000): NaverRoute {
        val pts = ArrayList<LonLat>()
        var d = 0
        while (d <= east) { pts.add(LonLat(lon0 + d / mPerLon, lat0)); d += 50 }
        var e = 50
        while (e <= north) { pts.add(LonLat(lon0 + east / mPerLon, lat0 + e / mPerLat)); e += 50 }
        val corner = east / 50
        return NaverRoute(
            option = "traoptimal",
            path = pts,
            guides = listOf(
                NaverGuide(corner, 2, "좌회전", east, 100_000),
                NaverGuide(pts.size - 1, 88, "목적지", north, 100_000)
            ),
            sections = listOf(
                NaverSection(0, corner, east, "A로", 1, 50),
                NaverSection(corner, pts.size - corner, north, "B로", 1, 50)
            ),
            distanceMeters = east + north,
            durationMs = 200_000,
            tollFare = 0, taxiFare = 0, fuelPrice = 0
        )
    }

    private fun onEast(m: Double, off: Double = 0.0) = Pair(lon0 + m / mPerLon, lat0 + off / mPerLat)

    @Test
    fun progress_and_next_guide() {
        val eng = NaverGuidanceEngine(lRoute())
        val (lon, lat) = onEast(400.0)
        val s = eng.update(lon, lat)
        assertEquals(2, s.nextGuide?.type)
        assertTrue("다음 안내까지 약 600m: ${s.nextGuideDistMeters}", abs(s.nextGuideDistMeters - 600) < 15)
        assertTrue("남은 거리 약 1600m: ${s.remainMeters}", abs(s.remainMeters - 1600) < 20)
        assertTrue("경로 위: ${s.offRouteMeters}", s.offRouteMeters < 3)
        assertEquals("A로", s.roadName)
        assertEquals(88, s.secondGuide?.type)
        assertTrue("두번째 안내까지 약 1600m: ${s.secondGuideDistMeters}", abs(s.secondGuideDistMeters - 1600) < 20)
        // 남은 시간: 첫 구간 60%(60초) + 둘째 구간 100초 = 약 160초
        assertTrue("남은 시간 약 160초: ${s.remainTimeSec}", abs(s.remainTimeSec - 160) < 6)
    }

    @Test
    fun passes_turn_then_next_is_destination() {
        val eng = NaverGuidanceEngine(lRoute())
        var s = eng.update(lon0 + 900 / mPerLon, lat0)
        assertEquals(2, s.nextGuide?.type)
        s = eng.update(lon0 + 1000 / mPerLon, lat0 + 100 / mPerLat)
        assertEquals(88, s.nextGuide?.type)
        assertTrue("목적지까지 약 900m: ${s.nextGuideDistMeters}", abs(s.nextGuideDistMeters - 900) < 15)
        assertEquals("B로", s.roadName)
    }

    @Test
    fun off_route_distance_is_measured() {
        val eng = NaverGuidanceEngine(lRoute())
        val (lon, lat) = onEast(400.0, off = 80.0)
        val s = eng.update(lon, lat)
        assertTrue("옆으로 80m: ${s.offRouteMeters}", abs(s.offRouteMeters - 80) < 5)
    }

    @Test
    fun arrival_detected() {
        val eng = NaverGuidanceEngine(lRoute())
        eng.update(lon0 + 1000 / mPerLon, lat0 + 500 / mPerLat)
        val s = eng.update(lon0 + 1000 / mPerLon, lat0 + 985 / mPerLat)
        assertTrue(s.arrived)
        assertFalse(eng.update(lon0 + 1000 / mPerLon, lat0 + 600 / mPerLat).arrived)
    }

    @Test
    fun reroute_gate_requires_persistent_off_route_and_spacing() {
        val eng = NaverGuidanceEngine(lRoute())
        val gate = RerouteGate()
        val (lon, lat) = onEast(400.0, off = 100.0)
        val off = eng.update(lon, lat)
        assertFalse("처음 순간엔 아직", gate.shouldReroute(0, off))
        assertFalse("3초 후도 아직", gate.shouldReroute(3_000, off))
        assertTrue("4초 이상 이어지면 재탐색", gate.shouldReroute(4_500, off))
        gate.noteRequested(4_500)
        assertFalse("요청 직후는 다시 안 함", gate.shouldReroute(9_000, off))
        assertFalse("20초 안에는 안 함", gate.shouldReroute(20_000, off))
        // 경로로 돌아오면 초기화
        val back = eng.update(lon0 + 400 / mPerLon, lat0)
        assertFalse(gate.shouldReroute(30_000, back))
    }

    @Test
    fun reroute_gate_caps_per_hour() {
        val eng = NaverGuidanceEngine(lRoute())
        val gate = RerouteGate(maxPerHour = 3, confirmMs = 0, minIntervalMs = 0)
        val (lon, lat) = onEast(400.0, off = 100.0)
        val off = eng.update(lon, lat)
        var count = 0
        for (i in 0 until 10) {
            val now = i * 1000L
            if (gate.shouldReroute(now, off)) { gate.noteRequested(now); count++ }
        }
        assertEquals(3, count)
    }

    @Test
    fun announce_urban_once_per_stage() {
        val eng = NaverGuidanceEngine(lRoute())
        val planner = AnnouncePlanner()
        val spoken = ArrayList<String>()
        var m = 600.0
        while (m <= 1000.0) {
            val (lon, lat) = onEast(m)
            planner.next(eng.update(lon, lat), 50.0)?.let { spoken.add(it) }
            m += 10.0
        }
        // 1000m 도달 전 600m~990m: 300m 앞, 100m 앞, 현재 안내 3번만
        assertEquals(3, spoken.size)
        assertTrue(spoken[0].endsWith("미터 앞 좌회전") && spoken[0].startsWith("300"))
        assertTrue(spoken[1].startsWith("100"))
        assertEquals("좌회전", spoken[2])
    }

    @Test
    fun announce_highway_starts_earlier() {
        val eng = NaverGuidanceEngine(lRoute(east = 3000))
        val planner = AnnouncePlanner()
        val (lon, lat) = onEast(2050.0)
        val said = planner.next(eng.update(lon, lat), 100.0)
        assertEquals("1킬로미터 앞 좌회전", said)
    }

    @Test
    fun announce_skips_when_no_label() {
        val route = lRoute().let { it.copy(guides = listOf(NaverGuide(8, 9999, "", 400, 1000))) }
        val eng = NaverGuidanceEngine(route)
        val planner = AnnouncePlanner()
        assertNull(planner.next(eng.update(lon0 + 380 / mPerLon, lat0), 40.0))
    }

    @Test
    fun turn_map_basics() {
        assertEquals(12, NaverTurnMap.toOpenpilotCode(2))
        assertEquals(13, NaverTurnMap.toOpenpilotCode(3))
        assertEquals(14, NaverTurnMap.toOpenpilotCode(6))
        assertEquals(201, NaverTurnMap.toOpenpilotCode(88))
        assertEquals(101, NaverTurnMap.toOpenpilotCode(67))
        assertEquals(102, NaverTurnMap.toOpenpilotCode(58))
        assertEquals(51, NaverTurnMap.toOpenpilotCode(9999))
        assertEquals("KNRGCode_LeftTurn", NaverTurnMap.kakaoLikeName(12))
    }

    @Test
    fun parser_reads_directions_response() {
        val json = """
        {"code":0,"message":"길찾기를 성공하였습니다.","currentDateTime":"2026-10-10T11:00:00",
         "route":{"traoptimal":[{
           "summary":{"distance":2000,"duration":200000,"tollFare":0,"taxiFare":9000,"fuelPrice":300},
           "path":[[127.0,37.5],[127.001,37.5],[127.001,37.501]],
           "section":[{"pointIndex":0,"pointCount":3,"distance":2000,"name":"테헤란로","congestion":1,"speed":40}],
           "guide":[{"pointIndex":1,"type":2,"instructions":"좌회전","distance":100,"duration":10000},
                    {"pointIndex":2,"type":88,"instructions":"목적지","distance":100,"duration":10000}]
         }]}}
        """.trimIndent()
        val r = NaverDirectionsParser.parse(json)
        assertTrue(r.ok)
        val route = r.routes.single()
        assertEquals("traoptimal", route.option)
        assertEquals(3, route.path.size)
        assertEquals(2, route.guides.size)
        assertEquals("테헤란로", route.sections[0].name)
        assertEquals(200_000L, route.durationMs)
    }

    @Test
    fun parser_reports_error_code() {
        val r = NaverDirectionsParser.parse("""{"code":2,"message":"출발지 또는 도착지가 도로에서 너무 멉니다"}""")
        assertFalse(r.ok)
        assertEquals(2, r.code)
    }
}
