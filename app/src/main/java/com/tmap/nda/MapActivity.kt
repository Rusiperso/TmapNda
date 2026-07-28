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
    private var tbtDistErrorLogged = false   // nTBTDist 리플렉션 실패는 최초 1회만 로깅
    private var isTmapMuted = true  // 티맵 안내음성 음소거 여부 (기본값: 기존 동작 유지 = 음소거)

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
        if (!isTmapMuted) {
            // 음소거 해제 시 실제 볼륨 적용. observableEDCData 콜백에서도 이 값을 참조해 매 갱신마다 재적용함.
            TmapUISDK.setVolume(this, 100)
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

        // 자동 업데이트 체크
        AutoUpdater.checkForUpdates(this)

        // 카카오 개발자콘솔 "네이티브 앱 키"에 등록할 키 해시 확인용 - 세션당 1회만 로그로 남김.
        // keystore 비밀번호 없이도 "실제 폰에 설치되어 서명 검증되는 그 앱"의 정확한 해시를 얻기 위함.
        // 확인 후 이 블록은 지워도 됨(진단 전용). #문제시 원복
        logKakaoKeyHashOnce()

        val minBottomSafeAreaPx = (36 * resources.displayMetrics.density).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
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

        isTmapMuted = sharedPref.getBoolean("tmap_muted", true)
        applyMuteState()
        binding.btnMuteToggle?.setOnClickListener {
            isTmapMuted = !isTmapMuted
            sharedPref.edit().putBoolean("tmap_muted", isTmapMuted).apply()
            applyMuteState()
            Toast.makeText(this, if (isTmapMuted) "티맵 안내음성 음소거" else "티맵 안내음성 켜짐", Toast.LENGTH_SHORT).show()
        }

        binding.btnCheckUpdate.setOnClickListener {
            Toast.makeText(this, "업데이트 확인 중...", Toast.LENGTH_SHORT).show()
            AutoUpdater.checkForUpdates(this)
        }

        binding.btnEditKey.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("auto_start", false)
            startActivity(intent)
            finish()
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
        // 터치 입력만 항상 차단. 해제 수단 없음(의도적).
        binding.vTouchLockOverlay?.setOnTouchListener { _, _ -> true }

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
            binding.llSearchPanel?.visibility = View.VISIBLE
            startVoiceSearch()
        }
        binding.btnOpenSearch?.setOnLongClickListener {
            // 텍스트로 직접 치고 싶을 때: 길게 누르면 키보드 포커스로 전환.
            binding.llSearchPanel?.visibility = View.VISIBLE
            binding.etDestination?.requestFocus()
            showSearchHistory()
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
    private val SEARCH_HISTORY_KEY = "search_history_json"
    private val SEARCH_HISTORY_MAX = 15

    private fun getSearchHistory(): List<String> {
        val sharedPref = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
        val raw = sharedPref.getString(SEARCH_HISTORY_KEY, "[]") ?: "[]"
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveSearchHistory(query: String) {
        if (query.isBlank()) return
        val sharedPref = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
        val current = getSearchHistory().toMutableList()
        current.remove(query) // 중복 있으면 지우고 맨 앞으로
        current.add(0, query)
        while (current.size > SEARCH_HISTORY_MAX) current.removeAt(current.size - 1)
        val arr = org.json.JSONArray()
        current.forEach { arr.put(it) }
        sharedPref.edit().putString(SEARCH_HISTORY_KEY, arr.toString()).apply()
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
            val text = history.getOrNull(i)
            if (tv == null) return@forEachIndexed
            if (text == null) {
                tv.visibility = View.GONE
            } else {
                tv.visibility = View.VISIBLE
                tv.text = text
                tv.setOnClickListener {
                    // 예전엔 클릭해도 화면상 반응이 전혀 안 보여서 사용자가 연타 -> 로그에서
                    // 같은 검색이 2초 안에 12번씩 중복 호출되는 게 확인됨(requestRoute NPE와 별개 문제).
                    // 탭 즉시 시각적 피드백 + 짧은 시간 재클릭 방지.
                    tv.isEnabled = false
                    tv.alpha = 0.5f
                    binding.etDestination?.setText(text)
                    performDestinationSearch(text)
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

    // "+ 더보기" 클릭 시 전체 이력을 중앙 다이얼로그로 표시
    private fun showFullSearchHistoryDialog() {
        val history = getSearchHistory()
        if (history.isEmpty()) return
        // 이전엔 플랫폼 android.app.AlertDialog + Theme.MaterialComponents 테마 조합이라
        // 텍스트/배경 색상이 제대로 안 먹어서 다이얼로그가 까맣게(또는 텍스트가 안 보이게) 뜨는
        // 문제가 있었음. 배경/텍스트 색을 아이템 레벨에서 명시적으로 강제해서 테마에 안 흔들리게 함.
        val adapter = object : android.widget.ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_1, history
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                (view as? android.widget.TextView)?.apply {
                    setTextColor(android.graphics.Color.WHITE)
                    setBackgroundColor(android.graphics.Color.parseColor("#181818"))
                    setPadding(24, 24, 24, 24)
                }
                return view
            }
        }
        val listView = android.widget.ListView(this)
        listView.adapter = adapter
        listView.setBackgroundColor(android.graphics.Color.parseColor("#181818"))
        listView.divider = android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#333333"))
        listView.dividerHeight = 1
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("검색 이력 전체")
            .setView(listView)
            .setNegativeButton("닫기", null)
            .create()
        listView.setOnItemClickListener { _, _, position, _ ->
            val picked = history[position]
            binding.etDestination?.setText(picked)
            dialog.dismiss()
            performDestinationSearch(picked)
        }
        dialog.show()
        // 다이얼로그 창 배경 자체도 명시적으로 지정 (테마 상속으로 까맣게 뜨는 것 방지)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#212121")))
    }

    private fun showSearchHistory() {
        val history = getSearchHistory()
        val listView = binding.lvSearchResults ?: return
        if (history.isEmpty()) {
            listView.visibility = View.GONE
            return
        }
        val adapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            history
        )
        listView.adapter = adapter
        listView.visibility = View.VISIBLE
        listView.setOnItemClickListener { _, _, position, _ ->
            val picked = history[position]
            binding.etDestination?.setText(picked)
            binding.etDestination?.setSelection(picked.length)
            listView.visibility = View.GONE
            performDestinationSearch(picked)
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
        val input = android.widget.EditText(this)
        input.hint = "카카오 REST API 키 (카카오 디벨로퍼스 > 내 앱 > REST API 키)"
        android.app.AlertDialog.Builder(this)
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
        saveSearchHistory(query)

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
                    val first = documents.getJSONObject(0)
                    val placeName = first.optString("place_name", query)
                    // 카카오 로컬 API 기본 좌표계는 WGS84 (x=경도, y=위도)
                    val lon = first.optDouble("x")
                    val lat = first.optDouble("y")
                    NavLogger.d(this@MapActivity, "카카오 검색 결과: $placeName lat=$lat lon=$lon")

                    runOnUiThread {
                        binding.tvSearchStatus?.text = "찾음: $placeName ($lat, $lon) - 경로요청 시도"
                        startKakaoOverlayGuidance(placeName, lat, lon)
                    }
                }
            }
        })
    }

    // ===== 카카오내비 오버레이 길안내 =====
    // 코드에 하드코딩하면 TmapNda 쓰는 모든 사람이 사용자 개인 카카오 앱 쿼터를 나눠쓰게 됨
    // (Tmap 하루1회 제한과 같은 문제 반복). 그래서 각자 본인 카카오 콘솔에서 발급받은
    // 네이티브 앱 키를 기기별 설정값으로 넣도록 변경. #문제시 원복
    // 설정 안 했으면 사용자 개인키로 폴백(당장 테스트용) - 나중엔 이 폴백도 빼는 게 맞음.
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
    private fun ensureKakaoNaviViewInflated(): KNNaviView? {
        if (kakaoNaviView == null) {
            kakaoNaviView = binding.naviViewStub?.inflate() as? KNNaviView
        }
        return kakaoNaviView
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
                    if (argsText.contains("exit", true) || argsText.contains("end", true) ||
                        method.name.contains("exit", true) || method.name.contains("finish", true)
                    ) {
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
        runOnUiThread {
            binding.flKakaoOverlay?.visibility = View.GONE
        }
    }

    private fun stopKakaoGuidanceAndReturnToTmap() {
        try {
            KNSDK.sharedGuidance()?.stop()
        } catch (e: Exception) {
            NavLogger.e(this, "카카오 안내 중지 예외: ${e.message}")
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

    private fun startKakaoOverlayGuidance(name: String, goalLat: Double, goalLon: Double) {
        dismissKeyboardAndSearchPanel()
        binding.flKakaoOverlay?.apply {
            visibility = View.VISIBLE
            bringToFront()
            requestLayout()
            invalidate()
        }
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
                        requestKakaoRoute(name, goalLat, goalLon)
                    } else {
                        NavLogger.e(this, "KNSDK 초기화 실패: ${error.code} / ${error.msg}")
                        Toast.makeText(this, "카카오내비 초기화 실패: ${error.msg}", Toast.LENGTH_LONG).show()
                        hideKakaoOverlay()
                    }
                }
            }
        } catch (e: Exception) {
            NavLogger.e(this, "KNSDK install/init 예외: ${e.message}")
            Toast.makeText(this, "카카오내비 초기화 예외: ${e.message}", Toast.LENGTH_LONG).show()
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

        KNSDK.makeTripWithStart(startPoi, goalPoi, null) { error, trip ->
            runOnUiThread {
                if (error != null || trip == null) {
                    NavLogger.e(this, "카카오 경로요청 실패: ${error?.msg ?: "알 수 없는 오류"}")
                    Toast.makeText(this, "경로 탐색 실패: ${error?.msg ?: "알 수 없는 오류"}", Toast.LENGTH_SHORT).show()
                    hideKakaoOverlay()
                } else {
                    NavLogger.d(this, "카카오 경로요청 성공, 안내 시작: $name")
                    val guidance = KNSDK.sharedGuidance()!!
                    val naviView = ensureKakaoNaviViewInflated()
                    if (naviView == null) {
                        NavLogger.e(this, "KNNaviView inflate 실패")
                        Toast.makeText(this, "카카오내비 화면 생성 실패", Toast.LENGTH_SHORT).show()
                        hideKakaoOverlay()
                        return@runOnUiThread
                    }
                    attachKakaoNaviViewExitHook(naviView)
                    // 델리게이트는 initWithGuidance 호출 전에 등록해야 안내 시작 콜백을 놓치지 않음.
                    val delegate = KakaoGuidanceDelegate(this) { stopKakaoGuidanceAndReturnToTmap() }
                    kakaoGuidanceDelegate = delegate
                    guidance.guideStateDelegate = delegate
                    guidance.routeGuideDelegate = delegate
                    guidance.safetyGuideDelegate = delegate
                    guidance.voiceGuideDelegate = delegate
                    guidance.citsGuideDelegate = delegate
                    guidance.locationGuideDelegate = delegate
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
                    var initRetryCount = 0
                    fun attemptInitWithGuidance() {
                        if (naviView.width > 0 && naviView.height > 0) {
                            naviView.initWithGuidance(
                                guidance,
                                trip,
                                KNRoutePriority.KNRoutePriority_Recommand,
                                KNRouteAvoidOption.KNRouteAvoidOption_None.value
                            )
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
                        }
                    }

                    if (it is android.os.Bundle) {
                        extractAndDisplaySdiInfo(it)
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
                        var usedInFix = 0
                        for (i in 0 until status.satelliteCount) {
                            if (status.usedInFix(i)) usedInFix++
                        }
                        if (usedInFix >= 4) {
                            binding.btnGpsStatus.text = "GOOD ($usedInFix)"
                            binding.btnGpsStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
                        } else {
                            binding.btnGpsStatus.text = "BAD ($usedInFix)"
                            binding.btnGpsStatus.setTextColor(android.graphics.Color.RED)
                        }
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

    override fun onDestroy() {
        super.onDestroy()
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
                        binding.tvBlockAvgSpeed?.text = "평균: ${blockAvgSpeed}km/h"
                        binding.tvBlockDist?.text = "거리: ${formatDistance(blockDist)}"
                        binding.tvBlockTime?.text = String.format("시간: %d:%02d", blockTime / 60, blockTime % 60)
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
                }
            } else {
                runOnUiThread {
                    binding.tvOffsetTitle?.text = "구간단속"
                    binding.llBlockInfo?.visibility = android.view.View.GONE
                    binding.tvSdiSpeedLimit?.text = ""
                    binding.tvSdiDist?.text = "--"
                    binding.tvSdiDescr?.text = "--"
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

