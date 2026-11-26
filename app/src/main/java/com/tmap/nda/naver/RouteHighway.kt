package com.tmap.nda.naver

import com.kakaomobility.knsdk.KNSDK
import com.kakaomobility.knsdk.trip.kntrip.knroute.KNRoute
import org.json.JSONArray
import org.json.JSONObject

/**
 * 네이버 경로선 위에 있는 고속도로 시설 1개(톨게이트/휴게소/IC/JC).
 * [type]은 티맵 nTBTType 번호(1=톨게이트, 2=휴게소, 3=IC, 4=JC), [alongM]은 네이버 경로 시작점부터 이 시설까지의 길이(m).
 */
data class RouteHighwayItem(val type: Int, val name: String, val alongM: Int)

/**
 * 같은 출발·도착으로 카카오에 경로만 받아(길안내 시작 안 함) 그 경로의 `highwayRGList()`에서
 * 휴게소·IC·JC·톨게이트 목록을 얻어 네이버 경로선 위의 거리로 바꾼다(카카오 화면 오른쪽 목록과 같은 정보).
 * 재억 요청(2026-11-26). #문제시 원복: 이 파일과 NaverNavigator의 highwayItems 관련 코드 제거
 */
object RouteHighway {
    /** 시설 점이 네이버 경로선에서 이 거리(m) 안이면 이 경로의 시설로 본다(휴게소 진입로 등은 본선에서 조금 떨어져 있음). */
    private const val MAX_OFF_ROUTE_M = 350.0
    const val MAX_ITEMS = 10

    private fun inKorea(lat: Double, lon: Double) = lat in 32.5..39.5 && lon in 123.5..132.5

    private fun toWgs(x: Double, y: Double): Pair<Double, Double>? {
        val w = try { KNSDK.convertKATECToWGS84(x.toInt(), y.toInt()) } catch (_: Exception) { null }
        if (w != null && inKorea(w.y, w.x)) return w.y to w.x
        if (inKorea(y, x)) return y to x
        return null
    }

    private fun typeOf(rg: Any): Int? {
        val name = try { rg.javaClass.getMethod("highwayRGType").invoke(rg)?.toString().orEmpty() } catch (_: Exception) { "" }
        return when {
            name.endsWith("_TG") -> 1
            name.endsWith("_SA") -> 2
            name.endsWith("_IC") -> 3
            name.endsWith("_JC") -> 4
            else -> null   // RA(회차로) 등은 뺀다
        }
    }

    fun build(kakao: KNRoute, naver: NaverRoute, drops: MutableList<String>? = null): List<RouteHighwayItem> {
        val path = naver.path
        if (path.size < 2) return emptyList()
        val groups = try { kakao.highwayRGList() } catch (e: Exception) { drops?.add("highwayRGList 실패 ${e.message}"); null } ?: return emptyList()
        val mPerLat = 111_000.0
        val cum = DoubleArray(path.size)
        for (i in 1 until path.size) cum[i] = cum[i - 1] + NaverGuidanceEngine.distance(path[i - 1], path[i])
        val out = ArrayList<RouteHighwayItem>()
        for (group in groups) {
            for (rg in group) {
                val type = typeOf(rg) ?: continue
                val name = rg.nodeName.orEmpty()
                if (name.isBlank()) { drops?.add("이름없음 type=$type"); continue }
                val p = rg.location.pos
                val here = toWgs(p.x, p.y) ?: run { drops?.add("좌표변환실패 $name"); null } ?: continue
                val lat = here.first; val lon = here.second
                val mPerLon = 111_000.0 * Math.cos(Math.toRadians(lat))
                var bestD = Double.MAX_VALUE; var bestAlong = 0.0
                for (i in 0 until path.size - 1) {
                    val ax = (path[i].lon - lon) * mPerLon; val ay = (path[i].lat - lat) * mPerLat
                    val bx = (path[i + 1].lon - lon) * mPerLon; val by = (path[i + 1].lat - lat) * mPerLat
                    val dx = bx - ax; val dy = by - ay
                    val len2 = dx * dx + dy * dy
                    val t = if (len2 < 1e-9) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
                    val d = Math.hypot(ax + dx * t, ay + dy * t)
                    if (d < bestD) { bestD = d; bestAlong = cum[i] + (cum[i + 1] - cum[i]) * t }
                }
                if (bestD > MAX_OFF_ROUTE_M) { drops?.add("선에서멂 type=$type $name 선까지=${bestD.toInt()}m"); continue }
                out.add(RouteHighwayItem(type, name, bestAlong.toInt()))
            }
        }
        return out.sortedBy { it.alongM }.distinctBy { it.type to it.name }
    }

    /** 지금 [progressM]까지 왔을 때 앞으로 남은 시설을 나브디/nMirror가 받는 JSON(tbtListInfos 모양)으로. 없으면 null. */
    fun toJson(items: List<RouteHighwayItem>, progressM: Double): String? {
        val ahead = items.map { it to (it.alongM - progressM).toInt() }.filter { it.second > 0 }.take(MAX_ITEMS)
        if (ahead.isEmpty()) return null
        val array = JSONArray()
        var prev = 0
        for ((item, remain) in ahead) {
            array.put(
                JSONObject()
                    .put("nTBTType", item.type)
                    .put("szTBTMainText", item.name)
                    .put("remainDistance", remain)
                    .put("distanceFromPreviousTBT", remain - prev)
                    .put("bHighway", true)
            )
            prev = remain
        }
        return array.toString()
    }
}
