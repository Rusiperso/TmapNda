package com.tmap.nda.naver

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import com.tmap.nda.KakaoRouteDataRepository
import com.tmap.nda.KakaoSdkState
import com.tmap.nda.GuideVoice
import com.tmap.nda.NavLogger
import com.tmap.nda.navdy.NavdySender
import com.tmap.nda.nmirror.GuidanceSnapshot
import com.tmap.nda.nmirror.NMirrorSender
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 네이버 길안내 한 판을 돌리는 곳: 위치 받기 → 엔진 갱신 → 음성 → 재탐색 → 콤마/HUD/클러스터 전송.
 * 전송은 카카오 때와 같은 창구(KakaoRouteDataRepository, GuidanceSnapshot)를 쓰므로 받는 쪽은 바뀌는 게 없다.
 * GPS도 여기서 받기 때문에 안내 화면이 닫혀도(티맵 화면으로 돌아가도) 안내와 전송은 계속된다.
 * 호출은 모두 메인 스레드에서 한다(재탐색 요청만 내부 스레드에서 돌고 결과는 메인으로 돌아온다).
 */
object NaverNavigator {

    interface Listener {
        fun onLocation(loc: Location) {}
        fun onState(state: GuidanceState, goalName: String) {}
        fun onRouteChanged(route: NaverRoute) {}
        /** 지도에 찍을 앞쪽 안전정보(가까운 순). 지나가면 목록에서 빠진다. */
        fun onSafety(points: List<SafetyPoint>) {}
        fun onStatus(text: String) {}
        fun onFinished(arrived: Boolean) {}
    }

    /** 교통 반영 갱신 간격. 호출 한도 때문에 짧게 하지 않는다. */
    private const val TRAFFIC_REFRESH_MS = 5 * 60_000L

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var appContext: Context? = null
    private var listener: Listener? = null
    private var engine: NaverGuidanceEngine? = null
    private var goalName: String = ""
    private var goal: LonLat? = null
    private var waypoints: List<LonLat> = emptyList()
    private var option: String = "traoptimal"
    private val gate = RerouteGate(offRouteMeters = 30.0, confirmMs = 1_500, minIntervalMs = 8_000)
    private val planner = AnnouncePlanner()
    private var rerouting = false
    /** 진행 중인 재탐색의 사유와 번호. 교통 갱신 중에 길을 벗어나면 이탈 재탐색이 앞지르고, 늦게 온 옛 결과는 버린다. */
    private var reroutingReason = ""
    private var rerouteSeq = 0
    private var lastState: GuidanceState? = null
    private var lastTrafficRefreshAt = 0L
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var appliedVoice = ""
    private val ttsPending = ArrayList<String>()

    var lastLocation: Location? = null
        private set
    var currentRoute: NaverRoute? = null
        private set
    private var locationOn = false
    private var lastGpsFixAt = 0L
    /** 지금 위치가 GPS가 아니라 네트워크 위치면 true(화면에 안내용). */
    var usingNetworkFix = false
        private set

    val isRunning: Boolean get() = engine != null

    /** 마지막 위치가 [maxAgeMs] 안의 최근 값일 때만 돌려준다(오래된 위치를 현재 위치로 쓰지 않기 위해). */
    fun currentFix(maxAgeMs: Long = 120_000L): Location? {
        val l = lastLocation ?: return null
        val ageMs = (android.os.SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000L
        return if (ageMs <= maxAgeMs) l else null
    }

    /** 지금 안내 중인 목적지(없으면 null). 화면이 다시 만들어졌을 때 같은 안내인지 확인하는 데 쓴다. */
    val goalPoint: LonLat? get() = goal

    /** 안내 음성 크기(0~1)와 음소거. 앱의 "길안내 음량" 설정에서 넘어온다. */
    @Volatile var guideVolume: Float = 1f
    @Volatile var muted: Boolean = false

    fun setListener(l: Listener?) { listener = l }

    /** [l]이 지금 등록된 리스너일 때만 해제한다(새 화면이 이미 갈아 끼웠다면 그대로 둔다). */
    fun clearListener(l: Listener) { if (listener === l) listener = null }

    // ===== 위치 =====
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) { handleLocation(location) }
        @Deprecated("API 29 이하 호환용")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    /** GPS 수신을 시작한다(이미 켜져 있으면 무시). 권한이 없으면 false. */
    fun ensureLocation(context: Context): Boolean {
        val ctx = context.applicationContext
        appContext = ctx
        if (locationOn) return true
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return false
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper())
            // 실내·터널처럼 GPS가 안 잡힐 때를 위한 보조: 네트워크 위치(GPS가 살아 있으면 무시한다)
            runCatching { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper()) }
            if (lastLocation == null) lastLocation = FreshLocation.lastFresh(ctx)
            locationOn = true
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 안내 중이 아닐 때만 GPS를 끈다. */
    fun releaseLocationIfIdle() {
        if (isRunning || !locationOn) return
        val ctx = appContext ?: return
        (ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(locationListener)
        locationOn = false
    }

    private fun handleLocation(loc: Location) {
        // GPS가 최근 5초 안에 들어왔다면 부정확한 네트워크 위치는 버린다(위치가 튀는 것 방지)
        if (loc.provider == LocationManager.GPS_PROVIDER) {
            lastGpsFixAt = System.currentTimeMillis()
        } else if (System.currentTimeMillis() - lastGpsFixAt < 5_000L) {
            return
        }
        usingNetworkFix = loc.provider != LocationManager.GPS_PROVIDER
        lastLocation = loc
        listener?.onLocation(loc)
        onLocation(loc)
    }

    // ===== 안내 시작/종료 =====
    /** 미리 받아둔 [route]로 안내를 시작한다. */
    fun begin(context: Context, route: NaverRoute, to: LonLat, toName: String, via: List<LonLat> = emptyList()) {
        val ctx = context.applicationContext
        appContext = ctx
        com.tmap.nda.GuideExclusive.stopKakao(ctx)
        ensureLocation(ctx)
        ensureTts(ctx)
        goal = to; goalName = toName; waypoints = via; option = route.option
        attachRoute(route)
        speak("경로 안내를 시작합니다")
        // 이번 달 네이버 길찾기 사용량을 안내 시작할 때 한 번 알려준다.
        val used = NaverDirectionsClient.monthlyCount(ctx)
        val cap = NaverDirectionsClient.DEFAULT_MONTHLY_CAP
        listener?.onStatus("이번 달 네이버 길안내 ${cap}건 중 ${Math.round(used * 100f / cap)}% (${used}건 사용 중)")
        // 다음 위치가 올 때까지 화면이 비어 있지 않도록, 가지고 있는 마지막 위치로 바로 한 번 계산한다
        lastLocation?.let { onLocation(it) }
    }


    /** 지금 경로 위의 안전정보(카카오 경로에서 가져와 네이버 경로선 위의 것만 남김). 지도 표시용. */
    @Volatile var safetyItems: List<RouteSafetyItem> = emptyList()
        private set
    private var kakaoSafetySeq = 0
    /** 카카오 목록에서 이미 지도용 목록으로 옮긴(또는 티맵 값과 겹쳐 건너뛴) 항목 번호. 같은 항목을 다시 올리지 않는다. */
    private val kakaoSeeded = HashSet<Int>()

    /**
     * 같은 출발·도착으로 카카오에 경로만 받아(길안내 시작 안 함) 안전정보 목록을 얻고, 네이버 경로선 25m 안의 것만 남긴다.
     * 경유지는 넘기지 않으므로 경유지 이후 구간은 카카오 경로와 달라 일부 빠질 수 있다.
     */
    private fun fetchKakaoSafety(ctx: Context, route: NaverRoute) {
        val to = goal ?: return
        val start = route.path.firstOrNull() ?: return
        val seq = ++kakaoSafetySeq
        safetyItems = emptyList()
        kakaoSeeded.clear()
        // 안전정보는 카카오 SDK가 있어야 얻는다: 카카오 네이티브 앱 키가 없으면 지도에 표시할 수 없다고 한 번 알려준다.
        val kakaoKey = ctx.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).getString("kakao_native_app_key", "").orEmpty().trim()
        if (kakaoKey.isEmpty()) {
            NavLogger.d(ctx, "[카카오안전정보] 카카오 키 없음 - 건너뜀")
            if (!safetyNoKeyWarned) {
                safetyNoKeyWarned = true
                listener?.onStatus("카카오 SDK(네이티브 앱 키)가 있어야 카메라 등 안전정보가 지도에 표시돼요. 설정에서 카카오 키를 넣어주세요.")
            }
            return
        }
        val app = ctx as? android.app.Application ?: return
        KakaoSdkState.ensureInitialized(app, kakaoKey) { ok, msg ->
            if (seq != kakaoSafetySeq || engine == null) return@ensureInitialized
            if (!ok) { NavLogger.d(ctx, "[카카오안전정보] 카카오 SDK 준비 실패: $msg"); return@ensureInitialized }
            requestKakaoSafety(ctx, route, start, to, seq)
        }
    }

    private var safetyNoKeyWarned = false

    private fun requestKakaoSafety(ctx: Context, route: NaverRoute, start: LonLat, to: LonLat, seq: Int) {
        try {
            KakaoSdkState.computeEtaForOptions(
                ctx, start.lat, start.lon, to.lat, to.lon,
                listOf(com.kakaomobility.knsdk.KNRoutePriority.KNRoutePriority_Recommand to 0),
                reportFailures = false
            ) { _, _, _, _, kRoute ->
                main.post {
                    if (seq != kakaoSafetySeq || engine == null) return@post
                    try {
                        val r = kRoute as? com.kakaomobility.knsdk.trip.kntrip.knroute.KNRoute
                        if (r == null) { NavLogger.d(ctx, "[카카오안전정보] 경로 못 받음"); return@post }
                        val total = r.safetyList()?.size ?: -1
                        val items = RouteSafety.build(r, route)
                        NavLogger.d(ctx, "[카카오안전정보] 카카오경로 ${r.totalDist}m/네이버경로 ${route.distanceMeters}m, 카카오목록 ${total}개 → 네이버선 ${RouteSafety.MAX_OFF_ROUTE_M.toInt()}m 안·같은방향 ${items.size}개")
                        items.take(15).forEach { NavLogger.d(ctx, "[카카오안전정보] 종류=${it.sdiType} 제한=${it.speedLimit} 경로위치=${it.alongM}m 선까지=${it.offM}m") }
                        safetyItems = items
                        kakaoSeeded.clear()
                    } catch (e: Exception) {
                        NavLogger.e(ctx, "[카카오안전정보] 읽기 예외: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            NavLogger.e(ctx, "[카카오안전정보] 요청 예외: ${e.message}")
        }
    }

    /** 지나간 첫 경유지를 목록에서 뺀다(이후 재탐색 때 다시 돌아가지 않게). */
    fun dropPassedWaypoint() { if (waypoints.isNotEmpty()) waypoints = waypoints.drop(1) }

    /** 말하던 음성과 대기 중인 음성을 바로 끊는다(다른 안내로 넘어갈 때). */
    fun silence() {
        ttsPending.clear()
        try { tts?.stop() } catch (_: Exception) {}
    }

    fun stop(arrived: Boolean = false) {
        engine = null
        goal = null
        currentRoute = null
        planner.reset()
        kakaoSafetySeq++; safetyItems = emptyList()
        camAnnounced = 0; camLastDist = Int.MAX_VALUE; camLastType = -1
        KakaoRouteDataRepository.reset()
        safety.clear()
        listener?.onSafety(emptyList())
        listener?.onFinished(arrived)
        releaseLocationIfIdle()
    }

    private fun onLocation(loc: Location) {
        val ctx = appContext ?: return
        val eng = engine ?: return
        val s = eng.update(loc.longitude, loc.latitude)
        lastState = s
        val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6 else 0.0
        val now = System.currentTimeMillis()

        publish(ctx, s)
        listener?.onState(s, goalName)
        planner.next(s, speedKmh, minimalOn(ctx))?.let { speak(it) }
        checkCameraSpeedBeep(ctx, speedKmh)
        announceCamera(ctx, speedKmh)
        trackSafety(s)

        if (s.arrived) {
            speak("목적지에 도착했습니다")
            stop(arrived = true)
            return
        }

        // GPS 오차가 큰 날에는 이탈 판단을 느슨하게 한다
        val offLimit = maxOf(30.0, loc.accuracy.toDouble() * 1.5)
        // 차가 서 있거나(속도 0) 속도 정보가 없는 위치(네트워크 위치)에서는 재탐색하지 않는다 - 건물 안에서 흔들리는 위치로 호출을 낭비하지 않기 위해
        val moving = loc.hasSpeed() && loc.speed > 1.5f
        val effective = if (!moving || s.offRouteMeters < offLimit) s.copy(offRouteMeters = 0.0) else s
        if ((!rerouting || reroutingReason == "교통갱신") && gate.shouldReroute(now, effective)) {
            reroute(ctx, loc, "이탈")
        } else if (!rerouting && now - lastTrafficRefreshAt > TRAFFIC_REFRESH_MS && lastTrafficRefreshAt > 0 &&
            ctx.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).getBoolean("naver_traffic_reroute_enabled", true)) {
            reroute(ctx, loc, "교통갱신")
        }
    }

    // ===== 지도에 찍을 안전정보 =====
    // 티맵은 가장 가까운 안전정보 2개만 "앞으로 몇 m"로 알려준다. 그 거리를 경로선 위 거리로 바꿔 한 번 본 것은 기억해 두었다가,
    // 지나가면(내 위치가 그 지점을 넘으면) 지운다. 그래서 연달아 나오는 2~3개도 하나씩 이어서 보인다.
    private val safety = ArrayList<SafetyPoint>()
    private var safetySeq = 0L
    private const val SAFETY_MAX_AHEAD_M = 1500
    private const val SAFETY_PASSED_M = 30.0
    private const val SAFETY_MATCH_M = 100.0
    private const val SAFETY_MAX_SHOWN = 4
    /** 티맵과 카카오는 같은 카메라를 서로 다른 종류 번호로 알려줄 수 있어, 단속 카메라 계열은 같은 종류로 본다. */
    private val CAMERA_KIND = setOf(0, 1, 6, 7, 9, 11, 12, 15, 16, 17)
    private fun sameKind(a: Int, b: Int) = a == b || (a in CAMERA_KIND && b in CAMERA_KIND)
    /** 카카오 목록 항목은 이 거리(m) 안으로 다가오면 지도에 올린다. */
    private const val KAKAO_SAFETY_MAX_AHEAD_M = 3000

    private fun trackSafety(s: GuidanceState) {
        val eng = engine ?: return
        val progress = s.progressMeters
        safety.removeAll { it.absMeters < progress - SAFETY_PASSED_M }
        fun feed(type: Int, dist: Int, limit: Int) {
            if (dist <= 0 || dist > SAFETY_MAX_AHEAD_M) return
            if (NaverMapIcons.safetyLabel(type, limit) == null) return
            // 방지턱은 티맵이 거리를 안 주면 앱이 임시로 150을 넣는다. 진짜 거리가 아니므로 지도에는 찍지 않는다.
            if (type == 22 && dist == 150) return
            val abs = progress + dist
            val at = eng.pointAt(abs) ?: return
            val i = safety.indexOfFirst { sameKind(it.type, type) && kotlin.math.abs(it.absMeters - abs) < SAFETY_MATCH_M }
            val p = SafetyPoint(if (i >= 0) safety[i].id else ++safetySeq, type, limit, abs, at.lon, at.lat, dist)
            if (i >= 0) safety[i] = p else safety.add(p)
        }
        // 카카오 경로에서 받아 둔 먼 곳 안전정보를 같은 목록에 먼저 올린다(티맵보다 멀리서부터 보임).
        // 이미 같은 종류가 100m 안에 있으면(티맵이 알려준 것) 겹친 것으로 보고 건너뛴다. 올린 항목은 다시 올리지 않는다.
        val kItems = safetyItems
        for ((idx, k) in kItems.withIndex()) {
            if (idx in kakaoSeeded) continue
            val ahead = k.alongM - progress
            if (ahead < -SAFETY_PASSED_M) { kakaoSeeded.add(idx); continue }
            if (ahead > KAKAO_SAFETY_MAX_AHEAD_M) continue
            if (NaverMapIcons.safetyLabel(k.sdiType, k.speedLimit) == null) { kakaoSeeded.add(idx); continue }
            kakaoSeeded.add(idx)
            if (safety.any { sameKind(it.type, k.sdiType) && kotlin.math.abs(it.absMeters - k.alongM) < SAFETY_MATCH_M }) continue
            safety.add(SafetyPoint(++safetySeq, k.sdiType, k.speedLimit, k.alongM.toDouble(), k.lon, k.lat, ahead.toInt()))
        }
        val r = com.tmap.nda.SdiDataRepository
        feed(r.sdiType, r.sdiDistance, r.sdiSpeedLimit)
        feed(r.sdi2Type, r.sdi2Distance, r.sdi2SpeedLimit)
        // 그래도 겹쳐 들어온 같은 계열 항목은 화면에서 하나만 보이게 한다(100m 안).
        val sortedAll = safety
            .map { it.copy(aheadMeters = kotlin.math.max(0, (it.absMeters - progress).toInt())) }
            .sortedBy { it.absMeters }
        val out = ArrayList<SafetyPoint>()
        for (p in sortedAll) {
            if (out.any { sameKind(it.type, p.type) && kotlin.math.abs(it.absMeters - p.absMeters) < SAFETY_MATCH_M }) continue
            out.add(p)
        }
        val shown = out.take(SAFETY_MAX_SHOWN)
        listener?.onSafety(shown)
    }

    private var lastCameraBeepAt = 0L

    private var camAnnounced = 0
    private var camLastDist = Int.MAX_VALUE
    private var camLastType = -1

    /**
     * 단속 카메라 음성. 네이버엔 카메라 정보가 없어 티맵 데이터를 쓰며, 티맵 안내음성이 꺼져 있을 때만 말한다(켜져 있으면 티맵이 직접 말함).
     * 시끄럽지 않게 안전정보 하나당 최대 2번(600m 안에서 한 번, 200m 안에서 한 번)만 말한다 - 카카오 최소 안내와 같은 기준.
     */
    private fun minimalOn(ctx: Context) =
        ctx.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).getBoolean("naver_minimal_guide_enabled", false)

    private fun announceCamera(ctx: Context, speedKmh: Double) {
        val type = com.tmap.nda.SdiDataRepository.sdiType
        val dist = com.tmap.nda.SdiDataRepository.sdiDistance
        val limit = com.tmap.nda.SdiDataRepository.sdiSpeedLimit
        // 티맵이 알려주는 안전·도로 정보(카메라·방지턱·구간단속·어린이보호구역·휴게소·톨게이트 등)를 전부 말한다.
        val name = when (type) {
            0 -> "신호 과속 단속 카메라"; 1 -> "과속 단속 카메라"; 2 -> "구간 단속 시작"; 3 -> "구간 단속 종료"; 4 -> "구간 단속 중"
            6 -> "신호 단속 카메라"; 7 -> "이동식 단속 카메라"; 8 -> "과속 위험 구간"; 9 -> "버스 전용차로 단속"; 11 -> "갓길 감시"
            12 -> "끼어들기 금지 단속"; 13 -> "교통정보 수집"; 15 -> "과적 차량 단속"; 16 -> "적재 불량 단속"; 17 -> "주차 단속"
            19 -> "철길 건널목"; 20 -> "어린이 보호구역"; 22 -> "과속 방지턱"; 25 -> "휴게소"; 26 -> "톨게이트"; 27 -> "안개 주의 구간"; 29 -> "사고 다발 구간"
            30 -> "급커브 구간"; 32 -> "급경사 구간"; 33 -> "야생동물 사고 구간"
            else -> null
        }
        if (name == null || dist <= 0) { camAnnounced = 0; camLastDist = Int.MAX_VALUE; camLastType = -1; return }
        // 거리가 다시 멀어졌거나 종류가 바뀌면 다른 카메라로 본다
        if (type != camLastType || dist > camLastDist + 150) camAnnounced = 0
        camLastType = type; camLastDist = dist
        if (!minimalOn(ctx) && camAnnounced >= 2) return
        if (!ctx.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).getBoolean("tmap_muted", false)) return
        val limitSay = if (limit >= 30) ". 제한속도 ${limit}킬로미터입니다" else ""
        if (minimalOn(ctx)) {
            // 최소 안내: 시내 300m·100m("잠시 후") 2번, 고속도로 2km·1km·300m("잠시 후") 3번
            val stages = if (speedKmh >= 80) intArrayOf(2000, 1000, 300) else intArrayOf(300, 100)
            // 이미 지나온 먼 단계는 말한 것으로 친다
            val idx = stages.indexOfLast { dist <= it }
            if (idx < 0 || camAnnounced > idx) return
            camAnnounced = idx + 1
            val st = stages[idx]
            val say = when {
                idx == stages.size - 1 -> "잠시 후 ${name}입니다$limitSay"
                st >= 1000 -> "전방 ${st / 1000}킬로미터 앞 ${name}입니다$limitSay"
                else -> "전방 ${st}미터 앞 ${name}입니다$limitSay"
            }
            speak(say, queue = true)
            return
        }
        when {
            camAnnounced == 0 && dist <= 600 -> {
                val d = (dist / 100 * 100).coerceAtLeast(100)
                speak("전방 ${d}미터 앞 ${name}입니다${if (limit >= 30) ". 제한속도 ${limit}킬로미터입니다" else ""}", queue = true)
                camAnnounced = if (dist <= 200) 2 else 1
            }
            camAnnounced == 1 && dist <= 200 -> {
                speak("잠시 후 ${name}입니다${if (limit >= 30) ". 제한속도 ${limit}킬로미터입니다" else ""}", queue = true)
                camAnnounced = 2
            }
        }
    }

    /**
     * 단속 카메라 300m 안에서 속도가 그 카메라 제한속도의 110%를 넘는 동안 2초마다 경고음을 낸다(카카오 길안내와 같은 규칙).
     * 속도를 110% 이내로 줄이거나 카메라를 지나면 멈춘다. 설정의 "속도 10% 초과 시 경고음"을 따른다.
     */
    private fun checkCameraSpeedBeep(ctx: Context, speedKmh: Double) {
        val limit = com.tmap.nda.SdiDataRepository.sdiSpeedLimit
        val dist = com.tmap.nda.SdiDataRepository.sdiDistance
        val near = com.tmap.nda.SdiDataRepository.sdiType > 0 && limit >= 30 && dist in 1..300
        if (!near || muted || speedKmh <= limit * 1.1) return
        val nowMs = android.os.SystemClock.elapsedRealtime()
        if (nowMs - lastCameraBeepAt < 2000L) return
        if (!ctx.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).getBoolean("over_speed_warning_enabled", true)) return
        lastCameraBeepAt = nowMs
        NavLogger.e(ctx, "[과속경고음발생][네이버화면] speedKph=${speedKmh.toInt()} limit=$limit (limit*1.1=${limit * 1.1}) dist=$dist")
        try {
            val volumePercent = com.tmap.nda.VolumeHelper.guideVolumePercent(ctx).coerceIn(1, 100)
            val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, volumePercent)
            tone.startTone(android.media.ToneGenerator.TONE_CDMA_PIP, 400)
            main.postDelayed({ tone.release() }, 500)
        } catch (e: Exception) {
            NavLogger.e(ctx, "[네이버] 속도경고음 재생 예외: ${e.message}")
        }
    }

    /** 아래 바 새로고침 버튼: 지금 위치에서 경로를 다시 받는다(짧아지지 않아도 새 경로로 바꾼다). */
    fun refreshRoute() {
        val ctx = appContext ?: return
        val loc = lastLocation ?: return
        if (rerouting || engine == null) return
        reroute(ctx, loc, "수동")
    }

    private fun reroute(ctx: Context, loc: Location, reason: String) {
        val to = goal ?: return
        rerouting = true
        reroutingReason = reason
        val seq = ++rerouteSeq
        if (reason == "이탈") gate.noteRequested(System.currentTimeMillis())
        // 응답을 기다리는 동안 바로 알려줘서 반응이 빠르게 느껴지게 한다
        if (reason == "이탈") speak("경로를 재탐색합니다")
        lastTrafficRefreshAt = System.currentTimeMillis()
        val from = LonLat(loc.longitude, loc.latitude)
        listener?.onStatus(when (reason) { "이탈" -> "경로를 벗어나 다시 찾는 중…"; "수동" -> "경로를 새로고침하는 중…"; else -> "교통 상황 갱신 중…" })
        NavLogger.d(ctx, "[네이버] 재탐색 요청 사유=$reason")
        io.execute {
            val res = NaverDirectionsClient.requestRoute(ctx, from, to, waypoints, option)
            main.post {
                if (seq != rerouteSeq) return@post   // 더 새 재탐색이 이미 시작됐다
                rerouting = false
                val route = res.routes.firstOrNull()
                if (!res.ok || route == null) {
                    listener?.onStatus("재탐색 실패(${res.code}): ${res.message}")
                    NavLogger.d(ctx, "[네이버] 재탐색 실패 code=${res.code} ${res.message}")
                    return@post
                }
                if (reason == "교통갱신" && !isMeaningfulChange(route)) {
                    listener?.onStatus("교통 갱신: 기존 경로 유지")
                    return@post
                }
                attachRoute(route)
                if (reason == "교통갱신") speak("더 빠른 경로로 안내합니다", essential = false)
                val km = route.distanceMeters / 1000.0
                val remainText = if (route.distanceMeters >= 1000) java.lang.String.format(java.util.Locale.US, "%.1fkm", km) else "${route.distanceMeters}m"
                listener?.onStatus("재탐색 완료: 남은 $remainText")
            }
        }
    }

    /** 교통 갱신 결과가 현재 경로보다 확실히 빠를 때만 갈아탄다(1분 이상 단축). */
    private fun isMeaningfulChange(newRoute: NaverRoute): Boolean {
        val cur = lastState ?: return true
        return cur.remainTimeSec - newRoute.durationMs / 1000 >= 60
    }

    private fun attachRoute(route: NaverRoute) {
        engine = NaverGuidanceEngine(route)
        currentRoute = route
        planner.reset()
        safety.clear()
        lastTrafficRefreshAt = System.currentTimeMillis()
        KakaoRouteDataRepository.routeCoordinates = thin(route.path)
        KakaoRouteDataRepository.routeCoordinatesUpdatedAt = System.currentTimeMillis()
        listener?.onRouteChanged(route)
        appContext?.let { fetchKakaoSafety(it, route) }
    }

    /** 콤마로 보내는 경로선은 점이 너무 많지 않게 솎는다. 약 20m 간격, 최대 800점. */
    private fun thin(path: List<LonLat>): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        var acc = 0.0
        out.add(path.first().lon to path.first().lat)
        for (i in 1 until path.size) {
            acc += NaverGuidanceEngine.distance(path[i - 1], path[i])
            if (acc >= 20.0 || i == path.size - 1) { out.add(path[i].lon to path[i].lat); acc = 0.0 }
        }
        if (out.size <= 800) return out
        val step = out.size / 800.0
        return List(800) { out[(it * step).toInt()] } + out.last()
    }

    private fun publish(ctx: Context, s: GuidanceState) {
        val g = s.nextGuide
        val second = s.secondGuide
        val type = g?.type ?: 0
        val autoDrive = ctx.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).getBoolean("auto_navi_drive_enabled", false)
        val turnCode = if (g != null) NaverTurnMap.toCommaCode(type, autoDrive) else NaverTurnMap.NONE
        val mainText = g?.instructions.orEmpty().ifBlank { NaverTurnMap.label(type) }.ifBlank { s.roadName }
        val nextText = second?.instructions.orEmpty().ifBlank { NaverTurnMap.label(second?.type ?: 0) }

        KakaoRouteDataRepository.tbtTurnType = turnCode
        KakaoRouteDataRepository.routeCoordinatesUpdatedAt = System.currentTimeMillis()
        KakaoRouteDataRepository.publishGuidance(
            tbtDist = s.nextGuideDistMeters,
            tbtMainText = mainText,
            remainDist = s.remainMeters,
            remainTime = s.remainTimeSec,
            roadName = s.roadName,
            rgCodeName = NaverTurnMap.kakaoLikeName(type),
            directionAngle = 0,
            destinationName = goalName,
            hasNextDirection = second != null,
            nextTbtDist = s.secondGuideDistMeters,
            nextRgCodeName = NaverTurnMap.kakaoLikeName(second?.type ?: 0),
            nextDirectionAngle = 0,
            nextTbtMainText = nextText
        )

        val snapshot = GuidanceSnapshot(
            turnDistanceMeters = s.nextGuideDistMeters,
            turnMainText = mainText,
            turnNodeName = "",
            roadName = s.roadName,
            rgCodeName = NaverTurnMap.kakaoLikeName(type),
            directionAngle = 0,
            remainDistanceMeters = s.remainMeters,
            remainTimeSeconds = s.remainTimeSec,
            destinationName = goalName,
            hasNext = second != null,
            nextTurnDistanceMeters = s.secondGuideDistMeters,
            nextTurnMainText = nextText,
            nextTurnNodeName = "",
            nextRgCodeName = NaverTurnMap.kakaoLikeName(second?.type ?: 0),
            nextDirectionAngle = 0,
            highwayListJson = null
        )
        try { NMirrorSender.send(ctx, snapshot) } catch (e: Exception) { NavLogger.e(ctx, "[네이버→nMirror] 전송 실패: ${e.message}") }
        try { NavdySender.sendGuidance(snapshot) } catch (e: Exception) { NavLogger.e(ctx, "[네이버→나브디] 전송 실패: ${e.message}") }
    }

    // ===== 음성 =====
    private fun ensureTts(ctx: Context) {
        if (tts != null) return
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
                tts?.let { GuideVoice.applySaved(ctx, it) }
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                ttsReady = true
                ttsPending.forEach { tts?.speak(it, TextToSpeech.QUEUE_ADD, null, "naver") }
                ttsPending.clear()
            }
        }
    }

    /** "최소 안내"가 켜져 있으면 "더 빠른 경로로 안내합니다" 멘트는 건너뛰고, 회전·카메라 안내는 말하는 횟수를 줄인다. */
    private fun speak(text: String, essential: Boolean = true, queue: Boolean = false) {
        if (!essential && appContext?.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                ?.getBoolean("naver_minimal_guide_enabled", false) == true) return
        if (!ttsReady) { ttsPending.add(text); return }
        if (muted) return
        // 설정에서 목소리를 바꿨으면 다음 안내부터 바로 적용
        appContext?.let { c ->
            val want = GuideVoice.saved(c)
            if (want != appliedVoice) { tts?.let { GuideVoice.applySaved(c, it) }; appliedVoice = want }
        }
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, guideVolume.coerceIn(0f, 1f)) }
        tts?.speak(text, if (queue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH, params, "naver")
    }
}
