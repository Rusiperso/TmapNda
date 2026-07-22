package com.tmap.nda

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
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
import androidx.core.view.WindowInsetsCompat

class MapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapBinding
    private var navigationFragment: NavigationFragment? = null
    private var isEditMode = false
    private var lastValidRoadLimit = 0  // 3번: 도로 규정속도 깜빡임 방지 - 마지막 유효값 저장

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 자동 업데이트 체크
        AutoUpdater.checkForUpdates(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
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

        binding.btnEditMode?.setOnClickListener {
            isEditMode = !isEditMode
            if (isEditMode) {
                binding.btnEditMode?.setBackgroundResource(R.drawable.shape_circle_green)
                Toast.makeText(this, "오버레이 편집 모드 켜짐", Toast.LENGTH_SHORT).show()
            } else {
                binding.btnEditMode?.setBackgroundResource(R.drawable.shape_circle_gray)
                Toast.makeText(this, "오버레이 편집 모드 꺼짐", Toast.LENGTH_SHORT).show()
            }
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
                    
                    try {
                        val methods = NavigationFragment::class.java.methods
                        for (m in methods) {
                            if (m.name.contains("mute", ignoreCase = true) || m.name.contains("volume", ignoreCase = true) || m.name.contains("sound", ignoreCase = true) || m.name.contains("audio", ignoreCase = true)) {
                                Log.e("TmapVolume", "NavigationFragment method: ${m.name}")
                            }
                        }
                        val uiMethods = TmapUISDK::class.java.methods
                        for (m in uiMethods) {
                            if (m.name.contains("mute", ignoreCase = true) || m.name.contains("volume", ignoreCase = true) || m.name.contains("sound", ignoreCase = true) || m.name.contains("audio", ignoreCase = true)) {
                                Log.e("TmapVolume", "TmapUISDK method: ${m.name}")
                            }
                        }
                        
                        // Let's also check TmapUISDK.Companion methods just in case
                        val compMethods = TmapUISDK.Companion::class.java.methods
                        for (m in compMethods) {
                            if (m.name.contains("mute", ignoreCase = true) || m.name.contains("volume", ignoreCase = true) || m.name.contains("sound", ignoreCase = true) || m.name.contains("audio", ignoreCase = true)) {
                                Log.e("TmapVolume", "TmapUISDK.Companion method: ${m.name}")
                            }
                        }
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

    // ===== 길안내(목적지 검색) UI =====
    private fun setupDestinationSearchUi() {
        binding.btnOpenSearch?.setOnClickListener {
            binding.llSearchPanel?.visibility = View.VISIBLE
            binding.etDestination?.requestFocus()
        }
        binding.btnSearchClose?.setOnClickListener {
            binding.llSearchPanel?.visibility = View.GONE
        }
        binding.btnSearchGo?.setOnClickListener {
            val query = binding.etDestination?.text?.toString()?.trim().orEmpty()
            if (query.isNotEmpty()) {
                performDestinationSearch(query)
            }
        }
        binding.btnStopGuidance?.setOnClickListener {
            stopGuidance()
        }
    }

    // 1단계: SDK 안에 실제로 어떤 검색/경로 관련 메서드가 있는지 로그로 확인.
    // adb logcat | grep TmapNav 로 실제 메서드 이름/시그니처를 확인한 뒤,
    // performDestinationSearch()의 TODO 부분을 그 이름으로 교체하면 됨.
    private fun dumpNavigationApiCandidates() {
        try {
            val keywords = listOf("search", "poi", "destination", "route", "guide", "navi", "goal")
            fun dump(label: String, clazz: Class<*>) {
                for (m in clazz.methods) {
                    val n = m.name.lowercase()
                    if (keywords.any { n.contains(it) }) {
                        Log.d("TmapNav", "$label: ${m.name}(${m.parameterTypes.joinToString { it.simpleName }}) -> ${m.returnType.simpleName}")
                    }
                }
            }
            dump("NavigationFragment", NavigationFragment::class.java)
            dump("TmapUISDK", TmapUISDK::class.java)
            dump("TmapUISDK.Companion", TmapUISDK.Companion::class.java)
        } catch (e: Exception) {
            Log.e("TmapNav", "dumpNavigationApiCandidates error: ${e.message}")
        }
    }

    // 검색 실행. 지금은 후보 API 확인용 스텁 — dumpNavigationApiCandidates() 로그 확인 후
    // 실제 메서드 이름으로 채워넣어야 함.
    private fun performDestinationSearch(query: String) {
        binding.tvSearchStatus?.text = "검색 중: $query"
        Log.d("TmapNav", "performDestinationSearch query=$query")

        // TODO: 실제 SDK 검색 API로 교체.
        // 후보 예시(확인 전이라 실제 존재 여부/시그니처 불확실):
        //   navigationFragment?.search(query, listener)
        //   TmapUISDK.searchPOI(this, query, callback)
        // dumpNavigationApiCandidates() 로그에서 정확한 이름 확인 후 아래를 채워넣기.
        try {
            val candidate = navigationFragment?.javaClass?.methods
                ?.firstOrNull { it.name.lowercase().contains("search") && it.parameterTypes.size >= 1 }
            if (candidate != null) {
                Log.d("TmapNav", "search 후보 메서드 발견: ${candidate.name} params=${candidate.parameterTypes.joinToString()}")
                binding.tvSearchStatus?.text = "후보 메서드 발견(로그 확인): ${candidate.name}"
            } else {
                binding.tvSearchStatus?.text = "검색 API 미확인 - logcat TmapNav 태그 확인 필요"
            }
        } catch (e: Exception) {
            Log.e("TmapNav", "performDestinationSearch error: ${e.message}")
            binding.tvSearchStatus?.text = "오류: ${e.message}"
        }
    }

    // 목적지가 정해졌을 때 실제 길안내를 시작. (검색 API 확인 후 이 함수도 채워야 함)
    private fun startGuidanceTo(destName: String, lat: Double, lon: Double) {
        Log.d("TmapNav", "startGuidanceTo dest=$destName lat=$lat lon=$lon")
        // TODO: 실제 SDK route/guide 시작 API로 교체.
        binding.llSearchPanel?.visibility = View.GONE
        binding.llGuidanceBar?.visibility = View.VISIBLE
        binding.tvGuidanceDest?.text = "길안내 중: $destName"
    }

    private fun stopGuidance() {
        Log.d("TmapNav", "stopGuidance")
        // TODO: 실제 SDK 경로 취소 API로 교체.
        binding.llGuidanceBar?.visibility = View.GONE
    }

    private fun startSafeDriveMode() {
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
                Log.d("MapActivity", "startSafeDrive() called")
            }, 1000)

            /* 
            // TODO: Use ObservableRouteData instead of DriveStatusListener
            */

            TmapUISDK.observableRouteData.observe(this@MapActivity, Observer { data ->
                data?.let {
                    Log.e("SdiDebug", "observableRouteData class: ${it.javaClass.name}")
                    Log.e("SdiDebug", "observableRouteData: $it")
                }
            })
            TmapUISDK.observableEDCData.observe(this@MapActivity, Observer { data ->
                data?.let {
                    Log.e("SdiDebug", "observableEDCData class: ${it.javaClass.name}")
                    Log.e("SdiDebug", "observableEDCData: $it")
                    
                    TmapUISDK.setVolume(this@MapActivity, 0)
                    
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
        }
    }

    // Reflection Caching
    private var sdkManagerCompanion: Any? = null
    private var getInstanceMethod: java.lang.reflect.Method? = null
    private var getRecentRGDataMethod: java.lang.reflect.Method? = null
    private var nRoadLimitSpeedField: java.lang.reflect.Field? = null
    private var nTBTDistField: java.lang.reflect.Field? = null

    // 분기 오매칭 방지용 상태
    private var pendingRoadLimit = 0
    private var pendingRoadLimitCount = 0
    private val REQUIRED_CONSECUTIVE = 2          // 오매칭 의심 시 최소 확인 횟수 (타이트하게)
    private val TBT_NEAR_THRESHOLD_M = 250        // 이 거리 이내면 "진짜 분기"로 간주하고 즉시 반영

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
                if (rgData != null) {
                    if (nTBTDistField == null) {
                        nTBTDistField = rgData.javaClass.getField("nTBTDist")
                    }
                    return nTBTDistField?.getInt(rgData) ?: Int.MAX_VALUE
                }
            }
        } catch (e: Exception) {
            Log.e("MapActivity", "Reflection error (TBTDist): ${e.message}")
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

