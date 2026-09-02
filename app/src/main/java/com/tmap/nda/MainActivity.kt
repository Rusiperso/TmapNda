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
import com.tmap.nda.navdy.NavdyAutoConnect

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

    // v: 재억 요청(2026-08-27) - Navdy 자동연결이 항상 "BLUETOOTH_CONNECT 권한 없음"으로
    // 건너뛰기만 했던 원인 확인됨: 이 권한을 사용자에게 물어보는 코드 자체가 없어서
    // 안드로이드 12+에서는 영원히 거부 상태로 남아있었음. 길안내 시작을 막는 필수 권한(위치 등)과
    // 묶으면 사용자가 블루투스만 거부해도 앱을 아예 못 켜게 되므로, Navdy 연결은 그것과 무관하게
    // 별도로 물어보고 거부돼도 조용히 넘어가게(=기존 "페어링 안 됐으면 무시" 동작과 동일하게) 함. #문제시 원복
    // v: 재억 제보(2026-08-27, 실기기 로그로 확인) - BLUETOOTH_CONNECT만 허용받아도 매번
    // "SecurityException: Need android.permission.BLUETOOTH_SCAN ... cancelDiscovery"로
    // 연결이 조용히 실패하고 있었음. 연결 전 cancelDiscovery() 호출에 안드로이드 12+에서
    // BLUETOOTH_SCAN이 별도로 필요해서, 두 권한을 한 번에 같이 요청하도록 변경. #문제시 원복
    private val navdyBluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val isGranted = results.values.all { it }
        if (isGranted) {
            NavdyAutoConnect.tryConnect(this)
        } else {
            // v: 재억 요청(2026-08-27) - 체크박스를 켜면 팝업으로 물어보고, 거부하면
            // 체크가 다시 풀려야 함(허용해야만 켜진 상태가 유지되는 게 정상 흐름). #문제시 원복
            binding.cbNavdyConnect.isChecked = false
            Toast.makeText(this, "블루투스 권한을 허용해야 Navdy 연결을 켤 수 있습니다.", Toast.LENGTH_SHORT).show()
            // v: 재억 요청(2026-08-27) - 실제 기기에서 확인됨: 이 권한을 이전에 한 번이라도
            // 거부한 적이 있으면, 안드로이드가 그 다음부터는 팝업 자체를 안 띄우고 조용히
            // 거부만 반복함(ACCESS_BACKGROUND_LOCATION에서 이미 겪은 것과 동일한 동작).
            // 다음번엔 아예 팝업을 시도하지 않고 바로 설정 화면으로 안내하도록 표시만 해둠. #문제시 원복
            getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).edit()
                .putBoolean("navdy_permission_denied_once", true).apply()
        }
    }

    // v: 재억 제보(2026-09-02, 실기기 로그로 확인) - 로그 전체가 "[Navdy] 권한 없음
    // (android.permission.BLUETOOTH_SCAN) - 자동연결 건너뜀"이었음. BLUETOOTH_CONNECT는
    // 허용됐는데 BLUETOOTH_SCAN만 거부 상태라, 나브디는 연결 시도조차 못 하고 있었음.
    // 예전 코드는 "한 번 거부한 적 있음(navdy_permission_denied_once)"이면 앱 켤 때마다
    // 말없이 설정 화면으로 튕겨보내면서 체크박스까지 꺼버려서, 사용자 입장에선 "왜 자꾸
    // 설정으로 가지?"였고 실제로 허용될 때까지 이어지지도 않았음. 이제:
    //  - 시작 시(fromUserToggle=false)에는 설정으로 강제 이동하지 않고, 무슨 권한이 왜
    //    필요한지 설명하는 창을 띄워 "설정 열기 / 나중에"를 고르게 함(체크는 안 풂).
    //  - 사용자가 직접 체크박스를 켠 경우(fromUserToggle=true)에만 예전처럼 바로 안내.
    //  - 거부 이력이 없으면 그냥 시스템 권한 팝업을 다시 띄움. #문제시 원복
    private fun tryConnectNavdyWithPermission(fromUserToggle: Boolean = false) {
        val missingBluetoothPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
                .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        } else {
            emptyList()
        }
        if (missingBluetoothPermissions.isEmpty()) {
            NavdyAutoConnect.tryConnect(this)
            return
        }
        NavLogger.d(this, "[Navdy] 권한 미허용 상태: ${missingBluetoothPermissions.joinToString()} (사용자조작=$fromUserToggle)")
        val deniedBefore = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
            .getBoolean("navdy_permission_denied_once", false)
        if (!deniedBefore) {
            navdyBluetoothPermissionLauncher.launch(missingBluetoothPermissions.toTypedArray())
            return
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("나브디 연결에 블루투스 권한이 필요합니다")
            .setMessage(
                "'주변 기기' 권한이 꺼져 있어서 나브디에 연결을 시도조차 못 하고 있습니다.\n" +
                    "(모자란 권한: ${missingBluetoothPermissions.joinToString { it.substringAfterLast('.') }})\n\n" +
                    "설정 > 앱 > TmapNda > 권한 > 주변 기기에서 허용해 주세요."
            )
            .setPositiveButton("설정 열기") { _, _ ->
                try {
                    val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = android.net.Uri.fromParts("package", packageName, null)
                    startActivity(intent)
                } catch (e: Exception) {
                    NavLogger.e(this, "설정 화면 이동 실패: ${e.message}")
                }
            }
            .setNegativeButton("나중에") { _, _ ->
                if (fromUserToggle) binding.cbNavdyConnect.isChecked = false
            }
            .show()
    }

    // v: 재억 요청(2026-09-02) - 설정 화면에서 '주변 기기' 권한을 켜고 돌아왔을 때, 앱을
    // 다시 껐다 켜지 않아도 그 자리에서 바로 연결을 시도하게 함. 거부 이력 플래그도
    // 실제로 허용됐으면 지워서 다음부터는 안내창이 안 뜨게 함. #문제시 원복
    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val allGranted = listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        if (!allGranted) return
        val prefs = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("navdy_permission_denied_once", false)) {
            prefs.edit().putBoolean("navdy_permission_denied_once", false).apply()
            NavLogger.d(this, "[Navdy] 블루투스 권한이 허용된 것을 확인 - 거부 이력 초기화 후 연결 재시도")
        }
        if (getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).getBoolean("REQ_NAVDY", false) ||
            (::binding.isInitialized && binding.cbNavdyConnect.isChecked)
        ) {
            NavdyAutoConnect.tryConnect(this)
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
            val stackTrace = Log.getStackTraceString(throwable)
            try {
                NavLogger.e(
                    applicationContext,
                    "===== FATAL: 앱 강제종료 (thread=${thread.name}) =====\n$stackTrace"
                )
            } catch (_: Exception) {
                // 로깅 자체가 실패해도 앱 종료 흐름은 막지 않음
            }
            try {
                // v: 재억 요청(2026-09-02) - 크래시가 나면 사람이 로그를 보내주지 않아도
                // 자동으로 디스코드에 도착하게 함(기존 이메일 "로그 보내기"는 그대로 둠). #문제시 원복
                DiscordReporter.reportCrash(applicationContext, thread.name, stackTrace)
            } catch (_: Exception) {
                // 자동 보고 실패도 앱 종료 흐름엔 영향 없어야 함
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        dumpTmapAudioSettings()

        observeCarConnectionType()
        
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
        val savedOpinetKey = sharedPref.getString("opinet_api_key", "")
        val savedEvChargerKey = sharedPref.getString("ev_charger_api_key", "")
        val savedTargetIp = sharedPref.getString("TARGET_IP", "255.255.255.255")
        val savedReqBackground = sharedPref.getBoolean("REQ_BACKGROUND", false)
        val savedReqNavdy = sharedPref.getBoolean("REQ_NAVDY", false)
        val savedReqOverlay = sharedPref.getBoolean("background_overlay_enabled", false)

        binding.etAppKey.setText(savedAppKey)
        binding.etKakaoAppKey.setText(savedKakaoKey)
        binding.etKakaoNativeAppKey.setText(savedKakaoNativeKey)
        binding.etOpinetApiKey.setText(savedOpinetKey)
        binding.etEvChargerApiKey.setText(savedEvChargerKey)
        binding.etTargetIp.setText(savedTargetIp)
        binding.cbBackgroundLocation.isChecked = savedReqBackground

        // v: 재억 요청(2026-08-27) - 버튼을 눌러야만 시도되던 걸, "항상 허용(백그라운드
        // 동작 허용)"과 똑같이 체크박스 설정으로 바꿈. 체크돼있으면 앱 켜질 때 자동으로
        // 권한을 요청하고 연결을 시도함(이후 실제 재시도는 UdpSenderService의 백그라운드
        // 루프가 계속 이어감). #문제시 원복
        binding.cbNavdyConnect.isChecked = savedReqNavdy
        if (savedReqNavdy) {
            tryConnectNavdyWithPermission()
        }

        // v: 재억 요청(2026-08-27) - "체크하면 바로 권한 팝업이 떠야 정상 아니냐"는 지적.
        // 기존엔 체크만 해두고 "길안내 시작"을 눌러야(또는 앱을 재시작해야) 팝업이 떴는데,
        // 그건 순서가 이상하다고 판단해 체크박스 자체에 리스너를 달아서 체크하는 즉시
        // 팝업이 뜨게 함. 초기값 세팅(바로 위 줄)은 리스너를 달기 전에 끝나므로
        // 앱 켤 때 저장된 값을 되살리는 것만으로는 팝업이 뜨지 않음. #문제시 원복
        binding.cbNavdyConnect.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                tryConnectNavdyWithPermission(fromUserToggle = true)
            }
            sharedPref.edit().putBoolean("REQ_NAVDY", isChecked).apply()
        }

        // v: 재억 요청(2026-08-27) - "다른 앱 위에 표시"는 Navdy 블루투스 권한과 마찬가지로
        // 앱 실행 중에 껐다 켰다 하는 설정이 아니라 최초에 한 번 권한을 허용받아야 하는
        // 항목이라, 메뉴 > 설정(PanelDragHelper) 대신 이 초기 화면으로 옮김. 이 권한은
        // requestPermissions로 못 받고 시스템 설정 화면에서 직접 허용해야 하므로,
        // 체크하는 순간 권한이 없으면 바로 그 설정 화면으로 안내함. #문제시 원복
        binding.cbBackgroundOverlay.isChecked = savedReqOverlay
        binding.cbBackgroundOverlay.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !NavOverlayManager.hasPermission(this)) {
                Toast.makeText(this, "다음 화면에서 TmapNda 항목을 켜주세요", Toast.LENGTH_LONG).show()
                NavOverlayManager.requestPermission(this)
            }
            sharedPref.edit().putBoolean("background_overlay_enabled", isChecked).apply()
        }

        binding.cbAutoReport.isChecked = DiscordReporter.isEnabled(this)
        binding.cbAutoReport.setOnCheckedChangeListener { _, isChecked ->
            DiscordReporter.setEnabled(this, isChecked)
        }
        binding.etNickname.setText(DiscordReporter.getNickname(this))

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
            val opinetKey = binding.etOpinetApiKey.text.toString().trim()
            val evChargerKey = binding.etEvChargerApiKey.text.toString().trim()
            val targetIp = binding.etTargetIp.text.toString().trim()
            val reqBackground = binding.cbBackgroundLocation.isChecked
            val reqNavdy = binding.cbNavdyConnect.isChecked
            val reqOverlay = binding.cbBackgroundOverlay.isChecked

            if (appKey.isEmpty()) {
                Toast.makeText(this, "App Key를 입력하세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            DiscordReporter.setNickname(this, binding.etNickname.text.toString())

            // Save to SharedPreferences
            sharedPref.edit().apply {
                putString("APP_KEY", appKey)
                putString("kakao_rest_api_key", kakaoKey)
                putString("kakao_native_app_key", kakaoNativeKey)
                putString("opinet_api_key", opinetKey)
                putString("ev_charger_api_key", evChargerKey)
                putString("TARGET_IP", targetIp)
                putBoolean("REQ_BACKGROUND", reqBackground)
                putBoolean("REQ_NAVDY", reqNavdy)
                putBoolean("background_overlay_enabled", reqOverlay)
                apply()
            }

            if (reqNavdy) {
                tryConnectNavdyWithPermission()
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
        // v: 재억 제보(2026-08-25) - 홈화면 바로가기로 앱을 열어도 그냥 평소 시작화면만
        // 뜨고 길안내로 안 이어짐. handleShortcutIntent()를 만들어놓고 실제로 아무 데서도
        // 호출을 안 했던 게 원인 - 바로가기 slot을 MapActivity로 그대로 전달. #문제시 원복
        this.intent.getStringExtra(QuickSlotShortcutHelper.EXTRA_SHORTCUT_SLOT)?.let { slot ->
            intent.putExtra(QuickSlotShortcutHelper.EXTRA_SHORTCUT_SLOT, slot)
        }
        startActivity(intent)
    }

    // v: 재억 요청(2026-09-03) - 이 함수가 앱을 켤 때마다 티맵 SDK의 오디오 관련 메서드
    // 목록(50줄 안팎)을 공용 로그 파일에도 그대로 복사하고 있었음. 이 목록은 SDK 버전이
    // 같으면 항상 똑같은 내용이고, 아래에서 이미 별도 파일(tmap_dump.txt)로 저장하고
    // logcat에도 남기므로 공용 로그에는 중복. 파일 로그 기록만 뺌. #문제시 원복
    private fun dumpTmapAudioSettings() {
        android.util.Log.e("TmapVolume", "dumpTmapAudioSettings started")
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
    // v: 사용자 요청(2026-08-08) - "한 번 보여주면 다신 안 물어봄" 방식이었는데, 그 한 번을
    // 놓치면 영원히 배터리 최적화가 꺼진 채로 방치될 수 있었음. 다른 권한(위치/알림)들처럼
    // 매번 실행할 때 꺼져있으면 무조건 다시 물어보도록 변경 - 앱 업데이트든 그냥 재실행이든
    // 상관없이 켜질 때까지 계속 뜸. #문제시 원복
    private fun requestIgnoreBatteryOptimizationsIfNeeded() {
        try {
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
        } catch (e: Exception) {
            NavLogger.e(this, "[배터리 최적화] 요청 실패: ${e.message}")
        }
    }

    // v: 사용자 요청(2026-08-12) - "다들 Android Auto 유선으로 쓰지 않냐"는 질문에 실제
    // 데이터로 답하기 위해 추가. CONNECTION_TYPE_PROJECTED가 뜨면 진짜 AA로 연결된 사용자,
    // NOT_CONNECTED면 재억처럼 헤드유닛에 직접 설치해서 쓰는 경우로 추정 가능. 다음 로그
    // 수집 때 실제 분포를 확인할 수 있음. #문제시 원복
    private fun observeCarConnectionType() {
        try {
            androidx.car.app.connection.CarConnection(this).type.observe(this) { type ->
                val label = when (type) {
                    androidx.car.app.connection.CarConnection.CONNECTION_TYPE_NOT_CONNECTED -> "미연결(폰 단독 실행 또는 헤드유닛 직접설치)"
                    androidx.car.app.connection.CarConnection.CONNECTION_TYPE_NATIVE -> "네이티브(헤드유닛에 직접 설치된 앱으로 실행 중)"
                    androidx.car.app.connection.CarConnection.CONNECTION_TYPE_PROJECTION -> "프로젝션(진짜 Android Auto로 연결됨 - 유선/무선 구분은 API로 불가)"
                    else -> "알수없음($type)"
                }
                OpenpilotStateRepository.carConnectionType = type
                OpenpilotStateRepository.carConnectionTypeLabel = label
                NavLogger.d(this, "[AA연결감지] $label")
            }
        } catch (e: Exception) {
            NavLogger.e(this, "[AA연결감지] 관찰 시작 실패: ${e.message}")
        }
    }
}
