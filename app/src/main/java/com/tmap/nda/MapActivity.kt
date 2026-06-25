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

        binding.tvAppVersion?.text = "v${BuildConfig.VERSION_NAME}"
        
        val sharedPref = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
        val appKey = sharedPref.getString("APP_KEY", "") ?: ""
        
        // Force Tmap SDK to run in background
        getSharedPreferences("user.settings.info", Context.MODE_PRIVATE).edit().putBoolean("set_suspend_in_background", false).apply()
        
        if (appKey.isEmpty()) {
            Toast.makeText(this, "App Key가 설정되지 않았습니다.", Toast.LENGTH_SHORT).show()
            finish()
            return
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

        // 여유가속(마진) 조절 로직
        var currentOffset = sharedPref.getInt("BLOCK_SPEED_OFFSET", 0)
        binding.tvOffsetValue?.text = currentOffset.toString()

        binding.btnOffsetInfo?.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("구간단속 여유가속(Boost) 안내")
                .setMessage("구간단속 중 현재 평균 속도가 낮을 경우, 설정된 값(km/h) 내에서 크루즈 설정 속도를 부드럽게 상향(Boost)하여 통과 시간을 단축시켜 줍니다.\n\n예시: 제한속도 100km/h 구간에서 현재 평균이 90km/h이고, 여유가속을 '+10'으로 설정했다면, 목표 속도를 최대 110km/h까지 올려 평균 속도를 맞춥니다.\n\n(단속 종료 지점이 다가오거나 내부에 일반 카메라가 나타나면 즉시 안전 속도로 자동 복귀합니다.)")
                .setPositiveButton("확인", null)
                .show()
        }

        setAutoRepeatButton(binding.btnDecreaseOffset) {
            if (currentOffset > 0) { // 음수 방지
                currentOffset--
                binding.tvOffsetValue?.text = currentOffset.toString()
                sharedPref.edit().putInt("BLOCK_SPEED_OFFSET", currentOffset).apply()
            }
        }

        setAutoRepeatButton(binding.btnIncreaseOffset) {
            if (currentOffset < 50) { // 최대 50km/h 제한
                currentOffset++
                binding.tvOffsetValue?.text = currentOffset.toString()
                sharedPref.edit().putInt("BLOCK_SPEED_OFFSET", currentOffset).apply()
            }
        }

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        
        binding.llRoadInfo.post {
            restorePosition(binding.llRoadInfo, "llRoadInfo", isLandscape, listOfNotNull(binding.llOffset))
        }
        binding.llOffset?.post {
            restorePosition(binding.llOffset!!, "llOffset", isLandscape, listOf(binding.llRoadInfo))
        }
        
        makeDraggable(binding.llRoadInfo, "llRoadInfo", isLandscape, listOfNotNull(binding.llOffset))
        binding.llOffset?.let { makeDraggable(it, "llOffset", isLandscape, listOf(binding.llRoadInfo)) }

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
            binding.tvCarrotVersion.text = "Ver: ${state.carrot2}"
            binding.tvCarrotIp.text = "IP: ${state.ip}"
            
            // Connection
            if (state.ip != "-" && state.ip.isNotEmpty()) {
                binding.vConnectionDot.setBackgroundResource(R.drawable.shape_circle_green)
                binding.tvConnectionStatus.text = "OP 연결됨"
                binding.tvConnectionStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
            } else {
                binding.vConnectionDot.setBackgroundResource(R.drawable.shape_circle_gray)
                binding.tvConnectionStatus.text = "OP 연결 대기"
                binding.tvConnectionStatus.setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
            }
            
            // Active
            if (state.active) {
                binding.tvActiveStatus.text = "OP ON"
                binding.tvActiveStatus.setBackgroundResource(R.drawable.bg_status_on)
            } else {
                binding.tvActiveStatus.text = "OP OFF"
                binding.tvActiveStatus.setBackgroundResource(R.drawable.bg_status_off)
            }

            // Traffic
            when (state.trafficState) {
                1 -> binding.vTrafficLight.setBackgroundResource(R.drawable.shape_circle_red)
                2 -> binding.vTrafficLight.setBackgroundResource(R.drawable.shape_circle_green)
                else -> binding.vTrafficLight.setBackgroundResource(R.drawable.shape_circle_gray)
            }

            // xState
            val (xText, xColor) = when (state.xState) {
                0 -> "LEAD" to "#2196F3"
                1 -> "CRUISE" to "#4CAF50"
                2 -> "E2E CRZ" to "#00BCD4"
                3 -> "E2E STP" to "#F44336"
                4 -> "PREPARE" to "#FFC107"
                5 -> "STOPPED" to "#B71C1C"
                else -> "-" to "#AAAAAA"
            }
            binding.tvXStateBadge.text = xText
            binding.tvXStateBadge.setTextColor(android.graphics.Color.parseColor(xColor))
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

    private fun startSafeDriveMode() {
        navigationFragment = getFragment() as NavigationFragment
        
        supportFragmentManager.beginTransaction()
            .add(R.id.tmapUILayout, navigationFragment!!)
            .commitAllowingStateLoss()

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
                    val realRoadLimit = getRoadLimitSpeedFromEngine()
                    runOnUiThread {
                        if (realRoadLimit >= 30) {
                            binding.llRoadSpeedLimit?.visibility = android.view.View.VISIBLE
                            binding.tvRoadSpeedLimit?.text = realRoadLimit.toString()
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
                locationManager.registerGnssStatusCallback(object : android.location.GnssStatus.Callback() {
                    override fun onStarted() {
                        binding.tvGpsStatus.text = "탐색 중"
                        binding.tvGpsStatus.setTextColor(android.graphics.Color.YELLOW)
                    }
                    override fun onStopped() {
                        binding.tvGpsStatus.text = "끊김 (NO_SIGNAL)"
                        binding.tvGpsStatus.setTextColor(android.graphics.Color.RED)
                    }
                    override fun onFirstFix(ttffMillis: Int) {
                        binding.tvGpsStatus.text = "수신 양호"
                        binding.tvGpsStatus.setTextColor(android.graphics.Color.GREEN)
                    }
                    override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
                        var usedInFix = 0
                        for (i in 0 until status.satelliteCount) {
                            if (status.usedInFix(i)) usedInFix++
                        }
                        if (usedInFix >= 4) {
                            binding.tvGpsStatus.text = "GOOD (위성 $usedInFix)"
                            binding.tvGpsStatus.setTextColor(android.graphics.Color.GREEN)
                        } else {
                            binding.tvGpsStatus.text = "BAD (위성 $usedInFix)"
                            binding.tvGpsStatus.setTextColor(android.graphics.Color.RED)
                        }
                    }
                }, android.os.Handler(android.os.Looper.getMainLooper()))
            } catch (e: SecurityException) {
                Log.e("MapActivity", "GPS Permission Error")
            }
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
                
                var isBoosting = false
                val sharedPref = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                val offset = sharedPref.getInt("BLOCK_SPEED_OFFSET", 0)
                if (isBlockSection && offset > 0 && sdiSpeedLimit > 0) {
                    if (blockAvgSpeed > 0 && sdiSpeedLimit - blockAvgSpeed >= 1) {
                        isBoosting = true
                    }
                }
                
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
                    binding.tvOffsetTitle?.text = if (isBoosting) "추가가속중" else "구간단속"
                    
                    if (isBlockSection && blockDist > 0) {
                        binding.llBlockInfo?.visibility = android.view.View.VISIBLE
                        binding.tvBlockAvgSpeed?.text = "평균: ${blockAvgSpeed}km/h"
                        binding.tvBlockDist?.text = "거리: ${formatDistance(blockDist)}"
                        binding.tvBlockTime?.text = String.format("시간: %d:%02d", blockTime / 60, blockTime % 60)
                    } else {
                        binding.llBlockInfo?.visibility = android.view.View.GONE
                    }
                    
                    binding.llSdiEvent.visibility = android.view.View.VISIBLE
                    if (sdiType > 0 || (sdiSpeedLimit > 0 && sdiDist > 0)) {
                        binding.tvEventSpeedLimit.text = if (sdiSpeedLimit > 0) sdiSpeedLimit.toString() else "-"
                        binding.tvEventDist.text = formatDistance(sdiDist)
                        
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
                        binding.tvEventType.text = typeName
                    } else {
                        binding.tvEventSpeedLimit.text = "-"
                        binding.tvEventDist.text = "0m"
                        binding.tvEventType.text = "이벤트 없음"
                    }
                }
            } else {
                runOnUiThread {
                    binding.tvOffsetTitle?.text = "구간단속"
                    binding.llBlockInfo?.visibility = android.view.View.GONE
                    binding.llSdiEvent.visibility = android.view.View.VISIBLE
                    binding.tvEventSpeedLimit.text = "-"
                    binding.tvEventDist.text = "0m"
                    binding.tvEventType.text = "이벤트 없음"
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

