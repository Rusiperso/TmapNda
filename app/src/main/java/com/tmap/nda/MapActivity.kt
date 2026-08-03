package com.tmap.nda

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Base64
import java.security.MessageDigest
import com.kakaomobility.knsdk.KNSDK
import com.kakaomobility.knsdk.KNLanguageType
import com.kakaomobility.knsdk.KNRoutePriority
import com.kakaomobility.knsdk.KNRouteAvoidOption
import com.kakaomobility.knsdk.common.objects.KNPOI
import com.kakaomobility.knsdk.common.objects.KNError
import com.kakaomobility.knsdk.guidance.knguidance.*
import com.kakaomobility.knsdk.guidance.knguidance.common.KNLocation
import com.kakaomobility.knsdk.guidance.knguidance.locationguide.KNGuide_Location
import com.kakaomobility.knsdk.guidance.knguidance.routeguide.KNGuide_Route
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.KNGuide_Safety
import com.kakaomobility.knsdk.trip.kntrip.KNTrip
import com.kakaomobility.knsdk.ui.view.KNNaviView
import android.speech.RecognizerIntent
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.tmap.nda.databinding.ActivityMapBinding

import com.tmapmobility.tmap.tmapsdk.ui.util.TmapUISDK
import com.tmapmobility.tmap.tmapsdk.ui.util.TmapUISDK.Companion.getFragment
import com.tmapmobility.tmap.tmapsdk.ui.util.TmapUISDK.Companion.initialize
import com.tmapmobility.tmap.tmapsdk.ui.fragment.NavigationFragment
import androidx.lifecycle.Observer
import android.content.res.Configuration
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

class MapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapBinding
    private var navigationFragment: NavigationFragment? = null
    private var isEditMode = false
    private var lastValidRoadLimit = 0  // 3번: 도로 규정속도 깜빡임 방지 - 마지막 유효값 저장
    private var lastEdcAnomalyState = false  // observableEDCData 이상징후 로깅용 (상태 변화시에만 기록)
    private var loggedEdcKeySetOnce = false  // 차선/신호등 진단용 전체 키 목록은 한 번만 로그
    private var tbtDistErrorLogged = false   // nTBTDist 리플렉션 실패는 최초 1회만 로깅
    private var isTmapMuted = false  // 티맵 안내음성 음소거 여부 (기본값: 소리 켜짐. 카카오는 실제 경로안내 중에만 말하므로 기본상태에선 티맵 음성이 나와야 함)

    // 음성 검색 결과 수신용 런처. 안드로이드 표준 음성인식 액티비티(RecognizerIntent)를 위임 호출하는 방식이라
    // 별도의 RECORD_AUDIO 런타임 권한 요청 없이 동작함 (인식은 시스템 음성입력 앱이 수행).
    // 로그 공유 화면(이메일 앱 등)에서 돌아왔을 때, 방금 보낸 로그 파일들을 삭제하기 위한 목록.
    // ACTION_SEND_MULTIPLE은 "진짜 전송됨"까지는 확인 못 하고 "공유 화면에서 돌아옴"까지만 알 수 있어서
    // 그 시점을 "보냈다"로 간주하고 삭제함.
    // 로그 공유 화면(이메일 앱 등)에서 돌아왔을 때 쓰던 삭제 로직은 제거함.
    // 이제 로그는 공유해도 지워지지 않고(계속 쌓임, 10MB 넘으면 회전), 앱 종료 시에만
    // 전체 삭제됨 (btnExitApp 참고). #문제시 원복
    private val shareLogLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* no-op: 공유해도 로그 유지 */ }

    private val voiceSearchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val spokenText = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
            if (!spokenText.isNullOrEmpty()) {
                binding.etDestination?.setText(spokenText)
                binding.etDestination?.setSelection(spokenText.length)
                NavLogger.d(this, "음성검색 결과: $spokenText")
                performDestinationSearch(spokenText)
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

    private fun applyMuteState() {
        binding.btnMuteToggle?.setImageResource(
            if (isTmapMuted) android.R.drawable.ic_lock_silent_mode else android.R.drawable.ic_lock_silent_mode_off
        )
        binding.btnMuteToggle?.setBackgroundResource(
            if (isTmapMuted) R.drawable.shape_circle_gray else R.drawable.shape_circle_green
        )
        // v1.6: 예전엔 음소거 '해제' 상태에서도 매번 setVolume(100)을 강제 호출해서, 사용자가
        // 하드웨어 버튼 등으로 50 같은 값으로 맞춰도 앱 재시작/토글 때마다 100으로 되돌아갔음
        // (재억 지적 - "볼륨 50으로 했는데 다음에 실행하면 다시 100이 됨"). 이제 음소거 상태일
        // 때만 실제로 볼륨을 만지고(0으로), 해제 상태에선 아예 건드리지 않음 - 사용자가 맞춰둔
        // 값을 그대로 둠. #문제시 원복
        if (isTmapMuted) {
            VolumeHelper.captureCurrentVolumePercent(this)
            TmapUISDK.setVolume(this, 0)
        }
        // 해제(!isTmapMuted) 상태에서 "복원"이 필요한 경우는 버튼 토글 핸들러에서 명시적으로
        // unmuteTmapVolume()을 호출하도록 분리함 - 여기(수동적 상태 갱신)에서는 손대지 않음.
    }

    private fun unmuteTmapVolume() {
        try {
            TmapUISDK.setVolume(this, VolumeHelper.savedVolumePercent(this))
        } catch (e: Exception) {
            NavLogger.e(this, "티맵 볼륨 복원 예외: ${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NavLogger.appContext = applicationContext
        // 시스템 내비게이션 바(제스처/버튼) 뒤까지 그려지는 edge-to-edge를 명시적으로 켜야
        // 아래 인셋 리스너가 실제 하단 바 높이를 받아온다. (기존엔 미설정이라 하단 UI가
        // 기기에 따라 시스템 바에 가려지는 문제 발생)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 현재 window의 실사용 영역(dp), density, 화면비를 기준으로 가로 HUD 폭을 계산한다.
        // 세로 화면에는 llLeftHudPanel이 없으므로 검색창/지도 margin을 건드리지 않는다.
        HudScale.install(
            binding.root,
            binding.llLeftHudPanel,
            listOf(binding.llSearchPanel, binding.llGuidanceBar, binding.flKakaoOverlay, binding.llLaneSignalBar)
        )

        // UiAutoScaler(전체 뷰트리 padding/margin/고정크기 일괄 스케일링)는
        // 태블릿(sw~940dp)에서 1.8배가 그대로 곱해지며 좌측 패널 폭을 넘어
        // 아이콘 겹침/텍스트 잘림을 유발함이 확인되어 즉시 롤백함.
        // #문제시 원복 대상이었으나 현재는 비활성 상태. (UiAutoScaler.kt 자체는 보존)
        // UiAutoScaler.apply(this, binding.root)

        // 자동 업데이트 체크
        AutoUpdater.checkForUpdates(this)

        // 카카오 개발자콘솔 "네이티브 앱 키"에 등록할 키 해시 확인용 - 세션당 1회만 로그로 남김.
        // keystore 비밀번호 없이도 "실제 폰에 설치되어 서명 검증되는 그 앱"의 정확한 해시를 얻기 위함.
        // 확인 후 이 블록은 지워도 됨(진단 전용). #문제시 원복
        logKakaoKeyHashOnce()

        val minBottomSafeAreaPx = (36 * resources.displayMetrics.density).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            // 지도(root)는 좌/상/우만 인셋 적용, 하단은 지도가 풀스크린으로 남도록 0 유지
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)

            // 세로모드 하단 상태바: 실제 시스템 바 높이와 최소 안전 여백 중 큰 값을 패딩으로 적용
            val detailPanel = v.findViewById<View>(R.id.llDetailPanel)
            detailPanel?.setPadding(
                detailPanel.paddingLeft, detailPanel.paddingTop, detailPanel.paddingRight,
                maxOf(systemBars.bottom, minBottomSafeAreaPx)
            )

            // 가로모드 좌측 HUD 패널(하단 버튼들이 여기 있음): 동일하게 하단 안전 여백 확보
            val leftHudPanel = v.findViewById<View>(R.id.llLeftHudPanel)
            leftHudPanel?.setPadding(
                leftHudPanel.paddingLeft, leftHudPanel.paddingTop, leftHudPanel.paddingRight,
                maxOf(systemBars.bottom, minBottomSafeAreaPx)
            )

            insets
        }

        val sharedPref = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
        val appKey = sharedPref.getString("APP_KEY", "") ?: ""
        
        // Force Tmap SDK to run in background
        getSharedPreferences("user.settings.info", Context.MODE_PRIVATE).edit().putBoolean("set_suspend_in_background", false).apply()
        
        if (appKey.isEmpty()) {
            Toast.makeText(this, "App Key가 설정되지 않았습니다.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        isTmapMuted = sharedPref.getBoolean("tmap_muted", false)
        applyMuteState()
        binding.btnMuteToggle?.setOnClickListener {
            isTmapMuted = !isTmapMuted
            sharedPref.edit().putBoolean("tmap_muted", isTmapMuted).apply()
            applyMuteState()
            if (!isTmapMuted) unmuteTmapVolume()
            Toast.makeText(this, if (isTmapMuted) "티맵 안내음성 음소거" else "티맵 안내음성 켜짐", Toast.LENGTH_SHORT).show()
        }

        binding.btnCheckUpdate.setOnClickListener {
            Toast.makeText(this, "업데이트 확인 중...", Toast.LENGTH_SHORT).show()
            AutoUpdater.checkForUpdates(this)
        }

        // v1.7: 짧게 누르기가 카카오키 재입력 화면으로 가버려서, 정작 자주 쓸 앱 설정
        // (속도초과 경고음/이동식카메라 감속 토글)은 길게 눌러야만 보였음 - 아무도
        // 그걸 몰라서 "설정 창에 그 기능이 안 보인다"는 문의로 이어짐. 우선순위를
        // 뒤집음: 짧게 누르기 = 앱 설정(토글들), 길게 누르기 = 카카오키 재입력(드묾). #문제시 원복
        binding.btnEditKey.setOnClickListener {
            showAppSettingsDialog()
        }
        binding.btnEditKey.setOnLongClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("auto_start", false)
            startActivity(intent)
            finish()
            true
        }

        binding.btnExitApp.setOnClickListener {
            // 공유시 삭제하던 방식에서 변경: 이제 로그는 실행 중엔 계속 쌓이고(10MB 회전),
            // 앱을 종료하는 이 시점에 전체 삭제. #문제시 원복
            NavLogger.deleteAllLogFiles(this)
            finishAffinity()
        }

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        // 스타일 D(HUD): 좌측 패널은 고정, llOffset 플로팅만 드래그 유지
        binding.llOffset?.let {
            makeDraggable(it, "llOffset", isLandscape, emptyList())
        }

        // Tmap 지도 터치 무력화: 화면/정보 표시는 그대로, 지도(NavigationFragment)로 가는
        // 터치 입력만 차단(기본값). v2.0: 설정에서 "잠금 해제"를 켰으면 통과시킴. #문제시 원복
        applyMapTouchLockState(
            getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                .getBoolean("map_touch_unlocked", false)
        )

        OpenpilotStateRepository.state.observe(this) { state ->
            binding.tvCarrotVersion.text = state.carrot2
            binding.tvCarrotIp.text = if (state.ip.isNotEmpty() && state.ip != "-") "IP: ${state.ip}" else "IP: -"

            if (state.ip != "-" && state.ip.isNotEmpty()) {
                binding.vConnectionDot.setBackgroundResource(R.drawable.shape_circle_green)
                binding.tvConnectionStatus.text = "연결됨"
                binding.tvConnectionStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
            } else {
                binding.vConnectionDot.setBackgroundResource(R.drawable.shape_circle_gray)
                binding.tvConnectionStatus.text = "연결 대기"
                binding.tvConnectionStatus.setTextColor(android.graphics.Color.parseColor("#555555"))
            }

            if (state.active) {
                binding.tvActiveStatus.text = "OP ON"
                binding.tvActiveStatus.setTextColor(android.graphics.Color.parseColor("#4FC3F7"))
                binding.tvActiveStatus.background = null
            } else {
                binding.tvActiveStatus.text = "OP OFF"
                binding.tvActiveStatus.setTextColor(android.graphics.Color.parseColor("#555555"))
                binding.tvActiveStatus.background = null
            }

            when (state.trafficState) {
                1 -> binding.vTrafficLight.setBackgroundResource(R.drawable.shape_circle_red)
                2 -> binding.vTrafficLight.setBackgroundResource(R.drawable.shape_circle_green)
                else -> binding.vTrafficLight.setBackgroundResource(R.drawable.shape_circle_gray)
            }
        }

        initTmapSdk(appKey)
    }

    private fun initTmapSdk(appKey: String) {
        // TmapUISDK 초기화
        initialize(this, "", appKey, "", "", object : TmapUISDK.InitializeListener {
            override fun onSuccess() {
                runOnUiThread {
                    // 회전(가로/세로 전환)으로 Activity가 destroy → recreate 되는 사이에
                    // 이전 Activity 인스턴스가 캡처한 이 콜백이 뒤늦게 실행되면서
                    // 이미 죽은 Activity의 FragmentManager에 add()를 시도해 크래시가 나던
                    // 문제 방지. 콜백 진입 시점에 이 Activity가 이미 종료/파괴됐으면 무시.
                    if (isFinishing || isDestroyed) {
                        NavLogger.d(this@MapActivity, "SDK onSuccess 도착했지만 Activity 이미 종료됨 - 무시")
                        return@runOnUiThread
                    }

                    try {
                        val methods = NavigationFragment::class.java.methods
                        for (m in methods) {
                            if (m.name.contains("mute", ignoreCase = true) || m.name.contains("volume", ignoreCase = true) || m.name.contains("sound", ignoreCase = true) || m.name.contains("audio", ignoreCase = true)) {
                                NavLogger.e(this@MapActivity, "NavigationFragment method: ${m.name}")
                            }
                        }
                        val uiMethods = TmapUISDK::class.java.methods
                        for (m in uiMethods) {
                            if (m.name.contains("mute", ignoreCase = true) || m.name.contains("volume", ignoreCase = true) || m.name.contains("sound", ignoreCase = true) || m.name.contains("audio", ignoreCase = true)) {
                                NavLogger.e(this@MapActivity, "TmapUISDK method: ${m.name}")
                            }
                        }
                        
                        // Let's also check TmapUISDK.Companion methods just in case
                        val compMethods = TmapUISDK.Companion::class.java.methods
                        for (m in compMethods) {
                            if (m.name.contains("mute", ignoreCase = true) || m.name.contains("volume", ignoreCase = true) || m.name.contains("sound", ignoreCase = true) || m.name.contains("audio", ignoreCase = true)) {
                                NavLogger.e(this@MapActivity, "TmapUISDK.Companion method: ${m.name}")
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    // SDK 전체 API 노출 여부 확인용 전수 덤프 (필터 없음)
                    try {
                        fun dumpClass(tag: String, clazz: Class<*>) {
                            NavLogger.e(this@MapActivity, "===== $tag (${clazz.name}) =====")
                            for (m in clazz.methods) {
                                NavLogger.e(this@MapActivity, "$tag method: ${m.name}(${m.parameterTypes.joinToString { it.simpleName }}): ${m.returnType.simpleName}")
                            }
                            for (f in clazz.declaredFields) {
                                NavLogger.e(this@MapActivity, "$tag field: ${f.name} : ${f.type.simpleName}")
                            }
                            // 이 클래스가 갖고 있는 내부/중첩 클래스도 이름만 같이 보여줌 (콜백 데이터 클래스 파악용)
                            for (inner in clazz.declaredClasses) {
                                NavLogger.e(this@MapActivity, "$tag inner class: ${inner.name}")
                            }
                        }
                        dumpClass("NavigationFragment", NavigationFragment::class.java)
                        dumpClass("TmapUISDK", TmapUISDK::class.java)
                        dumpClass("TmapUISDK.Companion", TmapUISDK.Companion::class.java)

                        // 이름상 데이터를 담고 있을 가능성이 높은 클래스는, 패키지 경로 추측 대신
                        // 실제 메서드의 리턴/파라미터 타입에서 Class를 런타임으로 가져와서 덤프
                        try {
                            val getConfigMethod = TmapUISDK::class.java.methods.firstOrNull { it.name == "getRouteGuidanceConfig" }
                            getConfigMethod?.returnType?.let { dumpClass("RouteGuidanceConfig", it) }
                        } catch (e: Exception) { e.printStackTrace() }

                        try {
                            val setListenerMethod = NavigationFragment::class.java.methods.firstOrNull { it.name == "setNavigationScreenStateListener" }
                            setListenerMethod?.parameterTypes?.firstOrNull()?.let { dumpClass("NavigationScreenStateListener", it) }
                        } catch (e: Exception) { e.printStackTrace() }

                        // 검색결과/목적지가 좌측 HUD 패널에 가려지는 문제 해결용:
                        // 지도 패딩(setMapPadding 류) 또는 카메라 오프셋 API가
                        // NavigationFragment가 아니라 실제 지도 엔진 객체 쪽에 있을 가능성이 높아서
                        // getMapView()/getVsmMapView()의 리턴 타입도 같이 덤프해서 확인. #문제시 원복
                        try {
                            val getMapViewMethod = NavigationFragment::class.java.methods.firstOrNull { it.name == "getMapView" }
                            getMapViewMethod?.returnType?.let { dumpClass("MapEngine", it) }
                        } catch (e: Exception) { e.printStackTrace() }

                        try {
                            val getVsmMapViewMethod = NavigationFragment::class.java.methods.firstOrNull { it.name == "getVsmMapView" }
                            getVsmMapViewMethod?.returnType?.let { dumpClass("VsmSdkMapView", it) }
                        } catch (e: Exception) { e.printStackTrace() }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    startSafeDriveMode()
                    startUdpSenderService()
                    initKakaoSdkAndShowIdleMap()
                }
            }

            override fun onFail(errorCode: Int, errorMsg: String?) {
                runOnUiThread {
                    Toast.makeText(this@MapActivity, "Tmap SDK 초기화 실패: $errorMsg", Toast.LENGTH_LONG).show()
                }
            }

            override fun savedRouteInfoExists(dest: String?) {
                // 경로 안내 중이 아니므로 무시
            }
        })
    }

    private var lastKnownLat: Double? = null
    private var lastKnownLon: Double? = null

    private var lastSearchClickAt = 0L
    private val SEARCH_CLICK_DEBOUNCE_MS = 800L

    // 카카오 개발자콘솔 "네이티브 앱 키 > 키 해시"에 등록할 값을 로그로 남김.
    // keystore 파일/비밀번호 없이도, 실제 이 기기에 설치되어 서명 검증되는 앱 그대로의
    // 정확한 해시를 얻을 수 있음. (릴리즈 서명 앱을 설치해서 실행하면 릴리즈용 해시가,
    // 디버그 빌드로 실행하면 디버그용 해시가 나옴 - 콘솔엔 둘 다 등록 가능)
    @Suppress("DEPRECATION")
    private fun logKakaoKeyHashOnce() {
        try {
            val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            for (signature in info.signatures ?: emptyArray()) {
                val md = MessageDigest.getInstance("SHA")
                md.update(signature.toByteArray())
                val keyHash = Base64.encodeToString(md.digest(), Base64.NO_WRAP)
                NavLogger.d(this, "카카오 콘솔용 KeyHash(패키지=$packageName): $keyHash")
            }
        } catch (e: Exception) {
            NavLogger.e(this, "KeyHash 계산 실패: ${e.message}")
        }
    }

    // ===== 길안내(목적지 검색) UI =====
    private fun setupDestinationSearchUi() {
        binding.btnOpenSearch?.setOnClickListener {
            // 좌측 HUD 패널에 최근 검색 이력이 항상 보이도록 바뀌어서, 팝업 이력을
            // 따로 볼 필요가 없어짐. 원래 요청대로 1탭 = 바로 음성인식으로 복귀.
            // v1.8: 음성검색 진입 시 이전에 남아있던 검색이력 목록이 뜨는 문제 방지. #문제시 원복
            binding.lvSearchResults?.visibility = View.GONE
            binding.llSearchPanel?.visibility = View.VISIBLE
            startVoiceSearch()
        }
        binding.btnOpenSearch?.setOnLongClickListener {
            // v1.8: "티맵 화면 텍스트검색도 카카오 화면처럼 팝업으로 띄워달라"(8번) - 인라인
            // 하단바 대신 다이얼로그 방식으로 통일. #문제시 원복
            showTmapTextSearchDialog()
            true
        }
        binding.etDestination?.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) showSearchHistory()
        }
        // 텍스트로 직접 치고 싶을 때를 위해 입력창을 길게 누르면 키보드 포커스로 전환.
        binding.etDestination?.setOnLongClickListener {
            binding.etDestination?.requestFocus()
            true
        }
        binding.btnSearchClose?.setOnClickListener {
            binding.llSearchPanel?.visibility = View.GONE
        }
        binding.btnSearchGo?.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastSearchClickAt < SEARCH_CLICK_DEBOUNCE_MS) {
                return@setOnClickListener // 연타 방지 (로그에서 같은 쿼리 수십회 중복 호출되던 문제)
            }
            lastSearchClickAt = now
            val query = binding.etDestination?.text?.toString()?.trim().orEmpty()
            if (query.isNotEmpty()) {
                performDestinationSearch(query)
            }
        }
        binding.btnSearchGo?.setOnLongClickListener {
            promptForKakaoKeyThenSearch("") // 빈 쿼리로 열면 저장만 하고 검색은 안 함(취소 눌러도 됨)
            true
        }
        binding.btnVoiceSearch?.setOnClickListener {
            startVoiceSearch()
        }
        binding.btnStopGuidance?.setOnClickListener {
            stopGuidance()
        }
        binding.btnShareLog?.setOnClickListener {
            shareNavLog()
        }
    }

    // v1.8: 카카오 화면(showInPlaceSearchDialog)과 동일한 스타일의 팝업. #문제시 원복
    private fun showTmapTextSearchDialog() {
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
                if (q.isNotEmpty()) performDestinationSearch(q)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // 로그 파일을 이메일(jaeeok.cho@icloud.com)로 공유 - 회전되어 쌓여있던 로그까지 전부 한번에 보냄.
    // 공유해도 삭제되지 않음(앱 종료시에만 전체 삭제).
    private fun shareNavLog() {
        val result = NavLogger.buildShareIntent(this)
        if (result == null) {
            Toast.makeText(this, "저장된 로그가 없어. 먼저 검색을 시도해봐.", Toast.LENGTH_SHORT).show()
            return
        }
        val (intent, paths) = result
        shareLogLauncher.launch(Intent.createChooser(intent, "로그 공유 (${paths.size}개 파일)"))
    }

    // 1단계: SDK 안에 실제로 어떤 검색/경로 관련 메서드가 있는지 로그로 확인.
    // adb logcat | grep TmapNav 로 실제 메서드 이름/시그니처를 확인한 뒤,
    // performDestinationSearch()의 TODO 부분을 그 이름으로 교체하면 됨.
    private fun dumpNavigationApiCandidates() {
        // 이전엔 안전운행 모드 시작할 때마다(즉 앱 켤 때마다) 수백 줄짜리 API 서베이 덤프를
        // 무조건 다시 찍고 있었음. 이건 API 시그니처 확인용 1회성 진단이라 최초 1번만 하면 충분함.
        val prefs = getSharedPreferences("nav_diag", Context.MODE_PRIVATE)
        if (prefs.getBoolean("api_candidates_dumped", false)) return
        try {
            val keywords = listOf(
                "search", "poi", "destination", "route", "guide", "navi", "goal",
                "lane", "link", "road", "match", "position", "location", "gps"
            )
            fun dump(label: String, clazz: Class<*>) {
                for (m in clazz.methods) {
                    val n = m.name.lowercase()
                    if (keywords.any { n.contains(it) }) {
                        NavLogger.d(this@MapActivity, "$label: ${m.name}(${m.parameterTypes.joinToString { it.simpleName }}) -> ${m.returnType.simpleName}")
                    }
                }
            }
            dump("NavigationFragment", NavigationFragment::class.java)
            dump("TmapUISDK", TmapUISDK::class.java)
            dump("TmapUISDK.Companion", TmapUISDK.Companion::class.java)
            try {
                val sdkManagerClass = Class.forName("com.skt.tmap.engine.navigation.SDKManager")
                dump("SDKManager", sdkManagerClass)
                val companion = sdkManagerClass.getField("Companion").get(null)
                if (companion != null) {
                    dump("SDKManager.Companion", companion.javaClass)
                }
            } catch (e: Exception) {
                NavLogger.e(this, "SDKManager dump error: ${e.message}")
            }
        } catch (e: Exception) {
            NavLogger.e(this, "dumpNavigationApiCandidates error: ${e.message}")
        }
    }

    private val httpClient by lazy { OkHttpClient() }

    // 옵션 A: 카카오 REST API 키를 사용자별로 입력받아 SharedPreferences에 저장.
    // (앱에 고정 키를 박아넣으면 모든 설치자가 같은 키/같은 할당량을 공유하게 되는 문제 방지)
    // ===== 목적지 검색 이력 =====
    // v2.1: 이력 클릭 시 "재검색"이 아니라 "그 장소로 바로 길안내"가 되려면 좌표까지
    // 저장해야 함. 그래서 검색어(String)가 아니라 실제 선택된 장소(HistoryEntry)를 저장.
    // 기존엔 검색 시도 시점(performDestinationSearch 진입 시)에 저장했는데, 이제는
    // "사용자가 실제로 어떤 결과를 골랐을 때"만 저장함(재검색 방지 목적과도 맞음). #문제시 원복
    private val SEARCH_HISTORY_KEY = "search_history_json"
    private val SEARCH_HISTORY_MAX = 15

    data class HistoryEntry(val name: String, val addr: String, val lat: Double, val lon: Double)

    private fun getSearchHistory(): List<HistoryEntry> {
        val sharedPref = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
        val raw = sharedPref.getString(SEARCH_HISTORY_KEY, "[]") ?: "[]"
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                HistoryEntry(
                    o.optString("name"),
                    o.optString("addr"),
                    o.optDouble("lat"),
                    o.optDouble("lon")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveSearchHistory(entry: HistoryEntry) {
        if (entry.name.isBlank()) return
        val sharedPref = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
        val current = getSearchHistory().toMutableList()
        current.removeAll { it.name == entry.name && it.lat == entry.lat && it.lon == entry.lon }
        current.add(0, entry)
        while (current.size > SEARCH_HISTORY_MAX) current.removeAt(current.size - 1)
        val arr = org.json.JSONArray()
        current.forEach {
            arr.put(
                org.json.JSONObject()
                    .put("name", it.name)
                    .put("addr", it.addr)
                    .put("lat", it.lat)
                    .put("lon", it.lon)
            )
        }
        sharedPref.edit().putString(SEARCH_HISTORY_KEY, arr.toString()).apply()
        updateRecentSearchPanel()
    }

    private fun deleteSearchHistoryEntry(entry: HistoryEntry) {
        val sharedPref = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
        val current = getSearchHistory().toMutableList()
        current.removeAll { it.name == entry.name && it.lat == entry.lat && it.lon == entry.lon }
        val arr = org.json.JSONArray()
        current.forEach {
            arr.put(
                org.json.JSONObject()
                    .put("name", it.name)
                    .put("addr", it.addr)
                    .put("lat", it.lat)
                    .put("lon", it.lon)
            )
        }
        sharedPref.edit().putString(SEARCH_HISTORY_KEY, arr.toString()).apply()
        updateRecentSearchPanel()
    }

    private fun clearSearchHistory() {
        val sharedPref = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
        sharedPref.edit().putString(SEARCH_HISTORY_KEY, "[]").apply()
        updateRecentSearchPanel()
    }

    // 좌측 HUD 패널(가로모드)에 최근 검색 1개만 상시로 보여줌. 예전엔 3개를 상시 표시했는데
    // 이력이 쌓일수록 이 패널 높이가 늘어나서 하단 "앱 종료" 버튼이 화면 밖으로 밀려나는
    // 문제가 있었음. 나머지는 "+ 더보기" 눌러서 전체 다이얼로그(showFullSearchHistoryDialog)로
    // 확인하는 구조라 기능 손실은 없음. #문제시 원복
    // 세로모드/이 뷰가 없는 레이아웃에서는 findViewById 결과가 null이라 안전하게 아무 것도 안 함.
    private fun updateRecentSearchPanel() {
        val panel = findViewById<View?>(resources.getIdentifier("llRecentSearchPanel", "id", packageName)) ?: return
        val history = getSearchHistory()
        val rows = listOf(
            findViewById<android.widget.TextView?>(resources.getIdentifier("tvRecentSearch1", "id", packageName))
        )
        if (history.isEmpty()) {
            panel.visibility = View.GONE
            return
        }
        panel.visibility = View.VISIBLE
        rows.forEachIndexed { i, tv ->
            val entry = history.getOrNull(i)
            if (tv == null) return@forEachIndexed
            if (entry == null) {
                tv.visibility = View.GONE
            } else {
                tv.visibility = View.VISIBLE
                tv.text = entry.name
                tv.setOnClickListener {
                    // 예전엔 클릭해도 화면상 반응이 전혀 안 보여서 사용자가 연타 -> 로그에서
                    // 같은 검색이 2초 안에 12번씩 중복 호출되는 게 확인됨(requestRoute NPE와 별개 문제).
                    // 탭 즉시 시각적 피드백 + 짧은 시간 재클릭 방지.
                    tv.isEnabled = false
                    tv.alpha = 0.5f
                    // v2.1: 재검색이 아니라 저장된 좌표로 바로 길안내 시작 (4번)
                    startKakaoOverlayGuidance(entry.name, entry.lat, entry.lon)
                    tv.postDelayed({
                        tv.isEnabled = true
                        tv.alpha = 1.0f
                    }, 1500)
                }
            }
        }
        findViewById<View?>(resources.getIdentifier("btnMoreHistory", "id", packageName))?.setOnClickListener {
            showFullSearchHistoryDialog()
        }
    }

    // "+ 더보기" 클릭 시 전체 이력을 중앙 다이얼로그로 표시.
    // v2.1: 전체삭제 버튼 + 항목별 개별삭제(✕) 추가, 클릭 시 재검색 대신 바로 길안내. #문제시 원복
    private fun showFullSearchHistoryDialog() {
        var history = getSearchHistory()
        if (history.isEmpty()) {
            Toast.makeText(this, "검색 이력이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }

        lateinit var dialog: android.app.AlertDialog
        lateinit var listView: android.widget.ListView

        fun buildAdapter() = object : android.widget.BaseAdapter() {
            override fun getCount() = history.size
            override fun getItem(position: Int) = history[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val entry = history[position]
                val row = android.widget.LinearLayout(this@MapActivity).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    setBackgroundColor(android.graphics.Color.parseColor("#181818"))
                    setPadding(24, 24, 12, 24)
                }
                val nameText = android.widget.TextView(this@MapActivity).apply {
                    text = if (entry.addr.isNotBlank()) "${entry.name}\n${entry.addr}" else entry.name
                    setTextColor(android.graphics.Color.WHITE)
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                }
                val deleteText = android.widget.TextView(this@MapActivity).apply {
                    text = "✕"
                    setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
                    setPadding(24, 0, 24, 0)
                    setOnClickListener {
                        deleteSearchHistoryEntry(entry)
                        history = getSearchHistory()
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
                    .setPositiveButton("삭제") { _, _ -> clearSearchHistory() }
                    .setNegativeButton("취소", null)
                    .show()
            }
            .setNegativeButton("닫기", null)
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            val picked = history[position]
            dialog.dismiss()
            // v2.1: 재검색이 아니라 저장된 좌표로 바로 길안내 시작 (4번)
            startKakaoOverlayGuidance(picked.name, picked.lat, picked.lon)
        }
        dialog.show()
        // 다이얼로그 창 배경 자체도 명시적으로 지정 (테마 상속으로 까맣게 뜨는 것 방지)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#212121")))
    }

    // v2.1: 인라인 리스트(lvSearchResults) 대신 카카오 화면과 동일한 팝업 다이얼로그 방식으로
    // 변경 - 인라인 리스트가 지도 화면 절반 가까이 가리던 문제(6번) 해결. 클릭 시 재검색 대신
    // 바로 길안내 시작(4번). #문제시 원복
    private fun showSearchHistory() {
        val history = getSearchHistory()
        binding.lvSearchResults?.visibility = View.GONE
        if (history.isEmpty()) return

        val listView = android.widget.ListView(this)
        listView.adapter = darkTextAdapter(history.map { if (it.addr.isNotBlank()) "${it.name}\n${it.addr}" else it.name })
        listView.setBackgroundColor(android.graphics.Color.parseColor("#181818"))
        listView.divider = android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#333333"))
        listView.dividerHeight = 1

        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("최근 검색")
            .setView(listView)
            .setNegativeButton("닫기", null)
            .create()
        listView.setOnItemClickListener { _, _, position, _ ->
            val picked = history[position]
            dialog.dismiss()
            startKakaoOverlayGuidance(picked.name, picked.lat, picked.lon)
        }
        dialog.setOnDismissListener { binding.etDestination?.clearFocus() }
        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#212121")))
    }

    // v1.7: 검색결과/이력 목록에 android.R.layout.simple_list_item_1을 그대로 쓰면 앱 기본
    // 테마(Light)의 글자색(검정)이 어두운 패널 배경(#EE111111 등) 위에서 안 보이는 문제가
    // 있었음(재억 지적: "텍스트 검색 시 뒷 배경색과 동일해서 텍스트 안보임"). 글자색을
    // 명시적으로 흰색으로 지정하는 어댑터로 교체. #문제시 원복
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

    // v1.6: 속도가 도로 제한속도의 110%를 넘으면 경고음 - 기본 꺼짐, 이 다이얼로그에서 토글. #문제시 원복
    private fun showAppSettingsDialog() {
        val pref = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
        val checkBox = android.widget.CheckBox(this).apply {
            text = "속도 10% 초과 시 경고음"
            isChecked = pref.getBoolean("over_speed_warning_enabled", false)
            setTextColor(android.graphics.Color.WHITE)
            setPadding(40, 30, 40, 30)
        }
        val camCheckBox = android.widget.CheckBox(this).apply {
            text = "이동식카메라 근처 자동 감속 (당근파일럿 외 fork용)"
            isChecked = pref.getBoolean("mobile_cam_slowdown_enabled", false)
            setTextColor(android.graphics.Color.WHITE)
            setPadding(40, 0, 40, 30)
        }
        // v2.0: 지도 핀치줌/드래그를 원하는 사용자를 위한 터치 잠금 해제 옵션.
        // 기본값은 계속 잠금(false=잠금 유지)이고, 체크하면 지도 터치가 풀림. #문제시 원복
        val unlockMapTouchCheckBox = android.widget.CheckBox(this).apply {
            text = "지도 터치 잠금 해제 (핀치줌/드래그 허용)"
            isChecked = pref.getBoolean("map_touch_unlocked", false)
            setTextColor(android.graphics.Color.WHITE)
            setPadding(40, 0, 40, 30)
        }
        // v2.1: "이동식카메라는 감속을 안 했으면 좋겠다" - 위 옵션(다른 fork에 감속을
        // 추가해주는 옵션)과는 정반대 성격. 이건 fork 종류와 무관하게 이동식카메라
        // 감속 자체를 원천 차단하는 옵션 - cam_type을 openpilot에 아예 안 보내버리면
        // 당근파일럿이든 다른 fork든 이동식카메라로 인식을 못 해서 감속이 안 걸림. #문제시 원복
        val disableMobileCamCheckBox = android.widget.CheckBox(this).apply {
            text = "이동식카메라 감속 끄기 (모든 openpilot fork 공통)"
            isChecked = pref.getBoolean("mobile_cam_slowdown_disabled", false)
            setTextColor(android.graphics.Color.WHITE)
            setPadding(40, 0, 40, 30)
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(checkBox)
            addView(camCheckBox)
            addView(disableMobileCamCheckBox)
            addView(unlockMapTouchCheckBox)
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("앱 설정")
            .setView(container)
            .setPositiveButton("저장") { _, _ ->
                pref.edit()
                    .putBoolean("over_speed_warning_enabled", checkBox.isChecked)
                    .putBoolean("mobile_cam_slowdown_enabled", camCheckBox.isChecked)
                    .putBoolean("mobile_cam_slowdown_disabled", disableMobileCamCheckBox.isChecked)
                    .putBoolean("map_touch_unlocked", unlockMapTouchCheckBox.isChecked)
                    .apply()
                applyMapTouchLockState(unlockMapTouchCheckBox.isChecked)
                Toast.makeText(this, "저장됨", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // v2.0: 잠금 해제 상태면 오버레이가 터치를 그냥 통과시켜서(false 리턴) 지도가 핀치줌/드래그를 받게 함.
    // 잠금 상태(기본값)면 오버레이가 계속 터치를 소비(true)해서 지도 조작을 차단. #문제시 원복
    private fun applyMapTouchLockState(unlocked: Boolean) {
        if (unlocked) {
            binding.vTouchLockOverlay?.setOnTouchListener { _, _ -> false }
        } else {
            binding.vTouchLockOverlay?.setOnTouchListener { _, _ -> true }
        }
    }

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

    private fun getUserKakaoKey(): String {
        return getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
            .getString("kakao_rest_api_key", "") ?: ""
    }

    private fun saveUserKakaoKey(key: String) {
        getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
            .edit().putString("kakao_rest_api_key", key).apply()
    }

    private fun promptForKakaoKeyThenSearch(query: String) {
        // v1.9: 여기만 setTextColor를 안 넣어놔서 검은 배경에 검은 글씨로 남아있던
        // 진짜 원인 - 다른 다이얼로그처럼 흰 글씨로 명시. #문제시 원복
        val input = android.widget.EditText(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.parseColor("#AAAAAA"))
            hint = "카카오 REST API 키 (카카오 디벨로퍼스 > 내 앱 > REST API 키)"
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("카카오 로컬 검색 API 키 입력")
            .setMessage("목적지 검색을 쓰려면 본인의 카카오 REST API 키가 필요해.\n각자 본인 키를 써야 검색 할당량을 나눠 쓰지 않아.\n(https://developers.kakao.com 에서 무료 발급)")
            .setView(input)
            .setPositiveButton("저장하고 검색") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isNotBlank()) {
                    saveUserKakaoKey(key)
                    if (query.isNotBlank()) {
                        performDestinationSearch(query)
                    } else {
                        Toast.makeText(this, "카카오 키 저장됨", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // 검색 실행: 카카오 로컬 API로 텍스트→좌표 변환 후 Tmap SDK로 경로요청/주행시작.
    // WayPoint 생성자/필드명이 SDK 문서 없이 리플렉션으로 확인된 게 아니라서,
    // 첫 실행 시 로그(NavLogger)에 시도한 생성자/실패 사유가 다 남게 해뒀음.
    // 실패하면 로그 캡처해서 다시 보내주면 정확한 시그니처로 고칠 수 있음.
    private fun performDestinationSearch(query: String) {
        binding.tvSearchStatus?.text = "검색 중: $query"
        NavLogger.d(this, "performDestinationSearch query=$query")
        binding.lvSearchResults?.visibility = View.GONE
        // v2.1: 이력은 "검색 시도"가 아니라 "실제로 고른 결과"에만 저장(아래 다이얼로그 클릭 시). #문제시 원복

        val restKey = getUserKakaoKey()
        if (restKey.isBlank()) {
            NavLogger.d(this, "사용자 카카오 키 없음 - 입력 다이얼로그 표시")
            promptForKakaoKeyThenSearch(query)
            return
        }

        val url = "https://dapi.kakao.com/v2/local/search/keyword.json?query=" +
            java.net.URLEncoder.encode(query, "UTF-8")
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "KakaoAK $restKey")
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                NavLogger.e(this@MapActivity, "카카오 검색 요청 실패: ${e.message}")
                runOnUiThread { binding.tvSearchStatus?.text = "검색 실패(네트워크): ${e.message}" }
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        val body = it.body?.string()
                        // 401/403이면 키 종류/헤더 형식 문제일 확률이 매우 높음.
                        NavLogger.e(this@MapActivity, "카카오 검색 실패 code=${it.code} body=$body")
                        runOnUiThread { binding.tvSearchStatus?.text = "검색 실패(${it.code}) - REST 키/헤더 확인" }
                        return@use
                    }
                    val json = JSONObject(it.body?.string() ?: "{}")
                    val documents = json.optJSONArray("documents")
                    if (documents == null || documents.length() == 0) {
                        NavLogger.d(this@MapActivity, "카카오 검색 결과 없음: query=$query")
                        runOnUiThread { binding.tvSearchStatus?.text = "검색 결과 없음: $query" }
                        return@use
                    }
                    // v1.7: "S Oil 검색하면 평택시 지산동 Soil 00점, 000점처럼 여러개 나와야 하는데
                    // 첫번째 결과로 바로 꽂힘" 지적 - 항상 documents[0]으로 바로 길안내를 시작하던
                    // 걸 없애고, 결과 목록을 보여준 뒤 사용자가 직접 골라서 시작하도록 변경. #문제시 원복
                    val hits = (0 until documents.length()).map { idx ->
                        val d = documents.getJSONObject(idx)
                        HistoryEntry(
                            d.optString("place_name", query),
                            d.optString("road_address_name", d.optString("address_name", "")),
                            d.optDouble("y"),
                            d.optDouble("x")
                        )
                    }
                    NavLogger.d(this@MapActivity, "카카오 검색 결과 ${hits.size}건: query=$query")

                    runOnUiThread {
                        binding.llSearchPanel?.visibility = View.VISIBLE
                        binding.tvSearchStatus?.text = "검색 결과 ${hits.size}건 - 목적지를 선택하세요"
                        // v2.1: 카카오 화면과 동일하게 결과 목록을 인라인 대신 팝업 다이얼로그로
                        // 표시 - 인라인 리스트가 지도 화면을 가리던 문제(1·6번) 해결. #문제시 원복
                        val listView = android.widget.ListView(this@MapActivity)
                        val labels = hits.map { h -> if (h.addr.isNotBlank()) "${h.name}\n${h.addr}" else h.name }
                        listView.adapter = darkTextAdapter(labels)
                        listView.setBackgroundColor(android.graphics.Color.parseColor("#181818"))
                        listView.divider = android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#333333"))
                        listView.dividerHeight = 1
                        val dialog = android.app.AlertDialog.Builder(this@MapActivity, android.R.style.Theme_Material_Dialog_Alert)
                            .setTitle("검색 결과 ${hits.size}건")
                            .setView(listView)
                            .setNegativeButton("취소", null)
                            .create()
                        listView.setOnItemClickListener { _, _, position, _ ->
                            val picked = hits[position]
                            dialog.dismiss()
                            binding.tvSearchStatus?.text = "찾음: ${picked.name} (${picked.lat}, ${picked.lon}) - 경로요청 시도"
                            saveSearchHistory(picked)
                            startKakaoOverlayGuidance(picked.name, picked.lat, picked.lon)
                        }
                        dialog.show()
                        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#212121")))
                    }
                }
            }
        })
    }

    // ===== 카카오내비 오버레이 길안내 =====
    // 코드에 하드코딩하면 TmapNda 쓰는 모든 사람이 재억 개인 카카오 앱 쿼터를 나눠쓰게 됨
    // (Tmap 하루1회 제한과 같은 문제 반복). 그래서 각자 본인 카카오 콘솔에서 발급받은
    // 네이티브 앱 키를 기기별 설정값으로 넣도록 변경. #문제시 원복
    // 설정 안 했으면 재억 개인키로 폴백(당장 테스트용) - 나중엔 이 폴백도 빼는 게 맞음.
    private fun getKakaoNativeAppKey(): String {
        val prefs = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
        val userKey = prefs.getString("kakao_native_app_key", "") ?: ""
        return userKey.ifBlank { "656bfa63fb6c4376040f2a119a5cd8b9" }
    }
    private var knsdkInitialized = false
    private var kakaoGuidanceDelegate: KakaoGuidanceDelegate? = null
    private var kakaoNaviView: KNNaviView? = null

    // KNNaviView는 SDK 초기화가 끝나기 전에 생성자가 돌면 죽는 것으로 추정됨 - #문제시 원복
    // 그래서 레이아웃엔 ViewStub만 두고, SDK 초기화 성공한 뒤에만 실제로 inflate함.
    // idle map(경로 없음, 기본 지도)에서만 씀 - 재사용해도 큰 문제 없었음.
    private fun ensureKakaoNaviViewInflated(): KNNaviView? {
        if (kakaoNaviView == null) {
            kakaoNaviView = binding.naviViewStub?.inflate() as? KNNaviView
            kakaoNaviView?.let { hookSurfaceViewLifecycle(it) }
        }
        return kakaoNaviView
    }

    // v1.0.62~68 시절엔 화면 전환은 되고 턴바이턴 음성/텍스트 안내도 계속 갱신됐는데
    // (지도 카메라/마커만 안 움직이는 순수 렌더링 버그), 이후 실제 길안내 시작 때마다
    // 같은 KNNaviView 인스턴스를 stop()/initWithGuidance()로 계속 재사용하다 보니
    // 내부적으로 이전 trip/guidance에 묶인 상태가 누적되어 오히려 더 심하게(TBT까지)
    // 멈추는 쪽으로 퇴보한 것으로 의심됨. 카카오 공식 iOS 샘플도 매 안내 시작마다
    // 새 KNNaviView 인스턴스를 만들어서 붙이는 방식이라 그 패턴을 그대로 따름 -
    // 실제 "길안내 시작"(requestKakaoRoute 성공 시점)에서만 이 함수를 쓰고,
    // 기존 뷰가 있으면 완전히 제거하고 새 인스턴스로 교체함. #문제시 원복
    private fun recreateKakaoNaviView(): KNNaviView? {
        return try {
            kakaoNaviView?.let { old ->
                try {
                    (old.parent as? android.view.ViewGroup)?.removeView(old)
                } catch (e: Exception) {
                    NavLogger.e(this, "기존 KNNaviView 제거 중 예외: ${e.message}")
                }
            }
            // 새 인스턴스라 이전 뷰에 붙였던 후킹/델리게이트 상태는 의미 없음 - 다시 붙여야 함.
            kakaoNaviViewExitHookAttached = false
            val fresh = KNNaviView(this)
            val params = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            binding.flKakaoOverlay?.addView(fresh, 0, params)
            hookSurfaceViewLifecycle(fresh)
            kakaoNaviView = fresh
            NavLogger.d(this, "recreateKakaoNaviView: 새 KNNaviView 인스턴스 생성/부착 완료")
            fresh
        } catch (e: Exception) {
            NavLogger.e(this, "recreateKakaoNaviView 예외: ${e.message}")
            null
        }
    }

    // naviViewGuideState/naviViewScreenState 델리게이트는 정상적으로 OnRouteGuide로 바뀌는데
    // 화면은 전혀 안 바뀌는 증상. 이전엔 KNMapView(SurfaceView)에 setZOrderOnTop(true)를
    // 강제로 걸어봤는데 - 실제로 적용은 됐지만(로그 확인) 화면은 여전히 안 바뀌었고,
    // 오히려 안내종료 버튼처럼 KNNaviView 내부의 일반 View들까지 서페이스 밑에 깔려서
    // 안 보이는 부작용만 생겼음. 즉 "다른 서페이스와 경쟁해서 밑에 깔린다"가 원인이
    // 아니라 KNMapView 자체의 GL 서페이스가 애초에 유효한 프레임을 못 그리고 있을
    // 가능성이 높음 - 그래서 setZOrderOnTop은 되돌리고, 대신 SurfaceHolder.Callback을
    // 리플렉션으로 얹어서 surfaceCreated/surfaceChanged/surfaceDestroyed가 실제로
    // 호출되는지, 언제/몇 번 호출되는지부터 순수 진단용으로 로그만 남김. #문제시 원복
    private fun hookSurfaceViewLifecycle(root: View) {
        try {
            if (root is android.view.SurfaceView) {
                NavLogger.d(this, "hookSurfaceViewLifecycle: SurfaceView 발견 (${root.javaClass.name}), holder.isCreating=${root.holder?.surface?.isValid}")
                root.holder?.addCallback(object : android.view.SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                        NavLogger.d(this@MapActivity, "[SurfaceHolder ${root.javaClass.simpleName}] surfaceCreated")
                    }
                    override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {
                        NavLogger.d(this@MapActivity, "[SurfaceHolder ${root.javaClass.simpleName}] surfaceChanged format=$format ${width}x$height")
                    }
                    override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                        NavLogger.d(this@MapActivity, "[SurfaceHolder ${root.javaClass.simpleName}] surfaceDestroyed")
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

    // KNNaviView 내장 UI엔 자체 "안내종료" 버튼이 있는데, 그건 우리 stopKakaoGuidanceAndReturnToTmap()과
    // 연결이 안 돼있어서 눌러도 flKakaoOverlay가 안 걷히는 문제가 있었음(카카오 화면만 내부적으로
    // 종료되고 우리 오버레이는 그대로 남음). KNSDK 버전에 따라 상태 델리게이트 인터페이스/메서드명이
    // 조금씩 다를 수 있어서(예: KNNaviView_StateDelegate), 리플렉션으로 "...Delegate"를 받는 setter를
    // 찾아서 프록시를 걸고, 콜백 인자에 "exit"/"end"/"종료" 계열 값이 보이면 오버레이를 닫음.
    // 정상적으로 등록 안 되더라도 앱 동작엔 영향 없음(그냥 훅이 안 걸릴 뿐). #문제시 원복
    private var kakaoNaviViewExitHookAttached = false
    private fun attachKakaoNaviViewExitHook(naviView: KNNaviView) {
        if (kakaoNaviViewExitHookAttached) return
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

                    // 하이브리드 전환: OnRouteGuide면 카카오 화면/음성 사용, OnSafetyGuide(경로 없는
                    // 배경 안전운행)로 떨어지면 티맵 화면으로 복귀(오버레이 감춤) + 음성도 티맵으로.
                    // (idle map을 기본 HUD로 쓰는 실험은 롤백함 - 재사용된 naviView가 경로모드로
                    // 전환될 때 화면이 안 바뀌는 문제가 반복 확인됨) #문제시 원복
                    if (method.name == "naviViewGuideState") {
                        if (argsText.contains("OnRouteGuide", true) && !isKakaoGuidanceExplicitlyStopped) {
                            isKakaoRouteGuideActive = true
                            runOnUiThread { reassertKakaoOverlayVisible() }
                        } else if (argsText.contains("OnSafetyGuide", true)) {
                            isKakaoRouteGuideActive = false
                            runOnUiThread {
                                applyMuteState()
                                if (isTmapMuted) {
                                    TmapUISDK.setVolume(this@MapActivity, 0)
                                } else {
                                    // v1.7: 여기 하드코딩된 100이 "안내종료 후 다시 100%로 튐" 버그의
                                    // 진짜 원인이었음. 사용자가 맞춰둔 값(unmuteTmapVolume)으로 복원. #문제시 원복
                                    unmuteTmapVolume()
                                }
                                hideKakaoOverlay()
                            }
                        }
                    }

                    if (argsText.contains("exit", true) || argsText.contains("end", true) ||
                        method.name.contains("exit", true) || method.name.contains("finish", true)
                    ) {
                        isKakaoGuidanceExplicitlyStopped = true
                        runOnUiThread { stopKakaoGuidanceAndReturnToTmap() }
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
            kakaoNaviViewExitHookAttached = true
        } catch (e: Exception) {
            NavLogger.e(this, "KNNaviView exit hook 예외: ${e.message}")
        }
    }

    private fun hideKakaoOverlay() {
        isKakaoGuidanceStarting = false
        runOnUiThread {
            binding.flKakaoOverlay?.visibility = View.GONE
        }
    }

    // 로그 분석 결과: 같은(또는 다른) 목적지를 짧은 간격으로 여러 번 재검색/재클릭하면
    // 매번 KNSDK.makeTripWithStart + 새 KakaoGuidanceDelegate 생성 + initWithGuidance()를
    // 처음부터 다시 실행했음. 이전 호출이 아직 화면에 실제로 반영되기 전에 새 호출이
    // 덮어써버려서, 중간의 시도들은 "[카카오안내] 시작됨" 콜백이 영영 안 오고 사실상
    // 버려짐 - 그래서 "화면 전환은 안 되고 음성만 나옴" 상태가 계속되다가, 마지막으로
    // 누른 시도만 살아남아 그제서야 전환되는 것으로 확인됨(로그의 여수시청/부산광역시청
    // 반복 케이스). 하나의 안내 시작 요청이 실제로 끝나기(성공 or 실패) 전까지는
    // 새 요청을 무시하도록 가드. #문제시 원복
    private var isKakaoGuidanceStarting = false
    // 이미 활성 중인 카카오 안내 세션이 있는지 추적. 기존엔 새 목적지를 검색할 때
    // 이전 안내 세션을 stop() 하지 않은 채로 initWithGuidance()를 또 호출했는데,
    // 로그 분석 결과 이 경우 "경로요청 성공"까지는 뜨지만 guidanceGuideStarted
    // 콜백이 영영 안 오는 경우가 확인됨(SDK 내부적으로 기존 세션과 충돌하는 것으로
    // 추정). 그래서 새로 시작하기 전에 기존 세션이 있으면 먼저 stop() 하도록 수정. #문제시 원복
    private var isKakaoGuidanceActive = false
    // makeTripWithStart()는 비동기 콜백이라, 콜백이 돌아오기 전에 사용자가 검색을
    // 재탭하면 stop()과 initWithGuidance()가 서로 다른 요청의 콜백과 뒤섞여 레이스가
    // 나던 것으로 의심됨(화면 전환 안 되던 증상과 연관). 요청 진행 중엔 재탭 무시. #문제시 원복
    private var isRequestingKakaoRoute = false

    // 실제 "경로 안내 중"인지(OnRouteGuide) vs "경로 없는 배경 안전운행"(OnSafetyGuide)인지 구분.
    // true일 때만 카카오 오버레이/음성을 쓰고, false면 티맵 화면+티맵 자체 안전운행 음성으로 되돌림.
    // (카메라/방지턱/구간단속 등 안내음은 세이프티모드에서도 티맵 걸 쓰기 위한 하이브리드 스위치) #문제시 원복
    private var isKakaoRouteGuideActive = false

    // "안내 종료" 눌렀을 때(exit/end 감지) 이후로, KNSDK가 내부적으로 종료 절차를 밟으며
    // naviViewGuideState(OnRouteGuide) 등을 한 번 더 흘려보내는 경우가 있어서, 위 하이브리드
    // 로직이 그걸 "다시 경로안내 시작됨"으로 오인해 오버레이를 재표시 -> 곧바로 종료가 실제
    // 처리되며 다시 사라짐, 이 과정이 반복되어 "떴다가 없어졌다가" 깜빡이는 문제가 있었음.
    // 명시적 종료 이후엔 새 안내가 진짜로 시작되기(startKakaoOverlayGuidance) 전까지
    // OnRouteGuide 재표시를 무시하도록 가드. #문제시 원복
    private var isKakaoGuidanceExplicitlyStopped = false

    private fun stopKakaoGuidanceAndReturnToTmap() {
        try {
            KNSDK.sharedGuidance()?.stop()
        } catch (e: Exception) {
            NavLogger.e(this, "카카오 안내 중지 예외: ${e.message}")
        }
        isKakaoGuidanceActive = false
        isKakaoGuidanceStarting = false
        isKakaoRouteGuideActive = false
        // idle map(카카오 지도를 기본 HUD로) 실험 롤백 - 다시 원래대로 오버레이를
        // 감추고 티맵 화면으로 복귀. 사용자 음소거 설정도 원복. #문제시 원복
        applyMuteState()
        if (isTmapMuted) {
            TmapUISDK.setVolume(this@MapActivity, 0)
        } else {
            // v1.7: 여기도 하드코딩된 100이 원인 - 저장된 사용자 볼륨으로 복원. #문제시 원복
            unmuteTmapVolume()
        }
        hideKakaoOverlay()
    }

    // 검색 성공 후 오버레이를 띄우기 직전에 호출. 검색창에 포커스가 남아있으면 소프트키보드가
    // 오버레이 위를 계속 가리고 있어서 "화면 전환이 안 된 것처럼" 보였음(실제론 전환은 됐는데
    // 키보드에 가려서 안 보였던 것). 최근 검색 목록 탭 시엔 애초에 키보드 포커스가 없어서
    // 이 문제가 없었음. #문제시 원복
    private fun dismissKeyboardAndSearchPanel() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        currentFocus?.let { imm?.hideSoftInputFromWindow(it.windowToken, 0) }
        binding.etDestination?.clearFocus()
        binding.llSearchPanel?.visibility = View.GONE
    }

    // flKakaoOverlay를 VISIBLE + 최상위로 강제. 검색 클릭 시점 1회로는, 실제 KNSDK
    // 안내가 준비되는 3~5초 사이 어딘가에서 다른 뷰(예: 소프트키보드 잔상, 검색패널
    // 리레이아웃 등)에 의해 z-order/가시성이 도로 밀리는 경우가 있는 것으로 보임.
    // (로그상 guidanceGuideStarted 콜백은 이미 왔는데도 화면은 안 넘어가 있다가
    // 재탭 시점에야 실제로 보이는 패턴 확인됨). 그래서 탭 시점뿐 아니라 안내가
    // 실제로 시작되는 시점(guidanceGuideStarted)에도 한 번 더 강제 재적용. #문제시 원복
    private fun reassertKakaoOverlayVisible() {
        runOnUiThread {
            binding.flKakaoOverlay?.apply {
                visibility = View.VISIBLE
                bringToFront()
                requestLayout()
                invalidate()
            }
        }
    }

    // ============================================================
    // 앱 시작 시점부터 카카오 지도를 기본 HUD로 사용하기 위한 실험적 기능.
    // 카카오 개발자포럼(devtalk.kakao.com/t/android-knsdk-safetiesonguide)에
    // KNNaviView.initWithGuidance(guidance, trip=null, ...)로 "경로 없이 지도+
    // 안전정보만" 표시하는 방식이 언급됨 - 공식 확정 답변은 아니고 다른 개발자의
    // 질문글이라 실기기 검증 필요. 안 되면 즉시 롤백 가능하도록 기존 흐름은
    // 그대로 보존하고 이 함수만 추가. #문제시 원복
    // guidance(guideState/route/safety/voice/cits 전부)에 우리 델리게이트를 등록.
    // 기존엔 목적지 검색해서 실제 길안내를 시작할 때만 이 델리게이트를 붙였는데,
    // 그러면 앱 기본 화면(카카오 idle map, 경로 없음)에는 음성 게이트(shouldPlayVoiceGuide)가
    // 아예 안 걸려있어서 "기본 안내음이 카카오로 나옴" 문제가 있었음. 이제 idle map을 처음
    // 띄우는 시점에도 반드시 이 함수로 델리게이트를 등록해서, 앱 켜진 순간부터
    // "경로 없으면 티맵 음성 / 실제 길안내 중에만 카카오 음성" 게이트가 항상 걸려있게 함.
    // 델리게이트/훅은 한 번만 만들고 재사용(중복 생성 금지 - 과거 버그 원인). #문제시 원복
    private fun ensureKakaoGuidanceDelegateAttached(guidance: KNGuidance, naviView: KNNaviView) {
        attachKakaoNaviViewExitHook(naviView)
        val delegate = kakaoGuidanceDelegate ?: KakaoGuidanceDelegate(
            this,
            onGuideEnded = { stopKakaoGuidanceAndReturnToTmap() },
            onGuideStarted = {
                isKakaoGuidanceStarting = false
                isKakaoGuidanceActive = true
                isKakaoRouteGuideActive = true
                reassertKakaoOverlayVisible()
                // 카카오 실제 길안내 음성과 티맵 음성이 동시에 나오는 걸 막기 위해
                // 안내 시작 시점엔 사용자의 티맵 음소거 설정과 무관하게 강제로 낮춤.
                // 사용자가 저장한 isTmapMuted 값 자체는 안 바꿈 - 안내 종료하면
                // showKakaoIdleMap()에서 그 설정대로 원복. #문제시 원복
                TmapUISDK.setVolume(this@MapActivity, 0)
            },
            isRouteGuideActive = { isKakaoRouteGuideActive }
        ).also { kakaoGuidanceDelegate = it }
        // naviView는 검색/재검색/idle전환마다 재사용되거나 새로 만들어질 수 있어서,
        // 델리게이트 콜백을 naviView로 릴레이하려면 항상 최신 참조로 갱신해줘야 함. #문제시 원복
        delegate.naviView = naviView
        guidance.guideStateDelegate = delegate
        guidance.routeGuideDelegate = delegate
        guidance.safetyGuideDelegate = delegate
        guidance.voiceGuideDelegate = delegate
        guidance.citsGuideDelegate = delegate
        guidance.locationGuideDelegate = delegate
    }

    private fun showKakaoIdleMap() {
        runOnUiThread {
            // 카카오 안내 중엔 강제로 티맵 음성을 낮췄는데, idle(경로 없음) 상태로 돌아오면
            // 사용자가 설정한 원래 음소거 상태로 복원. #문제시 원복
            applyMuteState()
            if (isTmapMuted) {
                TmapUISDK.setVolume(this@MapActivity, 0)
            }
            // idle(경로 없음) 상태이므로 음성 게이트도 티맵 쪽으로.
            isKakaoRouteGuideActive = false
            val naviView = ensureKakaoNaviViewInflated()
            if (naviView == null) {
                NavLogger.e(this, "카카오 기본지도 표시 실패: naviView inflate 실패")
                return@runOnUiThread
            }
            try {
                val guidance = KNSDK.sharedGuidance()
                if (guidance == null) {
                    NavLogger.e(this, "카카오 기본지도 표시 실패: sharedGuidance() null (KNSDK 미초기화)")
                    return@runOnUiThread
                }
                ensureKakaoGuidanceDelegateAttached(guidance, naviView)
                naviView.initWithGuidance(
                    guidance,
                    null,
                    KNRoutePriority.KNRoutePriority_Recommand,
                    KNRouteAvoidOption.KNRouteAvoidOption_None.value
                )
                NavLogger.d(this, "카카오 기본지도(경로 없음) 표시 성공")
            } catch (e: Exception) {
                NavLogger.e(this, "카카오 기본지도 표시 예외: ${e.message}")
                return@runOnUiThread
            }
            binding.flKakaoOverlay?.apply {
                visibility = View.VISIBLE
                bringToFront()
                requestLayout()
                invalidate()
            }
        }
    }

    // onCreate 이후 최초 1회, Tmap SDK 초기화 성공 시점에 호출.
    // "앱 시작부터 카카오 지도를 기본 HUD로" 실험은 idle map으로 초기화해둔 naviView를
    // 재사용해서 실제 경로 안내로 전환할 때 SDK 상태(OnRouteGuide)는 정상인데도 화면이
    // 안 바뀌는 문제가 반복 확인되어(로그로 검증) 롤백함. 원래대로 기본 화면은 티맵이고,
    // 카카오 SDK는 여기서 미리 install/initialize만 해둬서, 실제 목적지 검색 시점엔
    // 초기화 대기 없이 바로 naviView를 새로 inflate해서 길안내를 시작할 수 있게 함. #문제시 원복
    private fun initKakaoSdkAndShowIdleMap() {
        if (knsdkInitialized) return
        try {
            val dbPath = filesDir.absolutePath + "/knsdk"
            NavLogger.d(this, "KNSDK install 시도(앱 시작 시점 선행 초기화): $dbPath")
            KNSDK.install(application, dbPath)
            KNSDK.initializeWithAppKey(
                getKakaoNativeAppKey(),
                "1.0",
                "tmapnda_user",
                "ko",
                KNLanguageType.KNLanguageType_KOREAN
            ) { error ->
                runOnUiThread {
                    if (error == null) {
                        NavLogger.d(this, "KNSDK 초기화 성공(앱 시작 시점)")
                        knsdkInitialized = true
                        KakaoSdkState.initialized = true
                        // onResume()이 이미 지나간 뒤에 초기화가 끝날 수 있어서, 최초 활성 신호를
                        // 놓치지 않도록 초기화 성공 직후에도 한 번 전달. #문제시 원복
                        try {
                            KNSDK.handleWillEnterForeground()
                            KNSDK.handleDidBecomeActive()
                        } catch (e: Exception) {
                            NavLogger.e(this, "KNSDK 라이프사이클(초기화 직후) 전달 예외: ${e.message}")
                        }
                    } else {
                        NavLogger.e(this, "KNSDK 초기화 실패(앱 시작 시점): ${error.code} / ${error.msg}")
                    }
                }
            }
        } catch (e: Exception) {
            NavLogger.e(this, "KNSDK install/init 예외(앱 시작 시점): ${e.message}")
        }
    }

    // v1.0.83: flKakaoOverlay 위에서 visibility/z-order/reassert를 직접 관리하던 방식(기존 함수는
    // startKakaoOverlayGuidanceLegacy로 이름만 남겨두고 미사용 처리)을 버리고, CarrotNavi와 동일하게
    // 카카오 안내를 완전히 별도의 Activity(KakaoNaviActivity)로 분리함. startActivity()/finish()는
    // 안드로이드가 보장하는 화면전환이라 SurfaceView 타이밍/z-order 경쟁이 원천적으로 생기지 않음.
    // 검색 패널만 우리 쪽에서 미리 닫아주고, 나머지(초기화/경로요청/안내화면)는 새 Activity가 전담. #문제시 원복
    private fun startKakaoOverlayGuidance(name: String, goalLat: Double, goalLon: Double) {
        dismissKeyboardAndSearchPanel()
        val intent = Intent(this, KakaoNaviActivity::class.java).apply {
            putExtra("dest_name", name)
            putExtra("dest_lat", goalLat)
            putExtra("dest_lon", goalLon)
            putExtra("kakao_native_app_key", getKakaoNativeAppKey())
        }
        startActivity(intent)
    }

    private fun startKakaoOverlayGuidanceLegacy(name: String, goalLat: Double, goalLon: Double) {
        // 화면 전환이 안 돼 보여서 사용자가 검색을 연타하면, 앞선 makeTripWithStart()
        // 콜백이 아직 안 돌아온 상태에서 stop()/initWithGuidance()가 새 요청과 뒤섞여
        // 레이스가 나던 것으로 의심됨. 진행 중인 요청이 있으면 재탭은 무시. #문제시 원복
        if (isRequestingKakaoRoute) {
            NavLogger.d(this, "이미 카카오 경로 요청 진행 중 - 재탭 무시: $name")
            return
        }
        if (isKakaoGuidanceStarting) {
            // 이전 요청이 아직 화면 전환을 못 끝낸 상태 - 여기서 또 새로 시작하면
            // 그 요청이 덮어써져서 영영 전환 안 되는 게 반복됨. 그냥 무시.
            // (버그 수정: 예전엔 이 분기로 return하기 전에 이미 isRequestingKakaoRoute를
            // true로 만들어놔서, makeTripWithStart 콜백까지 아예 안 가는 이 경로에서
            // 그 플래그가 영원히 안 풀리는 문제가 있었음 - v1.0.62~68 시절엔 있었던
            // "최근 검색 재클릭하면 화면 넘어감" 워크어라운드까지 막아버린 원인.
            // isKakaoGuidanceStarting 체크를 isRequestingKakaoRoute=true 대입보다
            // 먼저 하도록 순서를 바꿔서 원천 차단.) #문제시 원복
            NavLogger.d(this, "카카오 안내 시작 진행 중 - 중복 요청 무시: $name")
            Toast.makeText(this, "안내 준비 중입니다. 잠시만 기다려줘", Toast.LENGTH_SHORT).show()
            return
        }
        isRequestingKakaoRoute = true
        // 안전장치: makeTripWithStart 콜백이 무슨 이유로든 끝내 안 오는 경우 재탭이
        // 영원히 막히지 않도록 10초 뒤 강제 해제(v1.0.68 isKakaoGuidanceStarting에
        // 있었던 것과 동일한 안전장치 - 새 가드에 빠뜨렸던 것 추가). #문제시 원복
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (isRequestingKakaoRoute) {
                NavLogger.e(this, "카카오 경로요청 콜백 10초 타임아웃 - isRequestingKakaoRoute 강제 해제")
                isRequestingKakaoRoute = false
            }
        }, 10000)
        isKakaoGuidanceStarting = true
        isKakaoGuidanceExplicitlyStopped = false
        // 안전장치: guidanceGuideStarted 콜백이 무슨 이유로든 끝내 안 오는 경우
        // 영원히 재시도가 막히지 않도록 10초 뒤엔 강제로 플래그 해제.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            isKakaoGuidanceStarting = false
        }, 10000)
        dismissKeyboardAndSearchPanel()
        reassertKakaoOverlayVisible()
        // 탭 시점 1회 강제로는 부족했던 것으로 보여서, 이후에도 몇 차례 더
        // 지연 재적용 (다른 뷰/키보드 애니메이션이 끝난 뒤 z-order가 밀리는 걸 방지). #문제시 원복
        binding.flKakaoOverlay?.postDelayed({ reassertKakaoOverlayVisible() }, 300)
        binding.flKakaoOverlay?.postDelayed({ reassertKakaoOverlayVisible() }, 1000)
        binding.flKakaoOverlay?.postDelayed({ reassertKakaoOverlayVisible() }, 2500)
        binding.btnStopKakaoGuidance?.setOnClickListener { stopKakaoGuidanceAndReturnToTmap() }

        // 세로모드: 상단 슬림 HUD 바 높이만큼 오버레이 상단을 내림 (그만큼이 실제 Tmap 지도 영역).
        // 가로모드는 레이아웃 XML에서 좌측 HUD 패널(220dp)만큼 layout_marginStart로 이미 처리됨.
        // #문제시 원복
        val topHudBar = findViewById<View?>(resources.getIdentifier("llTopHudBar", "id", packageName))
        if (topHudBar != null) {
            topHudBar.post {
                val params = binding.flKakaoOverlay?.layoutParams as? android.view.ViewGroup.MarginLayoutParams
                if (params != null && params.topMargin != topHudBar.height) {
                    params.topMargin = topHudBar.height
                    binding.flKakaoOverlay?.layoutParams = params
                }
            }
        }

        if (knsdkInitialized) {
            requestKakaoRoute(name, goalLat, goalLon)
            return
        }

        try {
            val dbPath = filesDir.absolutePath + "/knsdk"
            NavLogger.d(this, "KNSDK install 시도: $dbPath")
            KNSDK.install(application, dbPath)
            KNSDK.initializeWithAppKey(
                getKakaoNativeAppKey(),
                "1.0",
                "tmapnda_user",
                "ko",
                KNLanguageType.KNLanguageType_KOREAN
            ) { error ->
                runOnUiThread {
                    if (error == null) {
                        NavLogger.d(this, "KNSDK 초기화 성공")
                        knsdkInitialized = true
                        KakaoSdkState.initialized = true
                        try {
                            KNSDK.handleWillEnterForeground()
                            KNSDK.handleDidBecomeActive()
                        } catch (e: Exception) {
                            NavLogger.e(this, "KNSDK 라이프사이클(초기화 직후) 전달 예외: ${e.message}")
                        }
                        requestKakaoRoute(name, goalLat, goalLon)
                    } else {
                        NavLogger.e(this, "KNSDK 초기화 실패: ${error.code} / ${error.msg}")
                        Toast.makeText(this, "카카오내비 초기화 실패: ${error.msg}", Toast.LENGTH_LONG).show()
                        isRequestingKakaoRoute = false
                        hideKakaoOverlay()
                    }
                }
            }
        } catch (e: Exception) {
            NavLogger.e(this, "KNSDK install/init 예외: ${e.message}")
            Toast.makeText(this, "카카오내비 초기화 예외: ${e.message}", Toast.LENGTH_LONG).show()
            isRequestingKakaoRoute = false
            hideKakaoOverlay()
        }
    }

    private fun requestKakaoRoute(name: String, goalLat: Double, goalLon: Double) {
        // 출발지(현재위치): HUD 속도계 갱신용으로 이미 실시간 업데이트되고 있는
        // lastKnownLat/lastKnownLon을 최우선으로 사용. (기존엔 이 값을 안 쓰고
        // KNSDK 자체 GPS(항상 null, 한번도 안 채워짐)나 시스템 getLastKnownLocation()
        // 캐시(빈 경우 많음)만 확인해서, 앱 켜고 바로 검색하면 매번 첫 탭은 캐시가
        // 비어 "GPS 확인중" 루프에 걸리고, 재탭할 즈음엔 다른 리스너가 lastKnownLat/Lon을
        // 채워놔서 우연히 되는 것처럼 보였음 - 이게 "두번 눌러야 되는" 진짜 원인. #문제시 원복
        var startPoi: KNPOI? = null
        val cachedLat = lastKnownLat
        val cachedLon = lastKnownLon
        if (cachedLat != null && cachedLon != null) {
            val katecStart = KNSDK.convertWGS84ToKATEC(cachedLon, cachedLat)
            startPoi = KNPOI("현 위치", katecStart.x.toInt(), katecStart.y.toInt(), "")
        }
        if (startPoi == null) {
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
        }

        if (startPoi == null) {
            Toast.makeText(this, "GPS 확인 중입니다...", Toast.LENGTH_SHORT).show()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                requestKakaoRoute(name, goalLat, goalLon)
            }, 1000)
            return
        }

        val katec = KNSDK.convertWGS84ToKATEC(goalLon, goalLat)
        val goalPoi = KNPOI(name, katec.x.toInt(), katec.y.toInt(), "")

        // idle 지도 상태(경로 없는 initWithGuidance)도 SDK 입장에선 "이미 시작된 세션"으로
        // 취급될 수 있어서, isKakaoGuidanceActive 플래그와 무관하게 방어적으로 항상 정리.
        // stop()은 이미 멈춰있는 상태에 불러도 안전한 것으로 확인됨(에러 없이 무시됨).
        //
        // (이전엔 여기서 flKakaoOverlay를 잠깐 GONE 처리해서 이전 화면 잔상을 감췄는데,
        // SDK 내부 로그(naviViewGuideState)는 매번 정상적으로 OnRouteGuide로 바뀌는데도
        // 화면이 영영 안 뜨는 증상이 재발함 - KNNaviView 내부가 SurfaceView 기반이라
        // 부모를 GONE 시키면 서페이스 자체가 파괴되고 재생성이 안 되는 것으로 추정.
        // 잔상 한 번 보이는 것보다 화면이 아예 안 뜨는 게 훨씬 치명적이라 되돌림.) #문제시 원복
        run {
            NavLogger.d(this, "새 목적지 시작 전 기존 세션(또는 idle 지도) 정리")
            try {
                KNSDK.sharedGuidance()?.stop()
            } catch (e: Exception) {
                NavLogger.e(this, "기존 안내 정리 중 예외: ${e.message}")
            }
            isKakaoGuidanceActive = false
        }

        KNSDK.makeTripWithStart(startPoi, goalPoi, null) { error, trip ->
            runOnUiThread {
                if (error != null || trip == null) {
                    NavLogger.e(this, "카카오 경로요청 실패: ${error?.msg ?: "알 수 없는 오류"}")
                    Toast.makeText(this, "경로 탐색 실패: ${error?.msg ?: "알 수 없는 오류"}", Toast.LENGTH_SHORT).show()
                    isRequestingKakaoRoute = false
                    hideKakaoOverlay()
                } else {
                    NavLogger.d(this, "카카오 경로요청 성공, 안내 시작: $name")
                    val guidance = KNSDK.sharedGuidance()!!
                    val naviView = recreateKakaoNaviView()
                    if (naviView == null) {
                        NavLogger.e(this, "KNNaviView 생성 실패")
                        Toast.makeText(this, "카카오내비 화면 생성 실패", Toast.LENGTH_SHORT).show()
                        isRequestingKakaoRoute = false
                        hideKakaoOverlay()
                        return@runOnUiThread
                    }
                    // 델리게이트는 initWithGuidance 호출 전에 등록해야 안내 시작 콜백을 놓치지 않음.
                    // (idle map에서 이미 붙어있으면 재사용, 없으면 새로 생성 - 공통 함수)
                    ensureKakaoGuidanceDelegateAttached(guidance, naviView)
                    // guideNewDestinations()는 "이미 안내 중인 상태에서 목적지 변경"용 메서드라
                    // 처음 안내를 시작할 땐 naviView 내부 guidance 프로퍼티가 세팅되지 않아
                    // 몇 초 뒤 KNTrip 내부 콜백에서 UninitializedPropertyAccessException으로 크래시났음.
                    // 최초 시작은 반드시 initWithGuidance()를 써야 함. #문제시 원복
                    //
                    // ViewStub.inflate() 직후엔 KNNaviView가 아직 레이아웃/측정을 한 번도
                    // 거치지 않은 상태(크기 0x0)라서, 바로 initWithGuidance()를 부르면 내부
                    // GL 렌더링 서페이스가 화면에 안 그려지는 것으로 추정됨(그래서 "두 번째
                    // 시도에서만 보임"). post{} 1회로는 레이아웃 패스 1번 뒤에만 실행되는데,
                    // 실제로는 그 다음에도 내부 서페이스가 준비되기까지 시간이 더 걸려서
                    // 여전히 레이스가 남았음(post 한번으론 화면 전환이 되다가 멈추고,
                    // 두번째 탭에서야 완료되는 증상 재현됨). 그래서 post 1회 고정 대신
                    // naviView.width/height가 실제로 0보다 커질 때까지 재귀적으로
                    // post{}를 반복해서 진짜 레이아웃 완료 시점을 기다리도록 변경.
                    // 최대 대기횟수를 두어 무한루프 방지. #문제시 원복
                    // 기존엔 naviView가 매번 새로 inflate돼서 크기가 0x0 -> non-zero로 바뀌는
                    // 순간을 기다리는 걸로 "서페이스 준비 시간"을 확보했는데, 이제 idle map이
                    // 앱 시작 때부터 naviView를 계속 띄워놓고 재사용하다 보니 재검색 시점엔
                    // 이미 크기가 잡혀있어서 이 대기 로직이 사실상 0번 대기하고 그냥 바로
                    // 실행돼버림(원래 의도했던 "GL 서페이스 준비 시간"을 다시 안 기다리게 됨).
                    // 그래서 크기 조건과 별개로 최소 2프레임은 항상 대기하도록 수정. #문제시 원복
                    var initRetryCount = 0
                    val minSettleFrames = 2
                    fun attemptInitWithGuidance() {
                        if (naviView.width > 0 && naviView.height > 0 && initRetryCount >= minSettleFrames) {
                            NavLogger.d(this@MapActivity, "attemptInitWithGuidance: 정상 분기로 initWithGuidance 호출 (retry=$initRetryCount, ${naviView.width}x${naviView.height})")
                            naviView.initWithGuidance(
                                guidance,
                                trip,
                                KNRoutePriority.KNRoutePriority_Recommand,
                                KNRouteAvoidOption.KNRouteAvoidOption_None.value
                            )
                            // 초기화 직후 서페이스가 이전(idle) 프레임을 그대로 들고 있을 수 있어서
                            // 강제로 한 번 더 무효화/재측정 요청. #문제시 원복
                            naviView.requestLayout()
                            naviView.invalidate()
                            naviView.postDelayed({
                                naviView.requestLayout()
                                naviView.invalidate()
                                // initWithGuidance() 이후에야 KNMapView(SurfaceView)가 새로
                                // 생성될 수도 있어서, 못 잡았을 경우를 대비해 한 번 더 순회. #문제시 원복
                                hookSurfaceViewLifecycle(naviView)
                            }, 300)
                            isRequestingKakaoRoute = false
                        } else if (initRetryCount < 30) {
                            initRetryCount++
                            naviView.post { attemptInitWithGuidance() }
                        } else {
                            // 30프레임(약 0.5초) 지나도 크기가 안 잡히면 그냥 강행 (기존 동작으로 폴백)
                            NavLogger.e(this@MapActivity, "KNNaviView 레이아웃 대기 타임아웃 - 강제 초기화")
                            naviView.initWithGuidance(
                                guidance,
                                trip,
                                KNRoutePriority.KNRoutePriority_Recommand,
                                KNRouteAvoidOption.KNRouteAvoidOption_None.value
                            )
                            isRequestingKakaoRoute = false
                        }
                    }
                    attemptInitWithGuidance()
                }
            }
        }
    }

    // WayPoint 생성자 시그니처를 모르므로, NavigationFragment의 requestRoute 시그니처에서
    // 파라미터 타입(WayPoint)을 가져와 리플렉션으로 생성자를 순회 탐색함.
    private fun requestRouteAndStartDriving(name: String, lat: Double, lon: Double) {
        try {
            val requestRouteMethod = navigationFragment?.javaClass?.methods?.firstOrNull {
                it.name == "requestRoute" && it.parameterTypes.size == 8
            }
            if (requestRouteMethod == null) {
                NavLogger.e(this, "requestRoute(8 params) 메서드를 찾지 못함")
                binding.tvSearchStatus?.text = "경로요청 실패: requestRoute 시그니처 불일치"
                return
            }

            val wayPointClass = requestRouteMethod.parameterTypes[0] // WayPoint
            val wayPoint = buildWayPointViaReflection(wayPointClass, name, lat, lon)
            if (wayPoint == null) {
                NavLogger.e(this, "WayPoint 생성 실패 (${wayPointClass.name}) - 생성자 후보를 못 찾음")
                binding.tvSearchStatus?.text = "경로요청 실패: WayPoint 생성 불가 (로그 확인 필요)"
                return
            }

            val emptyWaypoints = java.util.ArrayList<Any>() // 경유지 없음

            // RouteRequestListener는 인터페이스라 동적 프록시로 구현.
            val listenerClass = requestRouteMethod.parameterTypes[4]
            val listenerProxy = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass)
            ) { _, method, args ->
                NavLogger.d(this, "RouteRequestListener.${method.name} 콜백 args=${args?.joinToString()}")
                when (method.name) {
                    "onSuccess", "onSuccessRoute", "onRouteRequestSuccess" -> {
                        val routeResult = args?.getOrNull(0)
                        if (routeResult != null) {
                            runOnUiThread { startDrivingWithRouteResult(routeResult) }
                        }
                    }
                    "onFailure", "onFail", "onRouteRequestFail" -> {
                        NavLogger.e(this, "경로 요청 실패 콜백: ${args?.joinToString()}")
                        runOnUiThread { binding.tvSearchStatus?.text = "경로 요청 실패 (SDK 콜백)" }
                    }
                }
                null
            }

            // requestRoute(origin, viaList, destination, ?, listener, ?, ?, ?)
            // 이전엔 origin에 null을 넘겼는데("null이면 현재위치를 쓰는 구현이 많다"는 가정),
            // 이 SDK 버전은 그걸 지원 안 해서 내부에서 NPE가 터지는 것으로 로그에서 확인됨.
            // 실제 현재 위치로 origin WayPoint를 만들어서 넘김. 위치를 아직 못 받은 경우엔
            // 어쩔 수 없이 null로 시도(기존 동작 유지, 대신 실패 원인은 아래서 정확히 로깅함).
            val originLat = lastKnownLat
            val originLon = lastKnownLon
            val originWayPoint = if (originLat != null && originLon != null) {
                buildWayPointViaReflection(wayPointClass, "현재위치", originLat, originLon)
            } else null
            if (originWayPoint == null) {
                NavLogger.e(this, "현재 위치를 아직 못 받아서 origin을 null로 시도함 (실패 가능성 있음)")
            }

            // args[6] (AdditionalRouteRequestData)에 null을 넘기면 이 SDK 버전에서 매번
            // "parameter additionalRouteRequestData is null" NPE로 100% 실패함(로그로 확인됨).
            // Kotlin의 non-null 파라미터 체크는 "객체 자체가 null이면 안 된다"는 것뿐이라,
            // 필드값은 전부 기본값이어도 되니 리플렉션으로 빈 인스턴스라도 만들어서 넘김.
            val additionalRouteRequestDataClass = requestRouteMethod.parameterTypes[6]
            val additionalRouteRequestData = buildDefaultInstanceViaReflection(additionalRouteRequestDataClass)
            if (additionalRouteRequestData == null) {
                NavLogger.e(this, "AdditionalRouteRequestData 기본 인스턴스 생성 실패 - 여전히 NPE 가능성 있음")
            }

            val args = arrayOfNulls<Any?>(8)
            args[0] = originWayPoint  // origin WayPoint (현재 위치)
            args[1] = emptyWaypoints  // via list
            args[2] = wayPoint        // destination WayPoint
            args[3] = false
            args[4] = listenerProxy
            args[5] = null
            args[6] = additionalRouteRequestData
            args[7] = false

            NavLogger.d(this, "requestRoute 호출 시도: dest=$name origin=(${originLat},${originLon})")
            requestRouteMethod.invoke(navigationFragment, *args)
        } catch (e: Exception) {
            // 리플렉션 invoke()가 내부 예외를 InvocationTargetException으로 한번 감싸서 던지는데,
            // 이 감싸는 예외 자체의 message는 항상 null이라 화면에 "null"만 찍히던 문제.
            // 진짜 원인은 e.cause 쪽에 있어서 그걸 우선 사용하도록 수정. #문제시 원복
            val realMessage = e.cause?.message ?: e.message ?: e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName
            NavLogger.e(this, "requestRouteAndStartDriving 예외: $realMessage (wrapper=${e.javaClass.simpleName}, cause=${e.cause?.javaClass?.simpleName})")
            binding.tvSearchStatus?.text = "경로요청 예외: $realMessage"
        }
    }

    // 필드 구조를 몰라도 되는 파라미터 객체(예: AdditionalRouteRequestData)를 위한 범용 리플렉션 생성기.
    // 각 생성자를 순회하며 모든 파라미터에 "가장 무난한 기본값"(reference=null, boolean=false,
    // 숫자=0, String="")을 채워서 시도. 필드 검증 로직이 있으면 실패할 수 있는데, 그건 실패 로그로
    // 다음에 정확히 대응 가능하게 남겨둠.
    private fun buildDefaultInstanceViaReflection(clazz: Class<*>): Any? {
        val ctors = clazz.declaredConstructors.sortedBy { it.parameterTypes.size }
        for (ctor in ctors) {
            try {
                ctor.isAccessible = true
                val args = ctor.parameterTypes.map { type ->
                    when {
                        type == Boolean::class.javaPrimitiveType -> false
                        type == Int::class.javaPrimitiveType -> 0
                        type == Long::class.javaPrimitiveType -> 0L
                        type == Short::class.javaPrimitiveType -> 0.toShort()
                        type == Byte::class.javaPrimitiveType -> 0.toByte()
                        type == Double::class.javaPrimitiveType -> 0.0
                        type == Float::class.javaPrimitiveType -> 0f
                        type == Char::class.javaPrimitiveType -> ' '
                        type == String::class.java -> ""
                        else -> null
                    }
                }.toTypedArray()
                val instance = ctor.newInstance(*args)
                NavLogger.d(this, "${clazz.simpleName} 기본 인스턴스 생성 성공: 생성자=$ctor")
                return instance
            } catch (e: Exception) {
                NavLogger.d(this, "${clazz.simpleName} 생성자 시도 실패: ${ctor} - ${e.cause?.message ?: e.message}")
            }
        }
        for (ctor in ctors) {
            NavLogger.d(this, "${clazz.simpleName} 생성자 후보: $ctor")
        }
        return null
    }

    private fun buildWayPointViaReflection(wayPointClass: Class<*>, name: String, lat: Double, lon: Double): Any? {
        for (ctor in wayPointClass.declaredConstructors) {
            val types = ctor.parameterTypes
            try {
                ctor.isAccessible = true
                val instance = when {
                    // (String name, double lat, double lon)
                    types.size == 3 && types[0] == String::class.java &&
                        (types[1] == Double::class.javaPrimitiveType) && (types[2] == Double::class.javaPrimitiveType) ->
                        ctor.newInstance(name, lat, lon)
                    // (double lat, double lon)
                    types.size == 2 && types[0] == Double::class.javaPrimitiveType && types[1] == Double::class.javaPrimitiveType ->
                        ctor.newInstance(lat, lon)
                    // (String name, MapPoint point)
                    types.size == 2 && types[0] == String::class.java && types[1] != String::class.java -> {
                        val mapPoint = buildMapPointViaReflection(types[1], lat, lon)
                        if (mapPoint != null) ctor.newInstance(name, mapPoint) else null
                    }
                    // (String name, MapPoint point, String extra, byte flag)
                    types.size == 4 && types[0] == String::class.java && types[1] != String::class.java &&
                        types[2] == String::class.java && types[3] == Byte::class.javaPrimitiveType -> {
                        val mapPoint = buildMapPointViaReflection(types[1], lat, lon)
                        if (mapPoint != null) ctor.newInstance(name, mapPoint, name, 0.toByte()) else null
                    }
                    // (String name, String address, MapPoint point)
                    types.size == 3 && types[0] == String::class.java && types[1] == String::class.java && types[2] != String::class.java -> {
                        val mapPoint = buildMapPointViaReflection(types[2], lat, lon)
                        if (mapPoint != null) ctor.newInstance(name, name, mapPoint) else null
                    }
                    // (String name, String address, MapPoint point, MapPoint navPoint)
                    types.size == 4 && types[0] == String::class.java && types[1] == String::class.java &&
                        types[2] != String::class.java && types[3] != String::class.java -> {
                        val mapPoint = buildMapPointViaReflection(types[2], lat, lon)
                        val navPoint = buildMapPointViaReflection(types[3], lat, lon)
                        if (mapPoint != null && navPoint != null) ctor.newInstance(name, name, mapPoint, navPoint) else null
                    }
                    else -> null
                }
                if (instance != null) {
                    NavLogger.d(this, "WayPoint 생성 성공: 생성자=${ctor}")
                    return instance
                }
            } catch (e: Exception) {
                NavLogger.d(this, "WayPoint 생성자 시도 실패: ${ctor} - ${e.message}")
            }
        }
        // 매칭되는 생성자가 없으면 전체 생성자 목록을 로그로 남겨서 다음에 정확히 매핑 가능하게 함.
        for (ctor in wayPointClass.declaredConstructors) {
            NavLogger.d(this, "WayPoint 생성자 후보: ${ctor}")
        }
        return null
    }

    // WayPoint 생성자에 필요한 MapPoint(위경도 좌표 객체)를 리플렉션으로 생성.
    // Tmap SDK 좌표계 기준 보통 (lat, lon) 순서지만, 실패하면 (lon, lat) 순서도 시도.
    private fun buildMapPointViaReflection(mapPointClass: Class<*>, lat: Double, lon: Double): Any? {
        for (ctor in mapPointClass.declaredConstructors) {
            val types = ctor.parameterTypes
            if (types.size == 2 && types[0] == Double::class.javaPrimitiveType && types[1] == Double::class.javaPrimitiveType) {
                ctor.isAccessible = true
                try {
                    return ctor.newInstance(lat, lon)
                } catch (e: Exception) {
                    NavLogger.d(this, "MapPoint(lat,lon) 생성 실패 - ${e.message}")
                }
                try {
                    return ctor.newInstance(lon, lat)
                } catch (e: Exception) {
                    NavLogger.d(this, "MapPoint(lon,lat) 생성 실패 - ${e.message}")
                }
            }
        }
        NavLogger.e(this, "MapPoint 생성 실패 (${mapPointClass.name}) - (double,double) 생성자 없음")
        for (ctor in mapPointClass.declaredConstructors) {
            NavLogger.d(this, "MapPoint 생성자 후보: ${ctor}")
        }
        return null
    }

    private fun startDrivingWithRouteResult(routeResult: Any) {
        try {
            val method = navigationFragment?.javaClass?.methods?.firstOrNull {
                it.name == "startDrivingForJava"
            }
            if (method == null) {
                NavLogger.e(this, "startDrivingForJava 메서드를 찾지 못함")
                binding.tvSearchStatus?.text = "주행시작 실패: 메서드 없음"
                return
            }
            // startDrivingForJava(int, RouteResult, boolean, DrivingStartCallback)
            val callbackClass = method.parameterTypes[3]
            val callbackProxy = java.lang.reflect.Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf(callbackClass)
            ) { _, m, args ->
                NavLogger.d(this, "DrivingStartCallback.${m.name} args=${args?.joinToString()}")
                null
            }
            NavLogger.d(this, "startDrivingForJava 호출 시도")
            method.invoke(navigationFragment, 0, routeResult, false, callbackProxy)
            binding.tvSearchStatus?.text = "길안내 시작 시도됨"
            binding.llSearchPanel?.visibility = View.GONE
            binding.llGuidanceBar?.visibility = View.VISIBLE
            binding.tvGuidanceDest?.text = binding.etDestination?.text?.toString().orEmpty()
        } catch (e: Exception) {
            NavLogger.e(this, "startDrivingWithRouteResult 예외: ${e.message}")
            binding.tvSearchStatus?.text = "주행시작 예외: ${e.message}"
        }
    }

    // 목적지가 정해졌을 때 실제 길안내를 시작. (검색 API 확인 후 이 함수도 채워야 함)
    private fun startGuidanceTo(destName: String, lat: Double, lon: Double) {
        NavLogger.d(this, "startGuidanceTo dest=$destName lat=$lat lon=$lon")
        // TODO: 실제 SDK route/guide 시작 API로 교체.
        binding.llSearchPanel?.visibility = View.GONE
        binding.llGuidanceBar?.visibility = View.VISIBLE
        binding.tvGuidanceDest?.text = "길안내 중: $destName"
    }

    private fun stopGuidance() {
        NavLogger.d(this, "stopGuidance")
        // TODO: 실제 SDK 경로 취소 API로 교체.
        binding.llGuidanceBar?.visibility = View.GONE
    }

    private fun startSafeDriveMode() {
        if (isFinishing || isDestroyed) {
            NavLogger.d(this, "startSafeDriveMode 호출됐지만 Activity 이미 종료됨 - 무시")
            return
        }
        navigationFragment = getFragment() as NavigationFragment
        
        supportFragmentManager.beginTransaction()
            .add(R.id.tmapUILayout, navigationFragment!!)
            .commitAllowingStateLoss()

        setupDestinationSearchUi()
        updateRecentSearchPanel()
        dumpNavigationApiCandidates()

        navigationFragment?.let { frag ->
            // 프래그먼트가 완전히 뷰에 등록된 후 안전운행 모드를 시작하도록 약간의 딜레이를 줍니다.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                frag.startSafeDrive()
                NavLogger.d(this@MapActivity, "startSafeDrive() called")
            }, 1000)

            /* 
            // TODO: Use ObservableRouteData instead of DriveStatusListener
            */

            TmapUISDK.observableRouteData.observe(this@MapActivity, Observer { data ->
                data?.let {
                    NavLogger.e(this@MapActivity, "observableRouteData class: ${it.javaClass.name}")
                    NavLogger.e(this@MapActivity, "observableRouteData: $it")
                }
            })
            TmapUISDK.observableEDCData.observe(this@MapActivity, Observer { data ->
                data?.let {
                    // 이전엔 매초 전체 Bundle을 그대로 파일에 찍어서 로그가 무한정 쌓였음.
                    // 정상 주행 중엔 로그 안 남기고, GPS 신호 끊김/매칭 이상 같은 "이상 징후"일 때만
                    // 그리고 그 상태가 바뀌는 순간에만 1줄 남기도록 축소. #문제시 원복
                    if (it is android.os.Bundle) {
                        val noSignal = it.getBoolean("noLocationSignal", false)
                        val gpsState = it.getInt("gpsState", -1)
                        val isAnomaly = noSignal || gpsState <= 0
                        if (isAnomaly != lastEdcAnomalyState) {
                            lastEdcAnomalyState = isAnomaly
                            NavLogger.e(this@MapActivity, "observableEDCData 상태변화: anomaly=$isAnomaly ($it)")
                        }

                        // v1.3: 차선정보/신호등 잔여시간 관련 키가 Tmap 번들에 있는지 확인.
                        // 전체 키 목록은 한 번만 찍고(스팸 방지), 이후엔 매칭되는 키의 값만
                        // 로그로 남김. #문제시 원복
                        if (!loggedEdcKeySetOnce) {
                            loggedEdcKeySetOnce = true
                            NavLogger.d(this@MapActivity, "[차선/신호등? Tmap 전체키] ${it.keySet().sorted()}")
                        }
                        val laneOrSignalKeys = it.keySet().filter { k ->
                            k.contains("lane", true) || k.contains("signal", true) || k.contains("traffic", true)
                        }
                        if (laneOrSignalKeys.isNotEmpty()) {
                            val dump = laneOrSignalKeys.joinToString(", ") { k -> "$k=${it.get(k)}" }
                            NavLogger.d(this@MapActivity, "[차선/신호등? Tmap 매칭] $dump")
                        }
                    }

                    // 음소거 옵션 켜져 있을 때만 강제 0. 꺼져 있으면 매 갱신마다 볼륨을 되돌리지 않도록 스킵.
                    if (isTmapMuted) {
                        TmapUISDK.setVolume(this@MapActivity, 0)
                    }
                    
                    // 도로 기본 제한속도 추출 및 UI 업데이트
                    // realRoadLimit이 -1이나 0이면 GPS/엔진 일시 소실 → 이전 유효값 유지 (깜빡임 방지)
                    //
                    // 추가: 분기로(예: 80→40) 순간 오매칭 방지
                    // - 다음 안내지점(nTBTDist)이 가까우면 실제 분기 진입 가능성이 높으므로 즉시 반영
                    // - 안내지점과 무관하게 갑자기 낮아지면 오매칭 의심 → 최소 확인 후 반영 (최대한 타이트하게)
                    val realRoadLimit = getRoadLimitSpeedFromEngine()
                    if (realRoadLimit >= 30) {
                        val tbtDist = getTbtDistFromEngine()
                        val nearManeuverPoint = tbtDist in 0..TBT_NEAR_THRESHOLD_M

                        if (realRoadLimit < lastValidRoadLimit && !nearManeuverPoint) {
                            if (realRoadLimit == pendingRoadLimit) {
                                pendingRoadLimitCount++
                            } else {
                                pendingRoadLimit = realRoadLimit
                                pendingRoadLimitCount = 1
                            }
                            if (pendingRoadLimitCount >= REQUIRED_CONSECUTIVE) {
                                lastValidRoadLimit = realRoadLimit
                                pendingRoadLimitCount = 0
                            }
                        } else {
                            // 안내지점 근처거나, 속도가 높아지는 방향 → 즉시 반영
                            lastValidRoadLimit = realRoadLimit
                            pendingRoadLimitCount = 0
                        }
                    }
                    runOnUiThread {
                        if (lastValidRoadLimit >= 30) {
                            binding.tvRoadSpeedLimit?.text = lastValidRoadLimit.toString()
                            SdiDataRepository.roadLimitSpeed = lastValidRoadLimit
                        }
                    }

                    if (it is android.os.Bundle) {
                        extractAndDisplaySdiInfo(it)
                    }
                    runOnUiThread {
                        renderLaneSignalBar(this@MapActivity, binding.llLaneSignalBar, binding.llLaneBoxes, binding.tvTrafficLightCountdown)
                    }
                }
            })

            // 안드로이드 기본 GPS 상태 리스너 등록
            try {
                val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager

                // GPS 속도 → tvCurrentSpeed 실시간 업데이트 (HUD 속도계용)
                // speedKph가 0이면 GPS 신호 일시 소실 가능성 → 이전 값 유지 (깜빡임 방지)
                val locationListener = android.location.LocationListener { location ->
                    lastKnownLat = location.latitude
                    lastKnownLon = location.longitude
                    val speedKph = (location.speed * 3.6).toInt()
                    if (speedKph > 0) {
                        runOnUiThread {
                            binding.tvCurrentSpeed?.text = speedKph.toString()
                        }
                    }
                    checkOverSpeedWarning(speedKph)
                    // 카카오 화면(KakaoNaviActivity)과 완전히 동일한 형식으로 통일:
                    // 위성개수 기반 표시랑 오차범위 기반 표시를 따로 뒀더니 좁은 패널에서
                    // 겹쳐 보인다는 지적 - 정확도(accuracy) 기준 하나로만 표시. #문제시 원복
                    if (location.hasAccuracy()) {
                        runOnUiThread {
                            val acc = location.accuracy.toInt()
                            if (acc <= 15) {
                                binding.btnGpsStatus.text = "GOOD (정확도 ${acc}m)"
                                binding.btnGpsStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
                            } else {
                                binding.btnGpsStatus.text = "BAD (정확도 ${acc}m)"
                                binding.btnGpsStatus.setTextColor(android.graphics.Color.parseColor("#FF5252"))
                            }
                        }
                    }
                }
                try {
                    locationManager.requestLocationUpdates(
                        android.location.LocationManager.GPS_PROVIDER, 500L, 0f,
                        locationListener, android.os.Looper.getMainLooper()
                    )
                    if (locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                        locationManager.requestLocationUpdates(
                            android.location.LocationManager.NETWORK_PROVIDER, 1000L, 0f,
                            locationListener, android.os.Looper.getMainLooper()
                        )
                    }
                } catch (_: SecurityException) {}

                try {
                    val lastKnown = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                        ?: locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                    if (lastKnown != null) {
                        lastKnownLat = lastKnown.latitude
                        lastKnownLon = lastKnown.longitude
                    }
                } catch (_: SecurityException) {}

                locationManager.registerGnssStatusCallback(object : android.location.GnssStatus.Callback() {
                    override fun onStarted() {
                        binding.btnGpsStatus.text = "탐색 중"
                        binding.btnGpsStatus.setTextColor(android.graphics.Color.YELLOW)
                    }
                    override fun onStopped() {
                        binding.btnGpsStatus.text = "NO_SIGNAL"
                        binding.btnGpsStatus.setTextColor(android.graphics.Color.RED)
                    }
                    override fun onFirstFix(ttffMillis: Int) {
                        binding.btnGpsStatus.text = "수신 양호"
                        binding.btnGpsStatus.setTextColor(android.graphics.Color.GREEN)
                    }
                    override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
                        // v1.5: 정확도(accuracy) 기준 단일 표시로 통일했으므로 위성개수 기반
                        // GOOD/BAD 텍스트 덮어쓰기는 제거(locationListener의 accuracy 표시와
                        // 겹쳐서 패널이 좁아 잘려 보였음). #문제시 원복
                    }
                }, android.os.Handler(android.os.Looper.getMainLooper()))
            } catch (e: SecurityException) {
                Log.e("MapActivity", "GPS Permission Error")
                NavLogger.e(this, "GPS Permission Error: ${e.message}")
            }

            // 앱 버전 표시 (tvAppVersion은 레이아웃에 있는 경우만)
            try {
                val vn = packageManager.getPackageInfo(packageName, 0).versionName
                binding.tvAppVersion?.text = "v$vn"
            } catch (_: Exception) {}
        }
    }

    private fun startUdpSenderService() {
        val intent = Intent(this, UdpSenderService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    // 카카오내비 화면이 initWithGuidance() 직후 "안내 시작한 지점에서 그대로 멈춰있는" 증상 -
    // KNSDK 공식 문서(class-KNSDK)에 앱 라이프사이클(재개/일시정지/포그라운드/백그라운드)을
    // KNSDK에 명시적으로 알려주는 handleDidBecomeActive()/handleWillResignActive() 등이
    // 있는데, 지금까지 MapActivity엔 onResume/onPause 자체가 없어서 이 신호를 KNSDK한테
    // 한 번도 전달한 적이 없었음. 특히 handleDidBecomeActive()는 문서상 "재개 시 음성 안내의
    // 일시정지 상태가 해제됨"이라고 명시돼 있어서, 위치 갱신에 따른 지도/안내 상태 갱신도
    // 이 신호에 연동돼 있을 가능성이 높음. KNSDK 초기화 전에는 호출하면 안 되므로
    // knsdkInitialized 가드. #문제시 원복
    override fun onResume() {
        super.onResume()
        NavLogger.d(this, "[MapActivity lifecycle] onResume")
        if (knsdkInitialized) {
            try {
                KNSDK.handleWillEnterForeground()
                KNSDK.handleDidBecomeActive()
                NavLogger.d(this, "KNSDK handleWillEnterForeground/handleDidBecomeActive 호출")
            } catch (e: Exception) {
                NavLogger.e(this, "KNSDK 라이프사이클(resume) 전달 예외: ${e.message}")
            }
        }
        // KakaoNaviActivity에서 검색/최근목적지 탭 -> finish() 하면서 남겨둔 요청 처리. #문제시 원복
        val autoQuery = PendingMapAction.autoSearchQuery
        if (autoQuery != null) {
            PendingMapAction.autoSearchQuery = null
            binding.etDestination?.setText(autoQuery)
            performDestinationSearch(autoQuery)
        } else if (PendingMapAction.openSearchPanel) {
            PendingMapAction.openSearchPanel = false
            binding.llSearchPanel?.visibility = View.VISIBLE
        }
        if (PendingMapAction.openFullHistoryDialog) {
            PendingMapAction.openFullHistoryDialog = false
            binding.llSearchPanel?.visibility = View.VISIBLE
            showFullSearchHistoryDialog()
        }
    }

    override fun onStart() {
        super.onStart()
        NavLogger.d(this, "[MapActivity lifecycle] onStart")
    }

    override fun onStop() {
        super.onStop()
        NavLogger.d(this, "[MapActivity lifecycle] onStop (KakaoNaviActivity가 위에 떴을 가능성)")
    }

    override fun onPause() {
        super.onPause()
        NavLogger.d(this, "[MapActivity lifecycle] onPause (KakaoNaviActivity가 위에 뜨는 중일 수 있음 - handleWillResignActive가 이 타이밍에 KNSDK로 전달됨)")
        if (knsdkInitialized) {
            try {
                KNSDK.handleWillResignActive()
                NavLogger.d(this, "KNSDK handleWillResignActive 호출")
            } catch (e: Exception) {
                NavLogger.e(this, "KNSDK 라이프사이클(pause) 전달 예외: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (knsdkInitialized) {
            try {
                KNSDK.handleWillTerminate()
            } catch (e: Exception) {
                NavLogger.e(this, "KNSDK 라이프사이클(terminate) 전달 예외: ${e.message}")
            }
        }
        val intent = Intent(this, UdpSenderService::class.java)
        stopService(intent)
    }


    private fun extractAndDisplaySdiInfo(bundle: android.os.Bundle) {
        try {
            val sdiObj = bundle.get("firstSDIInfo")
            if (sdiObj != null) {
                val sdiJsonStr = if (sdiObj is String) sdiObj else com.google.gson.Gson().toJson(sdiObj)
                val json = org.json.JSONObject(sdiJsonStr)
                
                val sdiType = json.optInt("nSdiType", 0)
                var sdiSpeedLimit = json.optInt("nSdiSpeedLimit", 0)
                val sdiDist = json.optInt("nSdiDist", 0)
                
                val blockDist = json.optInt("nSdiBlockDist", 0)
                val blockTime = json.optInt("nSdiBlockTime", 0)
                val blockAvgSpeed = json.optInt("nSdiBlockAverageSpeed", 0)
                val isBlockSection = sdiType == 2 || sdiType == 3 || sdiType == 4 || json.optBoolean("bSdiBlockSection", false)
                
                val sharedPref = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                
                if (sdiType == 22 && sdiSpeedLimit <= 0) {
                    sdiSpeedLimit = 30
                }
                
                val useKmFormat = sharedPref.getBoolean("USE_KM_DISTANCE_FORMAT", true)
                fun formatDistance(dist: Int): String {
                    return if (useKmFormat && dist >= 1000) {
                        String.format("%.1fkm", dist / 1000.0)
                    } else {
                        "${dist}m"
                    }
                }
                
                runOnUiThread {
                    binding.tvOffsetTitle?.text = "구간단속"

                    if (isBlockSection && blockDist > 0) {
                        binding.llBlockInfo?.visibility = android.view.View.VISIBLE
                        // v1.8: 평균속도/시간은 티맵/카카오 화면 자체에도 이미 나와서 중복된다는
                        // 지적(5번) - 왼쪽 HUD 패널은 "구간단속 00km" 거리만 간소하게 표시. #문제시 원복
                        binding.tvBlockAvgSpeed?.visibility = android.view.View.GONE
                        binding.tvBlockTime?.visibility = android.view.View.GONE
                        binding.tvBlockDist?.text = "구간단속 ${formatDistance(blockDist)}"
                    } else {
                        binding.llBlockInfo?.visibility = android.view.View.GONE
                    }

                    if (sdiType > 0 || (sdiSpeedLimit > 0 && sdiDist > 0)) {
                        binding.tvSdiSpeedLimit?.text = if (sdiSpeedLimit > 0) "${sdiSpeedLimit}km" else "-"
                        binding.tvSdiDist?.text = formatDistance(sdiDist)
                        val typeName = when (sdiType) {
                            1 -> "과속 단속"
                            2 -> "구간단속 시작"
                            3 -> "구간단속 종료"
                            4 -> "구간단속 중"
                            7 -> "이동식 단속"
                            22 -> "과속방지턱"
                            33 -> "어린이보호구역"
                            else -> if (sdiSpeedLimit > 0) "단속 카메라" else "주의 구간"
                        }
                        binding.tvSdiDescr?.text = typeName
                    } else {
                        binding.tvSdiSpeedLimit?.text = ""
                        binding.tvSdiDist?.text = "--"
                        binding.tvSdiDescr?.text = "--"
                    }

                    // KakaoNaviActivity의 미니 HUD가 읽을 수 있도록 실제 값 반영 (기존엔 아무데서도
                    // 호출을 안 해서 죽은 코드였음 - 이게 "300m 고정" 원인). #문제시 원복
                    SdiDataRepository.updateCurrentSdiState(
                        limitSpeed = SdiDataRepository.roadLimitSpeed,
                        type = sdiType,
                        speedLimit = sdiSpeedLimit,
                        distance = sdiDist,
                        blockType = if (isBlockSection) 1 else 0,
                        blockSpeed = blockAvgSpeed,
                        blockDist = blockDist,
                        blockTime = blockTime,
                        blockSection = isBlockSection && blockDist > 0
                    )
                }
            } else {
                runOnUiThread {
                    binding.tvOffsetTitle?.text = "구간단속"
                    binding.llBlockInfo?.visibility = android.view.View.GONE
                    binding.tvSdiSpeedLimit?.text = ""
                    binding.tvSdiDist?.text = "--"
                    binding.tvSdiDescr?.text = "--"
                    SdiDataRepository.updateCurrentSdiState(
                        limitSpeed = SdiDataRepository.roadLimitSpeed,
                        type = 0, speedLimit = 0, distance = 0,
                        blockType = 0, blockSpeed = 0, blockDist = 0
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("MapActivity", "Error extracting SDI Info: ${e.message}")
            NavLogger.e(this, "Error extracting SDI Info: ${e.message}")
        }
    }

    // Reflection Caching
    private var sdkManagerCompanion: Any? = null
    private var getInstanceMethod: java.lang.reflect.Method? = null
    private var getRecentRGDataMethod: java.lang.reflect.Method? = null
    private var nRoadLimitSpeedField: java.lang.reflect.Field? = null
    private var nTBTDistField: java.lang.reflect.Field? = null
    private var tbtDistFieldLookupFailed = false
    private var rgDataRecursiveDumped = false

    // 분기 오매칭 방지용 상태
    private var pendingRoadLimit = 0
    private var pendingRoadLimitCount = 0
    private val REQUIRED_CONSECUTIVE = 2          // 오매칭 의심 시 최소 확인 횟수 (타이트하게)
    private val TBT_NEAR_THRESHOLD_M = 250        // 이 거리 이내면 "진짜 분기"로 간주하고 즉시 반영

    // rgData 필드 재귀 덤프용 (LaneInfoData[]/TBTInfo 같은 객체·배열 내부 필드까지 확인하기 위함)
    // - 배열: 앞에서 maxArrayItems개까지만 각 원소를 펼쳐서 찍음 (로그 폭주 방지)
    // - com.skt.tmap.* 패키지의 객체: 한 단계 더 파고들어서 필드를 찍음 (depth 2까지만, 무한재귀 방지)
    // - 그 외(String/Number/Boolean 등): 그냥 toString
    private fun dumpValueRecursive(label: String, value: Any?, declaredType: Class<*>, depth: Int, maxArrayItems: Int = 3) {
        if (value == null) {
            NavLogger.e(this, "$label = null (${declaredType.simpleName})")
            return
        }

        val cls = value.javaClass

        if (cls.isArray) {
            val length = java.lang.reflect.Array.getLength(value)
            NavLogger.e(this, "$label = ${cls.simpleName} (length=$length)")
            val itemCount = minOf(length, maxArrayItems)
            for (i in 0 until itemCount) {
                val item = java.lang.reflect.Array.get(value, i)
                dumpValueRecursive("$label[$i]", item, cls.componentType, depth + 1, maxArrayItems)
            }
            if (length > itemCount) {
                NavLogger.e(this, "$label[...] 나머지 ${length - itemCount}개 생략")
            }
            return
        }

        val isPrimitiveLike = cls.isPrimitive ||
            value is String || value is Number || value is Boolean ||
            value is Char || value is CharSequence

        if (isPrimitiveLike) {
            NavLogger.e(this, "$label = $value (${cls.simpleName})")
            return
        }

        // com.skt.tmap.* 소속 커스텀 데이터 클래스면 한 단계 더 파고든다 (depth 2까지만)
        if (depth < 2 && (cls.name.startsWith("com.skt.tmap"))) {
            NavLogger.e(this, "$label = ${cls.simpleName} 인스턴스, 내부 필드:")
            for (field in cls.fields) {
                try {
                    field.isAccessible = true
                    val fieldValue = field.get(value)
                    dumpValueRecursive("$label.${field.name}", fieldValue, field.type, depth + 1, maxArrayItems)
                } catch (fe: Exception) {
                    NavLogger.e(this, "$label.${field.name} 읽기 실패: ${fe.message}")
                }
            }
            return
        }

        // 그 외 타입이거나 depth 한도 초과 시 toString으로만 표시
        NavLogger.e(this, "$label = $value (${cls.simpleName})")
    }

    // getRecentRGData()에서 nTBTDist(다음 안내지점까지 거리)를 뽑아온다.
    // 실패하거나 값이 없으면 Int.MAX_VALUE를 반환해 "안내지점 아님"으로 안전하게 처리한다.
    private fun getTbtDistFromEngine(): Int {
        try {
            if (sdkManagerCompanion == null) {
                val sdkManagerClass = Class.forName("com.skt.tmap.engine.navigation.SDKManager")
                val companionField = sdkManagerClass.getField("Companion")
                sdkManagerCompanion = companionField.get(null)
                getInstanceMethod = sdkManagerCompanion?.javaClass?.getMethod("getInstance")
            }

            val sdkManager = getInstanceMethod?.invoke(sdkManagerCompanion)
            if (sdkManager != null) {
                if (getRecentRGDataMethod == null) {
                    getRecentRGDataMethod = sdkManager.javaClass.getMethod("getRecentRGData")
                }
                val rgData = getRecentRGDataMethod?.invoke(sdkManager)

                // 세션당 1회만 재귀 덤프 (이전엔 10초 간격 영구 반복이라 로그가 계속 쌓였음)
                if (rgData != null && !rgDataRecursiveDumped) {
                    rgDataRecursiveDumped = true
                    try {
                        NavLogger.e(this, "===== rgData(getRecentRGData) 전체 필드 덤프 =====")
                        for (field in rgData.javaClass.fields) {
                            try {
                                field.isAccessible = true
                                val value = field.get(rgData)
                                dumpValueRecursive("rgData.${field.name}", value, field.type, 0)
                            } catch (fe: Exception) {
                                NavLogger.e(this, "rgData.${field.name} 읽기 실패: ${fe.message}")
                            }
                        }
                    } catch (e: Exception) {
                        NavLogger.e(this, "rgData 전체 덤프 실패: ${e.message}")
                    }
                }

                if (rgData != null) {
                    if (nTBTDistField == null && !tbtDistFieldLookupFailed) {
                        try {
                            nTBTDistField = rgData.javaClass.getField("nTBTDist")
                        } catch (fe: Exception) {
                            // "nTBTDist"라는 필드 자체가 이 SDK 버전엔 없음(stGuidePoint 내부 dump로 확인 필요).
                            // 매번 재시도+로깅하면 로그만 쌓이고 어차피 계속 실패하니 1회만 로깅하고 이후엔 조용히 스킵.
                            tbtDistFieldLookupFailed = true
                            Log.e("MapActivity", "Reflection error (TBTDist): ${fe.message}")
                            NavLogger.e(this, "Reflection error (TBTDist): ${fe.message} - 이후 재시도 안 함")
                        }
                    }
                    return nTBTDistField?.getInt(rgData) ?: Int.MAX_VALUE
                }
            }
        } catch (e: Exception) {
            // 그 외 예외(SDKManager 접근 실패 등)는 기존처럼 로깅
            Log.e("MapActivity", "Reflection error (TBTDist): ${e.message}")
            NavLogger.e(this, "Reflection error (TBTDist): ${e.message}")
        }
        return Int.MAX_VALUE
    }

    private fun getRoadLimitSpeedFromEngine(): Int {
        try {
            if (sdkManagerCompanion == null) {
                val sdkManagerClass = Class.forName("com.skt.tmap.engine.navigation.SDKManager")
                val companionField = sdkManagerClass.getField("Companion")
                sdkManagerCompanion = companionField.get(null)
                getInstanceMethod = sdkManagerCompanion?.javaClass?.getMethod("getInstance")
            }
            
            val sdkManager = getInstanceMethod?.invoke(sdkManagerCompanion)
            if (sdkManager != null) {
                if (getRecentRGDataMethod == null) {
                    getRecentRGDataMethod = sdkManager.javaClass.getMethod("getRecentRGData")
                }
                val rgData = getRecentRGDataMethod?.invoke(sdkManager)
                if (rgData != null) {
                    if (nRoadLimitSpeedField == null) {
                        nRoadLimitSpeedField = rgData.javaClass.getField("nRoadLimitSpeed")
                    }
                    val rawLimitSpeed = nRoadLimitSpeedField?.getInt(rgData) ?: 0
                    if (rawLimitSpeed > 0) {
                        return (rawLimitSpeed - 20) / 10
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MapActivity", "Reflection error: ${e.message}")
            NavLogger.e(this, "Reflection error (RoadLimitSpeed): ${e.message}")
        }
        return -1
    }

    private fun setAutoRepeatButton(button: android.view.View?, action: () -> Unit) {
        if (button == null) return
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                action()
                handler.postDelayed(this, 100) // 0.1초마다 반복
            }
        }
        button.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    action() // 첫 클릭 시 1회 즉시 실행
                    handler.postDelayed(runnable, 400) // 0.4초 후부터 연속 실행
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(runnable)
                    true
                }
                else -> false
            }
        }
    }
    private fun clampAndPreventOverlap(v: View, targetX: Float, targetY: Float, otherViews: List<View>): Pair<Float, Float> {
        var x = targetX
        var y = targetY
        
        // 1. Clamp to parent boundaries
        val parent = v.parent as? View
        if (parent != null) {
            val maxX = (parent.width - v.width).toFloat().coerceAtLeast(0f)
            val maxY = (parent.height - v.height).toFloat().coerceAtLeast(0f)
            x = x.coerceIn(0f, maxX)
            y = y.coerceIn(0f, maxY)
        }

        // 2. Prevent overlap with other views
        val targetRect = android.graphics.RectF(x, y, x + v.width, y + v.height)
        for (other in otherViews) {
            if (other.visibility == View.VISIBLE) {
                val otherRect = android.graphics.RectF(other.x, other.y, other.x + other.width, other.y + other.height)
                if (android.graphics.RectF.intersects(targetRect, otherRect)) {
                    // Try moving only X
                    val rectX = android.graphics.RectF(x, v.y, x + v.width, v.y + v.height)
                    // Try moving only Y
                    val rectY = android.graphics.RectF(v.x, y, v.x + v.width, y + v.height)
                    
                    val canMoveX = !android.graphics.RectF.intersects(rectX, otherRect)
                    val canMoveY = !android.graphics.RectF.intersects(rectY, otherRect)
                    
                    if (canMoveX && !canMoveY) {
                        y = v.y
                    } else if (!canMoveX && canMoveY) {
                        x = v.x
                    } else {
                        // Cannot move independently without collision, stop movement
                        x = v.x
                        y = v.y
                    }
                    targetRect.set(x, y, x + v.width, y + v.height)
                }
            }
        }
        return Pair(x, y)
    }

    private fun makeDraggable(view: View, keyPrefix: String, isLandscape: Boolean, otherViews: List<View> = emptyList()) {
        var dX = 0f
        var dY = 0f

        view.setOnTouchListener { v, event ->
            if (!isEditMode) return@setOnTouchListener false
            
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = v.x - event.rawX
                    dY = v.y - event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val rawX = event.rawX + dX
                    val rawY = event.rawY + dY
                    val (clampedX, clampedY) = clampAndPreventOverlap(v, rawX, rawY, otherViews)
                    
                    v.animate()
                        .x(clampedX)
                        .y(clampedY)
                        .setDuration(0)
                        .start()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val sharedPref = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                    val suffix = if (isLandscape) "land" else "port"
                    sharedPref.edit()
                        .putFloat("${keyPrefix}_x_${suffix}", v.x)
                        .putFloat("${keyPrefix}_y_${suffix}", v.y)
                        .apply()
                    true
                }
                else -> false
            }
        }
    }

    private fun restorePosition(view: View, keyPrefix: String, isLandscape: Boolean, otherViews: List<View> = emptyList()) {
        val sharedPref = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
        val suffix = if (isLandscape) "land" else "port"
        val x = sharedPref.getFloat("${keyPrefix}_x_${suffix}", -1f)
        val y = sharedPref.getFloat("${keyPrefix}_y_${suffix}", -1f)
        
        if (x != -1f && y != -1f) {
            val (clampedX, clampedY) = clampAndPreventOverlap(view, x, y, otherViews)
            view.x = clampedX
            view.y = clampedY
        }
    }
}
