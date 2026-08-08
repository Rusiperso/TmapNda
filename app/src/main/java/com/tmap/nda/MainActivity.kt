package com.tmap.nda

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tmap.nda.databinding.ActivityMainBinding
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val backgroundPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            // v4.18: 영상으로 확인함 - 이 기기/버전에서는 ACCESS_BACKGROUND_LOCATION을
            // 다이얼로그로 직접 요청해도 실제 UI 없이 즉시 거부됨(안드로이드 11+에서 흔한
            // 동작 - 설정 앱에서만 "항상 허용"으로 바꿀 수 있게 막아둠). 이 상태에서
            // startForeground()가 무방비로 크래시 나던 건 UdpSenderService 쪽에서 별도로
            // 고쳤고, 여기서는 다음에 또 헛되이 같은 다이얼로그를 반복하지 않도록 표시만 하고
            // 앱 설정 화면으로 안내. #문제시 원복
            getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).edit()
                .putBoolean("background_permission_denied_once", true).apply()
            Toast.makeText(this, "항상 허용 권한이 거부되었습니다. (일부 기능 제한될 수 있음)", Toast.LENGTH_SHORT).show()
        }
        startMapActivity()
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            checkBackgroundPermissionAndStart()
        } else {
            Toast.makeText(this, "권한이 필요합니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NavLogger.appContext = applicationContext

        // 이전엔 앱이 강제종료돼도 원인이 로그파일 어디에도 안 남아서(NavLogger는 우리가 명시적으로
        // 호출한 것만 기록함) 사후분석이 불가능했음. 전역 크래시 핸들러를 걸어서 마지막 순간에
        // 최소한 스택트레이스라도 파일에 남긴 뒤 기존 핸들러(시스템 강제종료 다이얼로그 등)로 넘김.
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                NavLogger.e(
                    applicationContext,
                    "===== FATAL: 앱 강제종료 (thread=${thread.name}) =====\n${Log.getStackTraceString(throwable)}"
                )
            } catch (_: Exception) {
                // 로깅 자체가 실패해도 앱 종료 흐름은 막지 않음
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        dumpTmapAudioSettings()
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        
        // 자동 업데이트 체크
        AutoUpdater.checkForUpdates(this)

        requestIgnoreBatteryOptimizationsIfNeeded()

        val sharedPref = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
        val savedAppKey = sharedPref.getString("APP_KEY", "")
        val savedKakaoKey = sharedPref.getString("kakao_rest_api_key", "")
        val savedKakaoNativeKey = sharedPref.getString("kakao_native_app_key", "")
        val savedTargetIp = sharedPref.getString("TARGET_IP", "255.255.255.255")
        val savedReqBackground = sharedPref.getBoolean("REQ_BACKGROUND", false)

        binding.etAppKey.setText(savedAppKey)
        binding.etKakaoAppKey.setText(savedKakaoKey)
        binding.etKakaoNativeAppKey.setText(savedKakaoNativeKey)
        binding.etTargetIp.setText(savedTargetIp)
        binding.cbBackgroundLocation.isChecked = savedReqBackground

        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            binding.tvAppVersion.text = "버전: ${pInfo.versionName}"
        } catch (e: Exception) {
            binding.tvAppVersion.text = "버전: -"
        }

        // 자동 실행 로직
        val shouldAutoStart = intent.getBooleanExtra("auto_start", true)
        if (!savedAppKey.isNullOrEmpty() && shouldAutoStart) {
            checkPermissionsAndStart()
        }

        binding.btnStartNavi.setOnClickListener {
            val appKey = binding.etAppKey.text.toString().trim()
            val kakaoKey = binding.etKakaoAppKey.text.toString().trim()
            val kakaoNativeKey = binding.etKakaoNativeAppKey.text.toString().trim()
            val targetIp = binding.etTargetIp.text.toString().trim()
            val reqBackground = binding.cbBackgroundLocation.isChecked

            if (appKey.isEmpty()) {
                Toast.makeText(this, "App Key를 입력하세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Save to SharedPreferences
            sharedPref.edit().apply {
                putString("APP_KEY", appKey)
                putString("kakao_rest_api_key", kakaoKey)
                putString("kakao_native_app_key", kakaoNativeKey)
                putString("TARGET_IP", targetIp)
                putBoolean("REQ_BACKGROUND", reqBackground)
                apply()
            }

            checkPermissionsAndStart()
        }
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            checkBackgroundPermissionAndStart()
        } else {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun checkBackgroundPermissionAndStart() {
        if (binding.cbBackgroundLocation.isChecked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                val deniedBefore = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                    .getBoolean("background_permission_denied_once", false)
                if (deniedBefore) {
                    // v4.18: 한 번 거부된 적 있으면 같은 다이얼로그를 또 띄워봐야 이 기기에서는
                    // 어차피 즉시 재거부될 뿐이라(영상으로 확인함) - 설정 앱으로 바로 안내. #문제시 원복
                    Toast.makeText(this, "설정 > 앱 > TmapNda > 권한 > 위치에서 '항상 허용'으로 바꿔주세요.", Toast.LENGTH_LONG).show()
                    try {
                        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        intent.data = android.net.Uri.fromParts("package", packageName, null)
                        startActivity(intent)
                    } catch (e: Exception) {
                        NavLogger.e(this, "설정 화면 이동 실패: ${e.message}")
                    }
                    startMapActivity()
                } else {
                    // 백그라운드 권한 요청 (설정 화면으로 이동됨)
                    Toast.makeText(this, "설정에서 '항상 허용'을 선택해 주세요.", Toast.LENGTH_LONG).show()
                    backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            } else {
                startMapActivity()
            }
        } else {
            startMapActivity()
        }
    }

    private fun startMapActivity() {
        val intent = Intent(this, MapActivity::class.java)
        startActivity(intent)
    }

    private fun dumpTmapAudioSettings() {
        android.util.Log.e("TmapVolume", "dumpTmapAudioSettings started")
        NavLogger.e(this, "dumpTmapAudioSettings started")
        val kw = listOf("mute", "volume", "sound", "audio", "tts", "speech", "voice", "guide", "guidance", "announce", "alert")
        val sb = java.lang.StringBuilder()
        sb.append("dumpTmapAudioSettings started\n")
        try {
            val classesToInspect = listOf(
                "com.skt.tmap.engine.navigation.TmapNavigation",
                "com.tmapmobility.tmap.tmapsdk.ui.util.TmapUISDK",
                "com.skt.tmap.engine.navigation.TmapNavigationAudio",
                "com.skt.tmap.engine.navigation.TTSHelper",
                "com.tmapmobility.tmap.tmapsdk.ui.fragment.NavigationFragment"
            )

            for (className in classesToInspect) {
                try {
                    val cls = Class.forName(className)
                    for (m in cls.methods) {
                        if (kw.any { m.name.contains(it, ignoreCase = true) }) {
                            val line = "$className method: ${m.name}(${m.parameterTypes.joinToString { it.name }}) -> ${m.returnType.name}\n"
                            sb.append(line)
                            android.util.Log.e("TmapVolume", line.trim())
                            NavLogger.e(this, line.trim())
                        }
                    }
                } catch (e: Throwable) {
                    sb.append("Failed to inspect $className\n")
                    android.util.Log.e("TmapVolume", "Failed to inspect $className")
                    NavLogger.e(this, "Failed to inspect $className")
                }
            }
        } catch (e: Throwable) {
            sb.append("Error in dumpTmapAudioSettings: ${e.message}\n")
            android.util.Log.e("TmapVolume", "Error in dumpTmapAudioSettings", e)
            NavLogger.e(this, "Error in dumpTmapAudioSettings: ${e.message}")
        }
        
        try {
            val file = java.io.File(getFilesDir(), "tmap_dump.txt")
            file.writeText(sb.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // v: 지금까지 배터리 최적화(Doze/앱 대기 모드) 예외를 단 한 번도 요청한 적이 없었음.
    // UdpSenderService가 PARTIAL_WAKE_LOCK은 잡고 있어도 그건 CPU만 안 재우는 거고,
    // 안드로이드/제조사(특히 커스텀 헤드유닛 ROM)의 배터리 최적화가 백그라운드 UDP
    // 네트워크 접근 자체를 조일 수 있음 - "처음엔 연결됐다가 한동안 지나면 조용히 끊기고
    // 다시는 안 되는" 미스터리의 추가 원인 후보. 매번 물어보면 시끄러우니 한 번 거부하면
    // 다시 안 물어봄(설정에서 직접 켜야 함). #문제시 원복
    private fun requestIgnoreBatteryOptimizationsIfNeeded() {
        try {
            val sharedPref = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
            if (sharedPref.getBoolean("battery_optimization_prompt_shown", false)) return

            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                NavLogger.d(this, "[배터리 최적화] 예외 미설정 상태 - 요청 다이얼로그 표시")
                val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } else {
                NavLogger.d(this, "[배터리 최적화] 이미 예외 설정되어 있음")
            }
            sharedPref.edit().putBoolean("battery_optimization_prompt_shown", true).apply()
        } catch (e: Exception) {
            NavLogger.e(this, "[배터리 최적화] 요청 실패: ${e.message}")
        }
    }
}
