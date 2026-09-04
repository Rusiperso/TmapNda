package com.tmap.nda

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.security.MessageDigest
import com.kakaomobility.knsdk.KNSDK
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
import com.tmapmobility.tmap.tmapsdk.ui.data.MapLayerType
import androidx.lifecycle.Observer
import android.content.res.Configuration
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
    // v3.9: PanelDragHelper.isEditMode로 이전 - Tmap/카카오 화면이 같은 편집모드 상태 공유
    private var lastValidRoadLimit = 0  // 3번: 도로 규정속도 깜빡임 방지 - 마지막 유효값 저장
    private var lastEdcAnomalyState = false  // observableEDCData 이상징후 로깅용 (상태 변화시에만 기록)
    private var loggedEdcKeySetOnce = false  // 차선/신호등 진단용 전체 키 목록은 한 번만 로그
    private var tbtDistErrorLogged = false   // nTBTDist 리플렉션 실패는 최초 1회만 로깅
    private var isTmapMuted = false  // 티맵 안내음성 음소거 여부 (기본값: 소리 켜짐. 카카오는 실제 경로안내 중에만 말하므로 기본상태에선 티맵 음성이 나와야 함)
    // v: 재억 제보(2026-09-04, 자동 워치독 로그로 확인) - Tmap 화면 -> 카카오 내비 화면
    // 전환 순간 5초 넘게 화면이 먹통되는 문제. 원인: 화면 전환 중엔 안드로이드가 이 화면의
    // onResume을 "잠깐 보였다가 바로 onPause"로 헛되이 부르는데(NavOverlayManager 주석 참고),
    // 그 짧은 onResume에서도 매번 KNSDK.handleWillEnterForeground/handleDidBecomeActive()라는
    // 무거운 카카오 SDK 호출을 실행하고 있었음 - 어차피 곧바로 사라질 화면인데도. 0.3초만
    // 기다렸다가 그때까지 화면이 진짜로 남아있을 때만 이 호출을 하도록 지연시키고, 그 전에
    // onPause가 오면(=화면 전환 중이었단 뜻) 예약을 취소해서 헛호출 자체를 없앰. #문제시 원복
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingKnsdkForegroundCall: Runnable? = null
    // v14.2: 재억 요청 - 즐겨찾기/이력/검색결과 목록에서 배경으로 예상시간을 계산하는
    // 도중에 사용자가 항목 하나를 눌러서 안내를 시작하려 하면, 아직 시작 안 한 나머지
    // 배경 계산들은 그만두고(이미 보낸 요청까지 취소는 안 되지만 더 안 늘어나게) 방금
    // 누른 항목 계산이 불필요한 경쟁 없이 나가도록 함("A,B,C,D 계산 중에 E를 누르면
    // 남은 배경 계산은 그만두고 E부터" - 재억 아이디어). 항목을 고르는 곳
    // (showRoutePriorityDialog)에서 이 숫자를 하나 올리면, 그 전에 시작된 배경 대기열들은
    // 자기가 시작될 때 봤던 숫자와 지금 숫자가 다른 걸 보고 스스로 멈춤. #문제시 원복
    private var etaQueueGeneration = 0
    private val originalBottomMargins = mutableMapOf<Int, Int>()
    private val originalTopMargins = mutableMapOf<Int, Int>()
    private var lastAppliedBottomInset = -1

    /**
     * 시스템 내비게이션 바/제스처 영역만큼 하단 고정 UI를 위로 올린다.
     * XML에 기존 bottomMargin이 생겨도 보존하도록 최초 값을 기준으로 합산한다.
     */
    private fun applyBottomSafeInset(view: View?, bottomInset: Int) {
        if (view == null) return
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val originalMargin = originalBottomMargins.getOrPut(view.id) { params.bottomMargin }
        val targetMargin = originalMargin + bottomInset
        if (params.bottomMargin == targetMargin) return

        params.bottomMargin = targetMargin
        view.layoutParams = params
    }

    /** 상단 HUD가 글자 크기/해상도에 따라 커지면 아래 오버레이도 같은 만큼 내린다. */
    private fun applyTopPanelExpansion(view: View?, expandedHeight: Int) {
        if (view == null) return
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val originalMargin = originalTopMargins.getOrPut(view.id) { params.topMargin }
        val targetMargin = originalMargin + expandedHeight
        if (params.topMargin == targetMargin) return

        params.topMargin = targetMargin
        view.layoutParams = params
    }

    private fun installTopPanelAutoOffset() {
        binding.llLeftHudPanel.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val panelHeight = bottom - top
            val baseHeight = binding.llTopBarRow.minimumHeight
            val expandedHeight = (panelHeight - baseHeight).coerceAtLeast(0)

            applyTopPanelExpansion(binding.llOffset, expandedHeight)
            applyTopPanelExpansion(binding.svSecondaryPanel, expandedHeight)
            // v4.0: 지도(tmapUILayout)는 이 자동보정 대상에서 빠져있어서, 바가 커지면
            // (큰 폰트 설정 등) 그만큼 지도가 더 가려지던 문제(사용자 지적 1번: "지도 부분이
            // 살짝씩 짤리는 증상"). 지도도 같이 밀어냄. #문제시 원복
            applyTopPanelExpansion(binding.tmapUILayout, expandedHeight)
        }
    }

    // v2.4: "50으로 줄여도 다음에 100으로 복귀" 버그의 진짜 원인 - VolumeHelper가 mute
    // 되는 "그 순간"에만 볼륨을 캡처했음. 그래서 mute 없이 볼륨만 바꾸면 그 변경은 저장이
    // 안 되고, 다음 mute/unmute 때 예전 값(또는 기본 100)으로 복원돼버림. 시스템 볼륨이
    // 바뀔 때마다(안 켜져 있을 때만) 실시간으로 캡처해서 이 구조적 문제를 없앰. #문제시 원복
    private val volumeChangeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isTmapMuted) {
                VolumeHelper.captureCurrentVolumePercent(this@MapActivity)
            }
        }
    }

    // 음성 검색 결과 수신용 런처. 안드로이드 표준 음성인식 액티비티(RecognizerIntent)를 위임 호출하는 방식이라
    // 별도의 RECORD_AUDIO 런타임 권한 요청 없이 동작함 (인식은 시스템 음성입력 앱이 수행).
    // 로그 공유 화면(이메일 앱 등)에서 돌아왔을 때, 방금 보낸 로그 파일들을 삭제하기 위한 목록.
    // ACTION_SEND_MULTIPLE은 "진짜 전송됨"까지는 확인 못 하고 "공유 화면에서 돌아옴"까지만 알 수 있어서
    // v10.1: "전송된 로그가 삭제 안 되고 계속 쌓인다"(재억 재요청) - 이전에 "공유해도
    // 안 지워지게" 바꿨던 걸 다시 원복. 공유 화면(이메일 앱 등)에서 앱으로 돌아오면
    // 그 시점을 "보냈다"로 간주하고 삭제함. #문제시 원복
    private val shareLogLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // v11.8: ACTION_SEND는 안드로이드 구조상 "진짜 보내졌는지" 확인할 방법이 없어서,
        // 실제로 잘 보내졌어도 대부분 "취소됐다"는 문구가 뜨는 부작용이 있었음(재억 지적) -
        // 재억 요청대로 성공/취소 문구 자체를 안 띄우기로 함. 삭제 여부 판단(RESULT_OK일
        // 때만 삭제)은 그대로 유지. #문제시 원복
        if (result.resultCode == RESULT_OK) {
            NavLogger.deleteAllLogFiles(this)
        }
    }

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
        // (사용자 지적 - "볼륨 50으로 했는데 다음에 실행하면 다시 100이 됨"). 이제 음소거 상태일
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
            TmapUISDK.setVolume(this, VolumeHelper.guideVolumePercent(this))
        } catch (e: Exception) {
            NavLogger.e(this, "티맵 볼륨 복원 예외: ${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NavLogger.appContext = applicationContext
        // v: 재억 재제보(2026-08-30) - 15초짜리 시작 워치독으로는 "카카오 종료할 때 등"의
        // 멈춤을 못 잡아서, 앱 전체 수명 동안 계속 도는 전역 워치독(MainThreadWatchdog)으로
        // 교체. 여기서든 KakaoNaviActivity에서든 한 번만 시작되면 계속 돎. #문제시 원복
        MainThreadWatchdog.ensureStarted(applicationContext)
        // 시스템 내비게이션 바(제스처/버튼) 뒤까지 그려지는 edge-to-edge를 명시적으로 켜야
        // 아래 인셋 리스너가 실제 하단 바 높이를 받아온다. (기존엔 미설정이라 하단 UI가
        // 기기에 따라 시스템 바에 가려지는 문제 발생)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        inflatedOrientation = resources.configuration.orientation

        // v2.6: 좌측 세로 패널 -> 상단 가로 바로 구조를 바꾸면서, 예전 HudScale(패널 "폭"을
        // 화면 크기에 맞춰 계산하던 로직)은 더 이상 안 맞음(이제 패널은 match_parent 폭의
        // 가로 바라서, HudScale이 폭을 줄이면 오히려 레이아웃이 깨짐). 높이 기준 버전으로
        // 다시 만들기 전까지 임시 비활성화. #TODO #문제시 원복
        // HudScale.install(
        //     binding.root,
        //     binding.llLeftHudPanel,
        //     listOf(binding.llSearchPanel, binding.llGuidanceBar, binding.flKakaoOverlay, binding.llLaneSignalBar)
        // )

        // UiAutoScaler(전체 뷰트리 padding/margin/고정크기 일괄 스케일링)는
        // 태블릿(sw~940dp)에서 1.8배가 그대로 곱해지며 좌측 패널 폭을 넘어
        // 아이콘 겹침/텍스트 잘림을 유발함이 확인되어 즉시 롤백함.
        // #문제시 원복 대상이었으나 현재는 비활성 상태. (UiAutoScaler.kt 자체는 보존)
        // UiAutoScaler.apply(this, binding.root)

        // 자동 업데이트 체크
        AutoUpdater.checkForUpdates(this)
        // v8.8: 실행 중에도 새 업데이트가 뜨면 반응하도록 5분 간격 백그라운드 폴링 시작
        // (사용자 요청: "실행 중에 업데이트 뜨면 바로 반응하게"). #문제시 원복
        AutoUpdater.startPeriodicCheck(this)

        // 카카오 개발자콘솔 "네이티브 앱 키"에 등록할 키 해시 확인용 - 세션당 1회만 로그로 남김.
        // keystore 비밀번호 없이도 "실제 폰에 설치되어 서명 검증되는 그 앱"의 정확한 해시를 얻기 위함.
        // 확인 후 이 블록은 지워도 됨(진단 전용). #문제시 원복
        logKakaoKeyHashOnce()

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val safeArea = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.mandatorySystemGestures()
            )
            // 지도(root)는 좌/상/우만 인셋 적용, 하단은 지도가 풀스크린으로 남도록 0 유지
            v.setPadding(safeArea.left, safeArea.top, safeArea.right, 0)
            com.tmap.nda.miniplayer.MiniPlayerManager.topSafeInsetPx = safeArea.top

            // v3.8: 티맵 SDK 내부 하단 메뉴(주행종료/현재위치/메뉴)는 tmapUILayout의
            // 맨 아래에 그려진다. 기존에는 root 하단 인셋을 0으로 둔 채 보조패널에만
            // 여백을 줘서, 세로 휴대폰의 3버튼/제스처 내비게이션 바 뒤에 버튼이 가려졌다.
            // 실제 시스템 인셋을 컨테이너와 모든 하단 고정 패널의 bottomMargin으로 적용해
            // 휴대폰/태블릿/엔미러의 해상도·회전·내비게이션 방식에 자동 대응한다.
            applyBottomSafeInset(binding.tmapUILayout, safeArea.bottom)
            applyBottomSafeInset(binding.llSearchPanel, safeArea.bottom)
            applyBottomSafeInset(binding.llGuidanceBar, safeArea.bottom)
            applyBottomSafeInset(binding.flKakaoOverlay, safeArea.bottom)

            // v3.0: svSecondaryPanel(≡ 메뉴로 열리는 보조패널)이 스크롤뷰라 화면 하단까지
            // 늘어날 수 있는데, 모바일폰(제스처 네비게이션 바 있는)에서는 마지막 버튼(앱종료 등)이
            // 시스템 바에 가려지던 문제(사용자 지적 5번: "모바일 폰 기준 UI가 하단 침범").
            // 스크롤 가능 영역 맨 아래에 안전여백만큼 패딩 추가. #문제시 원복
            binding.svSecondaryPanel?.let { panel ->
                panel.clipToPadding = false
                panel.setPadding(panel.paddingLeft, panel.paddingTop, panel.paddingRight, safeArea.bottom)
            }

            if (lastAppliedBottomInset != safeArea.bottom) {
                lastAppliedBottomInset = safeArea.bottom
                NavLogger.d(
                    this,
                    "[UI_INSETS] left=${safeArea.left} top=${safeArea.top} " +
                        "right=${safeArea.right} bottom=${safeArea.bottom}"
                )
            }

            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
        installTopPanelAutoOffset()

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
            NavLogger.d(this, "[업데이트확인] 버튼 클릭됨(Tmap화면)")
            binding.svSecondaryPanel?.visibility = View.GONE
            Toast.makeText(this, "업데이트 확인 중...", Toast.LENGTH_SHORT).show()
            AutoUpdater.checkForUpdates(this, isManual = true)
        }

        // v3.10: GitHub 브라우저로 여는 대신 앱 안 팝업으로 바로 보여줌
        // (사용자 지적 2번: "인터넷 페이지로 보내지 말고 팝업창으로"). #문제시 원복
        binding.btnHelp?.setOnClickListener {
            binding.svSecondaryPanel?.visibility = View.GONE
            AutoUpdater.showChangelogDialog(this)
        }

        // v4.9: 진짜 "도움말"(사용법 가이드) - README가 표/링크 등 서식이 있어서
        // 팝업보다 웹으로 여는 게 훨씬 보기 편함 (사용자: "이것도 팝업이 맞을까 인터넷
        // 페이지 연결이 맞을까?" -> 웹 링크로 결정). #문제시 원복
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
        // v4.12: 저장된 로그 파일이 안 지워진다는 사용자 지적 - 확인 다이얼로그와 함께
        // 전체 삭제 버튼 추가. #문제시 원복
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

        // v1.7: 짧게 누르기가 카카오키 재입력 화면으로 가버려서, 정작 자주 쓸 앱 설정
        // (속도초과 경고음/이동식카메라 감속 토글)은 길게 눌러야만 보였음 - 아무도
        // 그걸 몰라서 "설정 창에 그 기능이 안 보인다"는 문의로 이어짐. 우선순위를
        // 뒤집음: 짧게 누르기 = 앱 설정(토글들), 길게 누르기 = 카카오키 재입력(드묾). #문제시 원복
        binding.btnParkedLocation?.setOnClickListener {
            binding.svSecondaryPanel?.visibility = View.GONE
            ParkedLocationPopup.show(this)
        }

        binding.btnEditKey.setOnClickListener {
            binding.svSecondaryPanel?.visibility = View.GONE
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
            // v4.23: UdpSenderService 정지는 이제 여기(의도적 종료)에서만. #문제시 원복
            stopService(Intent(this, UdpSenderService::class.java))
            finishAffinity()
        }

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        // 스타일 D(HUD): 좌측 패널은 고정, llOffset 플로팅만 드래그 유지
        binding.llOffset?.let {
            PanelDragHelper.makeDraggable(this, it, "llOffset", isLandscape, emptyList())
        }

        // v2.9: 상단 HUD 바(llLeftHudPanel)도 드래그로 위치 옮길 수 있게 - 단, 예전처럼
        // 상시로 드래그되면 실수로 밀릴 수 있어서 "UI 편집" 버튼으로 편집모드를
        // 켜야만 움직임. 앱 재시작해도 저장된 위치로 복원됨. #문제시 원복
        binding.llLeftHudPanel?.let { panel ->
            PanelDragHelper.makeDraggable(this, panel, "llLeftHudPanel", isLandscape, emptyList())
            // v3.2: onCreate 시점엔 panel.width/height가 아직 0이라 clampAndPreventOverlap이
            // 저장된 위치를 무조건 (0,0)으로 눌러버리는 버그가 있었음(사용자 지적 3번: "편집으로
            // 내려도 재실행하면 다시 위로 올라감"). 레이아웃이 끝난 뒤(post)에 복원하도록 변경. #문제시 원복
            panel.post {
                PanelDragHelper.restorePosition(this, panel, "llLeftHudPanel", isLandscape, emptyList())
            }
        }

        // v: 재억 지적(2026-08-26) - "UI 편집"을 눌러도 카테고리 버튼은 반응이 없다는
        // 지적 - llLeftHudPanel만 드래그 대상이었고 이 버튼은 아예 빠져있었음. 같은
        // 방식(makeDraggable/restorePosition)으로 편집모드에서 같이 옮길 수 있게 추가. #문제시 원복
        binding.btnNearbyCategory?.let { btn ->
            PanelDragHelper.makeDraggable(this, btn, "btnNearbyCategory", isLandscape, emptyList())
            btn.post {
                PanelDragHelper.restorePosition(this, btn, "btnNearbyCategory", isLandscape, emptyList())
            }
        }
        // v: 신규기능(미니 플레이어) - 재억 요청(2026-08-28). 카카오 화면과 동일. #문제시 원복
        binding.flMiniPlayerContainer?.let { outer ->
            com.tmap.nda.miniplayer.MiniPlayerManager.attach(
                this, outer, binding.llMiniPlayer!!,
                binding.ivMiniPlayerArt, binding.tvMiniPlayerTitle, binding.tvMiniPlayerArtist,
                binding.btnMiniPlayerPlayPause, binding.btnMiniPlayerPrev, binding.btnMiniPlayerNext,
                binding.btnMiniPlayerConfirm, binding.vMiniPlayerResizeHandle,
                "llMiniPlayer", isLandscape
            )
        }
        // v3.9: Tmap/카카오 화면 동일 동작을 위해 공용 함수로 교체 (사용자: "기본 UI는 차등 두지 말 것")
        binding.btnEditPanelPosition?.let {
            PanelDragHelper.wireEditToggleButton(this, it, binding.svSecondaryPanel, binding.btnMoreMenu, binding.btnConfirmEditPosition, binding.llLeftHudPanel)
        }

        // Tmap 지도 터치 무력화: 화면/정보 표시는 그대로, 지도(NavigationFragment)로 가는
        // 터치 입력만 차단(기본값). v2.0: 설정에서 "잠금 해제"를 켰으면 통과시킴. #문제시 원복
        applyMapTouchLockState(
            getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                .getBoolean("map_touch_unlocked", false)
        )

        // v: 사용자 제보(2026-08-08) - 옛날 프로토콜(NDA)만 지원하는 openpilot 포크를 쓰는
        // 사용자는 NDA로 실제 잘 연결돼있어도 화면엔 계속 "대기중"만 떴음(메인 채널만 보던
        // 버그). 이제 메인 채널 또는 NDA 채널, 둘 중 하나라도 연결되면 "연결됨"으로 표시.
        // #문제시 원복
        fun updateConnectionUi() {
            val state = OpenpilotStateRepository.state.value
            val mainConnected = state != null && state.ip.isNotEmpty() && state.ip != "-"
            val ndaConnected = OpenpilotStateRepository.ndaConnected.value == true

            // v: 사용자 최종 확정(2026-08-10) - 화면에 뜨는 문구는 딱 4개만:
            // "Cruise On"/"Cruise Off"(위 줄), "콤마 연결 중"/"콤마 연결 대기"(아래 줄).
            // "콤마 연결됨"이나 빈 칸 상태는 없음 - 연결됐으면 그냥 "콤마 연결 중"으로 표시. #문제시 원복
            if (mainConnected || ndaConnected) {
                binding.vConnectionDot.setBackgroundResource(R.drawable.shape_circle_green)
                binding.tvConnectionStatus.text = "Comma 연결됨"
                // v: 사용자 요청(재억, 2026-08-12) - 연결됨 상태를 위쪽 Cruise
                // 표시(파란색 #4FC3F7)와 구분되게 초록색으로 강조. #문제시 원복
                binding.tvConnectionStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
            } else {
                binding.vConnectionDot.setBackgroundResource(R.drawable.shape_circle_gray)
                binding.tvConnectionStatus.text = "Comma 연결 대기"
                binding.tvConnectionStatus.setTextColor(android.graphics.Color.parseColor("#555555"))
            }
        }

        OpenpilotStateRepository.ndaConnected.observe(this) { updateConnectionUi() }

        OpenpilotStateRepository.state.observe(this) { state ->
            binding.tvCarrotVersion.text = state.carrot2
            binding.tvCarrotIp.text = if (state.ip.isNotEmpty() && state.ip != "-") "IP: ${state.ip}" else "IP: -"

            updateConnectionUi()

            // v4.23: 실주행 로그로 openpilot 자체 발신 active값이 초당 2회씩 진동하는 게
            // 확인됨(사용자 지적: OP ON/OFF 반복 점멸) - 원본 active 대신 안정화된
            // displayActive 사용. 진동이 심하면 ON/OFF 대신 "OP 불안정"으로 표시해서
            // 실제 불안정 상태를 숨기지 않으면서도 화면이 초당 2번 깜빡이진 않게 함. #문제시 원복
            when {
                state.isFlickering -> {
                    binding.tvActiveStatus.text = "Cruise 불안정"
                    binding.tvActiveStatus.setTextColor(android.graphics.Color.parseColor("#FFA726"))
                    binding.tvActiveStatus.background = null
                }
                state.displayActive -> {
                    binding.tvActiveStatus.text = "Cruise On"
                    binding.tvActiveStatus.setTextColor(android.graphics.Color.parseColor("#4FC3F7"))
                    binding.tvActiveStatus.background = null
                }
                else -> {
                    binding.tvActiveStatus.text = "Cruise Off"
                    binding.tvActiveStatus.setTextColor(android.graphics.Color.parseColor("#555555"))
                    binding.tvActiveStatus.background = null
                }
            }

            when (state.trafficState) {
                1 -> binding.vTrafficLight.setBackgroundResource(R.drawable.shape_circle_red)
                2 -> binding.vTrafficLight.setBackgroundResource(R.drawable.shape_circle_green)
                else -> binding.vTrafficLight.setBackgroundResource(R.drawable.shape_circle_gray)
            }
        }

        initTmapSdk(appKey)

        // v12.9: 안내 이어가기 - 앱을 새로 열었을 때 이전에 안내 중이던 목적지가
        // 저장돼 있으면 "이어서 안내할까요?" 물어봄(재억 요청). 자동으로 바로 시작하지
        // 않고 꼭 물어봐서, 다른 곳으로 갈 상황이면 그냥 무시하고 새로 검색할 수 있게 함. #문제시 원복
        ResumeGuidanceStore.get(this)?.let { saved ->
            android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("안내 이어가기")
                .setMessage("${saved.name}(으)로 가던 중이었어요. 이어서 안내할까요?")
                .setPositiveButton("이어서 안내") { _, _ ->
                    startKakaoOverlayGuidance(saved.name, saved.lat, saved.lon, saved.routePriorityName, saved.routeAvoidOption)
                }
                .setNegativeButton("아니요") { _, _ ->
                    ResumeGuidanceStore.clear(this)
                }
                .setCancelable(false)
                .show()
        }

        // v: 재억 제보(2026-08-25) - 홈화면 바로가기로 열었는데 그냥 시작화면만 뜨고
        // 길안내로 안 이어지던 문제 - MainActivity가 넘겨준 slot을 받아 실제로 처리.
        // 이 시점(방금 새로 켜진 화면)엔 다른 안내 중일 수 없어 확인팝업 없이 바로 시작. #문제시 원복
        // v: 재억 지적(2026-08-26) - 즐겨찾기에 저장된 이동방식(추천/고속도로/무료도로)을
        // 무시하고 항상 기본값으로 안내가 시작되던 문제. 저장된 방식이 있으면 그걸로 바로
        // 시작, 없으면 기존 검색 흐름과 동일하게 선택창을 띄움. #문제시 원복
        QuickSlotShortcutHelper.handleShortcutIntent(this, intent, { false }) { entry ->
            if (entry.routePriorityName != null) {
                startKakaoOverlayGuidance(entry.name, entry.lat, entry.lon, entry.routePriorityName, entry.routeAvoidOption)
            } else {
                showRoutePriorityDialog(entry)
            }
        }
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

                    // v14.9: 재억 지적 - 카카오 안내종료 후 티맵 화면으로 돌아올 때 가끔
                    // 화면이 몇 초간 멈추는 증상. 원인 확인해보니, 여기 있던 "SDK에 이런
                    // 기능이 있는지 찾아보는" 디버그용 리플렉션 전수 조사 코드(음소거/볼륨/
                    // 차선정보/위성지도 API를 찾으려고 SDK 클래스 여러 개의 함수를 통째로
                    // 로그로 찍던 코드, 한 번에 1500줄 넘게 찍음)가 화면이 새로 켜질 때마다
                    // 메인 스레드에서 실행되면서 그동안 화면을 멈추게 하고 있었음. 이미 필요한
                    // 기능(음소거/볼륨/차선정보/위성지도)은 다 찾아서 실제로 구현해뒀으므로
                    // 안전하게 통째로 제거함. #문제시 원복: 아래 git 이력에서 v14.8 이전 버전
                    // MapActivity.kt의 이 위치 코드를 참고

                    startSafeDriveMode()
                    startUdpSenderService()
                    // v4.20: getObservableLaneData() 조사 재도입 - 예전엔 startSafeDriveMode()
                    // 안(=startUdpSenderService()보다 먼저)에서 리플렉션 구독했는데, LiveData가
                    // 구독 시점에 이미 값을 갖고 있으면 observeForever()가 그 자리에서 동기적으로
                    // 콜백을 실행하는 안드로이드 표준 동작 때문에, 포그라운드서비스가 뜨기도 전에
                    // 메인 스레드에서 무거운 재귀 리플렉션이 돌 위험이 있었음(크래시 유력 원인으로
                    // 판단해서 v4.19에서 통째로 뺐었음). 이번엔 순서를 서비스 시작 뒤로 미루고,
                    // 실제 무거운 작업은 백그라운드 스레드로 넘기고, 값 1회만 받고 끝내도록 재작성. #문제시 원복
                    setupObservableLaneDataDump()
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
    // v: 신규기능(주변검색 진행/역방향 표시) - GPS의 bearing(진행방향)을 저장해뒀다가
    // 주변 카테고리 검색 결과 표시에 씀. API 추가 호출 없이 이미 들어오는 GPS 값만 사용. #문제시 원복
    private var lastKnownBearing: Float? = null

    private var lastSearchClickAt = 0L
    // v11.3: 집/회사/즐겨찾기 칸을 등록하려고 검색을 여는 중이면 여기에 어느 칸인지
    // 담아둠. 검색 결과를 고르는 순간(pickEntry) 이 값이 있으면 그 칸에 저장까지
    // 같이 해줌. #문제시 원복
    private var pendingQuickSlotRegistration: String? = null
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
        // v2.6: 상단바 개편 - 자주 안 쓰는 항목(이벤트/신호등/최근검색/설정류)을
        // ≡ 버튼으로 열고 닫는 보조 패널로 뺌. #문제시 원복
        binding.btnMoreMenu?.setOnClickListener { anchorView ->
            NavLogger.d(this, "[더보기메뉴] 버튼 클릭됨(Tmap화면)")
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
        // v11.2: 검색창을 직접 눌러서 타이핑하려고 할 때도 이력 팝업이 키보드랑 같이 떠서
        // 헷갈리던 문제(재억 지적) - v3.13 때 "검색창 탭 = 이력 다이얼로그"로 통일했던 걸
        // 되돌림. 이제 검색창을 직접 누르면 키보드만 뜨고, 이력은 "최근검색" 아이콘을
        // 눌렀을 때만 뜬다(아래 btnVoiceSearch). #문제시 원복
        // 텍스트로 직접 치고 싶을 때를 위해 입력창을 길게 누르면 키보드 포커스로 전환.
        binding.etDestination?.setOnLongClickListener {
            binding.etDestination?.requestFocus()
            true
        }
        // v2.9: 검색 실행 로직을 공용 함수로 - btnSearchGo(세로모드)와 IME 검색 액션(가로모드
        // 상단바 인라인 검색창) 둘 다 여기로. #문제시 원복
        fun executeSearchFromField() {
            val now = System.currentTimeMillis()
            if (now - lastSearchClickAt < SEARCH_CLICK_DEBOUNCE_MS) {
                return // 연타 방지 (로그에서 같은 쿼리 수십회 중복 호출되던 문제)
            }
            lastSearchClickAt = now
            val query = binding.etDestination?.text?.toString()?.trim().orEmpty()
            if (query.isNotEmpty()) {
                performDestinationSearch(query)
            }
        }
        binding.etDestination?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                executeSearchFromField()
                true
            } else {
                false
            }
        }
        binding.btnSearchClose?.setOnClickListener {
            binding.llSearchPanel?.visibility = View.GONE
        }
        binding.btnSearchGo?.setOnClickListener { executeSearchFromField() }
        binding.btnSearchGo?.setOnLongClickListener {
            promptForKakaoKeyThenSearch("") // 빈 쿼리로 열면 저장만 하고 검색은 안 함(취소 눌러도 됨)
            true
        }
        // v3.13: 최근검색 아이콘이 삭제 기능 없는 showSearchHistory()를 부르고 있었음 -
        // 삭제 가능한 showFullSearchHistoryDialog()로 교체해서 카카오 화면과 통일
        // (사용자 지적: "카카오맵엔 삭제 기능 있는데 티맵엔 빠짐"). #문제시 원복
        binding.btnVoiceSearch?.setOnClickListener {
            showFullSearchHistoryDialog()
        }
        binding.btnStopGuidance?.setOnClickListener {
            stopGuidance()
        }
        binding.btnShareLogTopBar?.setOnClickListener {
            shareNavLog()
        }
        // v11.9: 집/회사를 상단바 고정 버튼으로 뺌(재억 요청) - 짧게 누르면 등록 안 됐을 땐
        // 검색해서 등록+바로 안내, 등록 됐으면 검색 없이 바로 안내. 길게 누르면 이미
        // 등록돼 있어도 무시하고 다시 검색해서 덮어씀. #문제시 원복
        // v13.1-2: 세로모드 상단바에 새로 넣은 "최근검색" 아이콘 연결(재억 지적). 가로모드/
        // 카카오 화면엔 이 버튼이 원래 없으므로(검색창 옆 시계 아이콘이 이미 있음)
        // null 안전 처리(?.)로 세로모드에서만 동작. #문제시 원복
        binding.btnHistoryTopBar?.setOnClickListener { showFullSearchHistoryDialog() }
        wireTopBarQuickSlotButton(binding.btnHomeQuickSlot, QuickSlotStore.SLOT_HOME)
        wireTopBarQuickSlotButton(binding.btnWorkQuickSlot, QuickSlotStore.SLOT_WORK)
        setupNearbyCategoryButton()
    }

    // v12.3: 재억 요청 - 등록된 칸을 길게 누르면 예전처럼 바로 재검색하지 않고,
    // "다시 검색 / 이름 변경 / 삭제 / 취소" 선택창을 먼저 보여줌. 등록 안 된 칸은
    // 예전처럼 바로 검색창으로 감. 집/회사/즐겨찾기 전부 이 함수 하나로 공용 처리. #문제시 원복
    private fun showQuickSlotLongPressMenu(slot: String) {
        val existing = QuickSlotStore.get(this, slot)
        if (existing == null) {
            pendingQuickSlotRegistration = slot
            showTmapTextSearchDialog()
            return
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(existing.name)
            // v14.10: 재억 요청 - 메뉴 순서를 "다시 검색 -> 이름 변경 -> 경로 방식 변경 ->
            // 삭제 -> 취소"로 재변경. "안내 방법 변경"은 "경로 방식 변경"으로 이름도 변경. #문제시 원복
            // v: 신규기능(홈화면 바로가기) - "경로 방식 변경"과 "삭제" 사이에 추가(재억 확인
            // 완료). 즐겨찾기를 홈화면 아이콘으로 고정. #문제시 원복
            .setItems(arrayOf("다시 검색", "이름 변경", "경로 방식 변경", "홈화면에 추가", "삭제", "취소")) { _, which ->
                when (which) {
                    0 -> {
                        pendingQuickSlotRegistration = slot
                        showTmapTextSearchDialog()
                    }
                    1 -> {
                        val input = android.widget.EditText(this).apply {
                            setText(existing.name)
                            setTextColor(android.graphics.Color.WHITE)
                        }
                        android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                            .setTitle("이름 변경")
                            .setView(input)
                            .setPositiveButton("저장") { _, _ ->
                                val newName = input.text.toString().trim()
                                if (newName.isNotEmpty()) {
                                    QuickSlotStore.save(this, slot, existing.copy(name = newName))
                                }
                            }
                            .setNegativeButton("취소", null)
                            .show()
                    }
                    // v13.2-3: 재억 요청 - 저장해둔 이동 방식(추천/고속도로/무료도로)만 바꾸고
                    // 싶을 때. 고르면 저장만 되고(v13.10부터) 바로 안내를 시작하지는 않음. #문제시 원복
                    2 -> showRoutePriorityDialog(existing, saveToSlot = slot)
                    3 -> QuickSlotShortcutHelper.requestPin(this, slot, existing)
                    4 -> QuickSlotStore.delete(this, slot)
                }
            }
            .show()
    }

    // v: 재억 제보(2026-09-02) - "안내 중에 즐겨찾기를 누르면 경유지로 추가할지 물어봐야
    // 하는데 바로 새 안내를 시작한다". 티맵 화면에도 카카오 화면과 동일한 확인창을 붙임.
    // 티맵 화면엔 경로를 다시 짜는 코드가 없으므로, "경유지 추가"를 고르면 요청만 남기고
    // 화면을 닫아서 뒤에 살아있는 카카오 안내 화면이 이어받아 처리하게 함. #문제시 원복
    private fun handleQuickSlotTap(existing: HistoryEntry) {
        val guidingNow = KakaoRouteDataRepository.isFresh()
        val destName = KakaoRouteDataRepository.destinationName
        if (guidingNow) {
            android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("경유지로 추가할까요?")
                .setMessage("'${existing.name}'을(를) 지금 안내($destName)의 경유지로 추가할까요, 아니면 새 목적지로 바꿀까요?")
                .setPositiveButton("경유지 추가") { _, _ ->
                    PendingWaypointRequest.put(existing)
                    NavLogger.d(this, "[경유지추가][티맵화면] '${existing.name}' 요청 남기고 카카오 안내 화면으로 복귀")
                    Toast.makeText(this, "'${existing.name}' 경유지로 추가 중...", Toast.LENGTH_SHORT).show()
                    finish()
                }
                .setNegativeButton("새 목적지로") { _, _ -> startGuidanceToQuickSlot(existing) }
                .setNeutralButton("취소", null)
                .show()
        } else {
            startGuidanceToQuickSlot(existing)
        }
    }

    /** 즐겨찾기 칸의 장소로 새 안내 시작(예전 동작 그대로). #문제시 원복 */
    private fun startGuidanceToQuickSlot(existing: HistoryEntry) {
        // v13.5: 재억 지적 - 처음 고른 걸 자동으로 영구 저장해버리면 안 됨.
        // 저장 안 된 상태에서는 매번 물어보되, 그때 고른 건 "이번 한 번만" 쓰고
        // 저장은 안 함. 진짜로 계속 그 방식 쓰고 싶으면 길게 눌러서 "이동방식
        // 저장"으로 따로 저장해야 함. #문제시 원복
        if (existing.routePriorityName == null) {
            showRoutePriorityDialog(existing)
        } else {
            startKakaoOverlayGuidance(
                existing.name, existing.lat, existing.lon,
                existing.routePriorityName, existing.routeAvoidOption
            )
        }
    }

    private fun wireTopBarQuickSlotButton(button: View?, slot: String) {
        button?.setOnClickListener {
            val existing = QuickSlotStore.get(this, slot)
            if (existing != null) {
                handleQuickSlotTap(existing)
            } else {
                pendingQuickSlotRegistration = slot
                showTmapTextSearchDialog()
            }
        }
        button?.setOnLongClickListener {
            showQuickSlotLongPressMenu(slot)
            true
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

    // 로그 파일을 이메일(jaeeok.cho@icloud.com, choksa55@gmail.com 두 곳 동시)로 공유 -
    // 회전되어 쌓여있던 로그까지 전부 한번에 보냄.
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
    // v2.5: MapActivity/KakaoNaviActivity가 각자 이력 코드를 따로 갖고 있다가 서로 어긋나서
    // 생긴 버그(카카오 화면에 원본 JSON 노출) 이후로, SearchHistoryStore.kt 공용 저장소로 통합. #문제시 원복
    private fun getSearchHistory(): List<HistoryEntry> = SearchHistoryStore.get(this)

    private fun saveSearchHistory(entry: HistoryEntry) {
        SearchHistoryStore.save(this, entry)
        updateRecentSearchPanel()
    }

    private fun deleteSearchHistoryEntry(entry: HistoryEntry) {
        SearchHistoryStore.delete(this, entry)
        updateRecentSearchPanel()
    }

    private fun clearSearchHistory() {
        SearchHistoryStore.clear(this)
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
                    // v4.17: 이미 있는 최근목적지를 다시 탭해도 맨 위로 안 올라오던 문제
                    // (사용자 요청: 최신순 정렬) - 다시 저장해서 순서 갱신. #문제시 원복
                    saveSearchHistory(entry)
                    // v: 재억 재지적(2026-08-28) - "즐겨찾기/최근검색/목적지검색/경유지검색/
                    // 카테고리 다 확인해봐" - 화면에 상시 떠있는 이 "최근 검색" 패널(목록
                    // 팝업이 아니라 메인 화면의 tvRecentSearch 줄)도 저장된 이동방식 여부와
                    // 상관없이 무조건 곧바로 안내를 시작하고 있었음. #문제시 원복
                    showRoutePriorityDialog(entry)
                    tv.postDelayed({
                        tv.isEnabled = true
                        tv.alpha = 1.0f
                    }, 1500)
                }
            }
        }
        findViewById<View?>(resources.getIdentifier("btnMoreHistory", "id", packageName))?.setOnClickListener {
            binding.svSecondaryPanel?.visibility = View.GONE
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
        // v13.9: 재억 지적(영상으로 확인) - 시간이 계산되면서 줄 높이가 바뀌고, 그
        // 높이 변화 때문에 목록이 다시 그려지고, 다시 그려질 때 "검색 중"으로 되돌아가서
        // 계산을 또 새로 시작하는 게 끝없이 반복되던 문제(느린 안내/느린 검색의 진짜 원인).
        // 한 번 계산한 결과를 여기 저장해두고, 다시 그려질 땐 저장된 값을 바로 씀 - 계산도
        // "검색 중" 되돌림도 다시 안 함. #문제시 원복
        // v14.1: 재억 지적(스샷으로 확인 - "노블워시가 2시간 48분이었다가 22분으로 바뀜")
        // - 캐시를 "몇 번째 줄인지"(position)로 저장하고 있었는데, 검색을 새로 하면 최근
        // 이력 목록 순서가 바뀜(방금 검색한 게 위로 올라감). 그러면 "1번째 줄"이 가리키는
        // 목적지가 바뀌는데 캐시는 그걸 모르고 예전 값을 그대로 보여줌 - 실제로는 다른
        // 목적지의 계산 결과가 잘못 표시된 것. 순번이 아니라 목적지 좌표 자체를 키로 써서,
        // 순서가 바뀌어도 항상 그 목적지 고유의 값만 표시되도록 함. #문제시 원복
        fun etaCacheKey(entry: HistoryEntry) = "${entry.lat},${entry.lon}"
        val etaResultCache = HashMap<String, String?>()
        // v13.10: 재억 지적(영상+로그로 재확인) - 위 캐시로 "다시 계산하는 것"은 막았지만,
        // 목록이 처음 뜨는 순간엔 보이는 줄 전부(예: 6개)가 동시에 카카오 쪽에 계산을
        // 요청하고 있었음. 즐겨찾기 팝업에서 고친 것과 같은 문제 - 한꺼번에 몰리면
        // 카카오 SDK 쪽 응답이 하나씩 몇 초씩 밀려서 나오고, 그동안 목록이 멈춘 것처럼
        // 보임(실제로 최대 1분 가까이). 즐겨찾기와 동일하게 "동시 최대 2개까지만" 요청이
        // 나가도록 대기열을 둠 - 나머지 줄은 앞 요청이 끝나야 순서대로 시작됨. #문제시 원복
        val etaPendingQueue = ArrayDeque<Int>()
        val etaCallbacks = HashMap<String, MutableList<(String?) -> Unit>>()
        var etaActiveCount = 0
        val etaMaxConcurrent = 2
        val etaMyGeneration = etaQueueGeneration
        fun etaPump() {
            while (etaQueueGeneration == etaMyGeneration && etaActiveCount < etaMaxConcurrent && etaPendingQueue.isNotEmpty()) {
                val pos = etaPendingQueue.removeFirst()
                val entry = history.getOrNull(pos) ?: continue
                val key = etaCacheKey(entry)
                if (etaResultCache.containsKey(key)) continue
                val (curLat, curLon) = resolveCurrentWgs84LatLon()
                if (curLat == null || curLon == null) {
                    etaResultCache[key] = null
                    runOnUiThread { etaCallbacks.remove(key)?.forEach { it(null) } }
                    continue
                }
                etaActiveCount++
                var settled = false
                val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
                val timeoutRunnable = Runnable {
                    if (!settled) {
                        settled = true
                        NavLogger.e(this, "[이력소요시간] 8초 타임아웃 - 포기하고 다음으로: ${entry.name}")
                        etaResultCache[key] = null
                        etaActiveCount--
                        runOnUiThread { etaCallbacks.remove(key)?.forEach { it(null) } }
                        etaPump()
                    }
                }
                // v14.1: 재억 지적(스샷으로 확인 - "요청 시도"에서 안 넘어가는 증상) -
                // 요청 하나가 응답을 영영 못 받으면 대기열 전체가 거기서 멈춰버림.
                // 8초 최대 대기시간을 걸어서, 넘으면 포기하고 다음으로 넘어가게 함. #문제시 원복
                timeoutHandler.postDelayed(timeoutRunnable, 8000L)
                KakaoSdkState.computeEta(this, curLat, curLon, entry.lat, entry.lon) { minutes, _ ->
                    if (settled) return@computeEta
                    settled = true
                    timeoutHandler.removeCallbacks(timeoutRunnable)
                    val etaText = SearchRanking.formatEtaMinutes(minutes)
                    etaResultCache[key] = etaText
                    etaActiveCount--
                    runOnUiThread { etaCallbacks.remove(key)?.forEach { it(etaText) } }
                    etaPump()
                }
            }
        }
        fun requestEta(position: Int, onResult: (String?) -> Unit) {
            val entry = history.getOrNull(position) ?: return
            val key = etaCacheKey(entry)
            if (etaResultCache.containsKey(key)) {
                onResult(etaResultCache[key])
                return
            }
            etaCallbacks.getOrPut(key) { mutableListOf() }.add(onResult)
            if (!etaPendingQueue.contains(position)) {
                etaPendingQueue.addLast(position)
            }
            etaPump()
        }

        fun buildAdapter(): android.widget.BaseAdapter = object : android.widget.BaseAdapter() {
            override fun getCount() = history.size
            override fun getItem(position: Int) = history[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val entry = history[position]
                val row = android.widget.LinearLayout(this@MapActivity).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setBackgroundColor(android.graphics.Color.parseColor("#181818"))
                    setPadding(24, 20, 16, 20)
                }
                val nameText = android.widget.TextView(this@MapActivity).apply {
                    text = if (entry.addr.isNotBlank()) "${entry.name}\n${entry.addr}" else entry.name
                    setTextColor(android.graphics.Color.WHITE)
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                }
                // v13.8: 재억 지적 - 최근검색 이력 목록의 시간이 검색결과/즐겨찾기와 달리
                // 흰색으로만 나오던 문제 - highlightEta()를 안 쓰고 있었음. #문제시 원복
                fun applyHistoryLabel(etaText: String?, isFinal: Boolean) {
                    val base = if (etaText != null) "${entry.name} · $etaText" else entry.name
                    val label = if (entry.addr.isNotBlank()) "$base\n${entry.addr}" else base
                    nameText.text = if (isFinal) highlightEta(label, etaText) else label
                }
                val entryKey = etaCacheKey(entry)
                if (etaResultCache.containsKey(entryKey)) {
                    // v13.9: 이미 계산해둔 값이 있으면 바로 보여주고 끝 - 재계산 없음. #문제시 원복
                    applyHistoryLabel(etaResultCache[entryKey], isFinal = true)
                } else {
                    applyHistoryLabel("검색 중", isFinal = false)
                    requestEta(position) { etaText ->
                        applyHistoryLabel(etaText, isFinal = true)
                    }
                }
                // v14.4: 재억 요청 - 최근목적지 항목에서 바로 즐겨찾기(집/회사/즐겨찾기1~5)로
                // 등록할 수 있는 "저장" 버튼. 이미 등록된 칸을 골라도 그대로 덮어씀(재억 확인).
                // #문제시 원복
                val saveText = android.widget.TextView(this@MapActivity).apply {
                    text = "저장"
                    textSize = 14f
                    setTextColor(android.graphics.Color.parseColor("#A0C8F0"))
                    setBackgroundColor(android.graphics.Color.parseColor("#233A4A"))
                    setPadding(36, 20, 36, 20)
                    val marginParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    marginParams.marginStart = 16
                    layoutParams = marginParams
                    setOnClickListener {
                        showQuickSlotPickerForSave(entry)
                    }
                }
                // v10.9-5: 예전엔 "✕" 작은 글자 하나만 있어서 실차 화면에서 눌러야 하는
                // 위치가 잘 안 보이고 오터치도 잦았음(재억 지적) - 배경이 있는 "삭제" 글자
                // 버튼으로 바꾸고, 누르는 영역(패딩)도 훨씬 넓게 키움. #문제시 원복
                val deleteText = android.widget.TextView(this@MapActivity).apply {
                    text = "삭제"
                    textSize = 14f
                    setTextColor(android.graphics.Color.parseColor("#F0A0A0"))
                    setBackgroundColor(android.graphics.Color.parseColor("#3A2323"))
                    setPadding(36, 20, 36, 20)
                    val marginParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    marginParams.marginStart = 16
                    layoutParams = marginParams
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
                row.addView(saveText)
                row.addView(deleteText)
                return row
            }
        }

        listView = android.widget.ListView(this)
        listView.adapter = buildAdapter()
        listView.setBackgroundColor(android.graphics.Color.parseColor("#181818"))
        listView.divider = android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#333333"))
        listView.dividerHeight = 1

        // v11.3: 집/회사/즐겨찾기1/2/3 다섯 칸 빠른등록 아이콘 행 - 짧게 누르면 등록 안
        // 됐을 땐 검색해서 등록+바로 안내, 등록 됐으면 바로 안내. 길게 누르면 이미
        // 등록돼 있어도 무시하고 다시 검색해서 덮어씀. 글자 라벨 없이 아이콘만.
        // 다이얼로그 제목(setTitle) 자리에 이 행까지 포함한 커스텀 뷰를 넣음. #문제시 원복
        fun buildQuickSlotButton(slot: String, emoji: String): Pair<View, android.widget.TextView> {
            val etaText = android.widget.TextView(this).apply {
                textSize = 9f
                gravity = android.view.Gravity.CENTER
                setTextColor(android.graphics.Color.parseColor("#FFD54F"))
                // v13.0-3: 재억 지적 - "2시간 52분"처럼 긴 시간을 한 줄+말줄임표로 억지로
                // 우겨넣었더니 뒷부분이 "..."로 잘려서 몇 시간 몇 분인지 안 보였음.
                // 숫자가 안 잘리는 게 우선이라, 글자를 더 줄이고 최대 2줄까지 자연스럽게
                // 줄바꿈되도록 함(말줄임표 없음 - 다 보이는 게 목적). #문제시 원복
                maxLines = 2
            }
            // v12.2: 등록된 즐겨찾기 칸은 하트 대신 등록된 장소 이름을 보여줌(재억 요청 -
            // "서울역 검색해서 등록했으면 하트 대신 서울역으로 표시"). 이름이 길면 한 줄로
            // 잘라서 표시. 등록 안 된 칸은 지금처럼 하트 + "미등록". #문제시 원복
            val registeredEntry = QuickSlotStore.get(this@MapActivity, slot)
            val iconText = android.widget.TextView(this).apply {
                if (registeredEntry != null) {
                    text = registeredEntry.name
                    textSize = 12f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(android.graphics.Color.parseColor("#EEEEEE"))
                } else {
                    text = emoji
                    textSize = 20f
                }
                gravity = android.view.Gravity.CENTER
            }
            if (registeredEntry == null) {
                etaText.text = "미등록"
                etaText.setTextColor(android.graphics.Color.parseColor("#555555"))
                etaText.textSize = 9f
            }
            val container = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setBackgroundColor(android.graphics.Color.parseColor("#262626"))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginEnd = 12 }
                setPadding(8, 20, 8, 20)
                addView(iconText)
                addView(etaText)
                setOnClickListener {
                    val existing = QuickSlotStore.get(this@MapActivity, slot)
                    dialog.dismiss()
                    if (existing != null) {
                        // v: 재억 제보(2026-09-02) - 상단바 버튼과 동일하게, 안내 중이면
                        // "경유지 추가 / 새 목적지"를 먼저 물어봄. #문제시 원복
                        handleQuickSlotTap(existing)
                    } else {
                        pendingQuickSlotRegistration = slot
                        showTmapTextSearchDialog()
                    }
                }
                setOnLongClickListener {
                    dialog.dismiss()
                    showQuickSlotLongPressMenu(slot)
                    true
                }
            }
            return Pair(container, etaText)
        }
        // v13.0-4: 재억 요청 - 설정에서 정한 개수(0~5)만큼만 즐겨찾기 칸을 보여줌. #문제시 원복
        // v: \uC7AC\uC5B5 \uC694\uCCAD(2026-09-02) - \uCD5C\uB300 5\uAC1C \uD558\uB4DC\uCF54\uB529\uC744 10\uAC1C\uAE4C\uC9C0 \uB3D9\uC801 \uC0DD\uC131\uC73C\uB85C \uBCC0\uACBD. #\uBB38\uC81C\uC2DC \uC6D0\uBCF5
        val favoriteCount = QuickSlotStore.favoriteCount(this)
        val quickSlotButtons = QuickSlotStore.favoriteSlots(favoriteCount)
            .map { slot -> slot to buildQuickSlotButton(slot, "\u2764\uFE0F") }
        // v11.9: 집/회사는 상단바 고정 버튼으로 빠져서 여기 팝업엔 즐겨찾기 3칸만 남음.
        // 카드 하나당 너비를 고정폭(52dp)으로 줄여서, 제목("검색 이력 전체")과 같은 줄
        // 오른쪽에 나란히 놓이도록 함(재억 요청). #문제시 원복
        val quickSlotRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            quickSlotButtons.forEach { (_, pair) ->
                pair.first.layoutParams = android.widget.LinearLayout.LayoutParams(130, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = 8
                }
                addView(pair.first)
            }
        }
        // v11.4: 팝업이 뜨자마자, 등록된 칸들만 조용히 카카오 경로계산을 돌려서
        // "OO분"으로 채움. 계산 중엔 "…", 실패하면 빈 칸으로 둠(재억 요청). #문제시 원복
        // v14.1: 재억 지적(로그+스샷으로 재확인) - 세 군데(즐겨찾기/이력/검색결과)를 전부
        // "동시 최대 2개"로 통일. 추가로, 요청 하나가 응답을 영영 못 받으면(카카오 SDK
        // 쪽 문제) 대기열 전체가 거기서 영원히 멈춰버리는 문제가 있었음("요청 시도"에서
        // 안 넘어가는 증상, 스샷으로 확인). 각 요청에 8초 최대 대기시간을 걸어서, 넘으면
        // 포기하고 다음으로 넘어가도록 함. #문제시 원복
        val etaTimeoutMs = 8000L
        val quickSlotEntries = quickSlotButtons.mapNotNull { (slot, pair) ->
            val entry = QuickSlotStore.get(this, slot) ?: return@mapNotNull null
            Pair(pair.second, entry)
        }
        val (quickSlotCurLat, quickSlotCurLon) = resolveCurrentWgs84LatLon()
        quickSlotEntries.forEach { (etaText, _) ->
            etaText.text = "검색 중"
            etaText.setTextColor(android.graphics.Color.parseColor("#FFD54F"))
            etaText.textSize = 9f
        }
        val quickSlotPendingQueue = ArrayDeque<Int>()
        var quickSlotActiveCount = 0
        val quickSlotMaxConcurrent = 2
        val quickSlotMyGeneration = etaQueueGeneration
        fun quickSlotPump() {
            while (etaQueueGeneration == quickSlotMyGeneration && quickSlotActiveCount < quickSlotMaxConcurrent && quickSlotPendingQueue.isNotEmpty()) {
                val index = quickSlotPendingQueue.removeFirst()
                val (etaText, entry) = quickSlotEntries[index]
                if (quickSlotCurLat == null || quickSlotCurLon == null) {
                    etaText.text = ""
                    continue
                }
                quickSlotActiveCount++
                var settled = false
                val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
                val timeoutRunnable = Runnable {
                    if (!settled) {
                        settled = true
                        NavLogger.e(this, "[즐겨찾기소요시간] 8초 타임아웃 - 포기하고 다음으로: ${entry.name}")
                        etaText.text = ""
                        quickSlotActiveCount--
                        quickSlotPump()
                    }
                }
                timeoutHandler.postDelayed(timeoutRunnable, etaTimeoutMs)
                KakaoSdkState.computeEta(this, quickSlotCurLat, quickSlotCurLon, entry.lat, entry.lon) { minutes, _ ->
                    if (settled) return@computeEta
                    settled = true
                    timeoutHandler.removeCallbacks(timeoutRunnable)
                    runOnUiThread {
                        etaText.text = SearchRanking.formatEtaMinutes(minutes) ?: ""
                    }
                    quickSlotActiveCount--
                    quickSlotPump()
                }
            }
        }
        quickSlotEntries.indices.forEach { quickSlotPendingQueue.addLast(it) }
        quickSlotPump()
        // v11.9: 제목("검색 이력 전체")과 즐겨찾기 3칸을 같은 줄 좌우로 배치(재억 요청). #문제시 원복
        val titleView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#212121"))
            setPadding(24, 24, 24, 20)
            addView(android.widget.TextView(this@MapActivity).apply {
                text = "검색 이력 전체"
                textSize = 18f
                setTextColor(android.graphics.Color.WHITE)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            // v: 재억 요청(2026-09-02) - 즐겨찾기가 최대 10칸까지 늘어나면서 한 줄에 다
            // 안 들어감(칸당 고정폭 130px). 가로 스크롤로 감싸서 개수와 무관하게 항상
            // 전부 접근 가능하게 함. 5칸 이하일 땐 예전과 똑같이 보임. #문제시 원복
            addView(android.widget.HorizontalScrollView(this@MapActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(quickSlotRow)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 2f
                )
            })
        }

        dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setCustomTitle(titleView)
            .setView(listView)
            .setPositiveButton("전체 삭제") { _, _ ->
                android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("검색 이력 전체 삭제")
                    .setMessage("검색 이력을 전부 삭제할까요?")
                    .setPositiveButton("삭제") { _, _ -> clearSearchHistory() }
                    .setNegativeButton("취소", null)
                    .show()
            }
            .setNegativeButton("닫기") { _, _ ->
                // v2.6에서 고친 것과 동일: 닫을 때 포커스를 유지하고 키보드를 명시적으로
                // 다시 띄워줌 (검색창 탭 -> 이 다이얼로그가 뜨는 경로에서 닫으면 키보드가
                // 안 뜨던 문제 재발 방지). #문제시 원복
                binding.etDestination?.requestFocus()
                binding.etDestination?.post {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.showSoftInput(binding.etDestination, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
            }
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            val picked = history[position]
            dialog.dismiss()
            // v2.1: 재검색이 아니라 저장된 좌표로 바로 길안내 시작 (4번)
            // v4.17: 다시 탭해도 최신순 맨 위로 올라오게. #문제시 원복
            // v13.6: 재억 지적 - 최근검색 항목도 즐겨찾기와 동일하게 매번 "추천/고속도로/
            // 무료도로" 경로 선택 팝업을 거치도록 함(예전엔 그냥 추천으로 바로 안내 시작했음). #문제시 원복
            saveSearchHistory(picked)
            showRoutePriorityDialog(picked)
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
            .setNegativeButton("닫기") { _, _ ->
                // v2.6: clearFocus()가 소프트키보드까지 같이 닫아버려서, 다이얼로그를 닫은
                // 뒤 텍스트를 입력하려 해도 키보드가 안 뜨는 버그가 있었음(사용자 제보: 음성검색
                // 취소 후 텍스트 입력하려는데 이 다이얼로그가 막고, 닫으면 키보드까지 닫혀서
                // 입력 불가). 포커스는 유지하고 오히려 키보드를 명시적으로 다시 띄워줌. #문제시 원복
                binding.etDestination?.requestFocus()
                binding.etDestination?.post {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.showSoftInput(binding.etDestination, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
            }
            .create()
        listView.setOnItemClickListener { _, _, position, _ ->
            val picked = history[position]
            dialog.dismiss()
            // v4.17: 다시 탭해도 최신순 맨 위로 올라오게. #문제시 원복
            saveSearchHistory(picked)
            startKakaoOverlayGuidance(picked.name, picked.lat, picked.lon)
        }
        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#212121")))
    }

    // v1.7: 검색결과/이력 목록에 android.R.layout.simple_list_item_1을 그대로 쓰면 앱 기본
    // 테마(Light)의 글자색(검정)이 어두운 패널 배경(#EE111111 등) 위에서 안 보이는 문제가
    // 있었음(사용자 지적: "텍스트 검색 시 뒷 배경색과 동일해서 텍스트 안보임"). 글자색을
    // 명시적으로 흰색으로 지정하는 어댑터로 교체. #문제시 원복
    // v12.9: 검색 결과 목록의 "· N분"(소요시간) 부분을 SpannableString으로 색을 다르게
    // 입힐 수 있도록 String -> CharSequence로 확장(재억 요청 - 시인성 올리기). #문제시 원복
    // v14.9: redRowIndex - "저장된 방식 삭제" 같은 위험한 항목만 빨간 글씨로 강조하고
    // 싶을 때 그 줄 번호를 넘겨줌(없으면 전부 흰색 그대로). #문제시 원복
    private fun darkTextAdapter(items: List<CharSequence>, redRowIndex: Int? = null): android.widget.ArrayAdapter<CharSequence> {
        return object : android.widget.ArrayAdapter<CharSequence>(
            this, android.R.layout.simple_list_item_1, android.R.id.text1, items
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val tv = view.findViewById<android.widget.TextView>(android.R.id.text1)
                tv.setTextColor(
                    if (position == redRowIndex) android.graphics.Color.parseColor("#E24B4A")
                    else android.graphics.Color.WHITE
                )
                tv.setPadding(24, 20, 24, 20)
                return view
            }
        }
    }

    // v13.2-2: 재억 지적 - 검색 결과에서 고를 때만 경로 선택 팝업이 뜨고, 즐겨찾기/집/회사
    // 등록된 칸을 눌렀을 땐 안 뜨던 문제. 클래스 전체에서 쓸 수 있는 메소드로 빼서
    // 검색 결과/즐겨찾기/집/회사 전부 동일하게 이 팝업을 거치도록 함. #문제시 원복
    // v13.6: 재억 요청 - 무료도로 우선이 추천 경로랑 거리가 똑같으면(=톨게이트/유료도로
    // 자체가 없는 구간) 굳이 안 물어보고 바로 추천 경로로 감. 그래서 팝업을 미리 띄우지
    // 않고, 3개 다 계산이 끝날 때까지 조용히 기다렸다가 판단함. #문제시 원복
    private fun showRoutePriorityDialog(picked: HistoryEntry, saveToSlot: String? = null) {
        // v14.2: 재억 아이디어 - 항목을 골라서 여기로 들어온 순간, 배경에서 돌던 나머지
        // 목록 계산들은 더 안 늘어나게 멈춤(위 etaQueueGeneration 설명 참고). #문제시 원복
        etaQueueGeneration++
        val optionLabels = listOf("추천 경로", "고속도로 우선", "무료도로 우선")
        val optionPriorities = listOf(
            KNRoutePriority.KNRoutePriority_Recommand,
            KNRoutePriority.KNRoutePriority_HighWay,
            KNRoutePriority.KNRoutePriority_Recommand
        )
        val optionAvoidOptions = listOf(0, 0, KNRouteAvoidOption.KNRouteAvoidOption_Fare.value)

        // v14.9: 재억 요청 - "경로 방식 변경" 메뉴(saveToSlot != null)로 들어왔을 때만
        // 맨 아래에 "저장된 방식 삭제"를 추가함. 검색/즐겨찾기에서 바로 물어보는 경우(saveToSlot
        // == null)는 애초에 저장된 게 없을 수도 있는 상황이라 이 항목을 안 보여줌. #문제시 원복
        val CLEAR_OPTION_INDEX = 3

        fun goDirectly(index: Int) {
            if (index == CLEAR_OPTION_INDEX && saveToSlot != null) {
                QuickSlotStore.clearRoutePreference(this, saveToSlot)
                Toast.makeText(this, "${picked.name}의 저장된 이동방식을 지웠어요.", Toast.LENGTH_SHORT).show()
                return
            }
            if (saveToSlot != null) {
                // v13.10: 재억 지적 - "이동방식 저장" 메뉴로 들어왔을 때도 옵션을 고르면
                // 저장과 동시에 무조건 길안내까지 시작해버렸음. "그냥 이동방식만 바꿔서
                // 저장"하고 싶은 경우와 "골라서 바로 출발"하는 경우를 구분 안 한 게 원인.
                // saveToSlot이 있는 경우(=저장 전용 메뉴로 들어온 경우)엔 저장만 하고
                // 안내는 시작하지 않음. #문제시 원복
                QuickSlotStore.updateRoutePreference(this, saveToSlot, optionPriorities[index].name, optionAvoidOptions[index])
                Toast.makeText(this, "${picked.name}의 이동방식을 \"${optionLabels[index]}\"로 저장했어요.", Toast.LENGTH_SHORT).show()
                return
            }
            startKakaoOverlayGuidance(
                picked.name, picked.lat, picked.lon,
                optionPriorities[index].name, optionAvoidOptions[index]
            )
        }

        fun showPickerWithResults(minutesArr: Array<Int?>, distArr: Array<Int?>) {
            val labels = optionLabels.mapIndexed { i, label ->
                "$label\n${SearchRanking.formatEtaMinutes(minutesArr[i]) ?: "계산 실패"}"
            }.toMutableList()
            if (saveToSlot != null) {
                labels.add("저장된 방식 삭제")
            }
            val listView = android.widget.ListView(this)
            val adapter = darkTextAdapter(
                ArrayList<CharSequence>(labels),
                redRowIndex = if (saveToSlot != null) CLEAR_OPTION_INDEX else null
            )
            listView.adapter = adapter
            val routeDialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("${picked.name}\n어떻게 갈까요?")
                .setView(listView)
                .setNegativeButton("취소", null)
                .create()
            listView.setOnItemClickListener { _, _, position, _ ->
                routeDialog.dismiss()
                goDirectly(position)
            }
            routeDialog.show()
            routeDialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#212121")))
        }

        val (curLat, curLon) = resolveCurrentWgs84LatLon()
        if (curLat == null || curLon == null) {
            goDirectly(0)
            return
        }
        val minutesArr = arrayOfNulls<Int>(3)
        val distArr = arrayOfNulls<Int>(3)
        var receivedCount = 0
        // v13.6: 재억 지적(계산 느림) - "출발-도착 연결"을 3번 따로 안 하고 한 번만 해서
        // 그 위에서 3개 우선순위만 각각 계산하도록 함(computeEtaForOptions). #문제시 원복
        // v: 재억 재지적(2026-08-29) - v13.7의 PendingTripHolder 캐시 재사용(안내 시작
        // 딜레이 최소화용)이 "고른 이동방식이 실제 경로에 반영 안 되는 것 같다"는 원인으로
        // 의심돼 재사용 쪽(KakaoNaviActivity)을 제거함 - 여기서 저장만 하고 아무도 안
        // 꺼내 쓰는 죽은 코드가 됐으니 같이 정리. #문제시 원복
        KakaoSdkState.computeEtaForOptions(
            this, curLat, curLon, picked.lat, picked.lon,
            options = optionPriorities.zip(optionAvoidOptions)
        ) { index, minutes, distanceMeters ->
            runOnUiThread {
                minutesArr[index] = minutes
                distArr[index] = distanceMeters
                receivedCount++
                if (receivedCount == 3) {
                    // v: 재억 재지적(2026-08-29) - KakaoNaviActivity와 동일 - "왜 자꾸 팝업
                    // 없이 바로 안내를 시작하냐"는 강한 제보로 이 자동 생략 로직을 완전히
                    // 제거. #문제시 원복
                    showPickerWithResults(minutesArr, distArr)
                }
            }
        }
    }

    // v12.9: 이름/거리 뒤에 "· N분"(소요시간) 부분만 노란색(#FFD54F, 즐겨찾기 시간 색과
    // 통일)으로 강조. etaText가 없으면(계산 실패) 그냥 일반 텍스트. #문제시 원복
    private fun highlightEta(label: String, etaText: String?): CharSequence {
        if (etaText.isNullOrBlank()) return label
        val marker = " · $etaText"
        val start = label.lastIndexOf(marker)
        if (start < 0) return label
        val spannable = android.text.SpannableString(label)
        spannable.setSpan(
            android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#FFD54F")),
            start + 3, start + marker.length,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return spannable
    }

    // v1.6: 속도가 도로 제한속도의 110%를 넘으면 경고음 - 기본 꺼짐, 이 다이얼로그에서 토글. #문제시 원복
    private fun showAppSettingsDialog() {
        PanelDragHelper.showAppSettingsDialog(this, binding.vTouchLockOverlay) {
            applyTmapSatelliteViewSetting()
            applyTmapTrafficInfoSetting()
            // v: 재억 제보(2026-08-26) - 카테고리 버튼 표시를 꺼도 화면에서 바로 안 사라지던
            // 문제. 다른 설정들처럼 저장 즉시 반영. #문제시 원복
            val showCategoryButton = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                .getBoolean("show_category_button", true)
            binding.btnNearbyCategory?.visibility = if (showCategoryButton) View.VISIBLE else View.GONE
            binding.flMiniPlayerContainer?.let { outer ->
                com.tmap.nda.miniplayer.MiniPlayerManager.refresh(
                    this, outer,
                    binding.ivMiniPlayerArt, binding.tvMiniPlayerTitle, binding.tvMiniPlayerArtist,
                    binding.btnMiniPlayerPlayPause
                )
            }
        }
    }

    // v8.7: v8.5 조사에서 확인된 Tmap SDK 자체 API - NavigationFragment.setMapLayerTypeSetting()에
    // MapLayerType.Default(일반)/Aerial(위성사진)을 넘기면 실제로 지도 종류가 바뀜(카카오 쪽은
    // 이런 API 자체가 없어서 Tmap 화면 한정). 저장된 설정값을 읽어 현재 상태에 반영. #문제시 원복
    private fun applyTmapSatelliteViewSetting() {
        try {
            val enabled = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                .getBoolean("tmap_satellite_view_enabled", false)
            val layerType = if (enabled) MapLayerType.Aerial else MapLayerType.Default
            navigationFragment?.setMapLayerTypeSetting(this, layerType)
            NavLogger.d(this, "[티맵위성지도] 적용됨: $layerType")
        } catch (e: Exception) {
            NavLogger.e(this, "[티맵위성지도] 적용 예외: ${e.message}")
        }
    }

    // v10.9: [교통정보] 예전에 남겨둔 조사(v9.2)에서, 화면을 담당하는 MapEngine 클래스에
    // 진짜 켜고 끄는 함수(setShowTrafficInfo)가 있는 걸 확인함. 이 함수는 화면(NavigationFragment)
    // 안에 있는 지도 객체(getMapView())를 통해서만 부를 수 있어서, 그 경로로 접근해 저장된
    // 설정값을 실제로 반영함. 화면(NavigationFragment)이 아직 준비 안 됐을 때는 조용히 건너뜀.
    // v11.0: MapEngine 클래스를 직접 import해서 쓰면 빌드 시점에 그 클래스가 속한 부품을
    // 못 찾아서 컴파일이 깨지는 문제 발견(v11.0 CI 빌드 실패로 확인) - dumpTrafficApiCandidates()
    // 등 기존 코드들이 이럴 때 쓰던 방식과 동일하게, 클래스를 직접 안 쓰고 이름표(리플렉션)로만
    // 찾아서 호출하도록 바꿈. #문제시 원복
    private fun applyTmapTrafficInfoSetting(retryCount: Int = 0) {
        try {
            val enabled = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                .getBoolean("tmap_traffic_info_enabled", true)
            val getMapViewMethod = navigationFragment?.javaClass?.methods?.firstOrNull {
                it.name == "getMapView" && it.parameterCount == 0
            }
            val mapEngine = getMapViewMethod?.invoke(navigationFragment)
            if (mapEngine == null) {
                // v11.9: 앱을 막 켰을 때 지도가 아직 준비 안 돼서 이 시도가 실패하면, 그 뒤로
                // 다시 시도를 안 해서 재억이 꺼놓은 설정이 실제로는 반영이 안 되고 계속
                // 켜진 채로 남아있던 문제(재억 지적) - 지도가 준비될 때까지 최대 10번(1초
                // 간격, 총 10초)까지 다시 시도함. #문제시 원복
                NavLogger.d(this, "[티맵교통정보] 지도가 아직 준비 안 됨(재시도 $retryCount/10)")
                if (retryCount < 10) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        applyTmapTrafficInfoSetting(retryCount + 1)
                    }, 1000)
                } else {
                    NavLogger.e(this, "[티맵교통정보] 10번 재시도해도 지도 준비 안 됨 - 포기")
                }
                return
            }
            val setShowTrafficInfoMethod = mapEngine.javaClass.methods.firstOrNull {
                it.name == "setShowTrafficInfo" &&
                    it.parameterCount == 1 &&
                    (it.parameterTypes[0] == Boolean::class.javaPrimitiveType)
            }
            if (setShowTrafficInfoMethod == null) {
                NavLogger.e(this, "[티맵교통정보] setShowTrafficInfo 함수를 못 찾음")
                return
            }
            setShowTrafficInfoMethod.invoke(mapEngine, enabled)
            NavLogger.d(this, "[티맵교통정보] 적용됨: $enabled (재시도 $retryCount)")
            // v11.10: 설정 화면에서 껐다 켰다 할 땐 바로 반영되는데, 앱을 막 켰을 때만
            // 반영이 안 된다고 확인됨(재억 지적) - 지도가 "이제 막 준비됐다" 신호를 받은
            // 직후에 SDK가 스타일/테마를 마저 로딩하면서 교통정보를 자기 마음대로
            // 다시 켜버리는 것으로 추정. 한 번 성공해도 끝내지 않고, 앱 켤 때(재시도
            // 경로로 들어온 경우)는 2초/5초/9초 뒤에도 다시 덮어써서 SDK의 늦은 재설정을
            // 이겨내도록 함. 설정화면에서 직접 저장한 경우(retryCount==0으로 바로 성공)는
            // 이미 잘 되고 있으니 안 건드림 - retryCount>0(재시도를 거쳐 성공한 경우)일
            // 때만 보강. #문제시 원복
            if (retryCount > 0) {
                listOf(2000L, 5000L, 9000L).forEach { delayMs ->
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        applyTmapTrafficInfoSetting(-1)
                    }, delayMs)
                }
            }
        } catch (e: Exception) {
            NavLogger.e(this, "[티맵교통정보] 적용 예외: ${e.message}")
        }
    }

    // v9.2: [교통정보 조사] 재억 요청 - 도로 위 초록/주황/빨강 정체 색깔(실시간 교통정보)
    // 표시를 껐다 켰다 하는 버튼을 설정에 추가하고 싶어함. 위성지도 때(v8.5)와 같은 방식으로,
    // 실제로 그런 스위치가 SDK 안에 있는지부터 먼저 확인. "api_candidates_dumped"(전체 조사)와는
    // 별도 플래그를 써서, 이미 예전에 전체 조사를 끝낸 기기에서도 이번 조사는 새로 한 번 찍히게 함. #문제시 원복
    private fun dumpTrafficApiCandidates() {
        val prefs = getSharedPreferences("nav_diag", Context.MODE_PRIVATE)
        if (prefs.getBoolean("traffic_api_dumped", false)) return
        try {
            val keywords = listOf("traffic", "congestion", "jam", "layer", "roadevent", "roadinfo")
            fun dump(label: String, clazz: Class<*>) {
                for (m in clazz.methods) {
                    val n = m.name.lowercase()
                    if (keywords.any { n.contains(it) }) {
                        NavLogger.e(
                            this@MapActivity,
                            "[교통정보조사] $label: ${m.name}(${m.parameterTypes.joinToString { it.simpleName }}) -> ${m.returnType.simpleName}"
                        )
                    }
                }
            }
            dump("NavigationFragment", NavigationFragment::class.java)
            dump("TmapUISDK", TmapUISDK::class.java)
            dump("TmapUISDK.Companion", TmapUISDK.Companion::class.java)
            dump("MapLayerType", MapLayerType::class.java)
            try {
                val sdkManagerClass = Class.forName("com.skt.tmap.engine.navigation.SDKManager")
                dump("SDKManager", sdkManagerClass)
                val companion = sdkManagerClass.getField("Companion").get(null)
                if (companion != null) {
                    dump("SDKManager.Companion", companion.javaClass)
                }
            } catch (e: Exception) {
                NavLogger.e(this, "[교통정보조사] SDKManager dump error: ${e.message}")
            }
            NavLogger.e(this, "[교통정보조사] 조사 완료 - 위 목록에 후보가 없으면 SDK에 켜고끄는 스위치 자체가 없을 가능성이 높음")
            prefs.edit().putBoolean("traffic_api_dumped", true).apply()
        } catch (e: Exception) {
            NavLogger.e(this, "[교통정보조사] 예외: ${e.message}")
        }
    }

    // v13.6: 재억 지적 - 카메라 접근 알림음("띵 띵~")을 티맵 SDK가 끄고 켤 수 있는
    // 스위치가 있는지 조사. 위 dumpTrafficApiCandidates()랑 똑같은 방식으로 관련
    // 키워드 함수들을 로그로 찍음. #문제시 원복
    private fun dumpCameraAlarmApiCandidates() {
        val prefs = getSharedPreferences("nav_diag", Context.MODE_PRIVATE)
        if (prefs.getBoolean("camera_alarm_api_dumped", false)) return
        try {
            val keywords = listOf("alarm", "camera", "sound", "voice", "mute", "beep", "sdi", "safety")
            fun dump(label: String, clazz: Class<*>) {
                for (m in clazz.methods) {
                    val n = m.name.lowercase()
                    if (keywords.any { n.contains(it) }) {
                        NavLogger.e(
                            this@MapActivity,
                            "[카메라알림음조사] $label: ${m.name}(${m.parameterTypes.joinToString { it.simpleName }}) -> ${m.returnType.simpleName}"
                        )
                    }
                }
            }
            dump("NavigationFragment", NavigationFragment::class.java)
            dump("TmapUISDK", TmapUISDK::class.java)
            dump("TmapUISDK.Companion", TmapUISDK.Companion::class.java)
            val getMapViewMethod = navigationFragment?.javaClass?.methods?.firstOrNull {
                it.name == "getMapView" && it.parameterCount == 0
            }
            val mapEngine = getMapViewMethod?.invoke(navigationFragment)
            if (mapEngine != null) {
                dump("MapEngine(${mapEngine.javaClass.simpleName})", mapEngine.javaClass)
            }
            try {
                val sdkManagerClass = Class.forName("com.skt.tmap.engine.navigation.SDKManager")
                dump("SDKManager", sdkManagerClass)
                val companion = sdkManagerClass.getField("Companion").get(null)
                if (companion != null) {
                    dump("SDKManager.Companion", companion.javaClass)
                }
            } catch (e: Exception) {
                NavLogger.e(this, "[카메라알림음조사] SDKManager dump error: ${e.message}")
            }
            NavLogger.e(this, "[카메라알림음조사] 조사 완료 - 위 목록에 후보가 없으면 SDK에 켜고끄는 스위치 자체가 없을 가능성이 높음")
            prefs.edit().putBoolean("camera_alarm_api_dumped", true).apply()
        } catch (e: Exception) {
            NavLogger.e(this, "[카메라알림음조사] 예외: ${e.message}")
        }
    }

    // v2.0: 잠금 해제 상태면 오버레이가 터치를 그냥 통과시켜서(false 리턴) 지도가 핀치줌/드래그를 받게 함.
    // 잠금 상태(기본값)면 오버레이가 계속 터치를 소비(true)해서 지도 조작을 차단. #문제시 원복
    // v4.0: 상단바 이벤트(카메라/구간단속/방지턱) 표시 - 설정에서 끄면 안 보이게 함
    // (사용자 지적 5·6번). #문제시 원복
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

    private fun applyMapTouchLockState(unlocked: Boolean) {
        if (unlocked) {
            binding.vTouchLockOverlay?.setOnTouchListener { _, _ -> false }
        } else {
            binding.vTouchLockOverlay?.setOnTouchListener { _, _ -> true }
        }
    }

    private fun checkOverSpeedWarning(speedKph: Int) {
        val pref = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
        if (!pref.getBoolean("over_speed_warning_enabled", true)) return
        val limit = SdiDataRepository.roadLimitSpeed
        if (limit < 30 || speedKph <= 0) return
        val now = System.currentTimeMillis()
        // v: "65로 주행 중이었고 60 제한이면 10%(66)를 안 넘었는데 경고음이 났다"(사용자
        // 재지적) - 지금까지는 실제로 트리거된 순간의 값만 로그로 남겨서, 화면에 보이는
        // limit이랑 이 함수가 실제로 쓰는 limit이 서로 다른 타이밍일 가능성(구간 경계
        // 넘는 순간 limit이 잠깐 옛날 값에 머무는 지연)을 확인할 방법이 없었음. 트리거
        // 여부와 무관하게 3초에 한 번씩 limit 흐름 자체를 남김 - 다음 로그에서 "65 주행
        // 당시 limit이 실제로 몇이었는지"를 직접 대조할 수 있게 함. #문제시 원복
        // v: 재억 요청(2026-09-03) - 위 진단 목적으로 3초마다 무조건 남기던 걸, 제한속도가
        // 실제로 바뀌는 순간만 남기도록 변경(실기기 로그 665줄/58KB 차지). 대조에 필요한
        // 건 "limit이 언제 몇으로 바뀌었나"이고, 경고음이 실제로 난 순간은 아래
        // flushTrace가 앞뒤 상황까지 통째로 남기므로 정보 손실 없음. #문제시 원복
        NavLogger.dIfChanged(this, "과속경고음진단", "[과속경고음진단] limit=$limit (limit*1.1=${limit * 1.1}) 이때속도=$speedKph")
        // v: 재억 제보(2026-08-22) - 카메라 접근 중엔 300~500m에서 한 번, 100m 이내에서
        // 또 한 번(8초 쿨다운마다 반복) 울리던 걸 "이 카메라 하나당 한 번"으로 제한.
        // 카메라가 없을 때(그냥 과속 중)는 기존처럼 8초마다 반복 경고. #문제시 원복
        val nearCamera = SdiDataRepository.isNearCameraEvent()
        if (!nearCamera) {
            SdiDataRepository.cameraApproachWarned = false
        }
        val shouldWarn = if (nearCamera) {
            speedKph > limit * 1.1 && !SdiDataRepository.cameraApproachWarned
        } else {
            speedKph > limit * 1.1 && now - SdiDataRepository.lastOverSpeedWarningTime > 8000L
        }
        if (shouldWarn) {
            SdiDataRepository.lastOverSpeedWarningTime = now
            if (nearCamera) SdiDataRepository.cameraApproachWarned = true
            // v4.23: "60도로에 65로 주행시 경고음(10% 안 넘는데)"(사용자 1번) - 발생 순간의
            // 실제 limit/speedKph 값을 못 남기고 있어서 원인 특정이 안 됐음. 트리거되는
            // 바로 그 순간의 값을 남겨서 다음 로그로 어떤 limit이 실제로 쓰였는지
            // 확정할 수 있게 함. #문제시 원복
            NavLogger.e(this, "[과속경고음발생][Tmap화면] speedKph=$speedKph limit=$limit (limit*1.1=${limit * 1.1}) nearCamera=$nearCamera")
            try {
                // v: 재억 지적(2026-08-22) - 안내음량을 20%/30%로 낮춰도 이 경고음만
                // 항상 100% 고정 크기로 울렸음. 사용자가 설정해둔 안내음량 값을 그대로
                // 가져다 씀(0이면 최소한 들리게 1로 보정). #문제시 원복
                // v2: 숫자만 맞추고 채널은 STREAM_NOTIFICATION 그대로 둬서, 안내음성
                // (미디어 볼륨)이랑 여전히 따로 놀았음(재억 지적) - 안내음성과 같은
                // 미디어 채널로 재생해서 실제로 같이 움직이게 함. #문제시 원복
                val volumePercent = VolumeHelper.guideVolumePercent(this).coerceIn(1, 100)
                AudioStreamDiagnostics.log(this, "경고음발생[Tmap화면]")
                val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, volumePercent)
                tone.startTone(android.media.ToneGenerator.TONE_CDMA_PIP, 400)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tone.release() }, 500)
            } catch (e: Exception) {
                NavLogger.flushTrace(this, "voice", "속도경고음 재생 예외: ${e.message}")
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
        val nativeAppKey = getKakaoNativeAppKey()

        // 앱 시작 직후에는 KNSDK가 REST/네이티브 입력값을 판별하고 자동 정렬하는 중일 수 있다.
        // 그 전에 기존 REST 값을 헤더에 넣으면 첫 검색만 401이 나므로, SDK 키 확인이
        // 끝난 뒤 저장된 REST 값을 다시 읽어 실제 검색 요청을 보낸다.
        if (nativeAppKey.isNotBlank()) {
            binding.tvSearchStatus?.text = "카카오 키 확인 중..."

            KakaoSdkState.ensureInitialized(
                application,
                nativeAppKey
            ) { success, message ->
                if (isFinishing || isDestroyed) {
                    return@ensureInitialized
                }

                if (!success) {
                    NavLogger.e(
                        this,
                        "검색 전 KNSDK 키 확인 실패: $message"
                    )
                    binding.tvSearchStatus?.text =
                        "카카오 SDK 키 확인 실패: ${message ?: "알 수 없는 오류"}"
                    return@ensureInitialized
                }

                performDestinationSearchAfterKakaoKeyReady(query)
            }
            return
        }

        performDestinationSearchAfterKakaoKeyReady(query)
    }

    private fun performDestinationSearchAfterKakaoKeyReady(query: String) {
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

        // v10.9-4: "편의점", "주유소"처럼 상호명이 아니라 종류로 찾는 검색은, 일반
        // 키워드검색보다 카카오의 종류 전용 검색이 훨씬 정확함(재억 요청) - 현재 위치를
        // 알아야 반경 안에서 찾을 수 있어서, 위치를 못 구하면 그냥 기존 키워드검색으로
        // 넘어감. #문제시 원복
        val categoryCode = SearchRanking.categoryGroupCodeFor(query)
        if (categoryCode != null) {
            val (curLat, curLon) = resolveCurrentWgs84LatLon()
            if (curLat != null && curLon != null) {
                performCategorySearch(categoryCode, restKey, curLat, curLon)
                return
            }
            NavLogger.d(this, "[종류검색] 현재 위치 확인 안 됨 - 일반 검색으로 대체: query=$query")
        }

        performDestinationSearchWithRestKey(
            query = query,
            restKey = restKey,
            allowNativeFieldFallback = true
        )
    }

    // v10.9-4: 카카오 종류 전용 검색(category.json) - 이미 sort=distance로 가까운 순 정렬돼서
    // 오므로, 여기서는 재정렬 없이 그대로 보여줌. #문제시 원복
    private fun performCategorySearch(categoryCode: String, restKey: String, lat: Double, lon: Double) {
        val url = "https://dapi.kakao.com/v2/local/search/category.json?category_group_code=$categoryCode" +
            "&x=$lon&y=$lat&radius=20000&sort=distance"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "KakaoAK $restKey")
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                NavLogger.e(this@MapActivity, "카카오 종류검색 요청 실패: ${e.message}")
                runOnUiThread { binding.tvSearchStatus?.text = "검색 실패(네트워크): ${e.message}" }
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        NavLogger.e(this@MapActivity, "카카오 종류검색 실패 code=${it.code}")
                        runOnUiThread { binding.tvSearchStatus?.text = "검색 실패(${it.code})" }
                        return@use
                    }
                    val json = JSONObject(it.body?.string() ?: "{}")
                    val documents = json.optJSONArray("documents")
                    if (documents == null || documents.length() == 0) {
                        runOnUiThread { binding.tvSearchStatus?.text = "검색 결과 없음" }
                        return@use
                    }
                    val hits = (0 until documents.length()).map { idx ->
                        val d = documents.getJSONObject(idx)
                        HistoryEntry(
                            d.optString("place_name", "이름 없음"),
                            d.optString("road_address_name", d.optString("address_name", "")),
                            d.optDouble("y"),
                            d.optDouble("x"),
                            d.optString("distance").toDoubleOrNull()
                        )
                    }
                    NavLogger.d(this@MapActivity, "카카오 종류검색 결과 ${hits.size}건: category=$categoryCode")
                    runOnUiThread { showSearchResultsDialog(hits) }
                }
            }
        })
    }

    private fun performDestinationSearchWithRestKey(
        query: String,
        restKey: String,
        allowNativeFieldFallback: Boolean,
        page: Int = 1,
        accumulatedDocuments: MutableList<JSONObject> = mutableListOf()
    ) {
        // v9.5: 재억 요청 - "스타벅스" 검색하면 내 위치(예: 지산동 838-20)와
        // 무관하게 카카오가 자기네 인기순으로 아무 지역 결과나 던져줌.
        // 현재 위치(x=경도,y=위도)를 넘기고 sort=distance로 거리순 정렬해서
        // 실제로 가까운 곳부터 나오게 함. 위치를 못 구하면 좌표 없이 기존처럼 검색. #문제시 원복
        // v9.9: radius=20000이 검색 "범위"까지 20km로 제한해버려서 먼 지역(예: 예산시장)이
        // 아예 검색 결과에서 빠지는 문제 발견. radius 제거하여 전국 대상으로 검색.
        // v9.9-2: sort=distance만 쓰면 이름이 검색어와 무관해도 그냥 가까운 순으로만 나열돼서
        // "예산시장" 검색해도 진짜 예산시장은 저 아래로 밀려버림(재억 지적).
        // sort=accuracy(카카오 기본 정확도순)로 바꾸고, x/y는 계속 넘겨서 documents의
        // distance 필드는 그대로 받도록 함. 실제 정렬은 아래에서 이름 정확도+거리로 재구성.
        val (curLat, curLon) = resolveCurrentWgs84LatLon()
        val locationParams = if (curLat != null && curLon != null) {
            "&x=$curLon&y=$curLat"
        } else {
            ""
        }
        val url = "https://dapi.kakao.com/v2/local/search/keyword.json?query=" +
            java.net.URLEncoder.encode(query, "UTF-8") + locationParams + "&page=$page"
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
                        val keyTypeFailure =
                            (it.code == 401 || it.code == 403) &&
                                body.orEmpty().contains(
                                    "KA Header is required",
                                    ignoreCase = true
                                )

                        val alternateRestKey = if (allowNativeFieldFallback) {
                            getKakaoNativeAppKey()
                                .trim()
                                .takeIf { candidate ->
                                    candidate.isNotEmpty() && candidate != restKey
                                }
                        } else {
                            null
                        }

                        if (keyTypeFailure && alternateRestKey != null) {
                            NavLogger.d(
                                this@MapActivity,
                                "저장된 REST 키가 Native 키로 판정됨 - 다른 입력값으로 REST 검색 재시도"
                            )
                            runOnUiThread {
                                binding.tvSearchStatus?.text =
                                    "REST 키 자동 확인 후 재검색 중..."
                            }
                            performDestinationSearchWithRestKey(
                                query = query,
                                restKey = alternateRestKey,
                                allowNativeFieldFallback = false
                            )
                            return@use
                        }

                        // 401/403이면 키 종류/헤더 형식 문제일 확률이 매우 높음.
                        NavLogger.e(this@MapActivity, "카카오 검색 실패 code=${it.code} body=$body")
                        runOnUiThread { binding.tvSearchStatus?.text = "검색 실패(${it.code}) - REST 키/헤더 확인" }
                        return@use
                    }

                    if (!allowNativeFieldFallback) {
                        KakaoSdkState.persistValidatedKeyRoles(
                            application,
                            restKey
                        )
                    }

                    val json = JSONObject(it.body?.string() ?: "{}")
                    val documents = json.optJSONArray("documents")
                    if ((documents == null || documents.length() == 0) && accumulatedDocuments.isEmpty()) {
                        NavLogger.d(this@MapActivity, "카카오 키워드검색 결과 없음, 주소검색으로 재시도: query=$query")
                        performAddressSearchFallback(query, restKey)
                        return@use
                    }

                    // v10.9-4: 카카오가 한 번에 최대 15건만 주는데, 진짜 원하는 곳이 16번째
                    // 이후 순위면 아예 후보에도 못 들어와서 아무리 정렬을 잘해도 소용없던
                    // 문제(재억 지적) - 결과가 더 있으면(meta.is_end=false) 최대 4쪽(최대 60건)까지
                    // 자동으로 더 받아와서 합친 뒤에 정렬함. 3쪽 넘게 더 받아오진 않음(카카오
                    // 쿼터/속도 문제로 무한정 받아오진 않게 상한선을 둠). #문제시 원복
                    if (documents != null) {
                        for (i in 0 until documents.length()) {
                            accumulatedDocuments.add(documents.getJSONObject(i))
                        }
                    }
                    val meta = json.optJSONObject("meta")
                    val isEnd = meta?.optBoolean("is_end", true) ?: true
                    if (!isEnd && page < 4) {
                        performDestinationSearchWithRestKey(
                            query = query,
                            restKey = restKey,
                            allowNativeFieldFallback = allowNativeFieldFallback,
                            page = page + 1,
                            accumulatedDocuments = accumulatedDocuments
                        )
                        return@use
                    }
                    // v1.7: "S Oil 검색하면 평택시 지산동 Soil 00점, 000점처럼 여러개 나와야 하는데
                    // 첫번째 결과로 바로 꽂힘" 지적 - 항상 documents[0]으로 바로 길안내를 시작하던
                    // 걸 없애고, 결과 목록을 보여준 뒤 사용자가 직접 골라서 시작하도록 변경. #문제시 원복
                    // v9.9-2: "예산시장" 검색했는데 이름 일치 정도와 무관하게 그냥 가까운 순으로만
                    // 나와서 진짜 "예산시장"이 목록 아래로 밀리던 문제(재억 지적) - 장소명이
                    // 검색어와 정확히/거의 일치하는 결과를 최상단으로 올리고, 그 안에서는 가까운
                    // 순으로, 나머지는 거리순으로 배치하도록 재정렬.
                    // v10.9: "대구광역시청" 검색하면 본청이 아니라 별관/주차장/어린이집 같은
                    // 부속시설이 위로 올라오던 문제(재억 지적) - 이름 일치 정도를 두 단계로만
                    // 나눠서(딱 맞음/아예 다름) 뒤에 뭐가 붙어있든 전부 "딱 맞음"으로 같이
                    // 취급했던 게 원인. ①완전히 같은 이름 ②시작은 같은데 뒤에 뭔가 더 붙은
                    // 이름(부속시설류) ③순서만 지키며 다 포함(붙여쓰기 포함) ④전혀 다른 이름,
                    // 이렇게 세분화하고 ②③단계 안에서는 이름이 더 짧은 쪽(DT 붙은 건 예외로
                    // 항상 우선)을 먼저 보이도록 함. 이 정렬 규칙은 SearchRanking.kt로 모아서
                    // 카카오 화면과 공통으로 씀. #문제시 원복
                    val rawHits = accumulatedDocuments.map { d ->
                        val placeName = d.optString("place_name", query)
                        val distance = d.optString("distance").toDoubleOrNull() ?: Double.MAX_VALUE
                        Pair(
                            HistoryEntry(
                                placeName,
                                d.optString("road_address_name", d.optString("address_name", "")),
                                d.optDouble("y"),
                                d.optDouble("x"),
                                d.optString("distance").toDoubleOrNull()
                            ),
                            SearchRanking.rankKey(query, placeName, distance)
                        )
                    }
                    val hits = rawHits
                        .sortedWith(compareBy { it.second })
                        .map { it.first }
                        .take(50)
                    NavLogger.d(this@MapActivity, "카카오 검색 결과 ${hits.size}건: query=$query")

                    runOnUiThread { showSearchResultsDialog(hits) }
                }
            }
        })
    }

    // v7.8: "지산동 838-20 검색하면 그 번지가 안 나오고 송탄로 361번길 29처럼 엉뚱한
    // 다른 지점이 나옴" 지적(재억) - 키워드검색(/v2/local/search/keyword.json)은
    // 상호명/장소명 위주로 매칭돼서 순수 지번(번지) 주소는 잘 못 찾는 경우가 많음.
    // 카카오가 주소 전용으로 제공하는 /v2/local/search/address.json으로 한 번 더
    // 시도해서 지번/도로명 주소 정확도를 보강. #문제시 원복
    private fun performAddressSearchFallback(query: String, restKey: String) {
        val url = "https://dapi.kakao.com/v2/local/search/address.json?query=" +
            java.net.URLEncoder.encode(query, "UTF-8")
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "KakaoAK $restKey")
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                NavLogger.e(this@MapActivity, "카카오 주소검색 요청 실패: ${e.message}")
                runOnUiThread { binding.tvSearchStatus?.text = "검색 결과 없음: $query" }
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        NavLogger.e(this@MapActivity, "카카오 주소검색 실패 code=${it.code}")
                        runOnUiThread { binding.tvSearchStatus?.text = "검색 결과 없음: $query" }
                        return@use
                    }
                    val json = JSONObject(it.body?.string() ?: "{}")
                    val documents = json.optJSONArray("documents")
                    if (documents == null || documents.length() == 0) {
                        NavLogger.d(this@MapActivity, "카카오 주소검색도 결과 없음: query=$query")
                        runOnUiThread { binding.tvSearchStatus?.text = "검색 결과 없음: $query" }
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
                    NavLogger.d(this@MapActivity, "카카오 주소검색 결과 ${hits.size}건: query=$query")
                    runOnUiThread { showSearchResultsDialog(hits) }
                }
            }
        })
    }

    // v11.2: 검색 결과가 45건까지 늘어난 뒤로 한 화면에 다 몰아넣으면 스크롤이 너무 길어져서
    // (재억 요청) 10개씩 페이지로 끊어서 보여주고, 아래 "이전/다음/취소" 버튼으로 넘기도록
    // 바꿈. AlertDialog는 버튼이 최대 3개까지만 되는데 마침 딱 맞음(왼쪽부터 이전=중립,
    // 다음=긍정, 취소=부정). 다이얼로그를 다시 만들지 않고, 페이지 넘길 때마다 목록
    // 내용이랑 버튼 상태만 새로 그림(재생성하면 버튼이 자동으로 닫혀버려서). #문제시 원복
    // v14.4: 재억 요청 - 최근목적지 "저장" 버튼을 누르면 집/회사/즐겨찾기1~5 중 어디에
    // 넣을지 물어보는 창. 이미 그 칸에 다른 장소가 등록돼 있어도 그대로 덮어씀(재억 확인 -
    // "다 차 있어도 덮어 씌우는 방식"). 즐겨찾기 칸 개수는 설정값(0~5)만큼만 보여줌. #문제시 원복
    // v14.8: 재억 지적 - 원래 재억이 확인했던 미리보기는 흰 카드 안에 버튼들이 2~3칸씩
    // 그리드로 배치된 모양이었는데, 실제 코드는 그냥 안드로이드 기본 세로 목록(setItems)으로
    // 구현되어 있었음. 미리보기와 똑같이 카드+그리드 버튼 모양으로 다시 만듦. #문제시 원복
    private fun showQuickSlotPickerForSave(entry: HistoryEntry) {
        // v: 재억 요청(2026-09-02) - 즐겨찾기 10칸까지 동적 생성. #문제시 원복
        val favoriteCount = QuickSlotStore.favoriteCount(this)
        val slotLabels = mutableListOf("집" to QuickSlotStore.SLOT_HOME, "회사" to QuickSlotStore.SLOT_WORK)
        val favoriteSlots = QuickSlotStore.favoriteSlots(favoriteCount)
            .mapIndexed { index, slot -> "즐겨찾기 ${index + 1}" to slot }
        slotLabels.addAll(favoriteSlots)

        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setView(buildQuickSlotPickerView(entry, slotLabels))
            .create()
        dialog.show()
        // setView로 넣은 커스텀 버튼들이 dialog를 직접 닫아야 해서, 아래에서 dialog 참조를 넘겨줌.
        quickSlotPickerDialogInUse = dialog
    }

    private var quickSlotPickerDialogInUse: android.app.AlertDialog? = null

    private fun buildQuickSlotPickerView(
        entry: HistoryEntry,
        slotLabels: List<Pair<String, String>>
    ): android.view.View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val card = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        card.addView(android.widget.TextView(this).apply {
            text = "어디에 저장할까요?"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
        })
        card.addView(android.widget.TextView(this).apply {
            text = entry.name
            setTextColor(android.graphics.Color.parseColor("#4FA8E8"))
            textSize = 13f
            setPadding(0, dp(4), 0, dp(14))
        })

        fun makeSlotButton(label: String, slot: String): android.widget.Button {
            return android.widget.Button(this, null, android.R.attr.buttonStyle).apply {
                text = label
                textSize = 14f
                isAllCaps = false
                setOnClickListener {
                    QuickSlotStore.save(this@MapActivity, slot, entry)
                    Toast.makeText(this@MapActivity, "'${entry.name}' 저장 완료", Toast.LENGTH_SHORT).show()
                    quickSlotPickerDialogInUse?.dismiss()
                }
            }
        }

        fun addRow(items: List<Pair<String, String>>) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(8))
            }
            items.forEachIndexed { index, (label, slot) ->
                row.addView(
                    makeSlotButton(label, slot),
                    android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = if (index == items.lastIndex) 0 else dp(8)
                    }
                )
            }
            card.addView(row)
        }

        // 집/회사는 항상 한 줄에 2칸. 그 뒤 즐겨찾기는 2개씩 묶고, 마지막에 1개가 남으면
        // 바로 앞 줄과 합쳐서 3칸짜리 줄로 만듦(미리보기와 동일한 모양). #문제시 원복
        val fixedRow = slotLabels.take(2)
        val favoriteRest = slotLabels.drop(2)
        val rows = mutableListOf<List<Pair<String, String>>>()
        if (fixedRow.isNotEmpty()) rows.add(fixedRow)
        var i = 0
        val favRows = mutableListOf<MutableList<Pair<String, String>>>()
        while (i < favoriteRest.size) {
            favRows.add(favoriteRest.subList(i, minOf(i + 2, favoriteRest.size)).toMutableList())
            i += 2
        }
        if (favRows.size >= 2 && favRows.last().size == 1) {
            val leftover = favRows.removeAt(favRows.size - 1)
            favRows.last().addAll(leftover)
        }
        rows.addAll(favRows)
        rows.forEach { addRow(it) }

        card.addView(android.widget.Button(this).apply {
            text = "취소"
            textSize = 14f
            isAllCaps = false
            setOnClickListener { quickSlotPickerDialogInUse?.dismiss() }
        }, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })

        return card
    }

    private fun showSearchResultsDialog(hits: List<HistoryEntry>) {
        binding.llSearchPanel?.visibility = View.VISIBLE
        binding.tvSearchStatus?.text = "검색 결과 ${hits.size}건 - 목적지를 선택하세요"
        var didPickEntry = false
        val pageSize = 10
        var currentPage = 0
        val lastPage = (hits.size - 1) / pageSize

        val listView = android.widget.ListView(this@MapActivity)
        listView.setBackgroundColor(android.graphics.Color.parseColor("#181818"))
        listView.divider = android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#333333"))
        listView.dividerHeight = 1

        val dialog = android.app.AlertDialog.Builder(this@MapActivity, android.R.style.Theme_Material_Dialog_Alert)
            .setView(listView)
            .setNegativeButton("취소", null)
            .setNeutralButton("이전", null)
            .setPositiveButton("다음", null)
            .create()

        fun pickEntry(picked: HistoryEntry) {
            didPickEntry = true
            dialog.dismiss()
            // v11.3: 집/회사/즐겨찾기 칸 등록을 위해 검색을 연 거였으면, 저장만 하고
            // 안내는 시작하지 않음(재억 지적 - 등록할 땐 안내까지 필요 없음). #문제시 원복
            val registeringSlot = pendingQuickSlotRegistration
            if (registeringSlot != null) {
                QuickSlotStore.save(this@MapActivity, registeringSlot, picked)
                pendingQuickSlotRegistration = null
                Toast.makeText(this@MapActivity, "'${picked.name}' 등록 완료", Toast.LENGTH_SHORT).show()
                binding.etDestination?.apply {
                    isFocusable = false
                    isFocusableInTouchMode = false
                    clearFocus()
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setText("")
                }
                return
            }
            binding.tvSearchStatus?.text = "찾음: ${picked.name} (${picked.lat}, ${picked.lon}) - 경로요청 시도"
            // v10.9: 목적지를 고른 뒤에도 검색창에 입력했던 글자가 그대로 남아있던 문제
            // (재억 지적) - 카카오 화면(showInPlaceSearchResultsDialog)은 이미 고른 순간
            // 입력창을 비우고 있었는데, 이 화면만 그 정리 코드가 빠져있었음. 동일하게
            // 포커스 정리 후 비움. #문제시 원복
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
            saveSearchHistory(picked)
            showRoutePriorityDialog(picked)
        }

        // v13.1-2: 재억 요청 - 검색해서 목적지를 고른 뒤, 바로 안내 시작하지 않고
        // "추천 경로 / 고속도로 우선 / 무료도로 우선" 중 골라서 안내를 시작하도록 함.
        // 각각 실제 소요시간을 미리 계산해서 같이 보여줌(계산 중엔 "검색 중").
        // KNRoutePriority_HighWay(고속도로 우선), KNRouteAvoidOption_Fare(톨게이트/유료
        // 도로 피하기 = 무료도로 우선)는 로그로 확인된 실제 SDK 값. #문제시 원복
        fun renderPage() {
            val start = currentPage * pageSize
            val end = minOf(start + pageSize, hits.size)
            val pageHits = hits.subList(start, end)
            // v11.1: 검색 결과 각 항목 옆에 거리도 같이 보여줌(재억 요청). 이름 뒤에 붙여서
            // "스타벅스 평택송탄점 · 850m" 처럼 표시. 거리 정보가 없으면(주소검색 결과 등)
            // 이름만 그대로 표시. #문제시 원복
            // v12.7: 재억 요청 - 거리 옆에 소요시간도 같이("850m · 12분"). 지금 페이지에
            // 보이는 것만(최대 10개) 계산해서 부담을 줄이고, 페이지 넘기면 그 페이지 것만
            // 새로 계산함. 계산 중엔 "검색 중"으로 표시. #문제시 원복
            fun buildLabel(h: HistoryEntry, etaText: String?): String {
                val distanceText = SearchRanking.formatDistance(h.distanceMeters)
                val distanceAndEta = listOfNotNull(distanceText, etaText).joinToString(" · ")
                val nameWithExtra = if (distanceAndEta.isNotEmpty()) "${h.name} · $distanceAndEta" else h.name
                return if (h.addr.isNotBlank()) "$nameWithExtra\n${h.addr}" else nameWithExtra
            }
            val currentLabels: MutableList<CharSequence> = pageHits.map { buildLabel(it, "검색 중") as CharSequence }.toMutableList()
            // v12.8: 크래시 원인 - ArrayAdapter가 리스트를 복사하지 않고 원본을 그대로
            // 참조해서 써서, adapter.clear()를 부르면 currentLabels 자체도 같이
            // 비워져버렸음. 그 상태에서 다른 소요시간 계산 결과가 나중에 도착해
            // currentLabels[index]에 값을 넣으려다 "그런 자리 없음" 오류로 앱이 죽음
            // (재억 지적, 검색 결과에서 크래시). 어댑터한테는 복사본(toList())을 넘겨서
            // currentLabels와 완전히 분리시킴. #문제시 원복
            // v13.0-2: 재발한 크래시 원인 - .toList()는 항목이 0개나 1개일 때 코틀린이
            // "수정 불가능한" 특수 리스트를 돌려주는 최적화를 함. 검색 결과가 1개뿐인
            // 페이지에서 나중에 adapter.clear()를 부르면 "수정 불가" 오류로 앱이 죽었음
            // (재억 지적, UnsupportedOperationException). ArrayList(...)로 명시적으로
            // 감싸서 항목 개수와 무관하게 항상 진짜 수정 가능한 리스트로 만듦. #문제시 원복
            val adapter = darkTextAdapter(ArrayList(currentLabels))
            listView.adapter = adapter
            listView.setOnItemClickListener { _, _, position, _ -> pickEntry(pageHits[position]) }
            dialog.setTitle("검색 결과 ${hits.size}건 (${start + 1}-$end)")
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.isEnabled = currentPage > 0
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.isEnabled = currentPage < lastPage

            val (curLat, curLon) = resolveCurrentWgs84LatLon()
            if (curLat != null && curLon != null) {
                // v14.1: 재억 지적(로그로 재확인) - 즐겨찾기/최근검색 이력은 고쳤는데 이
                // "검색 결과" 목록만 빠져있었음. 페이지에 보이는 항목 전부(최대 10개)를
                // 한꺼번에 동시에 계산 요청해서, 카카오 SDK 쪽 응답이 하나씩 1~2초씩
                // 밀려서 나오고 총 15초 넘게 걸리는 게 로그로 확인됨(검색해서 안내 시작할
                // 때 지연의 세 번째 원인). 즐겨찾기/이력과 동일하게 "동시 최대 2개까지만"
                // 요청이 나가도록 대기열로 바꿈. #문제시 원복
                val etaPendingQueue = ArrayDeque<Int>()
                var etaActiveCount = 0
                val etaMaxConcurrent = 2
                val etaMyGeneration = etaQueueGeneration
                fun etaPump() {
                    while (etaQueueGeneration == etaMyGeneration && etaActiveCount < etaMaxConcurrent && etaPendingQueue.isNotEmpty()) {
                        val index = etaPendingQueue.removeFirst()
                        val h = pageHits.getOrNull(index) ?: continue
                        etaActiveCount++
                        var settled = false
                        val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
                        val timeoutRunnable = Runnable {
                            if (!settled) {
                                settled = true
                                NavLogger.e(this@MapActivity, "[검색결과소요시간] 8초 타임아웃 - 포기하고 다음으로: ${h.name}")
                                etaActiveCount--
                                runOnUiThread {
                                    currentLabels[index] = buildLabel(h, null)
                                    adapter.clear()
                                    adapter.addAll(currentLabels)
                                    adapter.notifyDataSetChanged()
                                }
                                etaPump()
                            }
                        }
                        // v14.1: 재억 지적(스샷으로 확인) - 요청 하나가 응답을 영영 못 받으면
                        // 대기열 전체가 멈춤. 8초 타임아웃으로 다음으로 넘어가게 함. #문제시 원복
                        timeoutHandler.postDelayed(timeoutRunnable, 8000L)
                        KakaoSdkState.computeEta(this@MapActivity, curLat, curLon, h.lat, h.lon) { minutes, _ ->
                            if (settled) return@computeEta
                            settled = true
                            timeoutHandler.removeCallbacks(timeoutRunnable)
                            val etaFormatted = SearchRanking.formatEtaMinutes(minutes)
                            etaActiveCount--
                            runOnUiThread {
                                currentLabels[index] = highlightEta(buildLabel(h, etaFormatted), etaFormatted)
                                adapter.clear()
                                adapter.addAll(currentLabels)
                                adapter.notifyDataSetChanged()
                            }
                            etaPump()
                        }
                    }
                }
                pageHits.indices.forEach { etaPendingQueue.addLast(it) }
                etaPump()
            } else {
                pageHits.forEachIndexed { index, h ->
                    currentLabels[index] = buildLabel(h, null)
                }
                adapter.clear()
                adapter.addAll(currentLabels)
                adapter.notifyDataSetChanged()
            }
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#212121")))
        // v11.3: 목록에서 아무것도 안 고르고 "취소" 누르거나 다이얼로그 밖을 눌러서 닫아도
        // 상태 표시줄에 "검색 결과 45건 - 목적지를 선택하세요"가 그대로 남아있던 문제
        // (재억 지적) - 다이얼로그가 닫힐 때, 실제로 목적지를 고른 게 아니면 상태
        // 표시줄을 지움. #문제시 원복
        dialog.setOnDismissListener {
            if (!didPickEntry) {
                binding.tvSearchStatus?.text = ""
            }
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            if (currentPage > 0) {
                currentPage--
                renderPage()
            }
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            if (currentPage < lastPage) {
                currentPage++
                renderPage()
            }
        }
        renderPage()
    }

    // ===== 카카오내비 오버레이 길안내 =====
    // 코드에 하드코딩하면 TmapNda 쓰는 모든 사람이 사용자 개인 카카오 앱 쿼터를 나눠쓰게 됨
    // (Tmap 하루1회 제한과 같은 문제 반복). 그래서 각자 본인 카카오 콘솔에서 발급받은
    // 네이티브 앱 키를 기기별 설정값으로 넣도록 변경. #문제시 원복
    // 네이티브 앱 키가 비어 있으면 SDK를 초기화하지 않는다.
    private fun getKakaoNativeAppKey(): String {
        val prefs = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
        return prefs.getString("kakao_native_app_key", "").orEmpty().trim()
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
        // v4.13: clearFocus()만으로는 포커스를 넘겨받을 다른 뷰가 근처에 없어서 실제로는
        // 포커스가 안 풀리고, 그래서 검색창 커서(깜빡임)가 계속 남아있는 문제(사용자 8번).
        // 잠깐 포커스 불가 상태로 만들었다가 되돌리는 방식으로 강제로 포커스를 떨어뜨림. #문제시 원복
        binding.etDestination?.apply {
            isFocusable = false
            isFocusableInTouchMode = false
            clearFocus()
            isFocusable = true
            isFocusableInTouchMode = true
        }
        // v3.5: 길안내 시작 후에도 검색창에 입력했던 텍스트가 계속 남아있던 문제
        // (사용자 지적 11번) - 포커스/패널만 닫고 텍스트는 안 지우고 있었음. #문제시 원복
        binding.etDestination?.setText("")
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

        val nativeAppKey = getKakaoNativeAppKey()

        if (nativeAppKey.isBlank()) {
            NavLogger.e(
                this,
                "KNSDK 선행 초기화 건너뜀: 카카오 네이티브 앱 키가 비어 있음"
            )
            return
        }

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
                    "KNSDK 초기화 완료(앱 시작 시점)"
                )

                knsdkInitialized = true

                try {
                    KNSDK.handleWillEnterForeground()
                    KNSDK.handleDidBecomeActive()
                } catch (e: Exception) {
                    NavLogger.e(
                        this,
                        "KNSDK 라이프사이클 전달 예외: ${e.message}"
                    )
                }
            } else {
                NavLogger.e(
                    this,
                    "KNSDK 초기화 실패(앱 시작 시점): $message"
                )
            }
        }
    }

    // v1.0.83: flKakaoOverlay 위에서 visibility/z-order/reassert를 직접 관리하던 방식(기존 함수는
    // startKakaoOverlayGuidanceLegacy로 이름만 남겨두고 미사용 처리)을 버리고, CarrotNavi와 동일하게
    // 카카오 안내를 완전히 별도의 Activity(KakaoNaviActivity)로 분리함. startActivity()/finish()는
    // 안드로이드가 보장하는 화면전환이라 SurfaceView 타이밍/z-order 경쟁이 원천적으로 생기지 않음.
    // 검색 패널만 우리 쪽에서 미리 닫아주고, 나머지(초기화/경로요청/안내화면)는 새 Activity가 전담. #문제시 원복
    private fun startKakaoOverlayGuidance(
        name: String,
        goalLat: Double,
        goalLon: Double,
        // v13.1-2: 재억 요청 - 검색해서 목적지 고른 뒤 "추천/고속도로우선/무료도로우선"
        // 선택하는 기능 추가. Intent로는 enum을 직접 못 넘기니 이름(String)으로 넘김. #문제시 원복
        routePriorityName: String? = null,
        routeAvoidOption: Int = 0
    ) {
        val nativeAppKey = getKakaoNativeAppKey()

        if (nativeAppKey.isBlank()) {
            Toast.makeText(
                this,
                "카카오 네이티브 앱 키를 먼저 입력하세요.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        dismissKeyboardAndSearchPanel()

        val intent = Intent(this, KakaoNaviActivity::class.java).apply {
            putExtra("dest_name", name)
            putExtra("dest_lat", goalLat)
            putExtra("dest_lon", goalLon)
            putExtra("kakao_native_app_key", nativeAppKey)
            if (routePriorityName != null) {
                putExtra("route_priority_name", routePriorityName)
                putExtra("route_avoid_option", routeAvoidOption)
            }
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

        val nativeAppKey = getKakaoNativeAppKey()

        if (nativeAppKey.isBlank()) {
            Toast.makeText(
                this,
                "카카오 네이티브 앱 키를 먼저 입력하세요.",
                Toast.LENGTH_LONG
            ).show()

            isRequestingKakaoRoute = false
            hideKakaoOverlay()
            return
        }

        KakaoSdkState.ensureInitialized(
            application,
            nativeAppKey
        ) { success, message ->
            if (isFinishing || isDestroyed) {
                isRequestingKakaoRoute = false
                return@ensureInitialized
            }

            if (success) {
                knsdkInitialized = true

                try {
                    KNSDK.handleWillEnterForeground()
                    KNSDK.handleDidBecomeActive()
                } catch (e: Exception) {
                    NavLogger.e(
                        this,
                        "KNSDK 라이프사이클 전달 예외: ${e.message}"
                    )
                }

                requestKakaoRoute(name, goalLat, goalLon)
            } else {
                val errorMessage = message ?: "알 수 없는 오류"

                NavLogger.e(
                    this,
                    "KNSDK 초기화 실패: $errorMessage"
                )

                Toast.makeText(
                    this,
                    "카카오내비 초기화 실패: $errorMessage",
                    Toast.LENGTH_LONG
                ).show()

                isRequestingKakaoRoute = false
                hideKakaoOverlay()
            }
        }
    }

    // v: 신규기능(주변 카테고리 검색) - 카카오 화면과 동일한 팝업(NearbyCategoryPopup)을 공용으로
    // 씀. 결과를 고르면 기존 startKakaoOverlayGuidance() 경로로 카카오 안내를 시작. #문제시 원복
    private fun setupNearbyCategoryButton() {
        binding.btnNearbyCategory?.setOnClickListener {
            val restKey = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                .getString("kakao_rest_api_key", null)
            if (restKey.isNullOrBlank()) {
                Toast.makeText(this, "카카오 REST API 키를 먼저 입력하세요.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val (curLat, curLon) = resolveCurrentWgs84LatLon()
            if (curLat == null || curLon == null) {
                Toast.makeText(this, "현재 위치를 확인할 수 없습니다", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // v: 재억 재지적(2026-08-28) - 카테고리(주변) 검색 결과도 팝업 없이 곧바로
            // 안내가 시작되고 있었음. #문제시 원복
            NearbyCategoryPopup.show(this, httpClient, restKey, curLat, curLon, lastKnownBearing) { picked ->
                showRoutePriorityDialog(picked)
            }
        }
    }

    // v9.5: 검색 결과 거리순 정렬용 현재 위치(WGS84 위도/경도) 조회.
    // requestKakaoRoute()와 동일한 우선순위(실시간 캐시 → 시스템 LocationManager)로
    // 재사용하되, 여기선 KATEC 변환 없이 위도/경도 그대로 반환. #문제시 원복
    private fun resolveCurrentWgs84LatLon(): Pair<Double?, Double?> {
        val cachedLat = lastKnownLat
        val cachedLon = lastKnownLon
        if (cachedLat != null && cachedLon != null) {
            return Pair(cachedLat, cachedLon)
        }
        return try {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val loc = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            if (loc != null) Pair(loc.latitude, loc.longitude) else Pair(null, null)
        } catch (e: SecurityException) {
            NavLogger.e(this, "위치 권한 없음(검색 거리순 정렬용): ${e.message}")
            Pair(null, null)
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
        // v14.2: 재억이 보내준 크래시 화면(사진)으로 확인 - "Fragment already added:
        // NavigationFragment" 예외로 앱이 강제종료되던 문제. 원인 - 이 함수가 화면
        // (NavigationFragment)을 이미 추가해뒀는지 확인 없이 무조건 새로 추가(add)하고
        // 있었음. SDK 초기화 콜백이 짧은 시간 안에 두 번 걸리는 경우(드묾) 등으로 이
        // 함수가 두 번 불리면, 두 번째 add() 시도에서 "이미 추가돼있다"는 예외로 죽음.
        // 이미 추가돼 있으면 다시 추가하지 않고 조용히 넘어가도록 함. #문제시 원복
        // v: 재억 제보(2026-09-04) - "분할화면 쓰면 상단패널이 눌러도 반응이 없다",
        // "분할화면<->전체화면 오가면 카메라 정보가 고정된다(초기화면 나갔다 오면 괜찮다)".
        // 둘 다 원인이 같음 - 화면이 다시 만들어질 때(회전/분할화면) 안드로이드가
        // NavigationFragment를 자동으로 되살려주는데, 위 조건에 걸려 여기서 통째로
        // return 해버리는 바람에 아래 있는 것들이 전부 안 불렸음:
        //  - setupDestinationSearchUi(): 상단바 ≡/검색/최근검색/집/회사/로그전송 버튼의
        //    클릭 리스너를 다는 곳 -> 패널은 보이는데 눌러도 아무 일이 안 일어남
        //  - frag.startSafeDrive(): 티맵 안전운행 모드를 켜는 곳 -> 위치/카메라/제한속도
        //    데이터 공급 자체가 멈춰서 마지막 값이 그대로 얼어붙음
        // 실기기 로그로 확인: 화면 재생성 뒤 "startSafeDrive() called"가 한 번도 안 찍히고,
        // 그 사이 [전체상태]의 차위치/카메라거리가 1분 내내 같은 값으로 고정됨(속도는 47).
        // 앱을 완전히 껐다 켠 13:57:07에야 startSafeDrive()가 다시 불리면서 값이 풀림 -
        // 재억이 말한 "초기화면 나갔다 오면 괜찮다"가 바로 이것.
        // 프래그먼트 추가만 건너뛰고 나머지는 전부 다시 연결함. #문제시 원복
        val restoredFragment = supportFragmentManager.findFragmentById(R.id.tmapUILayout) as? NavigationFragment
        if (restoredFragment != null) {
            NavLogger.d(this, "startSafeDriveMode: 화면(NavigationFragment)이 이미 추가돼있어 다시 추가하지 않음 - 배선과 안전운행 모드는 다시 연결")
            navigationFragment = restoredFragment
        } else {
            navigationFragment = getFragment() as NavigationFragment

            supportFragmentManager.beginTransaction()
                .add(R.id.tmapUILayout, navigationFragment!!)
                .commitAllowingStateLoss()

            // 진단용 리플렉션 전수조사라 무거움(예전에 화면 멈춤의 원인이었던 종류) -
            // 화면이 다시 만들어질 때마다 반복할 이유가 없어 처음 붙일 때만 실행. #문제시 원복
            dumpNavigationApiCandidates()
            dumpTrafficApiCandidates()
            dumpCameraAlarmApiCandidates()
        }

        setupDestinationSearchUi()
        updateRecentSearchPanel()
        applyTmapSatelliteViewSetting()
        applyTmapTrafficInfoSetting()

        // v3.4: 스티어링휠 마이크 버튼 대응 - 헤드유닛이 음성비서 인텐트로 앱을 띄운
        // 경우, UI가 다 셋업된 뒤에 처리
        handleVoiceCommandIntent(intent)

        navigationFragment?.let { frag ->
            // 프래그먼트가 완전히 뷰에 등록된 후 안전운행 모드를 시작하도록 약간의 딜레이를 줍니다.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                frag.startSafeDrive()
                NavLogger.d(this@MapActivity, "startSafeDrive() called")
            }, 1000)

            /*
            // TODO: Use ObservableRouteData instead of DriveStatusListener
            */

            // v4.8~: 티맵 차선/신호등 데이터 소스 조사용으로 넣었던 observableRouteData
            // 리플렉션 전수조사 코드 제거. 경로 데이터가 갱신될 때마다(주행 중 반복 발생)
            // 메인 스레드에서 모든 getter를 리플렉션으로 실행하고 있어서, 예전에 고쳤던
            // 화면 진입 시 멈춤과 같은 원인으로 주행 중 간헐적 티맵화면 멈춤을 유발하고
            // 있었음. 필요한 조사는 이미 끝났고 기능적으로도 안 쓰이는 코드라 제거함.
            // #문제시 원복: git 이력에서 이 커밋 이전 버전의 이 위치 코드 참고
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
                    // v4.13: Tmap 자체 내비게이션 엔진(rgData)에도 차선 정보 필드가 있는 걸
                    // 확인해서(사용자 2·3·4번) LaneSignalRepository에 연결. 카카오가 이미
                    // 최근에 갱신했으면 카카오 값을 안 건드림(둘이 서로 덮어쓰기 경쟁 방지). #문제시 원복
                    updateTmapLaneInfoFromEngine()
                    // v13.6: 재억 지적("평소엔 안뜨고 카카오 안내 끝내야 뜬다") - 실제로
                    // 화면(Tmap)에 렌더링을 시도하는 이 시점에 LaneSignalRepository 상태가
                    // 뭐였는지 15초 간격으로 남김. 카카오 쪽 로그([차선진단][카카오])랑
                    // 시간 맞춰보면 "갱신은 됐는데 화면에 안 뜬 건지" "갱신 자체가 하필
                    // 그 순간엔 안 됐던 건지" 구분 가능. #문제시 원복
                    // v: 재억 요청(2026-09-03) - 15초마다 무조건 남기던 걸 상태가 실제로
                    // 바뀔 때만 남기도록 변경. 여기서 보고 싶은 건 "떴다/안 떴다"가
                    // 바뀌는 순간이라 오히려 더 보기 쉬워짐. #문제시 원복
                    NavLogger.dIfChanged(
                        this@MapActivity,
                        "차선진단_렌더링",
                        "[차선진단][Tmap화면렌더링] source=${LaneSignalRepository.source} " +
                            "lanes=${LaneSignalRepository.lanes.size}개 fresh=${LaneSignalRepository.isFresh()} " +
                            "overlayEnabled(설정)=${getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).getBoolean("lane_overlay_tmap_enabled", true)}"
                    )
                    runOnUiThread {
                        renderLaneSignalBar(this@MapActivity, binding.llLaneSignalBar, binding.llLaneBoxes, binding.tvTrafficLightCountdown, "tmap")
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
                    if (location.hasBearing() && location.speed > 1.0f) { // 저속/정지 시 bearing이 부정확해서 어느정도 속도 있을 때만 갱신
                        lastKnownBearing = location.bearing
                    }
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

    private var udpServiceStartRetryCount = 0
    private fun startUdpSenderService() {
        // v4.18: startForeground()의 위치권한 케이스는 서비스 쪽에서 이미 방어했는데,
        // startForegroundService() 호출 그 자체도 안드로이드 12+에서는 "백그라운드에서
        // 포그라운드서비스 시작 제한"에 걸려 ForegroundServiceStartNotAllowedException을
        // 던질 수 있음 - 여기도 방어 없었음. 최소한 이 한 줄 때문에 앱 전체가 죽는 일은
        // 없게 함(서비스가 아예 안 뜨면 UDP 전송/HUD 기능만 빠지는 정도로 완화). #문제시 원복
        //
        // v4.23: 로그로 확인됨 - 이 예외가 실주행 중(08:26경, 앱이 이미 한참 실행 중이던
        // 시점)에도 발생함. 이건 OS가 메모리 부족 등으로 앱 프로세스를 껐다가 Activity만
        // 다시 띄우는 순간, 아직 "포그라운드로 인정"되기 직전 타이밍에 startForegroundService()가
        // 불려서 막히는 경우로 보임(사용자 지적 2·3번: 콤마 연결이 계속 끊겨 보이던 것의 원인
        // 중 하나). 실패해도 그냥 포기하지 않고, Activity가 완전히 포그라운드로 자리잡을
        // 시간을 준 뒤(2초) 딱 한 번 재시도. #문제시 원복
        // v: 실제 크래시 로그로 확인됨(사용자 제보 - 06:52~06:54 사이 2분간 20번 연속
        // 같은 예외로 반복 강제종료). "2초 후 딱 1번만 재시도, 실패하면 그 세션 내내 포기"로는
        // 부족했음 - mAllowStartForeground=false 상태가 2초 안에 안 풀리는 경우(콤마/차량
        // 부팅 초기 등)엔 그대로 셧다운. 최대 5번까지, 간격을 2s→4s→8s→16s→30s로 늘려가며
        // 재시도하도록 강화. #문제시 원복
        try {
            val intent = Intent(this, UdpSenderService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            udpServiceStartRetryCount = 0
        } catch (e: Exception) {
            NavLogger.e(this, "UdpSenderService 시작 실패(${udpServiceStartRetryCount + 1}번째): ${e.javaClass.simpleName}: ${e.message}")
            if (udpServiceStartRetryCount < 5) {
                val delayMs = 2000L shl udpServiceStartRetryCount // 2,4,8,16,32초
                udpServiceStartRetryCount++
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    NavLogger.d(this, "UdpSenderService 시작 재시도(${delayMs / 1000}초 후, ${udpServiceStartRetryCount}번째)")
                    startUdpSenderService()
                }, delayMs)
            } else {
                NavLogger.e(this, "UdpSenderService 재시도 5회 모두 실패 - 포기함")
            }
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
    // v3.4: 헤드유닛 스티어링휠 마이크 버튼 대응. 정확히 어떤 방식(인텐트로 앱을 새로
    // 띄우는지, 이미 떠있는 앱에 키 이벤트만 보내는지)을 쓰는 기기인지 몰라서 두 경로
    // 다 걸어둠. 실제로 버튼 눌렀을 때 로그에 "[VOICE_BTN]"이 찍히는지로 확인 가능. #문제시 원복
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleVoiceCommandIntent(intent)
    }

    private fun handleVoiceCommandIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_VOICE_COMMAND || action == Intent.ACTION_ASSIST) {
            NavLogger.d(this, "[VOICE_BTN] 음성명령 인텐트로 진입: action=$action")
            startVoiceSearch()
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        // KEYCODE_VOICE_ASSIST(231): 안드로이드 표준 음성비서 키. 일부 헤드유닛은
        // 스티어링휠 마이크 버튼을 KEYCODE_SEARCH(84)나 KEYCODE_HEADSETHOOK(79)로
        // 매핑하기도 해서 같이 잡아둠.
        if (keyCode == 231 /* KEYCODE_VOICE_ASSIST */ ||
            keyCode == android.view.KeyEvent.KEYCODE_SEARCH ||
            keyCode == android.view.KeyEvent.KEYCODE_HEADSETHOOK
        ) {
            NavLogger.d(this, "[VOICE_BTN] 키 이벤트로 진입: keyCode=$keyCode")
            startVoiceSearch()
            return true
        }
        // v: 재억 요청(2026-09-02) - "티맵 화면일 때는 그냥 음악 조절이 되게 해달라".
        // 예전엔 이 화면에서도 볼륨키를 가로채서 안내 음량을 조절했는데, 길안내 음량은
        // 카카오 안내 화면에서 조절하면 되고 티맵 화면에서는 평범하게 음악 볼륨이 조절되는
        // 게 자연스럽다는 판단. 볼륨키를 아예 가로채지 않고 안드로이드 기본 동작에 맡김.
        // #문제시 원복: 아래 두 줄 대신 VolumeHelper.adjustGuideVolumeByHardwareKey(...) 호출 복원
        return super.onKeyDown(keyCode, event)
    }

    // v4.14: dismissKeyboardAndSearchPanel()은 "목적지를 실제로 선택했을 때"만 호출됐음 -
    // 검색창을 눌러 포커스가 간 상태에서 "그냥 딴 데(지도든 어디든) 한 번 더 찍으면
    // 커서가 없어져야" 하는데, 그 일반적인 케이스(목적지 선택 없이 그냥 다른 곳을 탭)를
    // 처리하는 코드가 아예 없었음(사용자 지적: "여전하네"). 안드로이드 표준 패턴대로
    // 포커스가 가 있는 EditText 바깥을 탭하면 자동으로 포커스/키보드를 정리하도록
    // dispatchTouchEvent에서 전역으로 처리. #문제시 원복
    // v4.15: 사용자가 실제로 말한 건 이거였음 - "더보기"(btnMoreMenu) 눌러서 뜨는
    // svSecondaryPanel 팝업이 바깥을 찍어도 안 닫히고 버튼을 다시 눌러야만 닫힘.
    // 지금까지 각 메뉴 버튼 클릭 시에만 GONE 처리했지 "바깥 탭"에 대한 처리가 아예
    // 없었음. 같은 dispatchTouchEvent 안에서 팝업 바깥 탭도 같이 처리. #문제시 원복
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
            val panel = binding.svSecondaryPanel
            if (panel != null && panel.visibility == View.VISIBLE) {
                val panelRect = android.graphics.Rect()
                panel.getGlobalVisibleRect(panelRect)
                val touchInPanel = panelRect.contains(ev.rawX.toInt(), ev.rawY.toInt())
                // 토글 버튼(btnMoreMenu) 자체를 눌렀을 때는 그 버튼의 기존 토글 로직이
                // 알아서 처리하게 두고, 여기서 먼저 GONE으로 바꿔버리면 버튼 클릭 리스너가
                // "지금 GONE이네" 하고 다시 VISIBLE로 되돌려서 안 닫히는 것처럼 보임 - 그래서
                // 버튼 영역은 이 자동닫기 대상에서 제외. #문제시 원복
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
            if (focused is android.widget.EditText) {
                val outRect = android.graphics.Rect()
                focused.getGlobalVisibleRect(outRect)
                if (!outRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                    imm?.hideSoftInputFromWindow(focused.windowToken, 0)
                    focused.apply {
                        isFocusable = false
                        isFocusableInTouchMode = false
                        clearFocus()
                        isFocusable = true
                        isFocusableInTouchMode = true
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        NavLogger.d(this, "[MapActivity lifecycle] onResume")
        // v: 신규기능(경유지/카테고리 버튼 표시 옵션) - 재억 지적으로 두 스위치로 분리.
        // 설정 화면에서 바꾼 값이 바로 반영되도록 화면이 다시 보일 때마다 다시 읽어서 적용.
        // KakaoNaviActivity와 동일한 크래시 방지 목적으로 isInitialized 방어 추가. #문제시 원복
        if (::binding.isInitialized) {
            val showCategoryButton = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                .getBoolean("show_category_button", true)
            binding.btnNearbyCategory?.visibility = if (showCategoryButton) View.VISIBLE else View.GONE
        }
        // v: 재억 요청(2026-08-22) - 카카오 화면에서 돌아오는 순간뿐 아니라, 티맵 화면이
        // 다시 보이기 시작하는 즉시 최신 차선정보를 한 번 그려주고, 이후 새 데이터가 들어올
        // 때마다 곧바로 반영되도록 "지금 활성화된 화면" 자리에 이 화면을 등록. #문제시 원복
        LaneSignalRepository.activeRenderer = {
            renderLaneSignalBar(this@MapActivity, binding.llLaneSignalBar, binding.llLaneBoxes, binding.tvTrafficLightCountdown, "tmap")
        }
        LaneSignalRepository.notifyChanged()
        // v: 재억 제보(2026-08-30, 실기기로 확인 - "카카오 길안내 종료 후 티맵 화면에서
        // 미니플레이어 터치/갱신이 안 된다") - MiniPlayerManager.attach()가 onCreate에만
        // 걸려있어서 딱 한 번만 실행됐음. 카카오 화면으로 넘어가면 그쪽 attach()가 이
        // 싱글턴을 자기 뷰로 가져가는데, 다시 돌아올 땐 이 화면의 onCreate가 재실행되지
        // 않아(같은 인스턴스가 계속 살아있음) attach()가 다시 안 불려서 카카오 쪽의 이미
        // 사라진 뷰/컨트롤러를 계속 붙잡고 있었음. 화면이 다시 보일 때마다(onResume) 이
        // 화면 것으로 재부착. #문제시 원복
        binding.flMiniPlayerContainer?.let { outer ->
            com.tmap.nda.miniplayer.MiniPlayerManager.attach(
                this, outer, binding.llMiniPlayer!!,
                binding.ivMiniPlayerArt, binding.tvMiniPlayerTitle, binding.tvMiniPlayerArtist,
                binding.btnMiniPlayerPlayPause, binding.btnMiniPlayerPrev, binding.btnMiniPlayerNext,
                binding.btnMiniPlayerConfirm, binding.vMiniPlayerResizeHandle,
                "llMiniPlayer", resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            )
        }
        // v2.4: 볼륨 실시간 캡처 리시버 등록 (onPause에서 해제)
        try {
            registerReceiver(
                volumeChangeReceiver,
                android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            )
        } catch (e: Exception) {
            NavLogger.e(this, "볼륨 리시버 등록 예외: ${e.message}")
        }
        if (knsdkInitialized) {
            pendingKnsdkForegroundCall?.let { mainHandler.removeCallbacks(it) }
            val call = Runnable {
                try {
                    KNSDK.handleWillEnterForeground()
                    KNSDK.handleDidBecomeActive()
                    NavLogger.d(this, "KNSDK handleWillEnterForeground/handleDidBecomeActive 호출")
                } catch (e: Exception) {
                    NavLogger.e(this, "KNSDK 라이프사이클(resume) 전달 예외: ${e.message}")
                }
            }
            pendingKnsdkForegroundCall = call
            mainHandler.postDelayed(call, 300L)
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
        NavOverlayManager.activityStarted()
    }

    override fun onStop() {
        super.onStop()
        NavLogger.d(this, "[MapActivity lifecycle] onStop (KakaoNaviActivity가 위에 떴을 가능성)")
        NavOverlayManager.activityStopped(this)
    }

    override fun onPause() {
        super.onPause()
        NavLogger.d(this, "[MapActivity lifecycle] onPause (KakaoNaviActivity가 위에 뜨는 중일 수 있음 - handleWillResignActive가 이 타이밍에 KNSDK로 전달됨)")
        // v: 위 onResume에서 0.3초 지연 예약해둔 KNSDK 활성화 호출이 아직 실행 전이면 취소.
        // 화면 전환 중의 "잠깐 보였다가 바로 사라짐" 케이스라 실제로 필요 없었던 호출임. #문제시 원복
        pendingKnsdkForegroundCall?.let { mainHandler.removeCallbacks(it) }
        pendingKnsdkForegroundCall = null
        // v: 이 화면이 지금 등록된 화면이었으면 해제(카카오 화면이 자기 걸로 다시 등록할 것). #문제시 원복
        if (LaneSignalRepository.activeRenderer != null) {
            // 다른 화면이 이미 자기 걸로 덮어썼을 수도 있어 무조건 null로 밀지 않고,
            // 굳이 정밀 비교할 방법이 없으니(람다 동일성) 다음 화면의 onResume이 항상
            // 곧이어 자기 렌더러로 다시 덮어쓴다는 전제로 여기서는 그냥 null 처리.
            LaneSignalRepository.activeRenderer = null
        }
        // v2.4: 볼륨 리시버 해제 (onResume에서 등록)
        try {
            unregisterReceiver(volumeChangeReceiver)
        } catch (e: Exception) {
            // 등록 안 된 상태에서 해제 시도하면 예외 - 무시해도 안전
        }
        if (knsdkInitialized) {
            try {
                KNSDK.handleWillResignActive()
                NavLogger.d(this, "KNSDK handleWillResignActive 호출")
            } catch (e: Exception) {
                NavLogger.e(this, "KNSDK 라이프사이클(pause) 전달 예외: ${e.message}")
            }
        }
    }

    // v: 재억 제보(2026-09-03, 사진) - 분할화면을 쓰다가 전체화면으로 돌아오면 지도 안의
    // 글자/표지판이 통째로 확대돼 겹쳐 보이던 문제. 자세한 원인은 MapSurfaceRefresher 참고.
    // 티맵 지도(tmapUILayout)와 이 화면 안에 띄우는 카카오 지도 둘 다 대상.
    // #문제시 원복: 아래 세 함수만 지우면 됨
    // v: 재억 제보(2026-09-03, 사진) - 분할화면을 쓰고 나면 상단바의 목적지 검색칸이
    // 사라진 채로 뜨던 문제. 이 앱은 가로용/세로용 화면 배치 파일을 따로 갖고 있는데
    // (layout / layout-land), 어느 쪽을 쓸지는 화면을 처음 만들 때 딱 한 번 정해짐.
    // 분할화면일 때 창이 세로로 길어지면(예: 952x1048) 세로용 배치가 선택되고, 다시
    // 전체화면(가로)으로 돌아와도 configChanges 선언 때문에 화면을 다시 만들지 않아
    // 세로용 배치가 그대로 남음 - 세로용 상단바에는 검색칸이 없어서 사라진 것처럼 보임.
    // 가로/세로가 실제로 바뀐 경우에만 화면을 다시 만들어 올바른 배치를 쓰게 함(단순
    // 크기 변화로는 다시 만들지 않으므로 콤마 연결 유지 목적의 기존 처리는 그대로). #문제시 원복
    private var inflatedOrientation = 0

    // v: 재억 제보(2026-09-04) - 분할화면에 들어갈 때마다 화면을 통째로 다시 만드느라
    // 안내가 재시작되고 상단바 버튼도 먹통이 되던 문제. 분할화면일 때는 창이 세로로
    // 길어져도 배치를 갈아엎지 않고 지금 배치를 그대로 씀 - 그러면 전체화면으로 돌아와도
    // 원래 가로 배치 그대로라서 애초에 다시 만들 일이 없어짐(v19.2.92에서 이 재생성을
    // 넣은 이유였던 "세로 배치가 남는 문제"도 같이 사라짐). 진짜 기기 회전(전체화면)일
    // 때만 다시 만듦. #문제시 원복
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation != inflatedOrientation && !isInMultiWindowMode) {
            NavLogger.d(this, "[화면방향] 가로<->세로가 바뀌어 화면 배치를 다시 만듦")
            recreate()
            return
        }
        refreshMapSurfaces()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        NavLogger.d(this, "[MapActivity lifecycle] 분할화면 ${if (isInMultiWindowMode) "진입" else "해제"}")
        refreshMapSurfaces()
    }

    private fun refreshMapSurfaces() {
        MapSurfaceRefresher.onWindowResized(this, binding.tmapUILayout, "티맵")
        MapSurfaceRefresher.onWindowResized(this, kakaoNaviView, "카카오")
    }

    override fun onDestroy() {
        super.onDestroy()
        // v4.24: 이 로그가 그동안 없어서 "MapActivity가 백그라운드에서 OS에 의해
        // 강제 종료됐는지"를 로그로 직접 확인할 방법이 없었음(사용자: 카카오 화면 중
        // 카메라 감속 안 되던 문제 조사 때 아쉬웠던 부분) - 추가함. #문제시 원복
        NavLogger.d(this, "[MapActivity lifecycle] onDestroy (isFinishing=$isFinishing, isChangingConfigurations=$isChangingConfigurations)")
        com.tmap.nda.miniplayer.MiniPlayerManager.detach()
        // v8.8: MapActivity가 완전히 종료될 때(=앱 자체가 꺼질 때)만 폴링도 같이 정지.
        // 카카오 화면으로 넘어가는 것만으로는 MapActivity가 destroy 안 되니 계속 돌아감. #문제시 원복
        AutoUpdater.stopPeriodicCheck()

        laneDataObserver?.let { observableLaneDataLiveData?.removeObserver(it) }

        // v4.23: "티맵 화면에선 카메라 반응 감속이 되는데 카카오맵에선 안 된다"(사용자 지적) -
        // 원인으로 의심되는 구조적 문제를 찾음: UdpSenderService(카메라 감속 데이터를
        // openpilot으로 보내는 바로 그 서비스)가 MapActivity.onDestroy()에서 무조건
        // stopService()되고 있었음. MapActivity는 카카오 화면으로 넘어가도 보통은
        // 백스택에 남아있어서 destroy가 안 되지만, 헤드유닛처럼 메모리가 빠듯한 기기에서
        // OS가 백그라운드의 MapActivity를 메모리 확보 차원에서 강제로 destroy하면
        // (사용자는 여전히 카카오 화면을 보고 있는데) 이 UDP 서비스 전체가 조용히
        // 죽어버림 - 카메라 감속뿐 아니라 도로제한속도/HUD 데이터까지 전부 끊김.
        // "앱 종료" 버튼(의도적 종료)에서 명시적으로 멈추도록 옮기고, 여기서는 더 이상
        // 무조건 멈추지 않음. #문제시 원복
    }


    // v14.4: 안전정보(카메라/제한속도) 들어올 때마다 Gson을 매번 새로 만들던 것을
    // UdpSenderService.kt의 gson 캐싱 방식과 동일하게 한 번만 만들어 재사용하도록 수정.
    // 변환 결과나 화면 표시 로직은 전혀 안 바뀜. #문제시 원복
    private val sdiInfoGson = com.google.gson.Gson()

    private fun extractAndDisplaySdiInfo(bundle: android.os.Bundle) {
        try {
            val sdiObj = bundle.get("firstSDIInfo")
            if (sdiObj != null) {
                val sdiJsonStr = if (sdiObj is String) sdiObj else sdiInfoGson.toJson(sdiObj)
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
                        // v: 재억 지적(2026-08-13) - 코드 33은 실제로 "야생동물 사고 잦은
                        // 구간"인데 여기선 "어린이보호구역"으로 잘못 표시되고 있었음(원래
                        // 어린이보호구역은 코드 20). 20이 아예 처리 안 되고 있던 것도 같이
                        // 추가. 상단바에 전혀 안 뜨던 나머지 타입들(휴게소/톨게이트/안개/
                        // 사고다발/급커브/급경사/주차단속/갓길감시/끼어들기/교통정보수집/
                        // 과적·적재불량/버스전용차로/철길건널목/신호+과속/박스형과속)도
                        // 전부 표시되게 확장 + 타입별 아이콘 리소스 매핑 추가. #문제시 원복
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
                            0, 1, 7 -> R.drawable.ic_event_camera
                            2, 3, 4 -> R.drawable.ic_event_camera
                            6 -> R.drawable.ic_event_traffic_lights
                            8 -> R.drawable.ic_event_camera
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
                        updateTopBarEventDisplay(typeName, formatDistance(sdiDist), iconRes)
                    } else {
                        binding.tvSdiSpeedLimit?.text = ""
                        binding.tvSdiDist?.text = "--"
                        binding.tvSdiDescr?.text = "--"
                        updateTopBarEventDisplay(null, null, null)
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
                    updateTopBarEventDisplay(null, null, null)
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

    // v4.20: getObservableLaneData() 안전 재구현 (v4.17 대비 3가지 안전장치):
    // 1) 호출 순서를 startUdpSenderService() 이후로 이동 (포그라운드서비스 시작을 절대 안 막음)
    // 2) LiveData 구독/수신은 메인스레드에서 하되(안드로이드 필수), 실제 무거운 리플렉션
    //    덤프 작업은 Thread{}로 백그라운드에 위임 - 메인 스레드를 절대 안 묶음
    // 3) 딱 1번만 덤프하고 이후 값은 전부 무시 (dumped 플래그) - 주행 내내 반복 실행되며
    //    ANR을 유발할 위험 자체를 제거
    // 겸사겸사 배열 순회에도 상한(최대 20개)을 둬서 혹시 모를 대형 배열도 방어. #문제시 원복
    @Volatile private var laneDataDumped = false
    private var laneDataObserver: androidx.lifecycle.Observer<Any?>? = null
    private var observableLaneDataLiveData: androidx.lifecycle.LiveData<Any?>? = null
    @Suppress("UNCHECKED_CAST")
    private fun setupObservableLaneDataDump() {
        try {
            if (sdkManagerCompanion == null) {
                val sdkManagerClass = Class.forName("com.skt.tmap.engine.navigation.SDKManager")
                val companionField = sdkManagerClass.getField("Companion")
                sdkManagerCompanion = companionField.get(null)
                getInstanceMethod = sdkManagerCompanion?.javaClass?.getMethod("getInstance")
            }
            val sdkManager = getInstanceMethod?.invoke(sdkManagerCompanion) ?: return
            val method = sdkManager.javaClass.getMethod("getObservableLaneData")
            // v4.22: 실제 크래시 로그로 확인됨 - 이 LiveData가 가끔 null을 실제로 내보내는데,
            // Observer<Any>(non-null)로 선언해놨더니 코틀린 컴파일러가 자동으로 끼워넣는
            // null 체크(Intrinsics.checkNotNullParameter)가 내가 직접 쓴 "if (data==null)"
            // 보다 먼저 실행돼서 그 자리에서 NPE로 죽어버렸음(내 null 체크는 죽은 코드였던
            // 셈). Any -> Any?로 바꿔서 진짜로 null을 안전하게 받도록 수정. #문제시 원복
            val liveData = method.invoke(sdkManager) as? androidx.lifecycle.LiveData<Any?> ?: return
            observableLaneDataLiveData = liveData
            val observer = androidx.lifecycle.Observer<Any?> { data ->
                // 메인 스레드 콜백 - 여기선 플래그 체크와 백그라운드 위임만, 무거운 작업 없음
                if (data == null || laneDataDumped) return@Observer
                laneDataDumped = true
                observableLaneDataLiveData?.removeObserver(laneDataObserver ?: return@Observer)
                Thread {
                    try {
                        dumpLaneDataObjectSafely(data)
                    } catch (e: Exception) {
                        NavLogger.e(this, "[차선전용LiveData] 백그라운드 덤프 예외: ${e.message}")
                    }
                }.start()
            }
            laneDataObserver = observer
            liveData.observeForever(observer)
            NavLogger.d(this, "[차선전용LiveData] getObservableLaneData() 구독 성공")
        } catch (e: Exception) {
            NavLogger.e(this, "[차선전용LiveData] 구독 실패(리플렉션): ${e.message}")
        }
    }

    /** 백그라운드 스레드에서만 호출됨 - 메인 스레드 절대 안 씀. #문제시 원복 */
    private fun dumpLaneDataObjectSafely(data: Any) {
        val MAX_ARRAY_ITEMS = 20
        NavLogger.e(this, "===== [차선전용LiveData] getObservableLaneData 값 수신(1회, 백그라운드): class=${data.javaClass.name} =====")
        val dump = data.javaClass.methods
            .filter { m -> m.parameterTypes.isEmpty() && (m.name.startsWith("get") || m.name.startsWith("is")) }
            .joinToString(", ") { m ->
                try {
                    val v = m.invoke(data)
                    val vs = if (v?.javaClass?.isArray == true) {
                        val len = minOf(java.lang.reflect.Array.getLength(v), MAX_ARRAY_ITEMS)
                        (0 until len).joinToString(prefix = "[", postfix = if (java.lang.reflect.Array.getLength(v) > MAX_ARRAY_ITEMS) ",...]" else "]") { i ->
                            java.lang.reflect.Array.get(v, i)?.let { elem ->
                                try {
                                    elem.javaClass.methods
                                        .filter { em -> em.parameterTypes.isEmpty() && em.name.startsWith("get") }
                                        .joinToString(prefix = "{", postfix = "}") { em -> "${em.name}=${em.invoke(elem)}" }
                                } catch (ie: Exception) { elem.toString() }
                            } ?: "null"
                        }
                    } else v.toString()
                    "${m.name}=$vs"
                } catch (e: Exception) { "${m.name}=<실패>" }
            }
        NavLogger.e(this, "[차선전용LiveData] 전체덤프: $dump")
    }

    // Reflection Caching
    private var sdkManagerCompanion: Any? = null
    private var getInstanceMethod: java.lang.reflect.Method? = null
    private var getRecentRGDataMethod: java.lang.reflect.Method? = null
    private var nRoadLimitSpeedField: java.lang.reflect.Field? = null
    private var nTBTDistField: java.lang.reflect.Field? = null
    private var tbtDistFieldLookupFailed = false
    private var rgDataRecursiveDumped = false
    // v4.13: nTBTDist는 rgData 최상위가 아니라 rgData.stGuidePoint(TBTInfo) 안에 중첩되어
    // 있었음(전체 필드 덤프로 실제 확인함) - 그동안 최상위에서 찾아서 매번 실패하고
    // Int.MAX_VALUE만 반환했음(=도로제한속도 히스테리시스의 "안내지점 근접" 판정이 항상
    // 거짓으로 나오던 원인). stGuidePoint를 먼저 찾고 그 안에서 nTBTDist/nTBTTurnType을
    // 찾도록 수정. #문제시 원복
    private var stGuidePointField: java.lang.reflect.Field? = null
    private var stGuidePointFieldLookupFailed = false
    private var nTBTTurnTypeField: java.lang.reflect.Field? = null
    // v4.13: 순정 Tmap 안내 시 콤마에 회전방향(좌/우/유턴)을 못 보내는 문제(사용자 9번) -
    // 정확한 코드→방향 매핑표를 아직 확정 못 해서 표시 로직은 없지만, 실주행 중 실제로
    // 어떤 코드가 찍히는지 확인할 수 있게 0이 아닌 값이 뜰 때만(스팸 방지, 30초 간격)
    // 로그로 남김. 이 로그가 쌓이면 다음 세션에서 매핑표를 완성할 수 있음. #문제시 원복
    private var lastNonZeroTbtTurnTypeLogTime = 0L

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
                    if (stGuidePointField == null && !stGuidePointFieldLookupFailed) {
                        try {
                            stGuidePointField = rgData.javaClass.getField("stGuidePoint")
                        } catch (fe: Exception) {
                            stGuidePointFieldLookupFailed = true
                            NavLogger.e(this, "Reflection error (stGuidePoint): ${fe.message} - 이후 재시도 안 함")
                        }
                    }
                    val guidePoint = stGuidePointField?.get(rgData)
                    if (guidePoint != null) {
                        if (nTBTDistField == null && !tbtDistFieldLookupFailed) {
                            try {
                                nTBTDistField = guidePoint.javaClass.getField("nTBTDist")
                                nTBTTurnTypeField = guidePoint.javaClass.getField("nTBTTurnType")
                            } catch (fe: Exception) {
                                tbtDistFieldLookupFailed = true
                                Log.e("MapActivity", "Reflection error (TBTDist): ${fe.message}")
                                NavLogger.e(this, "Reflection error (TBTDist): ${fe.message} - 이후 재시도 안 함")
                            }
                        }
                        // v4.13: nTBTTurnType이 0이 아닌 값으로 찍히는 순간을 30초 간격으로
                        // 로그에 남김 - 다음 세션에서 이 값들을 모아 Tmap 자체 회전코드
                        // 매핑표(사용자 9번)를 완성하는 데 씀. #문제시 원복
                        try {
                            val turnType = (nTBTTurnTypeField?.get(guidePoint) as? Short)?.toInt() ?: 0
                            if (turnType != 0 && System.currentTimeMillis() - lastNonZeroTbtTurnTypeLogTime > 30000L) {
                                lastNonZeroTbtTurnTypeLogTime = System.currentTimeMillis()
                                NavLogger.d(this, "[Tmap 회전코드 수집] nTBTTurnType=$turnType nTBTDist=${nTBTDistField?.getInt(guidePoint)}")
                            }
                        } catch (ie: Exception) { /* 무시 - 수집용 로그라 실패해도 안전 */ }
                        return nTBTDistField?.getInt(guidePoint) ?: Int.MAX_VALUE
                    }
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

    private var nLaneCountField: java.lang.reflect.Field? = null
    private var bLaneField: java.lang.reflect.Field? = null
    private var aheadLaneInfoDataField: java.lang.reflect.Field? = null
    private var laneFieldLookupFailed = false
    private var aheadLaneInfoDataDumped = false

    // v4.13: Tmap 자체 안내 시엔 지금까지 차선정보가 아예 안 나왔던 문제(사용자 2·3·4번) -
    // Tmap의 EDCData 번들(얕은 경로)엔 차선 필드가 없지만, getRecentRGData()로 얻는
    // 진짜 엔진 구조체(rgData)엔 nLaneCount/bLane/aheadLaneInfoData가 실제로 존재함(로그로
    // 확인함). 다만 aheadLaneInfoData(LaneInfoData[])의 개별 필드가 "이 차선이 추천 차선인지"
    // 를 정확히 어떻게 나타내는지는(카카오의 getHighlightType 같은) 아직 실주행 값으로 확인을
    // 못 했음 - 잘못 추측해서 틀린 차선을 추천 표시하면 위험하므로(사용자 지적 사례와 동일한
    // 이유), 우선 "차선 개수"만 정직하게 표시(전부 추천 아님으로 렌더링)하고,
    // aheadLaneInfoData 내부 구조는 nLaneCount>0인 순간 1회 전체 덤프해서 다음 세션에서
    // 정확한 매핑을 완성할 재료로 남겨둠. #문제시 원복
    private fun updateTmapLaneInfoFromEngine() {
        try {
            // v5.4: 이 토글은 "화면 표시 여부"만 결정해야 함(재확인) - 데이터 수집 자체를
            // 막으면 안 됨. Tmap 자체 엔진에 혹시라도 값이 있으면(드물지만) 계속 수집하고,
            // 실제로 보여줄지는 renderLaneSignalBar에서 화면별로 결정. #문제시 원복
            // 카카오가 최근에 이미 갱신했으면 건드리지 않음(소스 경쟁 방지)
            if (LaneSignalRepository.source == "kakao" && LaneSignalRepository.isFresh()) return

            if (sdkManagerCompanion == null) {
                val sdkManagerClass = Class.forName("com.skt.tmap.engine.navigation.SDKManager")
                val companionField = sdkManagerClass.getField("Companion")
                sdkManagerCompanion = companionField.get(null)
                getInstanceMethod = sdkManagerCompanion?.javaClass?.getMethod("getInstance")
            }
            val sdkManager = getInstanceMethod?.invoke(sdkManagerCompanion) ?: return
            if (getRecentRGDataMethod == null) {
                getRecentRGDataMethod = sdkManager.javaClass.getMethod("getRecentRGData")
            }
            val rgData = getRecentRGDataMethod?.invoke(sdkManager) ?: return

            if (nLaneCountField == null && !laneFieldLookupFailed) {
                try {
                    nLaneCountField = rgData.javaClass.getField("nLaneCount")
                    bLaneField = rgData.javaClass.getField("bLane")
                    aheadLaneInfoDataField = rgData.javaClass.getField("aheadLaneInfoData")
                } catch (fe: Exception) {
                    laneFieldLookupFailed = true
                    NavLogger.e(this, "Reflection error (차선 필드): ${fe.message} - 이후 재시도 안 함")
                    return
                }
            }

            val laneCount = (nLaneCountField?.getInt(rgData)) ?: 0
            val laneActive = (bLaneField?.get(rgData) as? Boolean) ?: false

            // v9.1: "목적지 없는 안전운전 모드에서도 티맵 엔진이 차선 데이터를 주는지" 확인용
            // 진단 로그. 10초에 한 번만 남겨서 로그 도배 방지. 카카오가 실제 길안내 중인지
            // (isKakaoRouteGuideActive)도 같이 남겨서, "차선 데이터가 안 나오는 게 목적지가
            // 없어서인지 다른 이유인지" 나중에 로그만 보고 구분 가능하게 함. #문제시 원복
            // v: 재억 요청(2026-09-03) - 10초 간격 반복에서 값이 바뀔 때만 남기는 방식으로
            // 변경(내용이 거의 항상 동일했음). #문제시 원복
            NavLogger.dIfChanged(
                this,
                "차선진단_엔진",
                "[차선진단] 티맵엔진 nLaneCount=$laneCount bLane=$laneActive " +
                    "(카카오길안내중=$isKakaoRouteGuideActive, LaneSignalRepository.source=${LaneSignalRepository.source})"
            )

            if (laneCount > 0 && laneActive) {
                // 정확한 추천차선 판정 불가 - 우선 개수만 정직하게 표시 (전부 미추천으로)
                LaneSignalRepository.lanes = List(laneCount) { LaneDisplayInfo(recommended = false) }
                LaneSignalRepository.source = "tmap"
                LaneSignalRepository.lastUpdateTime = System.currentTimeMillis()
                LaneSignalRepository.notifyChanged()

                if (!aheadLaneInfoDataDumped) {
                    aheadLaneInfoDataDumped = true
                    val laneInfoArray = aheadLaneInfoDataField?.get(rgData)
                    if (laneInfoArray != null) {
                        NavLogger.e(this, "===== [차선정보 수집] aheadLaneInfoData 전체 덤프 (nLaneCount=$laneCount) =====")
                        dumpValueRecursive("aheadLaneInfoData", laneInfoArray, laneInfoArray.javaClass, 0, maxArrayItems = 10)
                    }
                }
            } else if (LaneSignalRepository.source == "tmap" && !laneActive) {
                LaneSignalRepository.lanes = emptyList()
                LaneSignalRepository.notifyChanged()
            }
        } catch (e: Exception) {
            NavLogger.e(this, "Tmap 차선정보 갱신 예외: ${e.message}")
        }
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
}
