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

    /** 지나간 첫 경유지를 목록에서 뺀다(이후 재탐색 때 다시 돌아가지 않게). */
    fun dropPassedWaypoint() { if (waypoints.isNotEmpty()) waypoints = waypoints.drop(1) }

    fun stop(arrived: Boolean = false) {
        engine = null
        goal = null
        currentRoute = null
        planner.reset()
        camAnnounced = 0; camLastDist = Int.MAX_VALUE; camLastType = -1
        KakaoRouteDataRepository.reset()
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
        planner.next(s, speedKmh)?.let { speak(it) }
        checkCameraSpeedBeep(ctx, speedKmh)
        announceCamera(ctx)

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

    private var lastCameraBeepAt = 0L

    private var camAnnounced = 0
    private var camLastDist = Int.MAX_VALUE
    private var camLastType = -1

    /**
     * 단속 카메라 음성. 네이버엔 카메라 정보가 없어 티맵 데이터를 쓰며, 티맵 안내음성이 꺼져 있을 때만 말한다(켜져 있으면 티맵이 직접 말함).
     * 시끄럽지 않게 안전정보 하나당 최대 2번(600m 안에서 한 번, 200m 안에서 한 번)만 말한다 - 카카오 최소 안내와 같은 기준.
     */
    private fun announceCamera(ctx: Context) {
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
        if (camAnnounced >= 2) return
        if (!ctx.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).getBoolean("tmap_muted", false)) return
        val limitText = if (limit >= 30) ", 제한속도 $limit" else ""
        when {
            camAnnounced == 0 && dist <= 600 -> {
                val d = (dist / 100 * 100).coerceAtLeast(100)
                speak("전방 ${d}미터 앞 $name$limitText", queue = true)
                camAnnounced = if (dist <= 200) 2 else 1
            }
            camAnnounced == 1 && dist <= 200 -> {
                speak("잠시 후 $name$limitText", queue = true)
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
        lastTrafficRefreshAt = System.currentTimeMillis()
        KakaoRouteDataRepository.routeCoordinates = thin(route.path)
        KakaoRouteDataRepository.routeCoordinatesUpdatedAt = System.currentTimeMillis()
        listener?.onRouteChanged(route)
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

    /** "최소 안내"가 켜져 있으면 회전 안내만 말하고, "더 빠른 경로로 안내합니다"(교통 반영 경로 변경) 멘트는 건너뛴다(시작·재탐색·도착 멘트는 그대로 말한다). */
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
