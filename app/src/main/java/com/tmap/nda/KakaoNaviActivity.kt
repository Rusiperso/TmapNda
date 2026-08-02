package com.tmap.nda

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.kakaomobility.knsdk.KNLanguageType
import com.kakaomobility.knsdk.KNRouteAvoidOption
import com.kakaomobility.knsdk.KNRoutePriority
import com.kakaomobility.knsdk.KNSDK
import com.kakaomobility.knsdk.common.objects.KNPOI
import com.kakaomobility.knsdk.ui.view.KNNaviView
import com.tmap.nda.databinding.ActivityKakaoNaviBinding
import com.tmapmobility.tmap.tmapsdk.ui.util.TmapUISDK
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * v1.0.83: TmapNda 안에서 flKakaoOverlay(FrameLayout)를 visibility/bringToFront/재적용
 * 타이머로 억지로 띄우던 기존 방식을 버리고, 카카오내비 화면 자체를 완전히 분리된
 * 별도 Activity로 만듦(CarrotNavi 앱 실제 동작 코드에서 확인된 패턴을 그대로 따름).
 *
 * 이렇게 하면 "SDK 상태(naviViewGuideState)는 OnRouteGuide로 정상인데 화면은 안 넘어감"
 * 증상의 근본 원인이었던 같은 윈도우 내 View z-order 경쟁/SurfaceView 재생성 타이밍
 * 문제가 원천적으로 사라짐 - startActivity()/finish()는 안드로이드 윈도우 매니저가
 * 직접 보장하는 화면전환이라 우리가 bringToFront()류로 손댈 필요가 없음.
 *
 * MapActivity → (검색 성공) → startActivity(이 Activity, dest_name/lat/lon 담아서)
 * 이 Activity: KNSDK 초기화(이미 됐으면 스킵) → 현재위치 확보 → makeTripWithStart →
 * naviView.initWithGuidance() → 안내 종료/도착/사용자 종료 버튼 시 finish()로 복귀.
 *
 * v1.0.90: KNSDK.sharedGpsManager()는 안드로이드 실시간 GPS를 자동으로 받는 게 아니라,
 * 앱이 LocationManager로 직접 구독해서 매번 gpsManager.onLocationChanged(location)을
 * 리플렉션으로 수동으로 찔러줘야 갱신됨(CarrotNavi 실제 코드에서 확인). 이걸 안 해줘서
 * 경로요청 시점 스냅샷 위치에 계속 멈춰있던 것 - 이 Activity도 LocationListener로
 * 실시간 GPS를 구독해서 매번 KNSDK GPS 매니저에 전달하도록 함.
 */
class KakaoNaviActivity : AppCompatActivity(), LocationListener {

    private lateinit var binding: ActivityKakaoNaviBinding
    private lateinit var naviView: KNNaviView
    private var kakaoGuidanceDelegate: KakaoGuidanceDelegate? = null
    private var exitHookAttached = false
    private var wasTmapMuted = false
    private var kakaoMuted = false
    private var locationManager: LocationManager? = null

    private fun applyTmapMute(muted: Boolean) {
        try {
            if (muted) {
                VolumeHelper.captureCurrentVolumePercent(this)
                TmapUISDK.setVolume(this, 0)
                KakaoSdkState.lastAppliedTmapVolume = 0
            }
            // MapActivity와 동일한 철학: 음소거 해제 상태에선 볼륨을 아예 안 건드림(사용자가
            // 하드웨어 버튼 등으로 맞춰둔 값을 그대로 둠). 명시적 해제 액션은
            // unmuteTmapVolume()에서 처리. #문제시 원복
            NavLogger.d(this, "[음소거] 티맵 볼륨 적용: muted=$muted")
        } catch (e: Exception) {
            NavLogger.e(this, "티맵 볼륨 적용 예외: ${e.message}")
        }
    }

    private fun unmuteTmapVolume() {
        try {
            TmapUISDK.setVolume(this, VolumeHelper.savedVolumePercent(this))
            KakaoSdkState.lastAppliedTmapVolume = VolumeHelper.savedVolumePercent(this)
        } catch (e: Exception) {
            NavLogger.e(this, "티맵 볼륨 복원 예외: ${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NavLogger.appContext = applicationContext
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val destName = intent.getStringExtra("dest_name")
        val destLat = intent.getDoubleExtra("dest_lat", Double.NaN)
        val destLon = intent.getDoubleExtra("dest_lon", Double.NaN)
        val nativeAppKey = intent.getStringExtra("kakao_native_app_key").orEmpty()

        if (destName == null || destLat.isNaN() || destLon.isNaN()) {
            NavLogger.e(this, "KakaoNaviActivity: 목적지 정보 누락됨")
            finish()
            return
        }

        // v1.0.97: 예전엔 카카오 화면에 들어오면 무조건 티맵 볼륨을 0으로 강제해서, 재억이
        // 원하는 "티맵 안내음량 버튼"이 있어도 의미가 없었음(항상 0으로 덮어써지니까).
        // 이제 MapActivity와 동일하게 사용자가 저장해둔 tmap_muted 값을 그대로 반영하고,
        // 아래 버튼으로 라이브 토글 가능하게 함. #문제시 원복
        val sharedPref = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
        wasTmapMuted = sharedPref.getBoolean("tmap_muted", false)
        applyTmapMute(wasTmapMuted)
        kakaoMuted = sharedPref.getBoolean("kakao_muted", false)

        if (KakaoSdkState.initialized) {
            setupContentAndStart(destName, destLat, destLon)
        } else {
            try {
                val dbPath = filesDir.absolutePath + "/knsdk"
                NavLogger.d(this, "KakaoNaviActivity: KNSDK install 시도: $dbPath")
                KNSDK.install(application, dbPath)
                KNSDK.initializeWithAppKey(
                    nativeAppKey,
                    "1.0",
                    "tmapnda_user",
                    "ko",
                    KNLanguageType.KNLanguageType_KOREAN
                ) { error ->
                    runOnUiThread {
                        if (error == null) {
                            NavLogger.d(this, "KakaoNaviActivity: KNSDK 초기화 성공")
                            KakaoSdkState.initialized = true
                            setupContentAndStart(destName, destLat, destLon)
                        } else {
                            NavLogger.e(this, "KakaoNaviActivity: KNSDK 초기화 실패: ${error.code} / ${error.msg}")
                            Toast.makeText(this, "카카오내비 초기화 실패: ${error.msg}", Toast.LENGTH_LONG).show()
                            finish()
                        }
                    }
                }
            } catch (e: Exception) {
                NavLogger.e(this, "KakaoNaviActivity: KNSDK install/init 예외: ${e.message}")
                Toast.makeText(this, "카카오내비 초기화 예외: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun setupContentAndStart(destName: String, destLat: Double, destLon: Double) {
        binding = ActivityKakaoNaviBinding.inflate(layoutInflater)
        setContentView(binding.root)
        naviView = binding.naviView
        binding.btnStopKakaoGuidance.setOnClickListener { finishGuidance() }

        try {
            KNSDK.handleWillEnterForeground()
            KNSDK.handleDidBecomeActive()
        } catch (e: Exception) {
            NavLogger.e(this, "KNSDK 라이프사이클 전달 예외: ${e.message}")
        }

        startRealtimeGpsForwarding()
        startMiniHudBinding()
        setupHudActionButtons()

        // CarrotNavi 실제 동작 코드에서 확인된 핵심 패턴: naviView를 목적지가 확정된
        // 시점에 initWithGuidance(trip=실제경로)로 처음 초기화하는 게 아니라,
        // Activity가 뜨자마자(목적지 정보가 아직 없어도) trip=null로 "경로 없는 idle map"
        // 상태로 먼저 initWithGuidance() 해버림. 그러면 실제 검색 결과(카카오 로컬 API
        // 네트워크 왕복 몇 초)가 돌아올 때쯤엔 naviView/서페이스가 이미 완전히 살아있는
        // 상태라 레이아웃/서페이스 타이밍 레이스가 원천적으로 안 생김. 실제 목적지가
        // 잡히면 initWithGuidance()를 또 부르는 게 아니라 guideNewDestinations()로
        // 이미 떠있는 세션에 목적지만 갈아끼움(이 방식이 정확히 CarrotNavi가 쓰는 방식).
        // #문제시 원복
        val guidance = KNSDK.sharedGuidance()
        if (guidance == null) {
            NavLogger.e(this, "setupContentAndStart: sharedGuidance() null")
            finish()
            return
        }
        hookSurfaceViewLifecycle(naviView)
        // v1.0.89: attachExitHook()이 naviView.setStateDelegate/setGuideStateDelegate/
        // setMapEventDelegate/setScaleDelegate 전부를 리플렉션으로 찾아 "로그만 찍고 아무
        // 실제 동작도 안 하는" 더미 Proxy로 덮어쓰고 있었음. CarrotNavi 실제 코드를 보면
        // naviView.stateDelegate = this@KakaoMapActivity 처럼 진짜 구현체를 등록해서 내부
        // UI 컴포넌트(속도판/방향안내/표지판 등)를 갱신하는 데 씀 - 우리 더미 Proxy가 이
        // 델리게이트 체인을 깨뜨려서 지도 타일은 뜨는데 안내 UI 컴포넌트들이 전부 숨김
        // 상태(vis=8)로 멈춰있던 것으로 추정됨. 지금은 전용 "안내 종료" 버튼이 이미 있어서
        // 리플렉션 exit-hook 자체가 필요 없음 - 완전히 제거. #문제시 원복
        // attachExitHook(naviView)
        val delegate = KakaoGuidanceDelegate(
            this,
            onGuideEnded = { finishGuidance() },
            onGuideStarted = {},
            isRouteGuideActive = { !kakaoMuted }
        ).also { kakaoGuidanceDelegate = it }
        delegate.naviView = naviView
        guidance.guideStateDelegate = delegate
        guidance.routeGuideDelegate = delegate
        guidance.safetyGuideDelegate = delegate
        guidance.voiceGuideDelegate = delegate
        guidance.citsGuideDelegate = delegate
        guidance.locationGuideDelegate = delegate

        NavLogger.d(
            this,
            "[진단] naviView 인터페이스 구현 확인: " +
                "LocationGuideDelegate=${naviView is com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_LocationGuideDelegate} " +
                "RouteGuideDelegate=${naviView is com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_RouteGuideDelegate} " +
                "GuideStateDelegate=${naviView is com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_GuideStateDelegate}"
        )

        NavLogger.d(this, "setupContentAndStart: initWithGuidance(trip=null) idle map 선초기화")
        naviView.initWithGuidance(
            guidance,
            null,
            KNRoutePriority.KNRoutePriority_Recommand,
            KNRouteAvoidOption.KNRouteAvoidOption_None.value
        )
        naviView.post { logNaviViewDiagnostics("idle map 초기화 직후") }

        resolveCurrentPositionThenRequestRoute(destName, destLat, destLon)
    }

    // requestKakaoRoute()에서 검증된 순서 그대로 재사용: lastKnown GPS 캐시 없이도
    // KNSDK 자체 GPS/시스템 캐시 순으로 폴백하고, 그래도 없으면 1초 뒤 재시도. #문제시 원복
    private fun resolveCurrentPositionThenRequestRoute(destName: String, destLat: Double, destLon: Double, finishOnFailure: Boolean = true) {
        var startPoi: KNPOI? = null
        val currentGps = KNSDK.sharedGpsManager()?.recentGpsData
        if (currentGps != null && currentGps.pos.x > 0 && currentGps.pos.y > 0) {
            startPoi = KNPOI("현 위치", currentGps.pos.x.toInt(), currentGps.pos.y.toInt(), "")
        } else {
            try {
                val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                val loc = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                if (loc != null) {
                    val katec = KNSDK.convertWGS84ToKATEC(loc.longitude, loc.latitude)
                    startPoi = KNPOI("현 위치", katec.x.toInt(), katec.y.toInt(), "")
                }
            } catch (e: SecurityException) {
                NavLogger.e(this, "위치 권한 없음: ${e.message}")
            }
        }

        if (startPoi == null) {
            Toast.makeText(this, "GPS 확인 중입니다...", Toast.LENGTH_SHORT).show()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!isFinishing) resolveCurrentPositionThenRequestRoute(destName, destLat, destLon, finishOnFailure)
            }, 1000)
            return
        }

        val katec = KNSDK.convertWGS84ToKATEC(destLon, destLat)
        val goalPoi = KNPOI(destName, katec.x.toInt(), katec.y.toInt(), "")

        KNSDK.makeTripWithStart(startPoi, goalPoi, null) { error, trip ->
            runOnUiThread {
                if (error != null || trip == null) {
                    NavLogger.e(this, "카카오 경로요청 실패: ${error?.msg ?: "알 수 없는 오류"}")
                    Toast.makeText(this, "경로 탐색 실패: ${error?.msg ?: "알 수 없는 오류"}", Toast.LENGTH_SHORT).show()
                    if (finishOnFailure) finish()
                    return@runOnUiThread
                }
                NavLogger.d(this, "카카오 경로요청 성공, 안내 시작: $destName")
                // naviView는 setupContentAndStart()에서 이미 initWithGuidance(trip=null)로
                // 초기화돼있는 상태(idle map) - 여기서 또 initWithGuidance()를 부르면 안 되고
                // guideNewDestinations()로 이미 떠있는 세션에 실제 목적지만 갈아끼움.
                // (CarrotNavi 실제 동작 코드에서 확인된 패턴) #문제시 원복
                //
                // v1.0.86: guideNewDestinations()가 내부적으로 성공해도 KNNaviView가
                // 화면을 다시 그리라는 신호를 못 받아 idle map 그대로 멈춰있을 수 있다는
                // 의심(재억 지적: GPS/위치 로그는 idle 상태에서도 계속 찍히므로 화면전환
                // 증거가 안 됨) - requestLayout()/invalidate()를 명시적으로 강제하고,
                // naviView의 실제 화면 상태(width/height/visibility/트립 식별자)를
                // 별도로 로그에 남겨서 "idle로 멈춘 건지 실제 경로가 붙은 건지"를
                // 로그만으로 구분할 수 있게 함. #문제시 원복
                naviView.guideNewDestinations(
                    trip,
                    KNRoutePriority.KNRoutePriority_Recommand,
                    KNRouteAvoidOption.KNRouteAvoidOption_None.value
                )
                naviView.requestLayout()
                naviView.invalidate()
                logNaviViewDiagnostics("guideNewDestinations 직후")
                naviView.postDelayed({
                    naviView.requestLayout()
                    naviView.invalidate()
                    logNaviViewDiagnostics("guideNewDestinations 300ms 후")
                }, 300)
                startNaviStateDiagnosticLoop()
            }
        }
    }

    // naviView가 idle map(경로 없음)에 멈춰있는지, 실제 trip이 붙은 상태인지를 로그만으로
    // 구분할 수 있도록 3초마다 상태를 남김. GPS 위치 로그는 idle 상태에서도 계속 찍히므로
    // 그것만으로는 화면전환 여부를 판단할 수 없다는 점(재억 지적)을 반영. #문제시 원복
    private val diagnosticHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var diagnosticLoopRunning = false
    private fun startNaviStateDiagnosticLoop() {
        if (diagnosticLoopRunning) return
        diagnosticLoopRunning = true
        val runnable = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) {
                    diagnosticLoopRunning = false
                    return
                }
                logNaviViewDiagnostics("주기 진단(3초)")
                // v1.0.93: 스크린샷 비교 결과 1분이 지나도 지도 화면이 1픽셀도 안 바뀜(카메라/
                // 마커가 GPS 갱신을 받아도 다시 안 그려지는 것으로 보임) - naviView가 SurfaceView
                // 기반 렌더러라 데이터만 갱신되고 실제 화면 갱신 신호(invalidate)가 안 걸리는
                // 것인지 확인하기 위해 3초마다 강제로 다시 그리기를 걸어봄. #문제시 원복
                if (::naviView.isInitialized) {
                    naviView.requestLayout()
                    naviView.invalidate()
                }
                diagnosticHandler.postDelayed(this, 3000)
            }
        }
        diagnosticHandler.postDelayed(runnable, 3000)
    }

    private fun logNaviViewDiagnostics(tag: String) {
        try {
            val curTrip = try {
                KNSDK.sharedGuidance()?.let { g ->
                    val m = g.javaClass.methods.firstOrNull { it.name.equals("getCurTrip", true) || it.name.equals("getTrip", true) }
                    m?.invoke(g)
                }
            } catch (e: Exception) { "조회실패(${e.message})" }
            val gps = try {
                KNSDK.sharedGpsManager()?.recentGpsData?.let { "pos=(${it.pos.x},${it.pos.y}) speed=${it.speed} angle=${it.angle}" }
            } catch (e: Exception) { "조회실패(${e.message})" }
            val childTree = try {
                dumpChildTree(naviView, 0)
            } catch (e: Exception) { "덤프실패(${e.message})" }
            NavLogger.d(
                this,
                "[naviView 진단:$tag] width=${naviView.width} height=${naviView.height} " +
                    "visibility=${naviView.visibility} isAttachedToWindow=${naviView.isAttachedToWindow} " +
                    "isShown=${naviView.isShown} curTrip=$curTrip gps=$gps childTree=$childTree"
            )
        } catch (e: Exception) {
            NavLogger.e(this, "logNaviViewDiagnostics 예외($tag): ${e.message}")
        }
    }

    // naviView 내부에 실제로 어떤 자식 뷰들이 붙어있는지(경로선/방향안내 바 등 가이드 UI
    // 구성요소가 실제로 attach됐는지) 확인하기 위한 트리 덤프. #문제시 원복
    private fun dumpChildTree(view: View, depth: Int): String {
        if (depth > 4) return ""
        val self = "${view.javaClass.simpleName}(${view.width}x${view.height},vis=${view.visibility})"
        if (view !is android.view.ViewGroup || view.childCount == 0) return self
        val children = (0 until view.childCount).joinToString(",") { dumpChildTree(view.getChildAt(it), depth + 1) }
        return "$self[$children]"
    }

    // 서페이스가 실제로 surfaceCreated/surfaceChanged까지 도달하는지 순수 진단용으로 로그만 남김.
    // (이전 MapActivity 오버레이 방식 디버깅에서 이 로그가 근본 원인 진단에 핵심적이었음) #문제시 원복
    private fun hookSurfaceViewLifecycle(root: View) {
        try {
            if (root is android.view.SurfaceView) {
                NavLogger.d(this, "hookSurfaceViewLifecycle: SurfaceView 발견 (${root.javaClass.name}), holder.isCreating=${root.holder?.surface?.isValid}")
                root.holder?.addCallback(object : android.view.SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                        NavLogger.d(this@KakaoNaviActivity, "[SurfaceHolder ${root.javaClass.simpleName}] surfaceCreated")
                    }
                    override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {
                        NavLogger.d(this@KakaoNaviActivity, "[SurfaceHolder ${root.javaClass.simpleName}] surfaceChanged format=$format ${width}x$height")
                    }
                    override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                        NavLogger.d(this@KakaoNaviActivity, "[SurfaceHolder ${root.javaClass.simpleName}] surfaceDestroyed")
                    }
                })
            }
            if (root is android.view.ViewGroup) {
                for (i in 0 until root.childCount) {
                    hookSurfaceViewLifecycle(root.getChildAt(i))
                }
            }
        } catch (e: Exception) {
            NavLogger.e(this, "hookSurfaceViewLifecycle 예외: ${e.message}")
        }
    }

    // KNNaviView 내장 "안내종료" 버튼은 우리 finishGuidance()와 안 이어져 있어서, 리플렉션으로
    // 델리게이트 세터를 찾아 프록시를 걸고 exit/end 계열 콜백이 오면 finish() 처리. #문제시 원복
    private fun attachExitHook(naviView: KNNaviView) {
        if (exitHookAttached) return
        try {
            val setterCandidates = naviView.javaClass.methods.filter {
                it.name.contains("Delegate", ignoreCase = true) &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].isInterface
            }
            for (setter in setterCandidates) {
                val ifaceClass = setter.parameterTypes[0]
                val proxy = java.lang.reflect.Proxy.newProxyInstance(
                    ifaceClass.classLoader,
                    arrayOf(ifaceClass)
                ) { _, method, args ->
                    val argsText = args?.joinToString { it?.toString() ?: "null" } ?: ""
                    NavLogger.d(this, "[KNNaviView ${ifaceClass.simpleName}] ${method.name}($argsText)")
                    if (argsText.contains("exit", true) || argsText.contains("end", true) ||
                        method.name.contains("exit", true) || method.name.contains("finish", true)
                    ) {
                        runOnUiThread { finishGuidance() }
                    }
                    if (method.returnType == Boolean::class.javaPrimitiveType) true else null
                }
                try {
                    setter.invoke(naviView, proxy)
                    NavLogger.d(this, "KNNaviView 델리게이트 후킹 성공: ${setter.name}(${ifaceClass.simpleName})")
                } catch (e: Exception) {
                    NavLogger.e(this, "KNNaviView 델리게이트 후킹 실패(${setter.name}): ${e.message}")
                }
            }
            exitHookAttached = true
        } catch (e: Exception) {
            NavLogger.e(this, "KNNaviView exit hook 예외: ${e.message}")
        }
    }

    // v1.0.94: 별도 Activity라 MapActivity 좌측 HUD가 구조적으로 안 비치는 문제를,
    // MapActivity와 동일한 앱 전역 싱글턴(OpenpilotStateRepository/SdiDataRepository)을
    // 그대로 관찰해서 자체 미니 HUD로 복제하는 방식으로 해결. #문제시 원복
    private val hudPollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private fun startMiniHudBinding() {
        OpenpilotStateRepository.state.observe(this) { state ->
            if (state.active) {
                binding.tvActiveStatus?.text = "OP ON"
                binding.tvActiveStatus?.setTextColor(android.graphics.Color.parseColor("#4FC3F7"))
            } else {
                binding.tvActiveStatus?.text = "OP OFF"
                binding.tvActiveStatus?.setTextColor(android.graphics.Color.parseColor("#555555"))
            }
            val connected = state.ip.isNotEmpty() && state.ip != "-"
            if (connected) {
                binding.tvConnectionStatus?.text = "연결됨"
                binding.tvConnectionStatus?.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
                binding.vConnectionDot?.setBackgroundResource(R.drawable.shape_circle_green)
            } else {
                binding.tvConnectionStatus?.text = "연결 대기"
                binding.tvConnectionStatus?.setTextColor(android.graphics.Color.parseColor("#555555"))
                binding.vConnectionDot?.setBackgroundResource(R.drawable.shape_circle_gray)
            }
            binding.tvCarrotVersion?.text = state.carrot2
            binding.tvCarrotIp?.text = if (state.ip.isNotEmpty() && state.ip != "-") "IP: ${state.ip}" else "IP: -"
            when (state.trafficState) {
                1 -> binding.vTrafficLight?.setBackgroundResource(R.drawable.shape_circle_red)
                2 -> binding.vTrafficLight?.setBackgroundResource(R.drawable.shape_circle_green)
                else -> binding.vTrafficLight?.setBackgroundResource(R.drawable.shape_circle_gray)
            }
        }

        // MapActivity의 extractAndDisplaySdiInfo()가 SdiDataRepository에 실제 값을 채워주도록
        // 고쳐놨음(예전엔 아무데서도 안 채워서 "300m 고정" 문제가 있었음) - 여기선 그 값을
        // 그대로 폴링해서 MapActivity HUD와 동일한 규칙으로 표시. #문제시 원복
        val sdiRunnable = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                binding.tvRoadSpeedLimit?.text = if (SdiDataRepository.roadLimitSpeed >= 30) SdiDataRepository.roadLimitSpeed.toString() else "--"

                if (SdiDataRepository.isBlockSection && SdiDataRepository.sdiBlockDist > 0) {
                    binding.llBlockInfo?.visibility = View.VISIBLE
                    binding.tvBlockAvgSpeed?.text = "평균: ${SdiDataRepository.sdiBlockSpeed}km/h"
                    binding.tvBlockDist?.text = "거리: ${SdiDataRepository.sdiBlockDist}m"
                    val bt = SdiDataRepository.sdiBlockTime
                    binding.tvBlockTime?.text = String.format("시간: %d:%02d", bt / 60, bt % 60)
                } else {
                    binding.llBlockInfo?.visibility = View.GONE
                }

                val sdiType = SdiDataRepository.sdiType
                val sdiSpeedLimit = SdiDataRepository.sdiSpeedLimit
                val sdiDist = SdiDataRepository.sdiDistance
                if (sdiType > 0 || (sdiSpeedLimit > 0 && sdiDist > 0)) {
                    binding.tvSdiSpeedLimit?.text = if (sdiSpeedLimit > 0) "${sdiSpeedLimit}km" else "-"
                    binding.tvSdiDist?.text = if (sdiDist >= 1000) String.format("%.1fkm", sdiDist / 1000.0) else "${sdiDist}m"
                    binding.tvSdiDescr?.text = when (sdiType) {
                        1 -> "과속 단속"
                        2 -> "구간단속 시작"
                        3 -> "구간단속 종료"
                        4 -> "구간단속 중"
                        7 -> "이동식 단속"
                        22 -> "과속방지턱"
                        33 -> "어린이보호구역"
                        else -> if (sdiSpeedLimit > 0) "단속 카메라" else "주의 구간"
                    }
                } else {
                    binding.tvSdiSpeedLimit?.text = ""
                    binding.tvSdiDist?.text = "--"
                    binding.tvSdiDescr?.text = "--"
                }
                hudPollHandler.postDelayed(this, 1000)
                renderLaneSignalBar(this@KakaoNaviActivity, binding.llLaneSignalBar, binding.llLaneBoxes, binding.tvTrafficLightCountdown)
            }
        }
        hudPollHandler.postDelayed(sdiRunnable, 1000)

        try {
            val vn = packageManager.getPackageInfo(packageName, 0).versionName
            binding.tvAppVersion?.text = "v$vn"
        } catch (e: Exception) { /* 무시 */ }
        binding.btnGpsStatus?.text = "GPS 확인 중"
    }

    // v1.0.97: MapActivity와 동일한 스타일의 티맵음소거/카카오음소거/검색/로그전송 버튼과
    // 최근 목적지 패널(5개+더보기)을 KakaoNaviActivity에도 추가. 검색/더보기는 자체 검색
    // UI를 새로 만들지 않고, MapActivity가 finish() 이후 백스택에서 그대로 재개될 때
    // PendingMapAction 신호로 처리하도록 함(중복 구현 회피). #문제시 원복
    private fun updateMuteButtonStyle() {
        binding.btnKakaoMuteToggle?.setImageResource(
            if (kakaoMuted) android.R.drawable.ic_lock_silent_mode else android.R.drawable.ic_lock_silent_mode_off
        )
        binding.btnKakaoMuteToggle?.setBackgroundResource(
            if (kakaoMuted) R.drawable.shape_circle_gray else R.drawable.shape_circle_green
        )
    }

    private fun setupHudActionButtons() {
        updateMuteButtonStyle()

        binding.btnKakaoMuteToggle?.setOnClickListener {
            kakaoMuted = !kakaoMuted
            getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).edit()
                .putBoolean("kakao_muted", kakaoMuted).apply()
            updateMuteButtonStyle()
            Toast.makeText(this, if (kakaoMuted) "카카오 안내음성 음소거" else "카카오 안내음성 켜짐", Toast.LENGTH_SHORT).show()
        }

        binding.btnOpenSearch?.setOnClickListener {
            showInPlaceSearchDialog()
        }

        binding.btnShareLog?.setOnClickListener {
            val result = NavLogger.buildShareIntent(this)
            if (result == null) {
                Toast.makeText(this, "저장된 로그가 없어.", Toast.LENGTH_SHORT).show()
            } else {
                val (shareIntent, paths) = result
                startActivity(android.content.Intent.createChooser(shareIntent, "로그 공유 (${paths.size}개 파일)"))
            }
        }

        renderRecentDestinationsPanel()
    }

    // MapActivity가 쓰는 것과 동일한 SharedPreferences 키("search_history_json")를 그대로
    // 읽어서 최근 목적지 최대 5개를 직접 표시. 나머지는 "+더보기"로 MapActivity의 전체
    // 이력 다이얼로그를 열도록 신호만 넘김. #문제시 원복
    private fun renderRecentDestinationsPanel() {
        val sharedPref = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
        val raw = sharedPref.getString("search_history_json", "[]") ?: "[]"
        val history = try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }

        if (history.isEmpty()) {
            binding.llRecentSearchPanel?.visibility = View.GONE
            return
        }
        binding.llRecentSearchPanel?.visibility = View.VISIBLE
        val rowsContainer = binding.llRecentSearchRows ?: return
        rowsContainer.removeAllViews()
        history.take(5).forEach { destText ->
            val tv = android.widget.TextView(this).apply {
                text = destText
                setTextColor(android.graphics.Color.parseColor("#DDDDDD"))
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(24, 20, 24, 20)
                setOnClickListener {
                    performInPlaceSearch(destText)
                }
            }
            rowsContainer.addView(tv)
        }
        binding.btnMoreHistory?.setOnClickListener {
            PendingMapAction.openFullHistoryDialog = true
            finishGuidance()
        }
    }

    // v1.6: 검색 버튼 누르면 화면이 티맵으로 나갔다 다시 들어오던 문제 - 굳이 MapActivity로
    // 안 돌아가고 이 화면 안에서 그대로 재검색하도록 함(Kakao 로컬 검색 API를 MapActivity와
    // 동일한 방식으로 여기서도 직접 호출). #문제시 원복
    private val searchHttpClient by lazy { OkHttpClient() }

    private fun showInPlaceSearchDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "목적지를 입력하세요 (예: 서울역)"
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.parseColor("#888888"))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("목적지 재검색")
            .setView(input)
            .setPositiveButton("검색") { _, _ ->
                val q = input.text.toString().trim()
                if (q.isNotEmpty()) performInPlaceSearch(q)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun performInPlaceSearch(query: String) {
        val restKey = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
            .getString("kakao_rest_api_key", "") ?: ""
        if (restKey.isBlank()) {
            Toast.makeText(this, "카카오 REST API 키가 없어 - 티맵 화면에서 검색을 한 번 먼저 설정해줘.", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "검색 중: $query", Toast.LENGTH_SHORT).show()
        val url = "https://dapi.kakao.com/v2/local/search/keyword.json?query=" +
            java.net.URLEncoder.encode(query, "UTF-8")
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "KakaoAK $restKey")
            .build()
        searchHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                NavLogger.e(this@KakaoNaviActivity, "인라인 재검색 실패: ${e.message}")
                runOnUiThread { Toast.makeText(this@KakaoNaviActivity, "검색 실패: ${e.message}", Toast.LENGTH_SHORT).show() }
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        runOnUiThread { Toast.makeText(this@KakaoNaviActivity, "검색 실패(${it.code})", Toast.LENGTH_SHORT).show() }
                        return@use
                    }
                    val json = JSONObject(it.body?.string() ?: "{}")
                    val documents = json.optJSONArray("documents")
                    if (documents == null || documents.length() == 0) {
                        runOnUiThread { Toast.makeText(this@KakaoNaviActivity, "검색 결과 없음: $query", Toast.LENGTH_SHORT).show() }
                        return@use
                    }
                    val first = documents.getJSONObject(0)
                    val placeName = first.optString("place_name", query)
                    val lon = first.optDouble("x")
                    val lat = first.optDouble("y")
                    NavLogger.d(this@KakaoNaviActivity, "인라인 재검색 결과: $placeName lat=$lat lon=$lon")
                    getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).apply {
                        val arr = try { org.json.JSONArray(getString("search_history_json", "[]") ?: "[]") } catch (e: Exception) { org.json.JSONArray() }
                        arr.put(0, query)
                        edit().putString("search_history_json", arr.toString()).apply()
                    }
                    runOnUiThread {
                        KakaoRouteDataRepository.reset()
                        resolveCurrentPositionThenRequestRoute(placeName, lat, lon, finishOnFailure = false)
                        renderRecentDestinationsPanel()
                    }
                }
            }
        })
    }

    private fun finishGuidance() {
        KakaoRouteDataRepository.reset()
        try {
            KNSDK.sharedGuidance()?.stop()
        } catch (e: Exception) {
            NavLogger.e(this, "카카오 안내 중지 예외: ${e.message}")
        }
        if (!isFinishing) finish()
    }

    override fun onStart() {
        super.onStart()
        NavLogger.d(this, "[lifecycle] onStart")
    }

    override fun onResume() {
        super.onResume()
        NavLogger.d(this, "[lifecycle] onResume")
    }

    override fun onPause() {
        super.onPause()
        NavLogger.d(this, "[lifecycle] onPause")
    }

    override fun onStop() {
        super.onStop()
        NavLogger.d(this, "[lifecycle] onStop")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        NavLogger.d(this, "[lifecycle] onWindowFocusChanged hasFocus=$hasFocus")
        if (::naviView.isInitialized) logNaviViewDiagnostics("onWindowFocusChanged(hasFocus=$hasFocus)")
    }

    override fun onBackPressed() {
        finishGuidance()
    }

    // v1.0.90: KNSDK.sharedGpsManager()는 안드로이드 실시간 GPS를 자동으로 받지 않음 -
    // 앱이 LocationManager로 직접 구독해서 매번 gpsManager.onLocationChanged(location)을
    // 리플렉션으로 찔러줘야 갱신됨(CarrotNavi 실제 코드에서 확인된 패턴). #문제시 원복
    // v1.0.91: NETWORK_PROVIDER(기지국 기반) 위치는 정확도가 낮고 캐시된 값이 그대로
    // 반복돼서(예: 매초 완전히 동일한 좌표) GPS_PROVIDER의 정확한 실시간 값과 번갈아
    // KNSDK로 들어가는 바람에 위치가 오락가락했음(재억 - "평택인데 용인으로 잡힘").
    // GPS_PROVIDER만 반영하고, 정확도가 너무 나쁜 픽스(accuracy > 50m)는 무시함. #문제시 원복
    private var lastOverSpeedWarningTime = 0L
    private fun checkOverSpeedWarning(speedKph: Int) {
        val pref = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
        if (!pref.getBoolean("over_speed_warning_enabled", false)) return
        val limit = SdiDataRepository.roadLimitSpeed
        if (limit < 30 || speedKph <= 0) return
        val now = System.currentTimeMillis()
        if (speedKph > limit * 1.1 && now - lastOverSpeedWarningTime > 8000L) {
            lastOverSpeedWarningTime = now
            try {
                val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 100)
                tone.startTone(android.media.ToneGenerator.TONE_CDMA_PIP, 400)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tone.release() }, 500)
            } catch (e: Exception) {
                NavLogger.e(this, "속도경고음 재생 예외: ${e.message}")
            }
        }
    }

    private fun startRealtimeGpsForwarding() {
        try {
            locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, this)
            NavLogger.d(this, "[GPS] LocationManager GPS_PROVIDER 실시간 구독 시작(KNSDK GPS 매니저로 전달용)")
        } catch (e: SecurityException) {
            NavLogger.e(this, "[GPS] 위치 권한 없음: ${e.message}")
        } catch (e: Exception) {
            NavLogger.e(this, "[GPS] LocationManager 구독 시작 예외: ${e.message}")
        }
    }

    override fun onLocationChanged(location: Location) {
        try {
            if (location.provider != LocationManager.GPS_PROVIDER) {
                NavLogger.d(this, "[GPS] provider=${location.provider} 무시(GPS_PROVIDER만 사용)")
                return
            }
            if (location.hasAccuracy() && location.accuracy > 50f) {
                NavLogger.d(this, "[GPS] 정확도 낮아 무시: accuracy=${location.accuracy}m")
                return
            }
            val speedKph = (location.speed * 3.6).toInt()
            binding.tvCurrentSpeed?.text = speedKph.toString()
            checkOverSpeedWarning(speedKph)
            binding.btnGpsStatus?.text = "GOOD (정확도 ${location.accuracy.toInt()}m)"
            binding.btnGpsStatus?.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
            val gpsManager = KNSDK.sharedGpsManager()
            if (gpsManager != null) {
                val m = gpsManager.javaClass.getMethod("onLocationChanged", Location::class.java)
                m.invoke(gpsManager, location)
                NavLogger.d(
                    this,
                    "[GPS] KNSDK로 전달됨: lat=${location.latitude} lon=${location.longitude} " +
                        "speed=${location.speed} bearing=${location.bearing} accuracy=${location.accuracy}"
                )
            }
        } catch (e: Exception) {
            NavLogger.e(this, "[GPS] KNSDK GPS 매니저 전달 예외: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hudPollHandler.removeCallbacksAndMessages(null)
        try {
            locationManager?.removeUpdates(this)
        } catch (e: Exception) {
            NavLogger.e(this, "[GPS] LocationManager 구독 해제 예외: ${e.message}")
        }
        try {
            val sharedPref = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
            val muted = sharedPref.getBoolean("tmap_muted", false)
            val vol = if (muted) 0 else VolumeHelper.savedVolumePercent(this)
            TmapUISDK.setVolume(this, vol)
            KakaoSdkState.lastAppliedTmapVolume = vol
            NavLogger.d(this, "[음소거] KakaoNaviActivity 종료 - 티맵 볼륨 복원(muted=$muted, vol=$vol)")
        } catch (e: Exception) {
            NavLogger.e(this, "티맵 볼륨 복원 예외: ${e.message}")
        }
        try {
            KNSDK.sharedGuidance()?.apply {
                guideStateDelegate = null
                routeGuideDelegate = null
                safetyGuideDelegate = null
                voiceGuideDelegate = null
                citsGuideDelegate = null
                locationGuideDelegate = null
            }
        } catch (e: Exception) {
            // KNSDK 상태에 따라 델리게이트 해제가 실패해도 앱 동작엔 영향 없음.
        }
    }
}
