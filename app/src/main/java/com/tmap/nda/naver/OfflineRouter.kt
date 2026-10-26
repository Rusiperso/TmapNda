package com.tmap.nda.naver

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tmap.nda.NavLogger
import com.valhalla.valhalla.Valhalla
import java.io.File
import java.util.Locale

/**
 * 인터넷이 없을 때 폰 안에서 길을 계산한다(Valhalla). 결과는 네이버 길찾기 응답과 같은 [NaverRoute] 모양으로
 * 바꿔서 돌려주므로, 네이버 안내 화면이 그대로 이 길로 안내한다.
 * 도로망 파일은 앱 외부 저장소의 valhalla 폴더(valhalla.json + tiles.tar)에 있어야 한다.
 */
object OfflineRouter {

    @Volatile private var engine: Valhalla? = null
    private val lock = Any()

    private fun dir(context: Context) = File(context.getExternalFilesDir(null), "valhalla")

    /** 도로망 파일이 폰에 있는지. */
    fun available(context: Context): Boolean {
        val d = dir(context)
        return File(d, "valhalla.json").exists() && File(d, "tiles.tar").exists()
    }

    /** 인터넷이 실제로 되는지(와이파이만 붙고 인터넷이 안 되는 경우도 안 되는 것으로 본다). */
    fun isOnline(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (e: Throwable) { true }

    /** 인터넷이 없고 도로망 파일이 있어서 오프라인 길찾기를 쓸 수 있는 상태인지. */
    fun shouldUse(context: Context): Boolean = available(context) && !isOnline(context)

    private fun engine(context: Context): Valhalla = engine ?: synchronized(lock) {
        engine ?: Valhalla(File(dir(context), "valhalla.json").absolutePath).also { engine = it }
    }

    /** 출발·(경유)·도착 순서의 좌표로 Valhalla가 계산한 원본 JSON을 돌려준다. 실패하면 null. */
    fun routeJson(context: Context, points: List<LonLat>, option: String = "traoptimal"): String? = try {
        val locations = points.joinToString(",") { String.format(Locale.US, "{\"lat\":%.6f,\"lon\":%.6f}", it.lat, it.lon) }
        val costingOptions = when (option) {
            "trafast" -> "\"costing_options\":{\"auto\":{\"use_highways\":1.0}},"
            "traavoidtoll" -> "\"costing_options\":{\"auto\":{\"use_tolls\":0.0}},"
            else -> ""
        }
        val req = "{\"locations\":[$locations],\"costing\":\"auto\",$costingOptions" +
            "\"directions_options\":{\"language\":\"ko-KR\",\"units\":\"kilometers\"}}"
        val out = engine(context).routeRaw(req)
        if (out.contains("\"trip\"")) out else null
    } catch (e: Throwable) {
        NavLogger.e(context, "[오프라인길찾기] 원본 계산 실패: ${e.javaClass.simpleName} ${e.message}")
        null
    }

    fun route(context: Context, start: LonLat, goal: LonLat, waypoints: List<LonLat> = emptyList(), option: String = "traoptimal"): NaverDirectionsResult {
        val tag = "[오프라인길찾기]"
        if (!available(context)) return NaverDirectionsResult(-20, "오프라인 도로망 파일이 없어요", emptyList())
        return try {
            val t0 = System.currentTimeMillis()
            val points = listOf(start) + waypoints + listOf(goal)
            val locations = points.joinToString(",") {
                String.format(Locale.US, "{\"lat\":%.6f,\"lon\":%.6f}", it.lat, it.lon)
            }
            // 네이버 옵션("traoptimal:trafast" 처럼 콜론으로 이어서 옴)마다 한 번씩 계산해, 옵션 이름을 붙여서 돌려준다.
            val routes = ArrayList<NaverRoute>()
            var lastOut = ""
            for (opt in option.split(":").filter { it.isNotBlank() }.ifEmpty { listOf("traoptimal") }) {
                val costingOptions = when (opt) {
                    "trafast" -> "\"costing_options\":{\"auto\":{\"use_highways\":1.0}},"
                    "tracomfort" -> "\"costing_options\":{\"auto\":{\"use_highways\":0.8}},"
                    "traavoidtoll" -> "\"costing_options\":{\"auto\":{\"use_tolls\":0.0}},"
                    else -> ""
                }
                val req = "{\"locations\":[$locations],\"costing\":\"auto\",$costingOptions" +
                    "\"directions_options\":{\"language\":\"ko-KR\",\"units\":\"kilometers\"}}"
                lastOut = engine(context).routeRaw(req)
                parse(lastOut)?.let { routes.add(it.copy(option = opt)) }
            }
            val ms = System.currentTimeMillis() - t0
            if (routes.isEmpty()) {
                NavLogger.e(context, "$tag 길을 못 찾았어요 (${ms}ms) ${lastOut.take(200)}")
                NaverDirectionsResult(-21, "오프라인으로 길을 찾지 못했어요", emptyList())
            } else {
                val r = routes.first()
                NavLogger.d(context, "$tag 시작=${start.lon},${start.lat} 도착=${goal.lon},${goal.lat} 옵션=$option → ${r.distanceMeters}m ${r.durationMs / 1000}초 안내=${r.guides.size}개 (${ms}ms)")
                NaverDirectionsResult(0, "오프라인", routes)
            }
        } catch (e: Throwable) {
            NavLogger.e(context, "$tag 실패: ${e.javaClass.simpleName} ${e.message}")
            NaverDirectionsResult(-22, "오프라인 길찾기 오류: ${e.message}", emptyList())
        }
    }

    internal fun parse(json: String): NaverRoute? {
        val root = try { JsonParser.parseString(json).asJsonObject } catch (e: Exception) { return null }
        val trip = root.getAsJsonObject("trip") ?: return null
        val legs = trip.getAsJsonArray("legs") ?: return null
        val path = ArrayList<LonLat>()
        val guides = ArrayList<NaverGuide>()
        var prevLenM = 0
        var prevTimeMs = 0L
        for ((legIdx, legEl) in legs.withIndex()) {
            val leg = legEl.asJsonObject
            val shape = decodePolyline6(leg.get("shape")?.asString ?: continue)
            val offset = if (path.isEmpty()) 0 else path.size - 1   // 두 번째 구간부터는 첫 점이 앞 구간의 끝점과 같다
            if (path.isEmpty()) path.addAll(shape) else path.addAll(shape.drop(1))
            val isLastLeg = legIdx == legs.size() - 1
            for (mEl in leg.getAsJsonArray("maneuvers") ?: continue) {
                val m = mEl.asJsonObject
                val vType = m.get("type")?.asInt ?: 0
                val idx = (offset + (m.get("begin_shape_index")?.asInt ?: 0)).coerceIn(0, path.size - 1)
                val type = mapType(vType, isLastLeg)
                if (type != null) {
                    guides.add(NaverGuide(
                        pointIndex = idx,
                        type = type,
                        instructions = m.get("instruction")?.asString.orEmpty(),
                        distance = prevLenM,
                        durationMs = prevTimeMs
                    ))
                }
                prevLenM = ((m.get("length")?.asDouble ?: 0.0) * 1000).toInt()
                prevTimeMs = ((m.get("time")?.asDouble ?: 0.0) * 1000).toLong()
            }
        }
        if (path.size < 2) return null
        val summary: JsonObject? = trip.getAsJsonObject("summary")
        return NaverRoute(
            option = "offline",
            path = path,
            guides = guides.sortedBy { it.pointIndex },
            sections = emptyList(),
            distanceMeters = ((summary?.get("length")?.asDouble ?: 0.0) * 1000).toInt(),
            durationMs = ((summary?.get("time")?.asDouble ?: 0.0) * 1000).toLong(),
            tollFare = 0,
            taxiFare = 0,
            fuelPrice = 0
        )
    }

    /** Valhalla 안내 종류 → 네이버 안내 종류(NaverTurnMap 기준). 출발 안내는 null로 건너뛴다. */
    private fun mapType(v: Int, isLastLeg: Boolean): Int? = when (v) {
        1, 2, 3 -> null                       // 출발
        4, 5, 6 -> if (isLastLeg) 88 else 87  // 목적지 / 경유지
        7, 8, 22, 17, 25, 27, 28, 29 -> 1     // 직진·이어짐·합류·회전교차로 나감
        9 -> 14                               // 약간 오른쪽
        10 -> 3                               // 우회전
        11 -> 16                              // 급 우회전
        12, 13 -> 6                           // 유턴
        14 -> 11                              // 급 좌회전
        15 -> 2                               // 좌회전
        16 -> 13                              // 약간 왼쪽
        18, 20, 23, 36 -> 5                   // 오른쪽 램프·출구·우측 유지
        19, 21, 24, 37 -> 4                   // 왼쪽 램프·출구·좌측 유지
        26 -> 98                              // 회전교차로 진입(12시 방향)
        else -> 1
    }

    /** 구글식 인코딩(정밀도 6자리) 좌표열을 푼다. 결과는 (경도, 위도). */
    internal fun decodePolyline6(s: String): List<LonLat> {
        val out = ArrayList<LonLat>()
        var i = 0; var lat = 0; var lon = 0
        while (i < s.length) {
            var shift = 0; var result = 0; var b: Int
            do { b = s[i++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            shift = 0; result = 0
            do { b = s[i++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20)
            lon += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            out.add(LonLat(lon / 1e6, lat / 1e6))
        }
        return out
    }
}
