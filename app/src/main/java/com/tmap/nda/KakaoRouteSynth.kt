package com.tmap.nda

import android.content.Context
import android.util.Base64
import com.tmap.nda.naver.LonLat
import com.tmap.nda.naver.OfflineRouter
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 인터넷이 없을 때 카카오 SDK가 길찾기 서버에서 받는 응답(api/v8/route-plan)을 폰 안에서 만들어 준다.
 * 길은 Valhalla가 계산하고, 그 결과를 카카오 SDK가 읽는 길 파일 형식(byteRoutes)으로 바꿔 쓴다.
 * 형식은 SDK의 KNRoute 읽기 코드를 보고 맞춘 것이라, 읽는 쪽이 요구하는 칸만 채우고 나머지는 비워 둔다.
 */
object KakaoRouteSynth {

    // KNRGCode 값(SDK 열거형 기준)
    private const val RG_START = 100
    private const val RG_GOAL = 101
    private const val RG_VIA = 1000
    private const val RG_STRAIGHT = 0
    private const val RG_LEFT = 1
    private const val RG_RIGHT = 2
    private const val RG_UTURN = 3
    private const val RG_LEFT_DIR = 5
    private const val RG_RIGHT_DIR = 6

    private const val MAX_LINK_M = 300.0
    private const val NETWORK_VERSION = 261019

    private class Man(val rgCode: Int, val shapeIdx: Int, val lenM: Double, val timeS: Double, val name: String)

    /** 요청 본문(JSON)을 읽어 응답 JSON 문자열을 만든다. 만들 수 없으면 null. */
    fun synthesize(context: Context, requestBody: ByteArray?): String? {
        val tag = "[카카오오프라인길]"
        try {
            if (requestBody == null || !OfflineRouter.available(context)) return null
            val req = JSONObject(String(requestBody, Charsets.UTF_8))
            val transId = req.optString("transId")
            val dest = req.optJSONObject("destination") ?: return null
            val origin = req.optJSONObject("origin") ?: return null
            fun lonLat(o: JSONObject): LonLat {
                val p = katecToWgs(o.getDouble("x"), o.getDouble("y"))
                return LonLat(p[0], p[1])
            }
            val points = ArrayList<LonLat>()
            points.add(lonLat(origin))
            req.optJSONArray("waypoints")?.let { arr -> for (i in 0 until arr.length()) points.add(lonLat(arr.getJSONObject(i))) }
            points.add(lonLat(dest))

            val valhalla = OfflineRouter.routeJson(context, points, "traoptimal") ?: return null
            val built = build(valhalla, transId, req.optJSONArray("priorities")) ?: return null
            NavLogger.d(context, "$tag 만들었어요: 링크=${built.second} 응답=${built.first.length}자")
            return built.first
        } catch (e: Throwable) {
            NavLogger.e(context, "$tag 실패: ${e.javaClass.simpleName} ${e.message}")
            return null
        }
    }

    /** (응답 JSON, 링크 개수) */
    private fun build(valhallaJson: String, transId: String, priorities: JSONArray?): Pair<String, Int>? {
        val trip = JSONObject(valhallaJson).optJSONObject("trip") ?: return null
        val legs = trip.optJSONArray("legs") ?: return null

        // 1) 모양(좌표)과 안내 지점을 한 줄로 이어 붙인다
        val shape = ArrayList<IntArray>()   // (x, y) 카텍 좌표
        val mans = ArrayList<Man>()
        for (li in 0 until legs.length()) {
            val leg = legs.getJSONObject(li)
            val pts = decodePolyline6(leg.optString("shape"))
            val offset = if (shape.isEmpty()) 0 else shape.size - 1
            for ((i, p) in pts.withIndex()) {
                if (li > 0 && i == 0) continue
                val k = wgsToKatec(p.first, p.second)
                shape.add(intArrayOf(k[0].toInt(), k[1].toInt()))
            }
            val isLast = li == legs.length() - 1
            val ms = leg.optJSONArray("maneuvers") ?: continue
            for (mi in 0 until ms.length()) {
                val m = ms.getJSONObject(mi)
                val vType = m.optInt("type")
                val code = rgCodeOf(vType, isLast, m.optInt("roundabout_exit_count"))
                val names = m.optJSONArray("street_names")
                val nm = if (names != null && names.length() > 0) names.optString(0) else ""
                mans.add(Man(code ?: -1, offset + m.optInt("begin_shape_index"), m.optDouble("length") * 1000.0, m.optDouble("time"), nm))
            }
        }
        if (shape.size < 2) return null

        // 2) 누적 거리
        val cum = DoubleArray(shape.size)
        for (i in 1 until shape.size) cum[i] = cum[i - 1] + Math.hypot((shape[i][0] - shape[i - 1][0]).toDouble(), (shape[i][1] - shape[i - 1][1]).toDouble())
        val totalM = cum.last()

        // 3) 링크 경계: 안내 지점마다 + 300m마다
        val cuts = java.util.TreeSet<Int>()
        cuts.add(0); cuts.add(shape.size - 1)
        for (m in mans) if (m.shapeIdx in 1 until shape.size - 1) cuts.add(m.shapeIdx)
        val bounds = ArrayList(cuts)
        val linkRanges = ArrayList<IntArray>()
        for (b in 0 until bounds.size - 1) {
            var s = bounds[b]
            val e = bounds[b + 1]
            while (s < e) {
                var t = s + 1
                while (t < e && cum[t] - cum[s] < MAX_LINK_M) t++
                linkRanges.add(intArrayOf(s, t))
                s = t
            }
        }

        // 4) 이름표(도로 이름)와 링크별 시간
        val nameIdx = LinkedHashMap<String, Int>()
        fun idxOf(n: String): Int = if (n.isBlank()) -1 else nameIdx.getOrPut(n) { nameIdx.size }
        // 링크가 속한 안내 구간(마지막으로 시작한 안내 지점)을 찾아 이름·시간을 나눈다
        val sortedMans = mans.filter { it.shapeIdx >= 0 }.sortedBy { it.shapeIdx }
        fun manFor(shapeIdx: Int): Man? = sortedMans.lastOrNull { it.shapeIdx <= shapeIdx }
        val linkNameIdx = IntArray(linkRanges.size)
        val linkDt = IntArray(linkRanges.size)
        var totalTime = 0
        for ((li, r) in linkRanges.withIndex()) {
            val m = manFor(r[0])
            linkNameIdx[li] = idxOf(m?.name.orEmpty())
            val segLen = (cum[r[1]] - cum[r[0]])
            val rate = if (m != null && m.lenM > 1.0) m.timeS / m.lenM else 0.08
            linkDt[li] = Math.max(1, Math.round(segLen * rate).toInt())
            totalTime += linkDt[li]
        }

        // 5) 안내 지점(RG): 시작, 각 안내, 도착
        class Rg(val x: Int, val y: Int, val linkIdx: Int, val code: Int)
        val rgs = ArrayList<Rg>()
        rgs.add(Rg(shape[0][0], shape[0][1], 0, RG_START))
        for (m in sortedMans) {
            if (m.rgCode < 0 || m.rgCode == RG_GOAL) continue
            if (m.shapeIdx <= 0 || m.shapeIdx >= shape.size - 1) continue
            val li = linkRanges.indexOfFirst { it[0] == m.shapeIdx }
            if (li < 0) continue
            rgs.add(Rg(shape[m.shapeIdx][0], shape[m.shapeIdx][1], li, m.rgCode))
        }
        rgs.add(Rg(shape.last()[0], shape.last()[1], linkRanges.size - 1, RG_GOAL))

        // 6) 이진 길 파일 쓰기(큰 쪽 순서)
        fun routeBytes(drkey: String): ByteArray {
            val out = ByteArrayOutputStream()
            val w = Writer(out)
            w.int(8)
            w.str(drkey)
            repeat(4) { w.bool(false) }
            w.int(nameIdx.size)
            for (n in nameIdx.keys) { w.str(n); w.int(0); w.int(0) }
            w.int(linkRanges.size)
            for ((li, r) in linkRanges.withIndex()) {
                w.long(9_000_000_000_000L + li)
                w.long(0L)
                w.byte(5); w.byte(2); w.byte(0)         // 도로 코드, 링크 종류, 시설 코드
                w.bool(false); w.bool(false); w.bool(false)
                w.byte(0)
                w.zero(6)
                w.byte(2)                               // 차선 수
                w.byte(1)
                w.short(linkDt[li])                     // 이 링크 시간(초)
                w.byte(4)
                w.short(50)
                w.short(linkNameIdx[li]); w.short(-1); w.short(-1); w.short(-1)
                val cnt = r[1] - r[0] + 1
                w.int(cnt)
                for (p in r[0]..r[1]) { w.int(shape[p][0]); w.int(shape[p][1]) }
                w.bool(false)                           // 차선 정보 없음
                w.int(0)
                w.byte(1); w.byte(0)
            }
            w.int(rgs.size)
            for (g in rgs) {
                w.int(g.x); w.int(g.y)
                w.int(g.linkIdx)
                w.short(g.code)
                w.zero(4)
                w.bool(false); w.bool(false)
                w.bool(false); w.bool(false); w.bool(false)   // 교차로·휴게소·요금소 정보 없음
                w.bool(false); w.bool(false)                  // 졸음쉼터 정보 없음
                w.short(-1)
            }
            repeat(6) { w.int(0) }   // 안전정보·버스전용차로·주변정보·음성정보·도로사건·종료링크
            return out.toByteArray()
        }

        // 7) 응답: 요청에 들어 있던 우선순위마다 같은 길을 한 개씩 담는다
        val pr = ArrayList<String>()
        if (priorities != null) for (i in 0 until priorities.length()) pr.add(priorities.getString(i))
        if (pr.isEmpty()) pr.add("RECOMMEND")
        val byteRoutes = JSONArray()
        val infos = JSONArray()
        for ((k, p) in pr.withIndex()) {
            val drkey = "$transId-00000${k}50100000"
            byteRoutes.put(Base64.encodeToString(routeBytes(drkey), Base64.NO_WRAP))
            val summary = JSONObject()
                .put("code", "0000").put("message", JSONObject.NULL)
                .put("priority", JSONObject().put("type", if (p == "PREFERRED_ROUTE") "RECOMMEND" else p))
                .put("drkey", drkey).put("networkVersion", NETWORK_VERSION)
                .put("pointCount", shape.size).put("distance", Math.round(totalM).toInt()).put("travelTime", totalTime)
                .put("toll", 0).put("tagMessage", JSONObject.NULL).put("routeIndex", k)
                .put("taxiFare", 0).put("hasTheOtherSideRoute", false).put("isDestinationOnLeftSide", false)
                .put("similarity", 1.0).put("fuelCost", 0).put("cost", totalTime)
                .put("predefinedRouteInfo", JSONObject().put("tags", JSONArray()).put("frequency", 0).put("ms", 0).put("dataUrl", "").put("averageDuration", 0))
            infos.put(JSONObject().put("routePriority", JSONObject().put("type", p)).put("routeSummaries", JSONArray().put(summary)))
        }
        val resp = JSONObject()
            .put("transId", transId).put("code", "0000").put("message", JSONObject.NULL)
            .put("yugoInfo", JSONObject.NULL).put("routes", JSONObject.NULL)
            .put("byteRoutes", byteRoutes).put("routeInfos", infos)
        return resp.toString() to linkRanges.size
    }

    /** Valhalla 안내 종류 → 카카오 안내 코드. 안내가 필요 없는 건 null. */
    private fun rgCodeOf(v: Int, isLastLeg: Boolean, roundaboutExit: Int): Int? = when (v) {
        1, 2, 3 -> null
        4, 5, 6 -> if (isLastLeg) RG_GOAL else RG_VIA
        7, 8, 22, 17, 25, 27, 28, 29 -> null
        9, 23, 36 -> RG_RIGHT_DIR
        10 -> RG_RIGHT
        11 -> RG_RIGHT
        12, 13 -> RG_UTURN
        14 -> RG_LEFT
        15 -> RG_LEFT
        16, 24, 37 -> RG_LEFT_DIR
        18, 20 -> RG_RIGHT_DIR
        19, 21 -> RG_LEFT_DIR
        26 -> RG_STRAIGHT
        else -> null
    }

    private fun decodePolyline6(s: String): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        var i = 0; var lat = 0; var lon = 0
        while (i < s.length) {
            var shift = 0; var result = 0; var b: Int
            do { b = s[i++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            shift = 0; result = 0
            do { b = s[i++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20)
            lon += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            out.add((lon / 1e6) to (lat / 1e6))   // (경도, 위도)
        }
        return out
    }

    private class Writer(val out: ByteArrayOutputStream) {
        fun byte(v: Int) = out.write(v and 0xff)
        fun bool(v: Boolean) = out.write(if (v) 1 else 0)
        fun zero(n: Int) = repeat(n) { out.write(0) }
        fun short(v: Int) { out.write((v shr 8) and 0xff); out.write(v and 0xff) }
        fun int(v: Int) { out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v).array()) }
        fun long(v: Long) { out.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(v).array()) }
        fun str(s: String) { val b = s.toByteArray(Charsets.UTF_8); int(b.size); out.write(b) }
    }
}

private val katecClass: Class<*> by lazy { Class.forName("com.kakaomobility.knsdk.common.gps.KNKATECKt") }

/** 위경도 → 카텍(미터). 반환은 [x, y]. SDK 안의 변환 함수를 그대로 쓴다. */
private fun wgsToKatec(lon: Double, lat: Double): DoubleArray {
    val p = katecClass.getMethod("WGS84ToKATEC", Double::class.javaPrimitiveType, Double::class.javaPrimitiveType).invoke(null, lon, lat)!!
    return doubleArrayOf(p.javaClass.getMethod("getX").invoke(p) as Double, p.javaClass.getMethod("getY").invoke(p) as Double)
}

/** 카텍 → 위경도. 반환은 [경도, 위도]. */
private fun katecToWgs(x: Double, y: Double): DoubleArray {
    val p = katecClass.getMethod("KATECToWGS84", Double::class.javaPrimitiveType, Double::class.javaPrimitiveType).invoke(null, x, y)!!
    return doubleArrayOf(p.javaClass.getMethod("getX").invoke(p) as Double, p.javaClass.getMethod("getY").invoke(p) as Double)
}
