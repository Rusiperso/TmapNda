package com.tmap.nda

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 국가교통정보센터 돌발상황(사고·공사·기상 등)을 우리 서버(Cloudflare 일꾼)에서 받아와 길안내 지도에 보여주는 데이터 창고.
 * 서버는 2분마다 국가교통정보센터에서 받아 저장해 두고, 앱은 지금 보는 구역 것만 가져간다.
 * 서버 주소에 보내는 건 내 위치를 0.2도(약 20km) 격자로 뭉갠 구역 값뿐이고, 못 받으면 조용히 건너뛴다. #문제시 원복
 */
object IncidentRepository {
    /** 일꾼을 배포하면 열리는 주소. 아직 배포 전이면 연결에 실패하고 아무것도 표시하지 않는다. */
    const val BASE_URL = "https://incident.tnda.workers.dev"
    const val PREF_KEY = "incident_overlay_enabled"
    /** 켜면(기본) 돌발상황을 화면 가운데 큰 경고+경고음으로, 끄면 상단바(어린이보호구역·카메라 경고 옆)에 같이 띄운다. */
    const val PREF_CENTER = "incident_center_enabled"
    private const val POLL_MS = 120_000L
    private const val FAIL_MS = 600_000L
    private const val NO_FIX_MS = 15_000L
    private const val GRID = 0.2

    data class Incident(
        val type: String,   // acc 사고, cor 공사, wea 기상, ete 기타돌발, dis 재난, etc 기타
        val detail: String,
        val startMs: Long,
        val endMs: Long,
        val lon: Double,
        val lat: Double,
        val road: String,
        val roadNo: String,
        val dir: String,
        val lanes: String,
        val message: String
    )

    /** 고속도로 정체 한 덩어리: (경도, 위도)는 들어서게 되는 쪽 끝, 속도는 평균 시속, 방향은 진행 방위각. */
    data class Jam(val lon: Double, val lat: Double, val speed: Int, val lenM: Int, val heading: Int, val road: String, val dir: String)

    /** 제한속도가 기본값보다 낮아진 가변속도제한표지. 방향(heading)을 모르면 -1. */
    data class Vsl(val lon: Double, val lat: Double, val limit: Int, val def: Int, val heading: Int)

    @Volatile private var jams: List<Jam> = emptyList()
    @Volatile private var vsls: List<Vsl> = emptyList()

    interface Listener { fun onIncidentsChanged(list: List<Incident>) }

    @Volatile var items: List<Incident> = emptyList()
        private set
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val main = Handler(Looper.getMainLooper())
    private var exec: ScheduledExecutorService? = null
    private var owner: Any? = null
    /** 위도, 경도, 진행 방향(도, 모르면 null) */
    private var locationProvider: (() -> Triple<Double, Double, Float?>?)? = null
    private var appContext: Context? = null

    fun isEnabled(ctx: Context) =
        ctx.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).getBoolean(PREF_KEY, true)

    /** 켜면(기본) 앞쪽 사고를 차량 쪽(계기판·HUD·나브디·콤마)에도 보낸다. */
    const val PREF_CAR = "incident_to_car_enabled"

    // 차량 쪽으로 보낼 앞쪽 사고 문구와 거리. 2~3초마다 갱신되고, 8초 넘게 안 갱신되면 없는 것으로 본다.
    @Volatile private var carText: String? = null
    @Volatile private var carDistM = 0
    @Volatile private var carAtMs = 0L
    @Volatile private var carKind = 90
    /** 콤마용 종류 번호: 사고 90, 공사 91, 재난 92, 가변 제한속도 93, 정체 94. 문구는 앱이 완성해서 함께 보낸다. */
    fun carAheadKind() = carKind
    fun carAhead(): Pair<String, Int>? {
        val t = carText ?: return null
        return if (System.currentTimeMillis() - carAtMs < 8000L) Pair(t, carDistM) else null
    }
    private fun clearAhead() { AccidentAlertRepository.clearIncident(); carText = null }

    /** 가운데 경고를 끈 상태 = 상단바에 띄우는 모드. */
    fun isTopBar(ctx: Context) =
        !ctx.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).getBoolean(PREF_CENTER, true)

    fun addListener(l: Listener) { if (!listeners.contains(l)) listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    /** 화면이 보이는 동안만 돌린다. 같은 화면이 두 번 불러도 한 번만 돈다. */
    @Synchronized
    fun start(ctx: Context, who: Any, location: () -> Triple<Double, Double, Float?>?) {
        appContext = ctx.applicationContext
        owner = who
        locationProvider = location
        // 화면이 보이는 동안(포그라운드)에 백그라운드 알림 서비스를 설정에 맞춰 켜둔다.
        if (who !== BG_OWNER) IncidentWatchService.sync(ctx)
        if (exec != null) return
        val e = Executors.newSingleThreadScheduledExecutor()
        exec = e
        e.schedule({ tick() }, 3, TimeUnit.SECONDS)
        e.scheduleWithFixedDelay({ aheadTick() }, 5, 3, TimeUnit.SECONDS)
    }

    /** 앞쪽에 새 돌발상황이 처음 잡힐 때 한 번 불린다(메인 스레드). 화면이 큰 경고창을 띄운다. */
    @Volatile var alertCallback: ((String, Boolean) -> Unit)? = null  // (문구, 경고음 여부)
    private val alerted = HashSet<String>()

    @Synchronized
    fun stop(who: Any) {
        if (owner !== who) return
        owner = null
        alertCallback = null
        locationProvider = null
        exec?.shutdownNow()
        exec = null
        // 화면이 사라졌는데 백그라운드 알림 서비스가 켜져 있으면 그쪽이 이어받는다.
        val p = bgProvider; val cb = bgCallback; val c = appContext
        if (who !== BG_OWNER && p != null && cb != null && c != null) {
            alertCallback = cb
            start(c, BG_OWNER, p)
        }
    }

    /** 재억 요청(2026-11-08): 백그라운드 사고 팝업 스위치. 켜면 앱이 뒤에 있어도 앞쪽 사고를 팝업으로 알린다. */
    const val PREF_BG = "incident_bg_enabled"
    fun isBgEnabled(ctx: Context) =
        ctx.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).getBoolean(PREF_BG, false)

    private val BG_OWNER = Any()
    private var bgProvider: (() -> Triple<Double, Double, Float?>?)? = null
    private var bgCallback: ((String, Boolean) -> Unit)? = null

    @Synchronized
    fun startBackground(ctx: Context, location: () -> Triple<Double, Double, Float?>?, callback: (String, Boolean) -> Unit) {
        appContext = ctx.applicationContext
        bgProvider = location
        bgCallback = callback
        if (owner == null) {
            alertCallback = callback
            start(ctx, BG_OWNER, location)
        }
    }

    @Synchronized
    fun stopBackground() {
        bgProvider = null
        bgCallback = null
        if (owner === BG_OWNER) stop(BG_OWNER)
    }

    /** 스위치를 끄거나 켰을 때 바로 반영. */
    fun refreshNow() {
        appContext?.let { IncidentWatchService.sync(it) }
        val e = exec ?: return
        e.schedule({ tick() }, 0, TimeUnit.SECONDS)
    }

    private fun publish(list: List<Incident>) {
        items = list
        main.post { for (l in listeners) try { l.onIncidentsChanged(list) } catch (_: Exception) { } }
    }

    private fun tick() {
        val ctx = appContext ?: return
        var next = POLL_MS
        try {
            if (!isEnabled(ctx)) {
                if (items.isNotEmpty()) publish(emptyList())
            } else {
                val loc = locationProvider?.invoke()
                NavLogger.dIfChanged(ctx, "incident_loc", "[돌발] 위치=${if (loc == null) "없음" else "있음"}")
                if (loc == null) {
                    next = NO_FIX_MS
                } else {
                    publish(fetch(loc.first, loc.second))
                    NavLogger.dIfChanged(ctx, "incident_cnt", "[돌발] 받은 건수=${items.size}")
                    fetchTraffic(ctx, loc.first, loc.second)
                    aheadTick()
                }
            }
        } catch (e: Exception) {
            next = FAIL_MS
            NavLogger.d(ctx, "[돌발] 받기 실패: ${e.javaClass.simpleName} ${e.message}")
        }
        try { exec?.schedule({ tick() }, next, TimeUnit.MILLISECONDS) } catch (_: Exception) { }
    }

    private const val AHEAD_MAX_M = 3000.0
    private const val AHEAD_MIN_M = 30.0
    private const val AHEAD_CONE_DEG = 45.0
    private const val VSL_MAX_M = 2000.0
    private const val VSL_CONE_DEG = 35.0
    // 경로 기준: 경로선에서 옆으로 이 거리 안이면 "내 길 위"로 본다. 안내 없을 때: 진행 방향 정면 ±12도·옆 60m 안만.
    private const val ROUTE_LATERAL_M = 50.0
    private const val JAM_ROUTE_LATERAL_M = 80.0
    private const val VSL_ROUTE_LATERAL_M = 40.0
    private const val ROUTE_SELF_MAX_M = 80.0
    private const val NARROW_CONE_DEG = 12.0
    private const val NARROW_LATERAL_M = 60.0

    /** 새 사고·재난 경고가 날 때마다 1씩 오르는 번호. 콤마로 보내서 계기판 빨강 번쩍임 신호로 쓴다(앱 껐다 켜도 새 번호라 상관없음). */
    @Volatile var flashSeq = (System.currentTimeMillis() / 1000).toInt()  // 앱을 다시 켜면 번호가 크게 달라져 콤마가 새 기준으로 받아들임

    /** 경로선(경도,위도 목록)에 점을 내려 (경로 시작부터의 거리 m, 경로선까지 옆 거리 m)를 돌려준다. */
    private fun projectOnRoute(lat: Double, lon: Double, route: List<Pair<Double, Double>>): Pair<Double, Double>? {
        if (route.size < 2) return null
        val oLat = route[0].second; val oLon = route[0].first
        val mLat = 111320.0; val mLon = 111320.0 * Math.cos(Math.toRadians(oLat))
        val px = (lon - oLon) * mLon; val py = (lat - oLat) * mLat
        var cum = 0.0; var bestPerp = Double.MAX_VALUE; var bestAlong = 0.0
        var prevX = 0.0; var prevY = 0.0
        for (i in 1 until route.size) {
            val cx = (route[i].first - oLon) * mLon; val cy = (route[i].second - oLat) * mLat
            val sx = cx - prevX; val sy = cy - prevY
            val len = Math.hypot(sx, sy)
            if (len > 0.0) {
                val t = (((px - prevX) * sx + (py - prevY) * sy) / (len * len)).coerceIn(0.0, 1.0)
                val perp = Math.hypot(px - (prevX + t * sx), py - (prevY + t * sy))
                if (perp < bestPerp) { bestPerp = perp; bestAlong = cum + t * len }
                cum += len
            }
            prevX = cx; prevY = cy
        }
        return if (bestPerp == Double.MAX_VALUE) null else bestAlong to bestPerp
    }

    private class Cand(val prio: Int, val dist: Double, val key: String, val what: String, val kind: Int, val sound: Boolean)

    private fun angleDiff(a: Double, b: Double) = Math.abs(((a - b + 540.0) % 360.0) - 180.0)

    private fun fmtDist(m: Int) = if (m >= 1000) String.format(java.util.Locale.US, "%.1fkm", m / 1000.0) else "${m}m"

    /** 앞쪽 3km 안의 돌발상황·정체·제한속도 중 가장 중요한(사고·재난 > 정체 > 제한속도 변경 > 공사) 하나를,
     *  같은 종류끼리는 가장 가까운 것을 골라 상단바 알림 자리·차량 쪽에 "2.4km 앞 사고발생!" 같은 글자로 올린다.
     *  진행 방향을 알면 앞쪽 부채꼴 안의 것만 본다. 방향을 모르면(멈춤) 돌발상황만 방향 상관없이 보고, 정체·제한속도는 건너뛴다. */
    private fun aheadTick() {
        val ctx = appContext ?: return
        try {
            if (!isEnabled(ctx)) { clearAhead(); return }
            val loc = locationProvider?.invoke()
            if (loc == null) { clearAhead(); return }
            val brg = loc.third
            val cands = ArrayList<Cand>()
            // 재억 요청(2026-11-08): 길안내 중이면 내 경로선을 따라 앞쪽 3km 안의 것만, 안내가 없으면 진행 방향 정면 좁은 폭만 본다.
            val route = KakaoRouteDataRepository.routeCoordinates
            val routeFresh = route.size >= 2 && (KakaoRouteDataRepository.isFresh(15000L) ||
                System.currentTimeMillis() - KakaoRouteDataRepository.routeCoordinatesUpdatedAt < 15000L)
            val curOnRoute = if (routeFresh) projectOnRoute(loc.first, loc.second, route) else null
            val routeMode = curOnRoute != null && curOnRoute.second <= ROUTE_SELF_MAX_M
            /** 앞쪽 거리(m)를 돌려주고, 대상이 아니면 null. 경로 모드: 경로선 위 거리, 아니면 직선거리+방향 폭 제한. */
            fun aheadDist(lat: Double, lon: Double, maxM: Double, routeLatM: Double, coneDeg: Double, narrow: Boolean): Double? {
                if (routeMode) {
                    val p = projectOnRoute(lat, lon, route) ?: return null
                    if (p.second > routeLatM) return null
                    val d = p.first - curOnRoute!!.first
                    return if (d in AHEAD_MIN_M..maxM) d else null
                }
                val d = distanceM(loc.first, loc.second, lat, lon)
                if (d < AHEAD_MIN_M || d > maxM) return null
                if (brg != null) {
                    val diff = angleDiff(bearingDeg(loc.first, loc.second, lat, lon), brg.toDouble())
                    if (narrow) {
                        if (diff > NARROW_CONE_DEG || d * Math.sin(Math.toRadians(diff)) > NARROW_LATERAL_M) return null
                    } else if (diff > coneDeg) return null
                }
                return d
            }
            for (inc in items) {
                if (inc.type != "acc" && inc.type != "cor" && inc.type != "dis") continue
                val d = aheadDist(inc.lat, inc.lon, AHEAD_MAX_M, ROUTE_LATERAL_M, AHEAD_CONE_DEG, narrow = true) ?: continue
                val (what, kind) = when (inc.type) { "acc" -> "사고발생!" to 90; "cor" -> "공사중!" to 91; else -> "재난발생!" to 92 }
                cands.add(Cand(if (inc.type == "cor") 3 else 0, d, "${inc.type}|${inc.lon}|${inc.lat}", what, kind, inc.type != "cor"))
            }
            if (brg != null || routeMode) {
                for (j in jams) {
                    val d = aheadDist(j.lat, j.lon, AHEAD_MAX_M, JAM_ROUTE_LATERAL_M, AHEAD_CONE_DEG, narrow = false) ?: continue
                    if (!routeMode && brg != null && angleDiff(j.heading.toDouble(), brg.toDouble()) > 75.0) continue   // 반대 방향 차로의 정체는 거른다
                    val len = if (j.lenM >= 1000) String.format(java.util.Locale.US, " (%.1fkm)", j.lenM / 1000.0) else ""
                    cands.add(Cand(1, d, "jam|${j.road}|${j.dir}|${Math.round(j.lon * 100)}|${Math.round(j.lat * 100)}", "정체 시속 ${j.speed}$len", 94, false))
                }
                for (v in vsls) {
                    val d = aheadDist(v.lat, v.lon, VSL_MAX_M, VSL_ROUTE_LATERAL_M, VSL_CONE_DEG, narrow = false) ?: continue
                    if (!routeMode && brg != null && v.heading >= 0 && angleDiff(v.heading.toDouble(), brg.toDouble()) > 60.0) continue
                    cands.add(Cand(2, d, "vsl|${v.lon}|${v.lat}|${v.limit}", "제한 ${v.limit} (기본 ${v.def})", 93, false))
                }
            }
            val b = cands.minWithOrNull(compareBy<Cand>({ it.prio }, { it.dist }))
            if (b == null) { clearAhead(); return }
            val m = b.dist.toInt() / 10 * 10
            val text = "${fmtDist(m)} 앞 ${b.what}"
            AccidentAlertRepository.updateIncident(text, m)
            if (ctx.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).getBoolean(PREF_CAR, true)) {
                carText = text; carDistM = m; carAtMs = System.currentTimeMillis(); carKind = b.kind
            } else carText = null
            // 상단바에 띄우는 설정이면 가운데 경고창은 띄우지 않는다. 경고음은 사고·재난일 때만.
            val isNew = alerted.add(b.key)
            // 새 사고·재난이 처음 잡히면 콤마 계기판 번쩍임 번호를 올린다(차량 전송을 켠 경우만).
            if (isNew && b.sound && ctx.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).getBoolean(PREF_CAR, true)) flashSeq++
            if (!isTopBar(ctx) && isNew) {
                main.post { try { alertCallback?.invoke(text, b.sound) } catch (_: Exception) { } }
            }
        } catch (e: Exception) {
            NavLogger.d(ctx, "[돌발] 앞쪽 계산 실패: ${e.message}")
        }
    }

    private fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2)
        return 2 * r * Math.asin(Math.sqrt(a))
    }

    private fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lon2 - lon1)
        val y = Math.sin(dl) * Math.cos(p2)
        val x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl)
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
    }

    private fun fetch(lat: Double, lon: Double): List<Incident> {
        val cx = Math.floor(lon / GRID) * GRID
        val cy = Math.floor(lat / GRID) * GRID
        val url = String.format(java.util.Locale.US,
            "%s/events?minX=%.1f&maxX=%.1f&minY=%.1f&maxY=%.1f",
            BASE_URL, cx - GRID, cx + GRID * 2, cy - GRID, cy + GRID * 2)
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000; c.readTimeout = 8000
            if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
            val body = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return parse(body, System.currentTimeMillis())
        } finally { c.disconnect() }
    }

    /** 고속도로 정체(/jam)와 가변 제한속도(/vsl)를 받는다. 서버에 없거나 실패하면 조용히 넘어간다. */
    private fun fetchTraffic(ctx: Context, lat: Double, lon: Double) {
        val cx = Math.floor(lon / GRID) * GRID
        val cy = Math.floor(lat / GRID) * GRID
        val q = String.format(java.util.Locale.US, "?minX=%.1f&maxX=%.1f&minY=%.1f&maxY=%.1f", cx - GRID, cx + GRID * 2, cy - GRID, cy + GRID * 2)
        try {
            jams = parseJams(getBody("$BASE_URL/jam$q"))
            vsls = parseVsls(getBody("$BASE_URL/vsl$q"))
            NavLogger.dIfChanged(ctx, "traffic_cnt", "[돌발] 정체=${jams.size}곳 제한속도변경=${vsls.size}곳")
        } catch (e: Exception) {
            NavLogger.dIfChanged(ctx, "traffic_fail", "[돌발] 정체·제한속도 받기 실패: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun getBody(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000; c.readTimeout = 8000
            if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { c.disconnect() }
    }

    internal fun parseJams(body: String): List<Jam> {
        val arr = JSONObject(body).optJSONArray("items") ?: return emptyList()
        val out = ArrayList<Jam>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val x = o.optDouble("x", Double.NaN); val y = o.optDouble("y", Double.NaN)
            if (x.isNaN() || y.isNaN()) continue
            out.add(Jam(x, y, o.optInt("v"), o.optInt("len"), o.optInt("h"), o.optString("r"), o.optString("d")))
        }
        return out
    }

    internal fun parseVsls(body: String): List<Vsl> {
        val arr = JSONObject(body).optJSONArray("items") ?: return emptyList()
        val out = ArrayList<Vsl>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val x = o.optDouble("x", Double.NaN); val y = o.optDouble("y", Double.NaN)
            if (x.isNaN() || y.isNaN()) continue
            out.add(Vsl(x, y, o.optInt("v"), o.optInt("d"), o.optInt("h", -1)))
        }
        return out
    }

    internal fun parse(body: String, nowMs: Long): List<Incident> {
        val arr = JSONObject(body).optJSONArray("items") ?: return emptyList()
        val out = ArrayList<Incident>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val end = o.optLong("e", 0L)
            if (end in 1 until nowMs) continue
            out.add(Incident(
                type = o.optString("t", "etc"), detail = o.optString("d"),
                startMs = o.optLong("s", 0L), endMs = end,
                lon = o.optDouble("x", Double.NaN), lat = o.optDouble("y", Double.NaN),
                road = o.optString("r"), roadNo = o.optString("n"), dir = o.optString("w"),
                lanes = o.optString("l"), message = o.optString("m")
            ))
        }
        return out.filter { !it.lon.isNaN() && !it.lat.isNaN() }
    }

    fun typeName(t: String) = when (t) {
        "acc" -> "교통사고"; "cor" -> "공사"; "wea" -> "기상"; "dis" -> "재난"; else -> "돌발상황"
    }
}
