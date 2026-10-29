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
    /** 콤마용 종류 번호: 사고 90, 공사 91, 재난 92 (콤마의 carrot_serv.py가 거리와 함께 문구를 만든다). */
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
        if (exec != null) return
        val e = Executors.newSingleThreadScheduledExecutor()
        exec = e
        e.schedule({ tick() }, 3, TimeUnit.SECONDS)
        e.scheduleWithFixedDelay({ aheadTick() }, 5, 3, TimeUnit.SECONDS)
    }

    /** 앞쪽에 새 돌발상황이 처음 잡힐 때 한 번 불린다(메인 스레드). 화면이 큰 경고창을 띄운다. */
    @Volatile var alertCallback: ((String) -> Unit)? = null
    private val alerted = HashSet<String>()

    @Synchronized
    fun stop(who: Any) {
        if (owner !== who) return
        owner = null
        alertCallback = null
        locationProvider = null
        exec?.shutdownNow()
        exec = null
    }

    /** 스위치를 끄거나 켰을 때 바로 반영. */
    fun refreshNow() {
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

    /** 앞쪽 3km 안의 사고·공사·재난 중 가장 가까운 것을 상단바 오른쪽 알림 자리에 "2.4km 앞 사고발생!"으로 올린다.
     *  진행 방향을 알면 앞쪽 부채꼴 안의 것만, 방향을 모르면(멈춤) 방향 상관없이 가장 가까운 것을 보여준다. */
    private fun aheadTick() {
        val ctx = appContext ?: return
        try {
            if (!isEnabled(ctx)) { clearAhead(); return }
            val loc = locationProvider?.invoke()
            if (loc == null) { clearAhead(); return }
            val brg = loc.third
            var best: Incident? = null
            var bestD = Double.MAX_VALUE
            for (inc in items) {
                if (inc.type != "acc" && inc.type != "cor" && inc.type != "dis") continue
                val d = distanceM(loc.first, loc.second, inc.lat, inc.lon)
                if (d < AHEAD_MIN_M || d > AHEAD_MAX_M || d >= bestD) continue
                if (brg != null) {
                    val diff = Math.abs(((bearingDeg(loc.first, loc.second, inc.lat, inc.lon) - brg + 540.0) % 360.0) - 180.0)
                    if (diff > AHEAD_CONE_DEG) continue
                }
                best = inc; bestD = d
            }
            val b = best
            if (b == null) { clearAhead(); return }
            val what = when (b.type) { "acc" -> "사고발생!"; "cor" -> "공사중!"; else -> "재난발생!" }
            val m = bestD.toInt() / 10 * 10
            val dist = if (m >= 1000) String.format(java.util.Locale.US, "%.1fkm", m / 1000.0) else "${m}m"
            val text = "$dist 앞 $what"
            AccidentAlertRepository.updateIncident(text, m)
            if (ctx.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).getBoolean(PREF_CAR, true)) {
                carText = text; carDistM = m; carAtMs = System.currentTimeMillis()
                carKind = when (b.type) { "acc" -> 90; "cor" -> 91; else -> 92 }
            } else carText = null
            // 상단바에 띄우는 설정이면 가운데 경고창은 띄우지 않는다.
            if (!isTopBar(ctx) && alerted.add("${b.type}|${b.lon}|${b.lat}")) {
                main.post { try { alertCallback?.invoke(text) } catch (_: Exception) { } }
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
