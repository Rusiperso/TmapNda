package com.tmap.nda

import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.car.app.notification.CarAppExtender
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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
    private val originalTopMargins = mutableMapOf<Int, Int>()

    private fun applyTopPanelExpansion(view: View?, expandedHeight: Int) {
        if (view == null) return
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val originalMargin = originalTopMargins.getOrPut(view.id) { params.topMargin }
        val targetMargin = originalMargin + expandedHeight
        if (params.topMargin == targetMargin) return

        params.topMargin = targetMargin
        view.layoutParams = params
    }

    // v4.10: 지도(naviView)는 바가 baseline 높이일 때도 마진이 0이면 카카오 자체 UI
    // 요소(도로번호/속도 표지 등)가 바 바로 밑에 딱 붙어서 가려짐(사용자 지적: "66
    // 서울톨골 표지가 살짝 짤린다"). applyTopPanelExpansion은 baseline보다 "더 커진
    // 만큼"만 밀어내서 baseline 상태에서 마진이 0이 되는 게 원인 - 이 함수는 바의
    // 실제 전체 높이만큼 정확히 밀어내서 gap도 안 남고 겹침도 안 생기게 함. #문제시 원복
    private fun applyExactTopOffset(view: View?, panelHeight: Int) {
        if (view == null) return
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (params.topMargin == panelHeight) return
        params.topMargin = panelHeight
        view.layoutParams = params
    }

    /** 상단 HUD 자동 확장 시 카카오 지도·플로팅 UI도 같은 만큼 아래로 이동한다. */
    private fun installTopPanelAutoOffset() {
        binding.llLeftHudPanel.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val panelHeight = bottom - top
            val baseHeight = binding.llTopBarRow.minimumHeight
            val expandedHeight = (panelHeight - baseHeight).coerceAtLeast(0)

            applyExactTopOffset(binding.naviView, panelHeight)
            applyTopPanelExpansion(binding.svSecondaryPanel, expandedHeight)
        }
    }

    // v1.7: 검색 버튼 짧게=음성, 길게=텍스트 - Tmap 화면과 동일하게 맞춤(사용자 지적 1번). #문제시 원복
    private val voiceSearchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val spokenText = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
            if (!spokenText.isNullOrEmpty()) {
                NavLogger.d(this, "음성검색 결과: $spokenText")
                performInPlaceSearch(spokenText)
            } else {
                Toast.makeText(this, "음성 인식 결과가 없습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "목적지를 말씀하세요")
        }
        try {
            voiceSearchLauncher.launch(intent)
        } catch (e: Exception) {
            NavLogger.e(this, "음성인식 실행 실패: ${e.message}")
            Toast.makeText(this, "이 기기에서 음성 인식을 사용할 수 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

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
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val destName = intent.getStringExtra("dest_name")
        val destLat = intent.getDoubleExtra("dest_lat", Double.NaN)
        val destLon = intent.getDoubleExtra("dest_lon", Double.NaN)
        val nativeAppKey = intent.getStringExtra("kakao_native_app_key").orEmpty()

        if (destName == null || destLat.isNaN() || destLon.isNaN()) {
            NavLogger.e(this, "KakaoNaviActivity: 목적지 정보 누락됨")
            finish()
            return
        }

        // v1.0.97: 예전엔 카카오 화면에 들어오면 무조건 티맵 볼륨을 0으로 강제해서, 사용자가
        // 원하는 "티맵 안내음량 버튼"이 있어도 의미가 없었음(항상 0으로 덮어써지니까).
        // 이제 MapActivity와 동일하게 사용자가 저장해둔 tmap_muted 값을 그대로 반영하고,
        // 아래 버튼으로 라이브 토글 가능하게 함. #문제시 원복
        val sharedPref = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
        wasTmapMuted = sharedPref.getBoolean("tmap_muted", false)
        applyTmapMute(wasTmapMuted)
        kakaoMuted = sharedPref.getBoolean("kakao_muted", false)

        // v3.13: 카카오 길안내 시작할 때마다 시스템 볼륨이 100%로 리셋되던 문제 - 마지막에
        // 저장해둔 볼륨 퍼센트로 강제로 다시 맞춤 (사용자: "50, 60으로 조정해도 다음 안내
        // 할 때 또 100프로"). #문제시 원복
        VolumeHelper.applySavedSystemVolume(this)

      KakaoSdkState.ensureInitialized(
    application,
    nativeAppKey
) { success, message ->
    if (isFinishing || isDestroyed) {
        return@ensureInitialized
    }

    if (success) {
        NavLogger.d(
            this,
            "KakaoNaviActivity: KNSDK 초기화 확인 완료"
        )

        try {
            KNSDK.handleWillEnterForeground()
            KNSDK.handleDidBecomeActive()
        } catch (e: Exception) {
            NavLogger.e(
                this,
                "KakaoNaviActivity: KNSDK 활성화 신호 전달 실패: ${e.message}"
            )
        }

        setupContentAndStart(
            destName,
            destLat,
            destLon
        )
    } else {
        val errorMessage = message ?: "알 수 없는 오류"

        NavLogger.e(
            this,
            "KakaoNaviActivity: KNSDK 초기화 실패: $errorMessage"
        )

        Toast.makeText(
            this,
            "카카오내비 초기화 실패: $errorMessage",
            Toast.LENGTH_LONG
        ).show()

        finish()
    }
}
    }

    private fun setupContentAndStart(destName: String, destLat: Double, destLon: Double) {
        binding = ActivityKakaoNaviBinding.inflate(layoutInflater)
        setContentView(binding.root)
        naviView = binding.naviView

        // v3.9: Tmap 화면과 동일하게 상단바 드래그 편집 기능 연결 (사용자: "기본 UI는
        // 티맵/카카오맵 차등을 주지 말고 동일하게 적용해야돼") - PanelDragHelper 공용
        // 코드라 편집모드 상태(PanelDragHelper.isEditMode)도 두 화면이 공유함. #문제시 원복
        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        binding.llLeftHudPanel?.let { panel ->
            PanelDragHelper.makeDraggable(this, panel, "llLeftHudPanel", isLandscape, emptyList())
            panel.post {
                PanelDragHelper.restorePosition(this, panel, "llLeftHudPanel", isLandscape, emptyList())
            }
        }

        // v3.3: 이 HudScale.install()이 llLeftHudPanel(이제 상단 가로바)을 예전
        // "좌측 세로 패널" 기준 폭으로 강제 리사이즈하고 있었음 - MapActivity에서는
        // v2.6에서 이미 비활성화했는데 KakaoNaviActivity에서는 빼먹어서, 카카오 길안내
        // 화면만 계속 구버전처럼 왼쪽 좁은 패널로 보였던 진짜 원인이었음
        // (사용자 지적: "패널창이 위가 아니라 왼쪽에 있는데??"). #문제시 원복
        // HudScale.install(
        //     binding.root,
        //     binding.llLeftHudPanel,
        //     listOf(binding.naviView, binding.llLaneSignalBar)
        // )

        // 차량 내비의 시스템 바/노치/커브드 가장자리 안전영역을 SDK 지도와 HUD 모두에 반영.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val safeArea = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.mandatorySystemGestures()
            )
            view.setPadding(safeArea.left, safeArea.top, safeArea.right, safeArea.bottom)
            // v3.0: Tmap 화면과 동일하게 보조패널 스크롤 하단에도 안전여백 추가 (사용자 지적 5번)
            binding.svSecondaryPanel?.let { panel ->
                panel.clipToPadding = false
                panel.setPadding(panel.paddingLeft, panel.paddingTop, panel.paddingRight, safeArea.bottom)
            }
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
        installTopPanelAutoOffset()

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

        ensureNavNotificationChannel()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 8420
            )
        }

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
        // v1.0.95: KNNaviView 내장 설정 팝업의 "안내종료" 버튼이 눌러도 반응이 없다는
        // 제보(재억) - v1.0.89 델리게이트 하이재킹(attachExitHook, 바로 아래 주석)이 지도
        // 렌더링을 통째로 멈추게 했던 전례가 있어서, 이번엔 델리게이트는 절대 안 건드리고
        // 화면에 "안내종료" 문구로 그려지는 View 자체를 찾아 우리 finishGuidance()를 클릭
        // 리스너로 얹기만 함. SDK 팝업은 필요할 때(사용자가 설정 아이콘을 눌렀을 때)에만
        // 새로 그려지므로, naviView 루트에 GlobalLayoutListener를 걸어 레이아웃이 바뀔
        // 때마다(팝업이 뜰 때마다) 가볍게 재스캔. 이미 훅 붙인 View는 태그로 표시해서
        // 중복 스캔해도 리스너를 다시 걸지 않게 함. #문제시 원복
        attachNativeExitButtonHook(naviView)
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

        // v4.16: [볼륨API스캔]으로도 확인됐지만, 카카오모빌리티 공식 문서
        // (사용자 맞춤 설정하기)에 명시된 공개 API였음 - KNNaviView.sndVolume(Float,
        // 0.0~1.0, 기본값 1f)이 내비게이션 음성 안내 음량을 직접 조정하는 진짜 방법.
        // 그동안 시스템 STREAM_MUSIC을 아무리 만져도 안 먹혔던 이유가 이거였음 - 카카오
        // SDK가 시스템 볼륨과 무관하게 내부적으로 항상 1.0(100%)로 재생하고 있었던 것.
        // 저장된 볼륨%(VolumeHelper)를 0.0~1.0으로 변환해서 실제로 반영.
        // PR#9 병합 과정에서 이 함수 전체가 실수로 같이 삭제됐었음 - 복원. #문제시 원복
        applyKakaoSdkVolume()
        naviView.post { logNaviViewDiagnostics("idle map 초기화 직후") }

        resolveCurrentPositionThenRequestRoute(destName, destLat, destLon)
    }

    // v4.16: naviView.sndVolume은 float(0.0~1.0)라 VolumeHelper의 %(0~100) 값과 변환이
    // 필요함. 이 함수를 초기화 시점뿐 아니라 실시간 볼륨 캡처(volumeChangeReceiver)에서도
    // 같이 호출해서, 하드웨어 볼륨버튼으로 조절할 때마다 카카오 SDK 자체 볼륨도 같이
    // 실시간으로 맞춰지게 함. 공식 문서 기준 이름은 "sndVolume"인데, 실제 설치된 SDK
    // 버전(1.12.8-hotfix02)에서 정확히 이 이름이 맞는지 확인이 안 된 상태라, 직접 프로퍼티
    // 접근(컴파일 타임 바인딩) 대신 리플렉션으로 안전하게 시도 - 이름이 다르면 컴파일이
    // 깨지는 대신 로그만 남기고 조용히 스킵됨. #문제시 원복
    private var sndVolumeSetterField: java.lang.reflect.Field? = null
    private var sndVolumeSetterMethod: java.lang.reflect.Method? = null
    private var sndVolumeLookupFailed = false
    private fun applyKakaoSdkVolume() {
        try {
            val percent = VolumeHelper.savedVolumePercent(this).coerceIn(0, 100)
            val fraction = percent / 100f

            if (!sndVolumeLookupFailed && sndVolumeSetterMethod == null && sndVolumeSetterField == null) {
                // Kotlin의 "var sndVolume: Float"는 바이트코드상 setSndVolume(float) 메서드로 컴파일됨
                try {
                    sndVolumeSetterMethod = naviView.javaClass.getMethod("setSndVolume", Float::class.javaPrimitiveType)
                } catch (e: NoSuchMethodException) {
                    try {
                        sndVolumeSetterField = naviView.javaClass.getField("sndVolume")
                    } catch (e2: NoSuchFieldException) {
                        sndVolumeLookupFailed = true
                        NavLogger.e(this, "[카카오SDK볼륨] setSndVolume/sndVolume 둘 다 못 찾음 - SDK 버전에서 이름이 다를 수 있음")
                    }
                }
            }
            when {
                sndVolumeSetterMethod != null -> {
                    sndVolumeSetterMethod!!.invoke(naviView, fraction)
                    NavLogger.d(this, "[카카오SDK볼륨] setSndVolume($fraction) 호출됨 (저장된 ${percent}%)")
                }
                sndVolumeSetterField != null -> {
                    sndVolumeSetterField!!.setFloat(naviView, fraction)
                    NavLogger.d(this, "[카카오SDK볼륨] sndVolume 필드에 $fraction 직접 대입 (저장된 ${percent}%)")
                }
            }
        } catch (e: Exception) {
            NavLogger.e(this, "[카카오SDK볼륨] 적용 예외: ${e.message}")
        }
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
                // 의심(사용자 지적: GPS/위치 로그는 idle 상태에서도 계속 찍히므로 화면전환
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
                // 카카오 SDK가 guideNewDestinations()로 새 길안내를 시작할 때 내부 음량을
                // 자체적으로 기본값(100%)으로 되돌리는 것으로 보임(사용자: "안내 종료하고
                // 다시 안내하면 조절해둔 음량이 아니라 임의로 조절됨") - initWithGuidance()
                // 시점 1회만 적용하던 걸, 새 길안내 시작 직후에도 저장된 값으로 한 번 더
                // 덮어써서 유지되게 함. #문제시 원복
                applyKakaoSdkVolume()
                logNaviViewDiagnostics("guideNewDestinations 직후")
                naviView.postDelayed({
                    naviView.requestLayout()
                    naviView.invalidate()
                    // 300ms 뒤에도 한 번 더 - SDK가 route 진입 애니메이션/초기화 과정에서
                    // 음량을 뒤늦게 재설정하는 케이스까지 커버. #문제시 원복
                    applyKakaoSdkVolume()
                    logNaviViewDiagnostics("guideNewDestinations 300ms 후")
                }, 300)
                startNaviStateDiagnosticLoop()
            }
        }
    }

    // naviView가 idle map(경로 없음)에 멈춰있는지, 실제 trip이 붙은 상태인지를 로그만으로
    // 구분할 수 있도록 3초마다 상태를 남김. GPS 위치 로그는 idle 상태에서도 계속 찍히므로
    // 그것만으로는 화면전환 여부를 판단할 수 없다는 점(사용자 지적)을 반영. #문제시 원복
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

    // v1.0.95: 델리게이트는 안 건드리고 View 트리에서 "안내종료" 텍스트를 가진 클릭 가능한
    // View만 찾아서 finishGuidance()를 추가로 걸어줌(기존 SDK 내부 클릭 동작을 대체 -
    // 눌러도 반응이 없던 버튼이라 대체해도 기존 동작을 깨뜨릴 게 없음). #문제시 원복
    private val nativeExitHookTagKey = "tmapnda_exit_hooked".hashCode()
    private fun attachNativeExitButtonHook(naviView: KNNaviView) {
        try {
            naviView.viewTreeObserver.addOnGlobalLayoutListener {
                try {
                    scanAndHookExitButton(naviView)
                } catch (e: Exception) {
                    NavLogger.e(this, "[안내종료훅] 스캔 예외: ${e.message}")
                }
            }
        } catch (e: Exception) {
            NavLogger.e(this, "[안내종료훅] 리스너 등록 예외: ${e.message}")
        }
    }

    private fun scanAndHookExitButton(view: View) {
        val text = try {
            when (view) {
                is android.widget.TextView -> view.text?.toString()
                else -> null
            }
        } catch (e: Exception) { null }

        if (text != null && (text == "안내종료" || text == "안내 종료")) {
            if (view.getTag(nativeExitHookTagKey) == null) {
                view.setTag(nativeExitHookTagKey, true)
                view.isClickable = true
                view.setOnClickListener {
                    NavLogger.d(this, "[안내종료훅] 내장 안내종료 버튼 클릭 감지 - finishGuidance() 직접 호출")
                    finishGuidance()
                }
                // v9.6: 160dp까지 늘려도 실제로는 안 넓어진다는 재억 실차 영상 확인 결과 -
                // 원인은 "바로 위 부모"가 원래 버튼만큼만 작아서, 그 부모 자체의 원래 테두리
                // 바깥은 애초에 터치가 전달되지 않았던 것. 더 큰 조상(패널 폭만큼 넓은 View)을
                // 찾아서 그쪽에 델리게이트를 걸어야 실제로 넓어짐. #문제시 원복
                view.post {
                    try {
                        var host: android.view.View = view
                        var cursor = view.parent
                        var depth = 0
                        while (cursor is android.view.View && depth < 6) {
                            if (cursor.width > host.width) host = cursor
                            cursor = cursor.parent
                            depth++
                        }
                        // v9.7: 글씨(view) rect에 가로만 넓혀서 세로(위아래)는 그대로였던 게 문제 -
                        // 재억 실차 확인 결과 배경(파란/빨간 박스) 전체가 안 눌림. host를 찾은
                        // 이상 host 자체의 전체 영역(가로+세로 다)을 그대로 터치범위로 사용. #문제시 원복
                        val localRect = android.graphics.Rect(0, 0, host.width, host.height)
                        host.touchDelegate = android.view.TouchDelegate(localRect, view)
                        NavLogger.d(this, "[안내종료훅] 델리게이트 호스트=${host.javaClass.simpleName}(w=${host.width}) rect=$localRect")
                    } catch (e: Exception) {
                        NavLogger.e(this, "[안내종료훅] 델리게이트 재계산 예외: ${e.message}")
                    }
                }
                NavLogger.d(this, "[안내종료훅] 내장 안내종료 View 찾아서 클릭리스너 부착 완료(터치영역 확장)")
            }
        }

        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                scanAndHookExitButton(view.getChildAt(i))
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
        // v: 사용자 최종 확정(2026-08-10) - 화면 문구는 딱 4개만: "Cruise On"/"Cruise Off",
        // "콤마 연결 중"/"콤마 연결 대기". "콤마 연결됨"이나 빈 칸 상태는 없음. #문제시 원복
        fun updateConnectionUi() {
            val state = OpenpilotStateRepository.state.value
            val mainConnected = state != null && state.ip.isNotEmpty() && state.ip != "-"
            val ndaConnected = OpenpilotStateRepository.ndaConnected.value == true
            if (mainConnected || ndaConnected) {
                binding.tvConnectionStatus?.text = "Comma 연결됨"
                // v: 사용자 요청(재억, 2026-08-12) - MapActivity와 동일하게 연결됨 상태를
                // 초록색으로 강조(Cruise On의 파란색 #4FC3F7과 구분). #문제시 원복
                binding.tvConnectionStatus?.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
                binding.vConnectionDot?.setBackgroundResource(R.drawable.shape_circle_green)
            } else {
                binding.tvConnectionStatus?.text = "Comma 연결 대기"
                binding.tvConnectionStatus?.setTextColor(android.graphics.Color.parseColor("#555555"))
                binding.vConnectionDot?.setBackgroundResource(R.drawable.shape_circle_gray)
            }
        }
        OpenpilotStateRepository.ndaConnected.observe(this) { updateConnectionUi() }

        OpenpilotStateRepository.state.observe(this) { state ->
            // v4.23: MapActivity와 동일 - 원본 active 대신 안정화된 displayActive 사용,
            // 진동이 심하면 "OP 불안정"으로 표시. #문제시 원복
            when {
                state.isFlickering -> {
                    binding.tvActiveStatus?.text = "Cruise 불안정"
                    binding.tvActiveStatus?.setTextColor(android.graphics.Color.parseColor("#FFA726"))
                }
                state.displayActive -> {
                    binding.tvActiveStatus?.text = "Cruise On"
                    binding.tvActiveStatus?.setTextColor(android.graphics.Color.parseColor("#4FC3F7"))
                }
                else -> {
                    binding.tvActiveStatus?.text = "Cruise Off"
                    binding.tvActiveStatus?.setTextColor(android.graphics.Color.parseColor("#555555"))
                }
            }
            updateConnectionUi()
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
                    // v: MapActivity(티맵 화면)와 동일한 버그 수정 + 타입/아이콘 확장 - 두
                    // 화면 항상 동일하게 유지하는 원칙에 따라 그대로 반영. #문제시 원복
                    val typeName = when (sdiType) {
                        0 -> "신호+과속 단속"
                        1 -> "과속 단속"
                        2 -> "구간단속 시작"
                        3 -> "구간단속 종료"
                        4 -> "구간단속 중"
                        6 -> "신호 단속"
                        7 -> "이동식 단속"
                        8 -> "과속위험구간"
                        9 -> "버스전용차로"
                        11 -> "갓길감시"
                        12 -> "끼어들기 금지"
                        13 -> "교통정보수집"
                        15 -> "과적차량 단속"
                        16 -> "적재불량 단속"
                        17 -> "주차단속"
                        19 -> "철길건널목"
                        20 -> "어린이보호구역"
                        22 -> "과속방지턱"
                        25 -> "휴게소"
                        26 -> "톨게이트"
                        27 -> "안개주의"
                        29 -> "사고다발구간"
                        30 -> "급커브 주의"
                        32 -> "급경사 주의"
                        33 -> "야생동물 사고구간"
                        else -> if (sdiSpeedLimit > 0) "단속 카메라" else "주의 구간"
                    }
                    val iconRes = when (sdiType) {
                        0, 1, 7, 2, 3, 4, 8 -> R.drawable.ic_event_camera
                        6 -> R.drawable.ic_event_traffic_lights
                        9 -> R.drawable.ic_event_bus
                        11 -> R.drawable.ic_event_shoulder
                        12 -> R.drawable.ic_event_cutin
                        13 -> R.drawable.ic_event_antenna
                        15, 16 -> R.drawable.ic_event_truck
                        17 -> R.drawable.ic_event_parking
                        19 -> R.drawable.ic_event_railroad
                        20 -> R.drawable.ic_event_school_zone
                        22 -> R.drawable.ic_event_hump
                        25 -> R.drawable.ic_event_rest_area
                        26 -> R.drawable.ic_event_toll
                        27 -> R.drawable.ic_event_fog
                        29 -> R.drawable.ic_event_accident
                        30 -> R.drawable.ic_event_curve
                        32 -> R.drawable.ic_event_downhill
                        33 -> R.drawable.ic_event_animal
                        else -> null
                    }
                    binding.tvSdiDescr?.text = typeName
                    updateTopBarEventDisplay(typeName, binding.tvSdiDist?.text?.toString(), iconRes)
                } else {
                    binding.tvSdiSpeedLimit?.text = ""
                    binding.tvSdiDist?.text = "--"
                    binding.tvSdiDescr?.text = "--"
                    updateTopBarEventDisplay(null, null, null)
                }
                // v: 사용자 최종 확정(2026-08-10)으로 연결 상태 텍스트는 updateConnectionUi()가
                // 상태 변경 즉시 처리하므로, 여기서 1초마다 따로 갱신할 필요가 없어짐(중복
                // 갱신은 예전에 실제로 버그를 냈던 패턴이라 아예 제거). #문제시 원복
                hudPollHandler.postDelayed(this, 1000)
                renderLaneSignalBar(this@KakaoNaviActivity, binding.llLaneSignalBar, binding.llLaneBoxes, binding.tvTrafficLightCountdown, "kakao")
                updateNavNotification()
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
    // v1.7: nMirror(안드로이드오토 미러링 앱) 분석 결과, BIND_NOTIFICATION_LISTENER_SERVICE +
    // androidx.car.app.NAVIGATION_TEMPLATES 권한을 갖고 있어서, 표준 안드로이드 내비게이션
    // 알림(CarAppExtender + CATEGORY_NAVIGATION)을 감지해 실제 차량
    // 클러스터/HUD로 전달해주는 것으로 추정됨(Tmap/카카오내비 원본 앱이 이 방식으로 이미
    // 뜨고 있었을 가능성). 루트 권한이나 nMirror 전용 프로토콜 없이, 표준 알림만 올려서
    // 시도해봄 - 안 잡히면 그냥 일반 알림 하나 뜨는 것 외엔 부작용 없음. #문제시 원복
    private val NAV_NOTIFICATION_CHANNEL_ID = "kakao_nav_channel"
    private val NAV_NOTIFICATION_ID = 8420

    private fun ensureNavNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                NAV_NOTIFICATION_CHANNEL_ID,
                "카카오 길안내",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun updateNavNotification() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            val kr = KakaoRouteDataRepository
            val distText = if (kr.tbtDist in 1..9998) "${kr.tbtDist}m 앞" else "안내 중"
            val mainText = kr.tbtMainText.ifEmpty { kr.roadName.ifEmpty { "카카오 안내" } }

            val builder = NotificationCompat.Builder(this, NAV_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_directions)
                .setContentTitle(distText)
                .setContentText(mainText)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .extend(
                    CarAppExtender.Builder()
                        .setImportance(NotificationManagerCompat.IMPORTANCE_LOW)
                        .build()
                )

            NotificationManagerCompat.from(this).notify(NAV_NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            NavLogger.e(this, "[HUD?] 내비게이션 알림 갱신 예외: ${e.message}")
        }
    }

    private fun cancelNavNotification() {
        try {
            NotificationManagerCompat.from(this).cancel(NAV_NOTIFICATION_ID)
        } catch (e: Exception) { /* 무시 */ }
    }

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

        // v3.0: Tmap 화면과 동일한 ≡ 메뉴 - 이벤트상세/신호등/최근검색 등을 열고 닫음
        binding.btnMoreMenu?.setOnClickListener { anchorView ->
            val panel = binding.svSecondaryPanel ?: return@setOnClickListener
            if (panel.visibility == View.VISIBLE) {
                panel.visibility = View.GONE
            } else {
                panel.visibility = View.VISIBLE
                panel.post {
                    PanelDragHelper.positionPopupNearAnchor(binding.root, anchorView, panel)
                }
            }
        }

        // v3.8: 티맵 화면과 동일한 동작 - 업데이트확인/도움말은 화면과 무관한 앱 전체
        // 기능이라 그대로 재사용, 앱종료는 이 화면부터 전체 태스크 종료. #문제시 원복
        binding.btnCheckUpdate?.setOnClickListener {
            binding.svSecondaryPanel?.visibility = View.GONE
            Toast.makeText(this, "업데이트 확인 중...", Toast.LENGTH_SHORT).show()
            AutoUpdater.checkForUpdates(this, isManual = true)
        }
        // v3.10: GitHub 브라우저 대신 앱 안 팝업으로 (사용자 지적 2번). #문제시 원복
        binding.btnHelp?.setOnClickListener {
            binding.svSecondaryPanel?.visibility = View.GONE
            AutoUpdater.showChangelogDialog(this)
        }
        // v4.9: 진짜 "도움말" - README 웹페이지로 (티맵 화면과 동일). #문제시 원복
        binding.btnGuideHelp?.setOnClickListener {
            binding.svSecondaryPanel?.visibility = View.GONE
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://github.com/Rusiperso/TmapNda/blob/openpilot/README.md")
                    )
                )
            } catch (e: Exception) {
                Toast.makeText(this, "도움말을 열 수 없습니다: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnDeleteAllLogs?.setOnClickListener {
            binding.svSecondaryPanel?.visibility = View.GONE
            android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("로그 전체 삭제")
                .setMessage("저장된 로그 파일을 전부 삭제할까요? 되돌릴 수 없습니다.")
                .setPositiveButton("삭제") { _, _ ->
                    NavLogger.deleteAllLogFiles(this)
                    Toast.makeText(this, "로그를 전부 삭제했습니다.", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("취소", null)
                .show()
        }
        binding.btnExitApp?.setOnClickListener {
            // v4.23: MapActivity.onDestroy()가 더 이상 자동으로 서비스를 안 멈추게 바꿔서
            // (카카오 화면 중 OS가 MapActivity만 강제로 destroy하는 경우에도 UDP 서비스가
            // 안 죽게 하려고), 카카오 화면의 "앱 종료"에서도 명시적으로 멈춰줘야
            // finishAffinity() 후에도 서비스가 고아 상태로 계속 도는 걸 방지함. #문제시 원복
            stopService(Intent(this, UdpSenderService::class.java))
            finishAffinity()
        }
        binding.btnEditPanelPosition?.let {
            PanelDragHelper.wireEditToggleButton(this, it, binding.svSecondaryPanel, binding.btnMoreMenu, binding.btnConfirmEditPosition, binding.llLeftHudPanel)
        }
        binding.btnEditKey?.setOnClickListener {
            binding.svSecondaryPanel?.visibility = View.GONE
            PanelDragHelper.showAppSettingsDialog(this, null)
        }

        binding.btnKakaoMuteToggle?.setOnClickListener {
            kakaoMuted = !kakaoMuted
            getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).edit()
                .putBoolean("kakao_muted", kakaoMuted).apply()
            updateMuteButtonStyle()
            Toast.makeText(this, if (kakaoMuted) "카카오 안내음성 음소거" else "카카오 안내음성 켜짐", Toast.LENGTH_SHORT).show()
        }

        binding.btnOpenSearch?.setOnClickListener {
            startVoiceSearch()
        }
        binding.btnOpenSearch?.setOnLongClickListener {
            showInPlaceSearchDialog()
            true
        }

        // v3.11: 티맵 화면과 완전히 동일하게 - 상단바 인라인 검색창(키보드 검색 액션)과
        // 최근검색 아이콘 연결 (사용자: "티맵꺼 그대로 카카오에 마춰"). #문제시 원복
        binding.etDestination?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                val query = binding.etDestination?.text?.toString()?.trim().orEmpty()
                if (query.isNotEmpty()) performInPlaceSearch(query)
                true
            } else {
                false
            }
        }
        binding.btnVoiceSearch?.setOnClickListener {
            showFullSearchHistoryDialog()
        }

        binding.btnShareLogTopBar?.setOnClickListener {
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
        val history = SearchHistoryStore.get(this)

        if (history.isEmpty()) {
            binding.llRecentSearchPanel?.visibility = View.GONE
            return
        }
        binding.llRecentSearchPanel?.visibility = View.VISIBLE
        val rowsContainer = binding.llRecentSearchRows ?: return
        rowsContainer.removeAllViews()
        history.take(5).forEach { entry ->
            val tv = android.widget.TextView(this).apply {
                text = entry.name
                setTextColor(android.graphics.Color.parseColor("#DDDDDD"))
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(24, 20, 24, 20)
                setOnClickListener {
                    // v2.5: 재검색이 아니라 저장된 좌표로 바로 길안내 시작
                    // v4.17: 최근목적지 패널에서 "이미 있는" 항목을 다시 탭했을 때도
                    // save()를 안 불러서 순서가 안 바뀌던 문제(사용자 요청: 최신순 정렬) -
                    // 다시 탭해도 맨 위로 올라오게 재저장. #문제시 원복
                    SearchHistoryStore.save(this@KakaoNaviActivity, entry)
                    renderRecentDestinationsPanel()
                    KakaoRouteDataRepository.reset()
                    resolveCurrentPositionThenRequestRoute(entry.name, entry.lat, entry.lon, finishOnFailure = false)
                }
            }
            rowsContainer.addView(tv)
        }
        binding.btnMoreHistory?.setOnClickListener {
            // v1.8: 여기서 finishGuidance()를 불러서 MapActivity로 돌아가 다이얼로그를 띄웠는데,
            // 그러면 진행 중이던 카카오 안내 자체가 끝나버림(사용자 지적 2번: "더보기 누르면
            // 안내 중 뒤로 나옴"). 안내를 끊지 않고 이 화면 안에서 그대로 전체 이력을 보여줌. #문제시 원복
            showFullSearchHistoryDialog()
        }
    }

    private fun showFullSearchHistoryDialog() {
        var history = SearchHistoryStore.get(this)
        if (history.isEmpty()) {
            Toast.makeText(this, "검색 이력이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }

        lateinit var dialog: android.app.AlertDialog
        lateinit var listView: android.widget.ListView

        fun buildAdapter(): android.widget.BaseAdapter = object : android.widget.BaseAdapter() {
            override fun getCount() = history.size
            override fun getItem(position: Int) = history[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val entry = history[position]
                val row = android.widget.LinearLayout(this@KakaoNaviActivity).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    setBackgroundColor(android.graphics.Color.parseColor("#181818"))
                    setPadding(24, 24, 12, 24)
                }
                val nameText = android.widget.TextView(this@KakaoNaviActivity).apply {
                    text = if (entry.addr.isNotBlank()) "${entry.name}\n${entry.addr}" else entry.name
                    setTextColor(android.graphics.Color.WHITE)
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                }
                val deleteText = android.widget.TextView(this@KakaoNaviActivity).apply {
                    text = "✕"
                    setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
                    setPadding(24, 0, 24, 0)
                    setOnClickListener {
                        SearchHistoryStore.delete(this@KakaoNaviActivity, entry)
                        renderRecentDestinationsPanel()
                        history = SearchHistoryStore.get(this@KakaoNaviActivity)
                        if (history.isEmpty()) {
                            dialog.dismiss()
                        } else {
                            listView.adapter = buildAdapter()
                        }
                    }
                }
                row.addView(nameText)
                row.addView(deleteText)
                return row
            }
        }

        listView = android.widget.ListView(this)
        listView.adapter = buildAdapter()
        listView.setBackgroundColor(android.graphics.Color.parseColor("#181818"))
        listView.divider = android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#333333"))
        listView.dividerHeight = 1

        dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("검색 이력 전체")
            .setView(listView)
            .setPositiveButton("전체 삭제") { _, _ ->
                android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("검색 이력 전체 삭제")
                    .setMessage("검색 이력을 전부 삭제할까요?")
                    .setPositiveButton("삭제") { _, _ ->
                        SearchHistoryStore.clear(this)
                        renderRecentDestinationsPanel()
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
            .setNegativeButton("닫기", null)
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            val picked = history[position]
            dialog.dismiss()
            // v2.5: 재검색이 아니라 저장된 좌표로 바로 길안내 시작
            // v4.17: 다시 탭해도 최신순 맨 위로 올라오게. #문제시 원복
            SearchHistoryStore.save(this, picked)
            KakaoRouteDataRepository.reset()
            resolveCurrentPositionThenRequestRoute(picked.name, picked.lat, picked.lon, finishOnFailure = false)
        }
        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#212121")))
    }

    // v1.6: 검색 버튼 누르면 화면이 티맵으로 나갔다 다시 들어오던 문제 - 굳이 MapActivity로
    // 안 돌아가고 이 화면 안에서 그대로 재검색하도록 함(Kakao 로컬 검색 API를 MapActivity와
    // 동일한 방식으로 여기서도 직접 호출). #문제시 원복
    private val searchHttpClient by lazy { OkHttpClient() }

    private fun showInPlaceSearchDialog() {
        // v1.7: 기본 AlertDialog.Builder(this)는 앱 라이트 테마를 상속해서 다이얼로그
        // 배경이 밝은데 입력창 글자색은 흰색으로 박아놔서 "흰 배경에 흰 글씨"로 안 보이던
        // 문제였음(사용자 지적 7번). Theme_Material_Dialog_Alert(다크)로 통일해서 해결. #문제시 원복
        val input = android.widget.EditText(this).apply {
            hint = "목적지를 입력하세요 (예: 서울역)"
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.parseColor("#AAAAAA"))
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("목적지 재검색")
            .setView(input)
            .setPositiveButton("검색") { _, _ ->
                val q = input.text.toString().trim()
                if (q.isNotEmpty()) performInPlaceSearch(q)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // v1.7: 검색결과 목록을 다크 테마 다이얼로그로 보여주고 사용자가 직접 고르게 함. #문제시 원복
    private fun darkTextAdapter(items: List<String>): android.widget.ArrayAdapter<String> {
        return object : android.widget.ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_1, android.R.id.text1, items
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val tv = view.findViewById<android.widget.TextView>(android.R.id.text1)
                tv.setTextColor(android.graphics.Color.WHITE)
                tv.setPadding(24, 20, 24, 20)
                return view
            }
        }
    }

    // v4.0: 상단바 이벤트(카메라/구간단속/방지턱) 표시 - 설정에서 끄면 안 보이게 함
    // (사용자 지적 5·6번, 티맵과 동일). #문제시 원복
    private fun updateTopBarEventDisplay(typeName: String?, distText: String?, iconRes: Int? = null) {
        val enabled = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
            .getBoolean("topbar_event_enabled", true)
        if (!enabled || typeName == null) {
            binding.tvTopBarEvent?.visibility = View.GONE
            binding.ivTopBarEvent?.visibility = View.GONE
            return
        }
        binding.tvTopBarEvent?.text = "$typeName $distText"
        binding.tvTopBarEvent?.visibility = View.VISIBLE
        if (iconRes != null) {
            binding.ivTopBarEvent?.setImageResource(iconRes)
            binding.ivTopBarEvent?.visibility = View.VISIBLE
        } else {
            binding.ivTopBarEvent?.visibility = View.GONE
        }
    }

    // v9.5: 검색 결과 거리순 정렬용 현재 위치(WGS84 위도/경도) 조회.
    // resolveCurrentPositionThenRequestRoute()와 동일한 우선순위(KNSDK GPS →
    // 시스템 LocationManager)로 재사용하되, KNSDK GPS는 KATEC 좌표라 역변환
    // 함수가 없어 검색용으론 LocationManager 값만 사용. #문제시 원복
    private fun resolveCurrentWgs84LatLonForSearch(): Pair<Double?, Double?> {
        return try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val loc = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            if (loc != null) Pair(loc.latitude, loc.longitude) else Pair(null, null)
        } catch (e: SecurityException) {
            NavLogger.e(this, "위치 권한 없음(검색 거리순 정렬용): ${e.message}")
            Pair(null, null)
        }
    }

    private fun performInPlaceSearch(query: String) {
        val restKey = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
            .getString("kakao_rest_api_key", "") ?: ""
        if (restKey.isBlank()) {
            Toast.makeText(this, "카카오 REST API 키가 없어 - 티맵 화면에서 검색을 한 번 먼저 설정해줘.", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "검색 중: $query", Toast.LENGTH_SHORT).show()
        // v9.5: 재억 요청 - MapActivity(Tmap화면)와 동일한 이유로 현재 위치 기준
        // 거리순 정렬 추가. 위치를 못 구하면 좌표 없이 기존처럼 검색. #문제시 원복
        val (curLat, curLon) = resolveCurrentWgs84LatLonForSearch()
        val locationParams = if (curLat != null && curLon != null) {
            "&x=$curLon&y=$curLat&radius=20000&sort=distance"
        } else {
            ""
        }
        val url = "https://dapi.kakao.com/v2/local/search/keyword.json?query=" +
            java.net.URLEncoder.encode(query, "UTF-8") + locationParams
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
                        NavLogger.d(this@KakaoNaviActivity, "카카오 키워드검색 결과 없음, 주소검색으로 재시도: query=$query")
                        performAddressSearchFallback(query, restKey)
                        return@use
                    }
                    // v2.5: 이력은 검색 시도가 아니라 실제로 고른 결과에만 저장(아래 클릭 시). #문제시 원복
                    // v1.8: "성심당 검색하면 성심당 본점/대전역점/케익부띠끄/롯데백화점점 처럼
                    // 여러 지점이 나와야 하는데 documents[0]으로 바로 안내가 시작됨" 지적(3번) -
                    // 결과 목록을 다이얼로그로 보여주고 사용자가 직접 골라서 시작하도록 변경. #문제시 원복
                    val hits = (0 until documents.length()).map { idx ->
                        val d = documents.getJSONObject(idx)
                        HistoryEntry(
                            d.optString("place_name", query),
                            d.optString("road_address_name", d.optString("address_name", "")),
                            d.optDouble("y"),
                            d.optDouble("x")
                        )
                    }
                    NavLogger.d(this@KakaoNaviActivity, "인라인 재검색 결과 ${hits.size}건: query=$query")
                    runOnUiThread { showInPlaceSearchResultsDialog(hits) }
                }
            }
        })
    }

    // v7.8: Tmap 화면(MapActivity)과 동일한 이유·로직 - 키워드검색이 지번(번지) 주소를
    // 잘 못 찾는 문제 대응. 결과 0건이면 주소 전용 API로 재시도. #문제시 원복
    private fun performAddressSearchFallback(query: String, restKey: String) {
        val url = "https://dapi.kakao.com/v2/local/search/address.json?query=" +
            java.net.URLEncoder.encode(query, "UTF-8")
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "KakaoAK $restKey")
            .build()

        searchHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                NavLogger.e(this@KakaoNaviActivity, "카카오 주소검색 요청 실패: ${e.message}")
                runOnUiThread { Toast.makeText(this@KakaoNaviActivity, "검색 결과 없음: $query", Toast.LENGTH_SHORT).show() }
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        NavLogger.e(this@KakaoNaviActivity, "카카오 주소검색 실패 code=${it.code}")
                        runOnUiThread { Toast.makeText(this@KakaoNaviActivity, "검색 결과 없음: $query", Toast.LENGTH_SHORT).show() }
                        return@use
                    }
                    val json = JSONObject(it.body?.string() ?: "{}")
                    val documents = json.optJSONArray("documents")
                    if (documents == null || documents.length() == 0) {
                        NavLogger.d(this@KakaoNaviActivity, "카카오 주소검색도 결과 없음: query=$query")
                        runOnUiThread { Toast.makeText(this@KakaoNaviActivity, "검색 결과 없음: $query", Toast.LENGTH_SHORT).show() }
                        return@use
                    }
                    val hits = (0 until documents.length()).map { idx ->
                        val d = documents.getJSONObject(idx)
                        val roadAddr = d.optJSONObject("road_address")
                        HistoryEntry(
                            d.optString("address_name", query),
                            roadAddr?.optString("address_name").orEmpty(),
                            d.optDouble("y"),
                            d.optDouble("x")
                        )
                    }
                    NavLogger.d(this@KakaoNaviActivity, "카카오 주소검색 결과 ${hits.size}건: query=$query")
                    runOnUiThread { showInPlaceSearchResultsDialog(hits) }
                }
            }
        })
    }

    private fun showInPlaceSearchResultsDialog(hits: List<HistoryEntry>) {
        val labels = hits.map { h -> if (h.addr.isNotBlank()) "${h.name}\n${h.addr}" else h.name }
        val listView = android.widget.ListView(this@KakaoNaviActivity)
        listView.adapter = darkTextAdapter(labels)
        listView.setBackgroundColor(android.graphics.Color.parseColor("#181818"))
        listView.divider = android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#333333"))
        listView.dividerHeight = 1
        val pickDialog = android.app.AlertDialog.Builder(this@KakaoNaviActivity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("검색 결과 ${hits.size}건 - 목적지를 선택하세요")
            .setView(listView)
            .setNegativeButton("취소", null)
            .create()
        listView.setOnItemClickListener { _, _, position, _ ->
            val picked = hits[position]
            pickDialog.dismiss()
            // v4.13: 카카오 화면 인라인 검색도 티맵 화면과 같은 커서 잔류
            // 문제가 있었음(사용자 8번) - 동일한 방식으로 포커스 강제 정리. #문제시 원복
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            currentFocus?.let { imm?.hideSoftInputFromWindow(it.windowToken, 0) }
            binding.etDestination?.apply {
                isFocusable = false
                isFocusableInTouchMode = false
                clearFocus()
                isFocusable = true
                isFocusableInTouchMode = true
                setText("")
            }
            SearchHistoryStore.save(this@KakaoNaviActivity, picked)
            renderRecentDestinationsPanel()
            KakaoRouteDataRepository.reset()
            resolveCurrentPositionThenRequestRoute(picked.name, picked.lat, picked.lon, finishOnFailure = false)
        }
        pickDialog.show()
    }

    private fun finishGuidance() {
        KakaoRouteDataRepository.reset()
        cancelNavNotification()
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

    // v2.4: MapActivity와 동일한 이유 - mute 순간에만 볼륨을 캡처하던 구조라 사용자가
    // mute 없이 볼륨만 바꾸면 저장이 안 되고 예전 값으로 복원되던 문제. 실제 주행 중엔
    // 이 화면(카카오 안내)이 주로 떠있으므로 여기서도 실시간 캡처가 특히 중요함. #문제시 원복
    private val volumeChangeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val muted = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                .getBoolean("tmap_muted", false)
            if (!muted) {
                VolumeHelper.captureCurrentVolumePercent(this@KakaoNaviActivity)
                // v4.16: 하드웨어 볼륨버튼으로 조절할 때마다 카카오 SDK 자체 볼륨
                // (naviView.sndVolume)도 실시간으로 같이 맞춤. PR#9 병합 때 유실됐던 것 복원. #문제시 원복
                if (::naviView.isInitialized) applyKakaoSdkVolume()
            }
        }
    }

    // v4.15: "티맵은 되는데 카카오 화면은 아무리 딴 데를 찍어도 검색창에서 안 빠져나온다"는
    // 사용자 지적 - 코드는 Tmap과 동일한데 실제로 다르게 동작한다는 건, dispatchTouchEvent가
    // 이 화면에서 아예 안 불리거나, currentFocus가 기대와 다르거나, KNNaviView 쪽에서 뭔가
    // 되돌리고 있다는 뜻. 추측으로 또 고치지 말고 원인을 확정할 수 있게 매 판정마다
    // 로그를 남김(스팸 방지 없이 - 이 화면은 어차피 탭이 잦지 않음). #문제시 원복
    // v4.15: "더보기"(btnMoreMenu) 눌러서 뜨는 svSecondaryPanel 팝업이 바깥을 찍어도
    // 안 닫히고 버튼을 다시 눌러야만 닫힘 - 각 메뉴 버튼 클릭 시에만 GONE 처리했지 "바깥
    // 탭"에 대한 처리가 없었음. PR#9 병합 때 이 블록이 실수로 같이 삭제됐었음 - 복원. #문제시 원복
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
            val panel = binding.svSecondaryPanel
            if (panel != null && panel.visibility == View.VISIBLE) {
                val panelRect = android.graphics.Rect()
                panel.getGlobalVisibleRect(panelRect)
                val touchInPanel = panelRect.contains(ev.rawX.toInt(), ev.rawY.toInt())
                var touchOnToggleButton = false
                binding.btnMoreMenu?.let { btn ->
                    val btnRect = android.graphics.Rect()
                    btn.getGlobalVisibleRect(btnRect)
                    touchOnToggleButton = btnRect.contains(ev.rawX.toInt(), ev.rawY.toInt())
                }
                if (!touchInPanel && !touchOnToggleButton) {
                    panel.visibility = View.GONE
                }
            }
            val focused = currentFocus
            NavLogger.d(this, "[검색창포커스진단] ACTION_DOWN currentFocus=${focused?.javaClass?.simpleName}(id=${focused?.id}) etDestinationId=${binding.etDestination?.id}")
            if (focused is android.widget.EditText) {
                val outRect = android.graphics.Rect()
                focused.getGlobalVisibleRect(outRect)
                val outside = !outRect.contains(ev.rawX.toInt(), ev.rawY.toInt())
                NavLogger.d(this, "[검색창포커스진단] EditText 포커스중 rect=$outRect touch=(${ev.rawX},${ev.rawY}) outside=$outside")
                if (outside) {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                    imm?.hideSoftInputFromWindow(focused.windowToken, 0)
                    focused.apply {
                        isFocusable = false
                        isFocusableInTouchMode = false
                        clearFocus()
                        isFocusable = true
                        isFocusableInTouchMode = true
                    }
                    NavLogger.d(this, "[검색창포커스진단] 포커스 해제 실행함, 실행직후 currentFocus=${currentFocus?.javaClass?.simpleName}")
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        NavLogger.d(this, "[lifecycle] onResume")
        try {
            registerReceiver(
                volumeChangeReceiver,
                android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            )
        } catch (e: Exception) {
            NavLogger.e(this, "볼륨 리시버 등록 예외: ${e.message}")
        }
    }

    override fun onPause() {
        super.onPause()
        NavLogger.d(this, "[lifecycle] onPause")
        try {
            unregisterReceiver(volumeChangeReceiver)
        } catch (e: Exception) {
            // 등록 안 된 상태에서 해제 시도하면 예외 - 무시해도 안전
        }
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
    // KNSDK로 들어가는 바람에 위치가 오락가락했음(사용자 - "평택인데 용인으로 잡힘").
    // GPS_PROVIDER만 반영하고, 정확도가 너무 나쁜 픽스(accuracy > 50m)는 무시함. #문제시 원복
    private var lastOverSpeedDiagLogTime = 0L
    private fun checkOverSpeedWarning(speedKph: Int) {
        val pref = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
        if (!pref.getBoolean("over_speed_warning_enabled", false)) return
        val limit = SdiDataRepository.roadLimitSpeed
        if (limit < 30 || speedKph <= 0) return
        val now = System.currentTimeMillis()
        // v: MapActivity와 동일한 진단 로그 - 트리거 여부와 무관하게 3초마다 limit 흐름을
        // 남겨서 "65 주행 당시 limit이 실제로 몇이었는지" 대조 가능하게 함. #문제시 원복
        if (now - lastOverSpeedDiagLogTime > 3000L) {
            lastOverSpeedDiagLogTime = now
            NavLogger.d(this, "[과속경고음진단] speedKph=$speedKph limit=$limit (limit*1.1=${limit * 1.1})")
        }
        if (speedKph > limit * 1.1 && now - SdiDataRepository.lastOverSpeedWarningTime > 8000L) {
            SdiDataRepository.lastOverSpeedWarningTime = now
            NavLogger.e(this, "[과속경고음발생][카카오화면] speedKph=$speedKph limit=$limit (limit*1.1=${limit * 1.1})")
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
        // v4.24: MapActivity와 동일 이유로 추가. #문제시 원복
        NavLogger.d(this, "[KakaoNaviActivity lifecycle] onDestroy (isFinishing=$isFinishing, isChangingConfigurations=$isChangingConfigurations)")
        cancelNavNotification()
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
