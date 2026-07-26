package com.tmap.nda

import android.content.Context
import android.content.Intent
import android.os.Bundle
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
    private var isTmapMuted = true  // 티맵 안내음성 음소거 여부 (기본값: 기존 동작 유지 = 음소거)

    // 음성 검색 결과 수신용 런처. 안드로이드 표준 음성인식 액티비티(RecognizerIntent)를 위임 호출하는 방식이라
    // 별도의 RECORD_AUDIO 런타임 권한 요청 없이 동작함 (인식은 시스템 음성입력 앱이 수행).
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

    private var lastSearchClickAt = 0L
    private val SEARCH_CLICK_DEBOUNCE_MS = 800L

    // ===== 길안내(목적지 검색) UI =====
    private fun setupDestinationSearchUi() {
        binding.btnOpenSearch?.setOnClickListener {
            // 검색버튼 클릭 시 기본 동작을 음성검색으로 변경 (요청사항).
            // 텍스트 입력 패널은 그대로 열어서 인식된 문구가 눈에 보이게 하되,
            // 키보드로 포커스 주는 대신 바로 음성인식을 띄운다.
            binding.llSearchPanel?.visibility = View.VISIBLE
            showSearchHistory()
            startVoiceSearch()
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

    // 로그 파일을 이메일(jaeeok.cho@icloud.com)로 공유
    private fun shareNavLog() {
        val intent = NavLogger.buildShareIntent(this)
        if (intent == null) {
            Toast.makeText(this, "저장된 로그가 없어. 먼저 검색을 시도해봐.", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent.createChooser(intent, "로그 공유"))
    }

    // 1단계: SDK 안에 실제로 어떤 검색/경로 관련 메서드가 있는지 로그로 확인.
    // adb logcat | grep TmapNav 로 실제 메서드 이름/시그니처를 확인한 뒤,
    // performDestinationSearch()의 TODO 부분을 그 이름으로 교체하면 됨.
    private fun dumpNavigationApiCandidates() {
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
                        requestRouteAndStartDriving(placeName, lat, lon)
                    }
                }
            }
        })
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
            // origin은 null 전달 시 현재 위치를 쓰는 구현이 많아 우선 null 시도.
            val args = arrayOfNulls<Any?>(8)
            args[0] = null            // origin WayPoint (현재 위치 기대)
            args[1] = emptyWaypoints  // via list
            args[2] = wayPoint        // destination WayPoint
            args[3] = false
            args[4] = listenerProxy
            args[5] = null
            args[6] = null
            args[7] = false

            NavLogger.d(this, "requestRoute 호출 시도: dest=$name")
            requestRouteMethod.invoke(navigationFragment, *args)
        } catch (e: Exception) {
            NavLogger.e(this, "requestRouteAndStartDriving 예외: ${e.message}")
            binding.tvSearchStatus?.text = "경로요청 예외: ${e.message}"
        }
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
                    NavLogger.e(this@MapActivity, "observableEDCData class: ${it.javaClass.name}")
                    NavLogger.e(this@MapActivity, "observableEDCData: $it")
                    
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
    private var lastRgDataDumpTime = 0L

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

                // rgData 객체의 모든 필드를 10초 간격으로 통째로 로그에 남김
                // (nTBTDist/nRoadLimitSpeed 외에 차선/신호등 관련 필드가 있는지 확인하기 위함)
                // 2026-07-24: LaneInfoData[]/TBTInfo 등 객체·배열 필드는 toString()만 찍혀서
                // 내부 구조가 안 보이는 문제 -> 재귀 덤프로 교체 (차선/신호등 잔여시간 필드 탐색용)
                if (rgData != null) {
                    val now = System.currentTimeMillis()
                    if (now - lastRgDataDumpTime > 10000) {
                        lastRgDataDumpTime = now
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
                }

                if (rgData != null) {
                    if (nTBTDistField == null) {
                        nTBTDistField = rgData.javaClass.getField("nTBTDist")
                    }
                    return nTBTDistField?.getInt(rgData) ?: Int.MAX_VALUE
                }
            }
        } catch (e: Exception) {
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

