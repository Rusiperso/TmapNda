package com.tmap.nda

import android.content.Context
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
 */
class KakaoNaviActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKakaoNaviBinding
    private lateinit var naviView: KNNaviView
    private var kakaoGuidanceDelegate: KakaoGuidanceDelegate? = null
    private var exitHookAttached = false
    private var wasTmapMuted = false

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

        // 안내 중엔 티맵 음성을 낮춰서 카카오 음성과 겹치지 않게 함. 사용자가 저장한
        // isTmapMuted 값 자체는 안 건드리고, 여기서 나갈 때(onDestroy) 원복함. #문제시 원복
        val sharedPref = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
        wasTmapMuted = sharedPref.getBoolean("tmap_muted", false)
        try {
            TmapUISDK.setVolume(this, 0)
        } catch (e: Exception) {
            NavLogger.e(this, "티맵 볼륨 낮추기 예외: ${e.message}")
        }

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

        resolveCurrentPositionThenRequestRoute(destName, destLat, destLon)
    }

    // requestKakaoRoute()에서 검증된 순서 그대로 재사용: lastKnown GPS 캐시 없이도
    // KNSDK 자체 GPS/시스템 캐시 순으로 폴백하고, 그래도 없으면 1초 뒤 재시도. #문제시 원복
    private fun resolveCurrentPositionThenRequestRoute(destName: String, destLat: Double, destLon: Double) {
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
                if (!isFinishing) resolveCurrentPositionThenRequestRoute(destName, destLat, destLon)
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
                    finish()
                    return@runOnUiThread
                }
                NavLogger.d(this, "카카오 경로요청 성공, 안내 시작: $destName")
                val guidance = KNSDK.sharedGuidance()!!
                attachExitHook(naviView)
                val delegate = KakaoGuidanceDelegate(
                    this,
                    onGuideEnded = { finishGuidance() },
                    onGuideStarted = {},
                    isRouteGuideActive = { true }
                ).also { kakaoGuidanceDelegate = it }
                delegate.naviView = naviView
                guidance.guideStateDelegate = delegate
                guidance.routeGuideDelegate = delegate
                guidance.safetyGuideDelegate = delegate
                guidance.voiceGuideDelegate = delegate
                guidance.citsGuideDelegate = delegate
                guidance.locationGuideDelegate = delegate

                naviView.initWithGuidance(
                    guidance,
                    trip,
                    KNRoutePriority.KNRoutePriority_Recommand,
                    KNRouteAvoidOption.KNRouteAvoidOption_None.value
                )
            }
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
                    if (argsText.contains("exit", true) || argsText.contains("end", true) ||
                        method.name.contains("exit", true) || method.name.contains("finish", true)
                    ) {
                        runOnUiThread { finishGuidance() }
                    }
                    if (method.returnType == Boolean::class.javaPrimitiveType) true else null
                }
                try {
                    setter.invoke(naviView, proxy)
                } catch (e: Exception) {
                    NavLogger.e(this, "KNNaviView 델리게이트 후킹 실패(${setter.name}): ${e.message}")
                }
            }
            exitHookAttached = true
        } catch (e: Exception) {
            NavLogger.e(this, "KNNaviView exit hook 예외: ${e.message}")
        }
    }

    private fun finishGuidance() {
        try {
            KNSDK.sharedGuidance()?.stop()
        } catch (e: Exception) {
            NavLogger.e(this, "카카오 안내 중지 예외: ${e.message}")
        }
        if (!isFinishing) finish()
    }

    override fun onBackPressed() {
        finishGuidance()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (!wasTmapMuted) {
                TmapUISDK.setVolume(this, 100)
            }
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
