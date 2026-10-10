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
import com.tmap.nda.databinding.ActivityNaverNaviBinding
import com.tmapmobility.tmap.tmapsdk.ui.util.TmapUISDK
import com.tmap.nda.naver.LonLat
import com.tmap.nda.naver.NaverDirectionsClient
import com.tmap.nda.naver.NaverEta
import com.tmap.nda.naver.NaverRouteOptions
import com.tmap.nda.naver.NaverGuidanceEngine
import com.tmap.nda.naver.NaverMapController
import com.tmap.nda.naver.NaverNavigator
import com.tmap.nda.naver.NaverRoute
import com.tmap.nda.naver.NaverTurnMap
import com.tmap.nda.naver.GuidanceState
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
class NaverNaviActivity : AppCompatActivity(), LocationListener {

    // v19.3.25: 재억 제보(폴드4 외부화면) - 카카오 SDK가 화면을 태블릿급으로 오판해 UI가
    // 잘려 보이는 문제 대응. 자세한 이유는 CoverScreenConfigFix 주석 참고. #문제시 원복
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(CoverScreenConfigFix.wrapIfDistorted(newBase))
    }

    private lateinit var binding: ActivityNaverNaviBinding
    private lateinit var naviView: android.widget.FrameLayout   // 네이버 지도가 들어가는 자리
    private lateinit var naverMap: NaverMapController
    private lateinit var guideOverlay: com.tmap.nda.naver.NaverGuideOverlay
    private var exitHookAttached = false
    private var wasTmapMuted = false
    private var kakaoMuted = false
    private var locationManager: LocationManager? = null
    // v14.2: MapActivity와 동일 - 항목을 골라 안내를 시작하려 할 때, 배경으로 돌던
    // 나머지 목록 계산들을 그만두게 하는 세대 카운터(재억 아이디어). #문제시 원복
    private var etaQueueGeneration = 0
    // v19.3.72: 신규기능(경로 선택 팝업에 목적지 핀 표시, 재억 요청 2026-09-18) - 지금
    // 찍혀있는 목적지 핀을 기억해뒀다가 팝업이 닫힐 때 지우기 위한 참조.
    // v19.3.78: 재억 실기기 제보 - 경로선택 카드가 떠있는 상태(완료/취소 전)에서 경유지 등
    // 다른 목적지를 또 고르면, showRouteChoicePanel()이 매번 새 카드를 만들어서 root에
    // 추가만 하고 이전 카드는 안 지워서 두 카드가 겹쳐 보였음. 지금 떠있는 카드를 기억해두고,
    // 새 카드를 띄우기 전에 먼저 이전 카드를 지우도록 함. #문제시 원복
    private var activeRouteChoicePanel: View? = null
    // v19.3.78: 이전 카드를 지울 때, 그 카드의 자동시작 카운트다운도 같이 멈춰야 함.
    // 안 멈추면 카드는 지워졌는데 타이머만 뒤에서 계속 돌다가, 시간이 다 되면 이미 지운
    // (엉뚱한) 예전 목적지로 안내가 시작돼버림. #문제시 원복
    private var activeRouteChoicePanelCancel: (() -> Unit)? = null
    // v19.3.72: 재억이 준 CarrotNavi 2.2.0 원본 소스(KakaoMapActivity.kt)에서 확인한 정석
    // 방식 - 경로 미리보기 중엔 지도 모드를 Top(진북고정 2D)으로 바꿔서 자동 추적을 잠깐
    // 멈추고, 끝나면 원래 모드로 되돌림. 이전에 썼던 "위치 갱신 자체를 끊는" 방식보다
    // 이게 원본이 검증한 진짜 방법. #문제시 원복
    // v19.3.72: 화면이 방금 막 열려서 아직 안내를 시작한 적 없는 첫 목적지 확정
    // 단계인지 표시. 경로선택 카드에서 "취소"를 눌렀을 때 티맵으로 돌아갈지
    // (finish) 판단하는 데 씀 - 이미 안내 중이던 걸 바꾸려다 취소한 경우는 false로
    // 유지되어 기존 안내가 그대로 이어짐. #문제시 원복
    private var isFirstTimeDestinationChoice = false
    private val originalTopMargins = mutableMapOf<Int, Int>()
    private val originalBottomMargins = mutableMapOf<Int, Int>()

    // v19.3.32: 설정 백업 파일을 골라오는 표준 파일 선택기(MapActivity와 동일). #문제시 원복
    private val restoreBackupLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val ok = SettingsBackup.restoreFromUri(this, uri)
            Toast.makeText(
                this,
                if (ok) "설정을 복원했습니다. 앱을 다시 시작해주세요." else "복원 실패 - 올바른 백업 파일인지 확인해주세요.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

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

    // v19.3.30: 재억 요청(Tmap 화면과 동일) - 상단바를 화면 아래로 내려서 확정하면 지도가
    // 그만큼 위로 올라와서 자리를 맞바꿔야 함. 상단바가 화면 위/아래 어느 가장자리에
    // 붙어있는지(PanelDragHelper.currentSnapEdge)에 따라 naviView 위/아래 여백을 다시 계산. #문제시 원복
    // v19.3.59: Tmap 화면과 동일 - 상단바가 반투명 유리로 바뀌면서, 지도를 상단바 높이
    // 전체만큼 밀어내면 바 밑에 지도가 없어 반투명이 안 보임. 바 기본 높이(baseHeight)만큼은
    // 지도를 그대로 두고, 그보다 "더 커진 만큼(expandedHeight)"만 밀어냄. #문제시 원복
    private fun applyMapOffsetForBarPosition() {
        val panel = binding.llLeftHudPanel ?: return
        val panelHeight = panel.height
        if (panelHeight <= 0) return
        val baseHeight = binding.llTopBarRow.minimumHeight
        val expandedHeight = (panelHeight - baseHeight).coerceAtLeast(0)
        val naviViewRef = naviView
        val params = naviViewRef.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val originalTop = originalTopMargins.getOrPut(naviViewRef.id) { 0 }
        val originalBottom = originalBottomMargins.getOrPut(naviViewRef.id) { params.bottomMargin }
        when (PanelDragHelper.currentSnapEdge(panel)) {
            PanelDragHelper.SnapEdge.TOP -> {
                params.topMargin = originalTop + expandedHeight
                params.bottomMargin = originalBottom
            }
            PanelDragHelper.SnapEdge.BOTTOM -> {
                params.topMargin = originalTop
                params.bottomMargin = originalBottom + expandedHeight
            }
            PanelDragHelper.SnapEdge.OTHER -> {
                params.topMargin = originalTop
                params.bottomMargin = originalBottom
            }
        }
        naviViewRef.layoutParams = params
    }

    // v19.3.37: Tmap 화면과 동일 - 상단바가 GONE이면 panelHeight가 0이 돼서
    // applyMapOffsetForBarPosition()이 아무것도 안 하고 리턴해버리므로, 숨길 때는
    // 이 함수가 직접 naviView 여백을 원래대로 되돌림. #문제시 원복
    private fun isTopPanelHidden(): Boolean =
        getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).getBoolean("top_panel_hidden", false)

    private fun setTopPanelHidden(hidden: Boolean) {
        getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).edit()
            .putBoolean("top_panel_hidden", hidden).apply()
        binding.llLeftHudPanel.visibility = if (hidden) View.GONE else View.VISIBLE
        // v19.3.40: Tmap 화면과 동일 - 화살표 대신 "숨김"/"표시" 글자로 명확하게 표시. #문제시 원복
        binding.btnToggleTopPanel?.text = if (hidden) "표시" else "숨김"
        if (hidden) {
            val naviViewRef = naviView
            val params = naviViewRef.layoutParams as? ViewGroup.MarginLayoutParams
            if (params != null) {
                val originalTop = originalTopMargins.getOrPut(naviViewRef.id) { 0 }
                val originalBottom = originalBottomMargins.getOrPut(naviViewRef.id) { params.bottomMargin }
                params.topMargin = originalTop
                params.bottomMargin = originalBottom
                naviViewRef.layoutParams = params
            }
        } else {
            applyMapOffsetForBarPosition()
        }
    }

    /** 상단 HUD 자동 확장 시 카카오 지도·플로팅 UI도 같은 만큼 아래로 이동한다. */
    private fun installTopPanelAutoOffset() {
        binding.llLeftHudPanel.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val panelHeight = bottom - top
            val baseHeight = binding.llTopBarRow.minimumHeight
            val expandedHeight = (panelHeight - baseHeight).coerceAtLeast(0)

            // v19.3.33: Tmap 화면과 동일 - 바 안의 글자만 바뀌어도 이 리스너가 불리면서 바 위치와
            // 상관없이 지도 위쪽을 바 높이만큼 다시 밀어내, 바를 아래로 내려도 위에 여백이
            // 남았음. 바 위치를 보고 계산하는 함수로 통일. #문제시 원복
            applyMapOffsetForBarPosition()
            applyTopPanelExpansion(binding.svSecondaryPanel, expandedHeight)
            // v19.3.36: Tmap 화면과 동일 - 상단바가 맨 아래에 붙어있을 때 바 높이가 커지면
            // 아래쪽(검색창/메뉴 등)이 화면 밖으로 밀려 안 보이는 문제 방지. #문제시 원복
            val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            PanelDragHelper.reclampBottomEdgeIfNeeded(this, binding.llLeftHudPanel, "llLeftHudPanel", isLandscape)
        }
    }

    // v1.7: 검색 버튼 짧게=음성, 길게=텍스트 - Tmap 화면과 동일하게 맞춤(사용자 지적 1번). #문제시 원복
    private val voiceSearchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) voiceAssistant.cancelAnswer()
        if (result.resultCode == RESULT_OK) {
            val spokenList = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.map { it.trim() }?.filter { it.isNotEmpty() }
            if (!spokenList.isNullOrEmpty()) {
                NavLogger.d(this, "음성검색 결과: ${spokenList.first()}")
                voiceAssistant.handleAlternatives(spokenList)
            } else {
                Toast.makeText(this, "음성 인식 결과가 없습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val voiceAssistant by lazy {
        VoiceAssistant(this, object : VoiceAssistant.Host {
            override fun currentLatLon() = resolveCurrentWgs84LatLonForSearch()
            override fun search(query: String) = performInPlaceSearch(query)
            override fun goFavorite(entry: HistoryEntry, priorityIndex: Int?, replace: Boolean) {
                if (priorityIndex == null) {
                    if (replace) startGuidanceToQuickSlot(entry) else handleQuickSlotTap(entry)
                    return
                }
                val prio = if (priorityIndex == 1) KNRoutePriority.KNRoutePriority_HighWay
                else KNRoutePriority.KNRoutePriority_Recommand
                val avoid = if (priorityIndex == 2) KNRouteAvoidOption.KNRouteAvoidOption_Fare.value else 0
                applyRouteOption(prio, avoid)
                KakaoRouteDataRepository.reset()
                activeWaypoints.clear()
                syncWaypointsToIntent()
                resolveCurrentPositionThenRequestRoute(entry.name, entry.lat, entry.lon, finishOnFailure = false)
            }
            override fun applyDayNight() = applyKakaoDayNight()
            override fun isMuted(): Boolean? = kakaoMuted
            override fun launchRecognizer() = startVoiceSearch()
            override fun currentBearing(): Float? = lastKnownBearing
            override fun registerFavorite(slot: String, query: String): Boolean {
                pendingQuickSlotRegistration = slot
                performInPlaceSearch(query)
                return true
            }
            override fun switchScreen(toKakao: Boolean): String {
                if (toKakao) return "이미 안내 화면이에요"
                return "안내 화면에서 티맵 화면으로 가려면 안내를 종료해야 해요. 안내를 종료할까요라고 말씀해 주세요"
            }
            override fun addWaypoint(entry: HistoryEntry) = addWaypointToActiveGuidance(entry)
            override fun addWaypointBySearch(query: String): Boolean {
                pendingWaypointAddition = true
                performInPlaceSearch(query)
                return true
            }
            override fun applySettingSideEffects() {
                val prefs = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                binding.btnAddWaypoint?.visibility = if (prefs.getBoolean("show_waypoint_button", true)) View.VISIBLE else View.GONE
                binding.btnNearbyCategory?.visibility = if (prefs.getBoolean("show_category_button", true)) View.VISIBLE else View.GONE
                binding.btnFavorites?.visibility = if (prefs.getBoolean("show_favorites_button", true)) View.VISIBLE else View.GONE
                syncMenuButtonDetach()
                binding.btnToggleTopPanel?.visibility = if (prefs.getBoolean("show_toggle_top_panel_button", false)) View.VISIBLE else View.GONE
                binding.flMiniPlayerContainer?.let { outer ->
                    com.tmap.nda.miniplayer.MiniPlayerManager.refresh(
                        this@NaverNaviActivity, outer,
                        binding.ivMiniPlayerArt, binding.tvMiniPlayerTitle, binding.tvMiniPlayerArtist,
                        binding.btnMiniPlayerPlayPause
                    )
                }
            }
        })
    }

    private fun startVoiceSearch() {
        val intent = voiceAssistant.recognizerIntent()
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
            TmapUISDK.setVolume(this, VolumeHelper.guideVolumePercent(this))
            KakaoSdkState.lastAppliedTmapVolume = VolumeHelper.guideVolumePercent(this)
        } catch (e: Exception) {
            NavLogger.e(this, "티맵 볼륨 복원 예외: ${e.message}")
        }
    }

    // 재억 요청(2026-09-28): 설정의 "메뉴 버튼 따로 떼어내기"에 맞춰 ≡ 버튼 위치를 맞춤. #문제시 원복
    private fun syncMenuButtonDetach() {
        val btn = binding.btnMoreMenu ?: return
        MenuButtonDetach.sync(this, btn, binding.btnFavorites?.parent as? android.view.ViewGroup, binding.tvConnectionStatus?.parent?.parent as? View)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NavLogger.appContext = applicationContext
        // v: 재억 재제보(2026-08-30) - "카카오 종료할 때 등"의 멈춤도 잡기 위해 여기서도
        // 시작 시도(이미 시작돼 있으면 내부에서 무시됨, MapActivity와 동일한 전역 워치독
        // 하나만 계속 돎). #문제시 원복
        MainThreadWatchdog.ensureStarted(applicationContext)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val destName = intent.getStringExtra("dest_name")
        val destLat = intent.getDoubleExtra("dest_lat", Double.NaN)
        val destLon = intent.getDoubleExtra("dest_lon", Double.NaN)
        // v13.1-2: 재억 요청 - 검색결과에서 "추천/고속도로우선/무료도로우선" 골랐으면
        // 그 값을 받아서 실제 안내 시작할 때 반영. 안 넘어오면(즐겨찾기 등 기존 경로는)
        // 그냥 기본값(추천) 그대로. #문제시 원복
        val routePriorityName = intent.getStringExtra("route_priority_name")
        val routeAvoidOption = intent.getIntExtra("route_avoid_option", 0)
        val chosenRoutePriority: KNRoutePriority = try {
            if (routePriorityName != null) {
                KNRoutePriority.valueOf(routePriorityName)
            } else {
                KNRoutePriority.KNRoutePriority_Recommand
            }
        } catch (e: Exception) {
            NavLogger.e(this, "[경로선택] KNRoutePriority.valueOf($routePriorityName) 실패: ${e.message}")
            KNRoutePriority.KNRoutePriority_Recommand
        }
        activeRoutePriority = chosenRoutePriority
        activeRouteAvoidOption = routeAvoidOption

        if (destName == null || destLat.isNaN() || destLon.isNaN()) {
            NavLogger.e(this, "KakaoNaviActivity: 목적지 정보 누락됨")
            finish()
            return
        }
        currentDestName = destName
        currentDestLat = destLat
        currentDestLon = destLon

        // v1.0.97: 예전엔 카카오 화면에 들어오면 무조건 티맵 볼륨을 0으로 강제해서, 사용자가
        // 원하는 "티맵 안내음량 버튼"이 있어도 의미가 없었음(항상 0으로 덮어써지니까).
        // 이제 MapActivity와 동일하게 사용자가 저장해둔 tmap_muted 값을 그대로 반영하고,
        // 아래 버튼으로 라이브 토글 가능하게 함. #문제시 원복
        val sharedPref = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
        wasTmapMuted = sharedPref.getBoolean("tmap_muted", false)
        applyTmapMute(wasTmapMuted)
        kakaoMuted = sharedPref.getBoolean("kakao_muted", false)
        NaverNavigator.muted = kakaoMuted

        // v3.13: 카카오 길안내 시작할 때마다 볼륨이 100%로 리셋되던 문제 - 저장해둔 값으로 다시 맞춤.
        // v: 재억 요청(2026-09-02, A안) - 미디어(음악) 볼륨을 건드리던 걸 길안내 음량만
        // 맞추는 것으로 교체. 음악 볼륨은 이제 앱이 절대 안 건드림. #문제시 원복
        VolumeHelper.applyGuideVolume(this)

        // 네이버 안내: 카카오 SDK 초기화를 기다릴 필요 없이 바로 화면을 만든다.
        setupContentAndStart(destName, destLat, destLon, routePriorityName)
    }

    // 지도 낮/밤: 설정(자동/항상 낮/항상 밤)에 맞춰 카카오 화면 밤 모드(useDarkMode)를 켜고 끔.
    // 자동은 해 뜨고 지는 시각 기준이라 5분마다 다시 확인하고, 바뀌었을 때만 다시 적용. #문제시 원복
    private var lastAppliedKakaoNight: Boolean? = null
    private val kakaoDayNightTick = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            applyKakaoDayNight(force = false)
            window.decorView.postDelayed(this, 5 * 60 * 1000L)
        }
    }

    private fun applyKakaoDayNight(force: Boolean = true) {
        try {
            val night = DayNightHelper.isNight(this)
            if (!force && night == lastAppliedKakaoNight) return
            if (::naverMap.isInitialized) naverMap.setNight(night)
            lastAppliedKakaoNight = night
            NavLogger.d(this, "[네이버낮밤] 적용됨: ${if (night) "밤" else "낮"} (설정=${DayNightHelper.mode(this)})")
        } catch (e: Exception) {
            NavLogger.e(this, "[네이버낮밤] 적용 예외: ${e.message}")
        }
    }

    // 설정의 네이버 지도 옵션(위성지도·교통 정보·평면 보기)을 지도에 반영(안내 시작 때와 설정 창을 닫을 때).
    private fun applyNaverSatellite() {
        val pref = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
        val sat = pref.getBoolean("naver_satellite_view_enabled", false)
        val traffic = pref.getBoolean("naver_traffic_enabled", false)
        val flat = pref.getBoolean("naver_flat_view_enabled", false)
        if (::naverMap.isInitialized) {
            naverMap.setSatellite(sat); naverMap.setTraffic(traffic); naverMap.setFlat(flat)
        }
        NavLogger.d(this, "[네이버지도옵션] 위성=$sat 교통=$traffic 평면=$flat")
    }

    private fun setupContentAndStart(destName: String, destLat: Double, destLon: Double, routePriorityName: String?) {
        binding = ActivityNaverNaviBinding.inflate(layoutInflater)
        setContentView(binding.root)
        inflatedOrientation = resources.configuration.orientation
        naviView = binding.naviView
        // 지도: 카카오 지도 대신 네이버 지도를 이 자리에 넣는다(UI는 그대로).
        naverMap = NaverMapController(this, binding.naviView)
        naverMap.init(NaverDirectionsClient.keyId(this), null) { }
        applyNaverSatellite()
        guideOverlay = com.tmap.nda.naver.NaverGuideOverlay(this, binding.root)
        PopupCard.onPopupVisibilityChanged = { visible -> runOnUiThread { guideOverlay.setObscured(visible) } }
        applyKakaoDayNight()
        window.decorView.removeCallbacks(kakaoDayNightTick)
        window.decorView.postDelayed(kakaoDayNightTick, 5 * 60 * 1000L)
        setupWaypointAddButton()
        setupNearbyCategoryButton()

        // v3.9: Tmap 화면과 동일하게 상단바 드래그 편집 기능 연결 (사용자: "기본 UI는
        // 티맵/카카오맵 차등을 주지 말고 동일하게 적용해야돼") - PanelDragHelper 공용
        // 코드라 편집모드 상태(PanelDragHelper.isEditMode)도 두 화면이 공유함. #문제시 원복
        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        // v19.3.39: 재억 요청(Tmap 화면과 동일) - 자유 드래그 대신 "이동" 핸들을 누르면
        // 반대쪽 가장자리로 한 번에 점프하는 버튼으로 바꿈. #문제시 원복
        binding.llLeftHudPanel?.let { panel ->
            fun updateDragHandleArrow() {
                binding.btnDragHandleTopBar?.text = PanelDragHelper.dragHandleArrow(panel)
            }
            binding.btnDragHandleTopBar?.setOnClickListener {
                PanelDragHelper.snapPanelToOppositeEdge(this, panel, "llLeftHudPanel", isLandscape) {
                    applyMapOffsetForBarPosition()
                }
                updateDragHandleArrow()
            }
            panel.post {
                PanelDragHelper.restorePosition(this, panel, "llLeftHudPanel", isLandscape, emptyList())
                // v19.3.30: 저장된 위치를 복원한 직후에도 그 위치 기준으로 지도 여백을 맞춤
                applyMapOffsetForBarPosition()
                updateDragHandleArrow()
            }
        }

        // v: 재억 지적(2026-08-26) - "UI 편집"을 눌러도 경유지/카테고리/경유지취소 버튼은
        // 반응이 없다는 지적 - llLeftHudPanel만 드래그 대상이었고 이 버튼들은 빠져있었음.
        // 같은 방식으로 편집모드에서 같이 옮길 수 있게 추가. #문제시 원복
        // v19.3.41: 재억 요청 - "UI 편집" 모드에 안 들어가도, 플로팅 버튼(상단바 표시/숨김)
        // 처럼 1초 꾹 누르면 바로 그 자리에서 옮길 수 있게. 짧게 누르면 원래 클릭 동작이
        // 그대로 나가야 해서 onTap에서 버튼 자신의 performClick()을 호출. #문제시 원복
        // v19.3.42: 재억 실기기에서 강제 배치까지 해서 재확인 - bringToFront()+requestLayout()+
        // invalidate()(PanelDragHelper.forceToFront)만으로 상단바 표시/숨김 어느 상태에서도,
        // 상단바와 겹치는 자리를 포함해 정상적으로 보이고 눌림. 그래서 충돌 회피로 막지 않고
        // 재억 요청대로 상단바 위로도 자유롭게 이동 가능하게 둠. #문제시 원복
        // v19.3.80: 재억 요청 - 네 아이콘을 2줄x2칸 격자 + 크기 3단 + 살짝 끌어 이동 + 2초 꾹 크기
        // 변경으로 통일(QuickIconGrid). 즐겨찾기/주변 위, 경유지/취소 아래. #문제시 원복
        binding.btnFavorites?.setOnClickListener { showFavoritesCard() }
        val quickItems = ArrayList<QuickIconGrid.Item>()
        binding.btnFavorites?.let { btn -> quickItems.add(QuickIconGrid.Item(btn, "btnFavorites", 0) { btn.performClick() }) }
        binding.btnNearbyCategory?.let { btn -> quickItems.add(QuickIconGrid.Item(btn, "btnNearbyCategory", 1) { btn.performClick() }) }
        binding.btnAddWaypoint?.let { btn -> quickItems.add(QuickIconGrid.Item(btn, "btnAddWaypoint", 2) { btn.performClick() }) }
        binding.btnCancelWaypoint?.let { btn -> quickItems.add(QuickIconGrid.Item(btn, "btnCancelWaypoint", 3) { btn.performClick() }) }
        QuickIconGrid.snapTargets = { listOfNotNull(binding.tvCurrentSpeed?.parent as? View, binding.tvConnectionStatus?.parent?.parent as? View) }
        if (quickItems.isNotEmpty()) QuickIconGrid.setup(this, quickItems, binding.tvConnectionStatus?.parent?.parent as? View)
        syncMenuButtonDetach()
        // 화면이 새로 만들어질 때도 저장된 버튼 표시 설정을 바로 적용(onResume이 먼저 지나가 건너뛴 경우 대비)
        getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).let { p ->
            binding.btnAddWaypoint?.visibility = if (p.getBoolean("show_waypoint_button", true)) View.VISIBLE else View.GONE
            binding.btnNearbyCategory?.visibility = if (p.getBoolean("show_category_button", true)) View.VISIBLE else View.GONE
            binding.btnFavorites?.visibility = if (p.getBoolean("show_favorites_button", true)) View.VISIBLE else View.GONE
            syncMenuButtonDetach()
        }
        // v19.3.37: 재억 요청 - Tmap 화면과 동일한 상단바 표시/숨김 플로팅 버튼. 카카오
        // SDK 자체가 화면이 좁을수록 왼쪽 안내 박스를 겹쳐 그리는 문제 대응 - 눌러서
        // 상단바를 통째로 치우고 지도한테 세로 공간을 최대한 양보. v19.3.37b: "UI 편집"
        // 모드를 따로 켤 필요 없이 이 버튼 자체를 1초 꾹 누르면 바로 그 자리에서 드래그
        // 이동, 짧게 탭하면 토글. #문제시 원복
        binding.btnToggleTopPanel?.let { btn ->
            // v19.3.44: 재억 요청 - 이 버튼은 기본값 안 보임 - 메뉴 > 설정 > 화면 표시에서
            // 켜야만 보이게 함(Tmap 화면과 동일). #문제시 원복
            btn.visibility = if (getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                    .getBoolean("show_toggle_top_panel_button", false)) View.VISIBLE else View.GONE
            btn.post {
                PanelDragHelper.restorePosition(this, btn, "btnToggleTopPanel", isLandscape, emptyList())
                PanelDragHelper.forceToFront(btn)
            }
            PanelDragHelper.makeLongPressDraggable(this, btn, "btnToggleTopPanel", isLandscape) {
                setTopPanelHidden(!isTopPanelHidden())
            }
        }
        setTopPanelHidden(isTopPanelHidden())

        // v: 신규기능(미니 플레이어) - 재억 요청(2026-08-28). 카드를 길게 누르면 "위치
        // 이동"/"크기 조절" 메뉴가 뜸. #문제시 원복
        binding.flMiniPlayerContainer?.let { outer ->
            com.tmap.nda.miniplayer.MiniPlayerManager.attach(
                this, outer, binding.llMiniPlayer!!,
                binding.ivMiniPlayerArt, binding.tvMiniPlayerTitle, binding.tvMiniPlayerArtist,
                binding.btnMiniPlayerPlayPause, binding.btnMiniPlayerPrev, binding.btnMiniPlayerNext,
                binding.btnMiniPlayerConfirm, binding.vMiniPlayerResizeHandle,
                "llMiniPlayer", isLandscape
            )
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
            com.tmap.nda.miniplayer.MiniPlayerManager.topSafeInsetPx = safeArea.top
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

        // 네이버 안내 엔진의 상태(다음 안내·남은 거리 등)를 이 화면에 보여주도록 연결한다.
        NaverNavigator.setListener(naverGuidanceListener)


        // v4.16: [볼륨API스캔]으로도 확인됐지만, 카카오모빌리티 공식 문서
        // (사용자 맞춤 설정하기)에 명시된 공개 API였음 - KNNaviView.sndVolume(Float,
        // 0.0~1.0, 기본값 1f)이 내비게이션 음성 안내 음량을 직접 조정하는 진짜 방법.
        // 그동안 시스템 STREAM_MUSIC을 아무리 만져도 안 먹혔던 이유가 이거였음 - 카카오
        // SDK가 시스템 볼륨과 무관하게 내부적으로 항상 1.0(100%)로 재생하고 있었던 것.
        // 저장된 볼륨%(VolumeHelper)를 0.0~1.0으로 변환해서 실제로 반영.
        // PR#9 병합 과정에서 이 함수 전체가 실수로 같이 삭제됐었음 - 복원. #문제시 원복
        // v: 재억 요청(2026-09-02, A안) - 물리 볼륨버튼으로 "길안내 음량만" 조절하려면
        // VolumeHelper가 카카오 화면의 naviView에 값을 넣을 수 있어야 함. 화면이 살아있는
        // 동안만 등록해두고 onDestroy에서 해제. #문제시 원복
        VolumeHelper.kakaoGuideVolumeApplier = { fraction ->
            runOnUiThread { if (::naviView.isInitialized) applyKakaoSdkVolume(fraction) }
        }
        // v: 재억 제보(2026-09-02) - 토스트는 하나씩 순서대로 뜨고 각각 2초씩 머물러서,
        // 볼륨키를 길게 누르면 값이 뒤늦게 띄엄띄엄(50/45/35) 나타났음. 화면에 직접 그리는
        // 표시로 바꿔서 누르는 즉시 숫자가 갱신되게 함. #문제시 원복
        VolumeHelper.guideVolumeIndicator = { percent ->
            runOnUiThread { showGuideVolumeIndicator(percent) }
        }
        applyKakaoSdkVolume()

        // 안내가 이미 돌고 있는데 화면만 다시 만들어진 경우(가로↔세로 회전, 분할화면, 티맵 화면에서 돌아옴):
        // 같은 목적지라면 길찾기를 새로 하지 않고 돌고 있는 안내에 화면만 다시 이어 붙인다.
        val runningGoal = NaverNavigator.goalPoint
        if (NaverNavigator.isRunning && runningGoal != null &&
            NaverGuidanceEngine.distance(runningGoal, LonLat(destLon, destLat)) < 50.0
        ) {
            restoreWaypointsFromIntent()
            NaverNavigator.currentRoute?.let { r ->
                naverMap.showActiveRoute(r)
                guideOverlay.show()
            }
            NaverNavigator.lastLocation?.let { naverMap.follow(it, animated = false) }
            return
        }

        // v: 화면이 다시 만들어진 경우(분할화면 전환 등) 경유지를 넣어둔 채였다면 그 경유지를
        // 포함해서 경로를 다시 짜야 함 - 그냥 목적지만 요청하면 경유지가 조용히 사라짐. #문제시 원복
        restoreWaypointsFromIntent()
        val resumedWithWaypoints = if (activeWaypoints.isNotEmpty()) {
            NavLogger.d(this, "[경유지] 화면 재생성 - 경유지 ${activeWaypoints.size}개를 그대로 이어감")
            rebuildRouteWithWaypoints(activeWaypoints.toList(), "경유지 유지")
        } else {
            false
        }
        if (!resumedWithWaypoints) {
            // v19.3.72: 신규기능(재억 요청 2026-09-18) - "즐겨찾기/주변탐색/검색 등
            // 어디서 들어오든 다 목적지 지도가 나와야 하는 거 아니냐"는 지적 - MapActivity가
            // 방식(route_priority_name)을 미리 정해서 넘겨준 경우(경유지 재구성 등)만 그대로
            // 바로 안내를 시작하고, 안 넘어온 "새로 목적지 고른" 경우는 여기서(idle map이
            // 이미 초기화된 뒤라 핀을 찍을 지도가 있음) 핀 찍고 경로선택 팝업을 직접 띄움.
            // 이러면 MapActivity 쪽 즐겨찾기/검색/주변탐색 등 모든 진입점이 이 화면으로만
            // 넘어오게 통일해두면 자동으로 지도 미리보기가 붙게 됨. #문제시 원복
            val parkedAt = intent.getLongExtra("parked_view_saved_at", 0L)
            if (parkedAt > 0L) {
                // 티맵 화면의 "내 차 위치 > 지도에서 보기"로 열린 경우 - 경로 없이 차 위치만 보여줌
                parkedLaunchedFromTmap = true
                startParkedCarViewWhenReady(destLat, destLon, parkedAt, 0)
            } else if (routePriorityName != null) {
                resolveCurrentPositionThenRequestRoute(destName, destLat, destLon)
            } else {
                // v19.3.72: 재억 실기기 제보 - "취소 눌러도 티맵으로 안 돌아간다"의 원인 -
                // currentDestName이 onCreate 초반에 이미 destName으로 채워져 있어서,
                // "이미 안내 중이던 걸 바꾸려다 취소" 판별에 currentDestName을 썼던 게
                // 이 경로(방금 새로 열린 화면)에서도 항상 false가 되어버렸음. 화면이 갓
                // 열려서 아직 안내를 한 번도 시작한 적 없는 이 경우만 표시해두는 전용
                // 플래그로 교체. #문제시 원복
                isFirstTimeDestinationChoice = true
                showRoutePriorityDialog(HistoryEntry(destName, "", destLat, destLon))
            }
        }
    }

    // 카카오 SDK 자체 음량과 동기화하던 부분 - 네이버 안내는 우리 음량 값 하나만 쓰므로 필요 없다.
    private fun syncGuideVolumeFromKakaoNow() {}

    // v: 재억 제보(2026-09-02) - 볼륨키를 길게 누를 때 값이 즉시 보이도록, 화면 가운데
    // 아래쪽에 잠깐 떴다 사라지는 표시를 직접 그림(토스트와 달리 밀리지 않고 바로 갱신됨).
    // 1.2초 동안 새 입력이 없으면 자동으로 사라짐. #문제시 원복
    private var guideVolumeIndicatorView: android.widget.TextView? = null
    private val guideVolumeIndicatorHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private fun showGuideVolumeIndicator(percent: Int) {
        try {
            val root = window?.decorView as? android.view.ViewGroup ?: return
            var view = guideVolumeIndicatorView
            if (view == null || view.parent == null) {
                view = android.widget.TextView(this).apply {
                    setTextColor(android.graphics.Color.WHITE)
                    setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
                    textSize = 22f
                    gravity = android.view.Gravity.CENTER
                    setPadding(56, 28, 56, 28)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.parseColor("#CC1A1A1A"))
                        cornerRadius = 28f
                        setStroke(2, android.graphics.Color.parseColor("#66FFFFFF"))
                    }
                    elevation = 40f
                }
                val lp = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.BOTTOM
                    bottomMargin = 160
                }
                root.addView(view, lp)
                guideVolumeIndicatorView = view
            }
            view.text = "🔊 길안내 음량 ${percent}%"
            view.visibility = View.VISIBLE
            guideVolumeIndicatorHandler.removeCallbacksAndMessages(null)
            guideVolumeIndicatorHandler.postDelayed({
                guideVolumeIndicatorView?.visibility = View.GONE
            }, 1200)
        } catch (e: Exception) {
            NavLogger.e(this, "[안내음량] 화면 표시 예외: ${e.message}")
        }
    }

    // v: 재억 요청(2026-09-02, A안) - 이제 "길안내 음량"이 미디어 음량과 완전히 분리된
    // 별도 값(VolumeHelper.guideVolumePercent)이라 그걸 읽어서 적용함. 네이버 안내 음성(TTS)에 적용. #문제시 원복
    private fun applyKakaoSdkVolume() {
        applyKakaoSdkVolume(VolumeHelper.guideVolumePercent(this) / 100f)
    }

    private fun applyKakaoSdkVolume(fractionIn: Float) {
        val fraction = fractionIn.coerceIn(0f, 1f)
        NaverNavigator.guideVolume = fraction
        NavLogger.dIfChanged(this, "안내음량", "[네이버안내음량] 적용 ${(fraction * 100).toInt()}%")
    }

    // 예전 카카오 SDK 음량과의 양방향 동기화 진단 - 네이버 안내는 우리 값 하나만 쓰므로 할 일이 없다.
    private var lastVolumeDiagKey = ""
    private fun logKakaoVolumeDiagnostics() {}

    // ===================== 네이버 길찾기 · 안내 시작 =====================
    // 카카오 SDK(makeTripWithStart/guideNewDestinations)로 하던 경로 요청과 안내 시작을
    // 네이버 길찾기 API + NaverNavigator(안내 엔진)로 바꾼 부분. 화면 UI는 그대로 쓴다.

    /** 앱 전체가 쓰는 이동방식 이름(KNRoutePriority 이름·무료도로 회피 값)을 네이버 길찾기 옵션으로 바꾼다. */
    private fun naverOptionFor(priority: KNRoutePriority, avoidOption: Int): String = when {
        avoidOption != 0 -> "traavoidtoll"
        priority == KNRoutePriority.KNRoutePriority_HighWay -> "trafast"
        priority == KNRoutePriority.KNRoutePriority_WideWay -> "tracomfort"
        else -> "traoptimal"
    }

    private fun currentStartLonLat(): LonLat? {
        NaverNavigator.ensureLocation(this)
        val loc = NaverNavigator.currentFix() ?: com.tmap.nda.naver.FreshLocation.lastFresh(this) ?: return null
        return LonLat(loc.longitude, loc.latitude)
    }

    private fun resolveCurrentPositionThenRequestRoute(destName: String, destLat: Double, destLon: Double, finishOnFailure: Boolean = true) {
        // 안내 중에 목적지를 바꾸면(즐겨찾기/검색으로 새 안내 시작) 여기 넘어온 값이 진짜 지금 목적지다.
        // 화면이 다시 만들어져도(분할화면 전환 등) 처음 목적지로 되돌아가지 않게 intent에도 갱신해 둔다.
        currentDestName = destName
        currentDestLat = destLat
        currentDestLon = destLon
        intent.putExtra("dest_name", destName)
        intent.putExtra("dest_lat", destLat)
        intent.putExtra("dest_lon", destLon)

        val from = currentStartLonLat()
        if (from == null) {
            Toast.makeText(this, "GPS 확인 중입니다...", Toast.LENGTH_SHORT).show()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!isFinishing) resolveCurrentPositionThenRequestRoute(destName, destLat, destLon, finishOnFailure)
            }, 1000)
            return
        }
        val option = naverOptionFor(activeRoutePriority, activeRouteAvoidOption)
        val vias = activeWaypoints.map { LonLat(it.lon, it.lat) }
        val to = LonLat(destLon, destLat)
        Thread {
            val res = NaverDirectionsClient.requestRoute(this, from, to, vias, option)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val route = res.routes.firstOrNull()
                if (!res.ok || route == null) {
                    NavLogger.e(this, "네이버 경로요청 실패: code=${res.code} ${res.message}")
                    Toast.makeText(this, "경로 탐색 실패(${res.code}): ${res.message}", Toast.LENGTH_SHORT).show()
                    if (finishOnFailure) finish()
                    return@runOnUiThread
                }
                NavLogger.d(this, "네이버 경로요청 성공, 안내 시작: $destName (${route.distanceMeters}m)")
                startNaverGuidance(route, destName, destLat, destLon)
            }
        }.start()
    }

    /** 받아온 경로로 실제 안내를 시작한다(카카오 때의 guideNewDestinations 자리). */
    private fun startNaverGuidance(route: NaverRoute, destName: String, destLat: Double, destLon: Double) {
        // 안내 이어가기 - 안내가 실제로 시작되는 이 시점에 목적지를 저장. 도착/종료 때 finishGuidance()에서 지워진다.
        ResumeGuidanceStore.save(this, HistoryEntry(destName, "", destLat, destLon, routePriorityName = activeRoutePriority.name, routeAvoidOption = activeRouteAvoidOption))
        clearDestinationPin()
        naverMap.showActiveRoute(route)
        naverMap.resumeFollow()
        NaverNavigator.begin(applicationContext, route, LonLat(destLon, destLat), destName, activeWaypoints.map { LonLat(it.lon, it.lat) })
        guideOverlay.show()
        applyKakaoSdkVolume()
        NaverNavigator.lastLocation?.let { naverMap.follow(it, animated = false) }
    }

    // 안내 상태 문구(사용량·재탐색 등)를 알림창 대신 화면 아래에 5초 동안 띄운다(알림창은 최대 3.5초라 짧음).
    private var statusBoxView: android.widget.TextView? = null
    private val statusBoxHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private fun showStatusBox(text: String, durationMs: Long = 5000L) {
        if (!::binding.isInitialized) return
        val root = binding.root as? ViewGroup ?: return
        var v = statusBoxView
        if (v == null || v.parent == null) {
            v = android.widget.TextView(this).apply {
                setTextColor(android.graphics.Color.WHITE)
                textSize = 16f
                gravity = android.view.Gravity.CENTER
                setPadding(PopupCard.dp(this@NaverNaviActivity, 22), PopupCard.dp(this@NaverNaviActivity, 12), PopupCard.dp(this@NaverNaviActivity, 22), PopupCard.dp(this@NaverNaviActivity, 12))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#E61B2430"))
                    cornerRadius = PopupCard.dp(this@NaverNaviActivity, 22).toFloat()
                }
                elevation = 30f
            }
            root.addView(v, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                bottomMargin = PopupCard.dp(this@NaverNaviActivity, 48)
            })
            statusBoxView = v
        }
        v.text = text
        v.visibility = View.VISIBLE
        statusBoxHandler.removeCallbacksAndMessages(null)
        statusBoxHandler.postDelayed({ statusBoxView?.visibility = View.GONE }, durationMs)
    }

    /** NaverNavigator(안내 엔진)가 알려주는 상태를 이 화면에 그려준다. */
    private val naverGuidanceListener = object : NaverNavigator.Listener {
        override fun onLocation(loc: Location) {
            if (!::naverMap.isInitialized) return
            naverMap.setLocation(loc)
            if (NaverNavigator.isRunning) naverMap.follow(loc)
        }

        override fun onState(state: GuidanceState, goalName: String) {
            if (::guideOverlay.isInitialized) guideOverlay.update(state, goalName)
            val total = NaverNavigator.currentRoute?.distanceMeters?.toDouble() ?: 0.0
            if (total > 0) naverMap.setProgress(state.progressMeters / total)
        }

        override fun onRouteChanged(route: NaverRoute) {
            if (!::naverMap.isInitialized) return
            runOnUiThread {
                naverMap.showActiveRoute(route)
                showRerouteBanner()
            }
        }

        override fun onStatus(text: String) {
            runOnUiThread { showStatusBox(text) }
        }

        override fun onFinished(arrived: Boolean) {
            runOnUiThread { if (!isFinishing) finishGuidance() }
        }
    }


    // v1.0.94: 별도 Activity라 MapActivity 좌측 HUD가 구조적으로 안 비치는 문제를,
    // MapActivity와 동일한 앱 전역 싱글턴(OpenpilotStateRepository/SdiDataRepository)을
    // 그대로 관찰해서 자체 미니 HUD로 복제하는 방식으로 해결. #문제시 원복
    private val hudPollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    // v19.3.30: 재억 제보 - "화면을 축소해서 전체 경로를 보면 회전 화살표가 제대로 나오는데,
    // 평소(확대) 상태에선 잘린 것처럼 얇게 나온다"는 관찰. logNaviViewDiagnostics는 기존엔
    // 길안내 시작 시점에만 찍혀서, 이렇게 나중에(주행 중 줌 상태 바뀔 때) 재현되는 경우를
    // 못 잡았음. 아래 sdiRunnable(1초 주기)에 얹어서 15초마다 한 번씩 이 진단을 같이
    // 찍음 - 카카오 SDK가 그리는 회전 박스(KNComponentCurDirectionView 등)의 실제
    // 가로/세로 픽셀 크기가 그대로 로그에 남아서, 다음에 짤린 순간의 스크린샷+로그를
    // 같이 받으면 "박스 자체가 비정상적으로 큰 건지 vs 아이콘 모양만 저런 건지" 바로 구분 가능. #문제시 원복
    private var naviViewDiagnosticTick = 0
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
                // v: 재억 제보(2026-08-30, "경유지 지나갔는데 취소 버튼이 그대로 남아있다") -
                // 경유지가 추가돼 있는 상태에서, 카카오가 "이제 최종목적지로 향하고 있다"
                // (경유지를 이미 지남)고 알려주면 자동으로 취소 버튼을 숨기고 상태를 정리.
                // 명시적으로 "경유지 취소"를 눌렀을 때와 똑같이 처리하되, 경로 재계산은
                // 필요 없음(이미 지나갔으니 그대로 진행). #문제시 원복
                // v: 재억 요청(2026-09-02) - 경유지 여러 개 지원. 전부 지난 경우(최종목적지로
                // 향함)뿐 아니라 "앞의 몇 개만 지난" 경우도 처리해야 해서, 델리게이트가
                // 계산해둔 passedViaCount만큼 목록 앞에서 지움. #문제시 원복
                // v: 재억 요청(2026-09-06, 실기기 로그로 확인) - 위 headingToFinalDestination/
                // passedViaCount는 카카오의 getLocationsOfPois()가 계속 pois.size=1을 돌려주는
                // 버그(경유지가 실제로 있는데도 "없다"고 판단)에 의존하고 있어서 계속 안 됐음.
                // "티맵처럼 일정 범위에 들어가면 경유지 완료 처리해달라"는 요청대로, 카카오 API에
                // 의존하지 않고 저희가 이미 갖고 있는 경유지 좌표 + 현재 GPS 위치의 거리로 직접
                // 판정하도록 교체(50m 이내면 통과로 간주). #문제시 원복
                if (activeWaypoints.isNotEmpty()) {
                    val (curLat, curLon) = resolveCurrentWgs84LatLonForSearch()
                    if (curLat != null && curLon != null) {
                        val next = activeWaypoints.first()
                        val distToNext = distanceMeters(curLat, curLon, next.lat, next.lon)
                        if (distToNext < WAYPOINT_ARRIVAL_RADIUS_M) {
                            activeWaypoints.removeAt(0)
                            syncWaypointsToIntent()
                            NavLogger.d(this@NaverNaviActivity, "[경유지] 거리기반 통과 감지(${distToNext.toInt()}m): '${next.name}' 목록에서 제거 (남은 ${activeWaypoints.size}개)")
                            if (activeWaypoints.isEmpty()) {
                                binding.btnCancelWaypoint?.visibility = View.GONE
                            }
                            removePassedViaFromKakaoTrip(next.name)
                        }
                    }
                }
                // v: 재억 제보(2026-09-02) - 카카오 메뉴 음량과의 동기화. 이 1초 루프는
                // 진단 로그용으로만 남기고, 실제 동기화는 아래 250ms 루프가 담당함. #문제시 원복
                logKakaoVolumeDiagnostics()
                hudPollHandler.postDelayed(this, 1000)
                renderLaneSignalBar(this@NaverNaviActivity, binding.llLaneSignalBar, binding.llLaneBoxes, binding.tvTrafficLightCountdown, "kakao")
                renderAlertBanners(this@NaverNaviActivity, binding.llAccidentAlert, binding.tvAccidentAlert, binding.llEmergencyAlert, binding.tvEmergencyAlert)
                updateNavNotification()
            }
        }
        hudPollHandler.postDelayed(sdiRunnable, 1000)

        // v: 재억 요청(2026-09-02) - "카카오 길안내 화면에서 카카오 자체 음량이랑 실시간
        // 동기화도 가능한가?" -> 가능. 1초 주기 진단 루프에 얹어두면 최대 1초까지 늦어서
        // "실시간"으로 안 느껴지므로, 카카오 쪽 음량 값만 따로 250ms마다 확인해서 바뀐 게
        // 보이면 즉시 앱 저장값에 반영하고 화면에도 표시함. 값을 읽기만 하는 가벼운 작업이라
        // 부담이 거의 없음. #문제시 원복
        val kakaoVolumeSyncRunnable = object : Runnable {
            override fun run() {
                syncGuideVolumeFromKakaoNow()
                hudPollHandler.postDelayed(this, 250)
            }
        }
        hudPollHandler.postDelayed(kakaoVolumeSyncRunnable, 250)

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
                "네이버 길안내",
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
            val mainText = kr.tbtMainText.ifEmpty { kr.roadName.ifEmpty { "네이버 안내" } }

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
        binding.ivKakaoMuteToggleIcon?.setImageResource(
            if (kakaoMuted) android.R.drawable.ic_lock_silent_mode else android.R.drawable.ic_lock_silent_mode_off
        )
        binding.btnKakaoMuteToggle?.setBackgroundResource(
            if (kakaoMuted) R.drawable.shape_rounded_gray else R.drawable.shape_rounded_blue
        )
        binding.tvKakaoMuteToggleLabel?.text = if (kakaoMuted) "무음" else "켜짐"
    }

    private fun setupHudActionButtons() {
        updateMuteButtonStyle()

        // v3.0: Tmap 화면과 동일한 ≡ 메뉴 - 이벤트상세/신호등/최근검색 등을 열고 닫음
        binding.btnMoreMenu?.setOnClickListener { anchorView ->
            NavLogger.d(this, "[더보기메뉴] 버튼 클릭됨(카카오화면)")
            val panel = binding.svSecondaryPanel ?: return@setOnClickListener
            // v19.3.79: 재억 요청 - 메뉴도 다른 팝업들과 같은 카드 형식으로. 기존 세로 목록
            // 패널은 숨겨둔 채 그 안의 버튼들을 읽어서 카드로 보여주고, 누르면 원래 버튼의
            // 클릭을 대신 실행함. #문제시 원복
            panel.visibility = View.GONE
            PopupCard.showMenuFromPanel(this, binding.root as ViewGroup, panel as ViewGroup)
        }

        // v3.8: 티맵 화면과 동일한 동작 - 업데이트확인/도움말은 화면과 무관한 앱 전체
        // 기능이라 그대로 재사용, 앱종료는 이 화면부터 전체 태스크 종료. #문제시 원복
        binding.btnCheckUpdate?.setOnClickListener {
            NavLogger.d(this, "[업데이트확인] 버튼 클릭됨(카카오화면)")
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
            android.app.AlertDialog.Builder(this, R.style.RoundedDialogTheme)
                .setTitle("로그 전체 삭제")
                .setMessage("저장된 로그 파일을 전부 삭제할까요? 되돌릴 수 없습니다.")
                .setPositiveButton("삭제") { _, _ ->
                    NavLogger.deleteAllLogFiles(this)
                    Toast.makeText(this, "로그를 전부 삭제했습니다.", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("취소", null)
                .show()
                .let { PanelDragHelper.tintDestructivePositiveButton(it) }
        }
        binding.btnExitApp?.setOnClickListener {
            // v4.23: MapActivity.onDestroy()가 더 이상 자동으로 서비스를 안 멈추게 바꿔서
            // (카카오 화면 중 OS가 MapActivity만 강제로 destroy하는 경우에도 UDP 서비스가
            // 안 죽게 하려고), 카카오 화면의 "앱 종료"에서도 명시적으로 멈춰줘야
            // finishAffinity() 후에도 서비스가 고아 상태로 계속 도는 걸 방지함. #문제시 원복
            // v: 재억 제보(2026-09-23) - "앱 종료"를 눌러도 백그라운드에 프로세스가 살아있음
            // (finishAffinity()는 화면만 닫지 프로세스는 안 죽임). 명시적으로 프로세스를
            // 죽여서 확실히 종료되게 함. 단, 알림 접근 권한이 켜져 있으면 그 서비스는 시스템이
            // 자체적으로 다시 띄울 수 있음(설정에서 권한을 꺼야 완전히 막힘 - 앱 코드로는
            // 제어 불가). #문제시 원복
            stopService(Intent(this, UdpSenderService::class.java))
            finishAffinity()
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        // v: 재억 요청(2026-09-20) - "UI 편집" 모드 삭제(Tmap 화면과 동일). 상단바를 꾹 눌러 끌어서 옮김. #문제시 원복
        binding.btnEditPanelPosition?.visibility = View.GONE
        binding.llLeftHudPanel?.let { panel ->
            topBarDrag = PanelDragHelper.TopBarLongPressDrag(this, panel, "llLeftHudPanel",
                resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                applyMapOffsetForBarPosition()
            }
        }
        binding.btnParkedLocation?.setOnClickListener {
            binding.svSecondaryPanel?.visibility = View.GONE
            ParkedLocationPopup.show(this) { lat, lon, at -> startParkedCarView(lat, lon, at) }
        }

        binding.btnEditKey?.setOnClickListener {
            binding.svSecondaryPanel?.visibility = View.GONE
            // v: 재억 제보(2026-08-26) - 경유지/카테고리 버튼 표시를 꺼도 화면에서 바로
            // 안 사라지던 문제. onSaved 콜백을 안 넘겨줘서 저장 즉시 반영이 안 되고
            // 다음 onResume(다른 화면 갔다 오기)에야 적용됐음. 저장 직후 바로 반영. #문제시 원복
            PanelDragHelper.showAppSettingsDialog(
                this, null,
                onRestoreRequested = { restoreBackupLauncher.launch("application/json") },
                onDayNightChanged = { applyKakaoDayNight() }
            ) {
                applyKakaoDayNight()
                applyNaverSatellite()
                val showWaypointButton = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                    .getBoolean("show_waypoint_button", true)
                val showCategoryButton = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                    .getBoolean("show_category_button", true)
                val showCancelWaypointButton = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                    .getBoolean("show_cancel_waypoint_button", true)
                // v: 재억 제보(2026-08-26) - 최신버전에서도 여전히 안 사라진다는 지적 -
                // 콜백이 실제로 실행되는지, 읽은 값이 뭔지 확실히 확인하기 위한 로그. #문제시 원복
                NavLogger.d(this, "[버튼표시설정] 저장직후 적용: 경유지=$showWaypointButton 카테고리=$showCategoryButton 경유지취소=$showCancelWaypointButton")
                binding.btnAddWaypoint?.visibility = if (showWaypointButton) View.VISIBLE else View.GONE
                binding.btnNearbyCategory?.visibility = if (showCategoryButton) View.VISIBLE else View.GONE
                binding.btnFavorites?.visibility = if (getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                        .getBoolean("show_favorites_button", true)) View.VISIBLE else View.GONE
                syncMenuButtonDetach()
                binding.btnCancelWaypoint?.visibility = if (showCancelWaypointButton && activeWaypoints.isNotEmpty()) View.VISIBLE else View.GONE
                // v19.3.44: 재억 요청 - 상단바 표시/숨김 플로팅 버튼은 기본 안 보이고, 설정에서
                // 켰을 때만 보이게. 다른 설정들처럼 저장 즉시 반영. #문제시 원복
                binding.btnToggleTopPanel?.visibility = if (getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                        .getBoolean("show_toggle_top_panel_button", false)) View.VISIBLE else View.GONE
                binding.flMiniPlayerContainer?.let { outer ->
                    com.tmap.nda.miniplayer.MiniPlayerManager.refresh(
                        this, outer,
                        binding.ivMiniPlayerArt, binding.tvMiniPlayerTitle, binding.tvMiniPlayerArtist,
                        binding.btnMiniPlayerPlayPause
                    )
                }
                // v: 재억 제보(2026-08-26) - 경유지취소 버튼이 저장한 위치에 안 있고 계속
                // 움직이던 문제 - 처음엔 GONE 상태라 크기가 0이라 위치복원이 제대로 안 됐고,
                // 그 뒤 VISIBLE로 바뀔 때마다 저장된 위치를 다시 안 불러서 기본위치로 돌아갔음.
                // VISIBLE 되는 시점마다 위치를 다시 복원. #문제시 원복
                if (showCancelWaypointButton && activeWaypoints.isNotEmpty()) {
                    binding.btnCancelWaypoint?.post {
                        QuickIconGrid.restore(this, binding.btnCancelWaypoint!!)
                    }
                }
            }
        }

        binding.btnKakaoMuteToggle?.setOnClickListener {
            kakaoMuted = !kakaoMuted
            NaverNavigator.muted = kakaoMuted
            getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).edit()
                .putBoolean("kakao_muted", kakaoMuted).apply()
            updateMuteButtonStyle()
            Toast.makeText(this, if (kakaoMuted) "안내음성 음소거" else "안내음성 켜짐", Toast.LENGTH_SHORT).show()
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

        // v11.9: MapActivity와 동일 - 집/회사를 상단바 고정 버튼으로 뺌(재억 요청). #문제시 원복
        wireTopBarQuickSlotButton(binding.btnHomeQuickSlot, QuickSlotStore.SLOT_HOME)
        wireTopBarQuickSlotButton(binding.btnWorkQuickSlot, QuickSlotStore.SLOT_WORK)
        // v: 재억 요청(2026-09-20) - 집/회사 칸 글자를 저장한 이름으로 표시. #문제시 원복
        topBarSlotLabelListener = QuickSlotStore.watchTopBarLabels(
            this, binding.root.findViewById(R.id.tvHomeSlotLabel), binding.root.findViewById(R.id.tvWorkSlotLabel)
        )

        renderRecentDestinationsPanel()
    }

    // v12.3: MapActivity와 동일 - 등록된 칸을 길게 누르면 바로 재검색하지 않고
    // "다시 검색 / 이름 변경 / 삭제 / 취소" 선택창을 먼저 보여줌. #문제시 원복
    private fun showQuickSlotLongPressMenu(slot: String) {
        val existing = QuickSlotStore.get(this, slot)
        if (existing == null) {
            pendingQuickSlotRegistration = slot
            showInPlaceSearchDialog()
            return
        }
        android.app.AlertDialog.Builder(this, R.style.RoundedDialogTheme)
            .setTitle(existing.name)
            // v14.10: MapActivity와 동일 - 순서를 "다시 검색 -> 이름 변경 -> 경로 방식 변경
            // -> 삭제 -> 취소"로 재변경, "안내 방법 변경"을 "경로 방식 변경"으로 이름도 변경(재억 요청). #문제시 원복
            // v: 재억 요청(2026-08-22) - "경로추가"(지금 안내 중인 목적지는 그대로 두고
            // 이 즐겨찾기를 경유지로 끼워넣기) 항목 추가. #문제시 원복
            .setItems(arrayOf("다시 검색", "이름 변경", "경로 방식 변경", "경로추가", "삭제", "취소")) { _, which ->
                when (which) {
                    0 -> {
                        pendingQuickSlotRegistration = slot
                        showInPlaceSearchDialog()
                    }
                    1 -> {
                        val input = android.widget.EditText(this).apply {
                            setText(existing.name)
                            setTextColor(android.graphics.Color.WHITE)
                        }
                        android.app.AlertDialog.Builder(this, R.style.RoundedDialogTheme)
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
                    // v13.2-3: MapActivity와 동일 - 저장해둔 이동 방식만 바꾸고 싶을 때(재억 요청).
                    // v13.10부터 저장만 되고 바로 안내를 시작하지는 않음. #문제시 원복
                    2 -> showRoutePriorityDialog(existing, saveToSlot = slot)
                    3 -> addWaypointToActiveGuidance(existing)
                    4 -> QuickSlotStore.delete(this, slot)
                }
            }
            .show()
    }

    // v: 재억 제보(2026-09-02) - "안내 중에 즐겨찾기(집/회사/하트)를 누르면 바로 안내할지
    // 경유지로 추가할지 물어봐야 하는데 곧바로 목적지를 갈아치운다". 짧게 누르는 경로가
    // 화면마다 여러 군데 흩어져 있어서, "등록된 칸을 눌렀을 때"의 처리를 이 함수 하나로
    // 모음. 이미 카테고리 검색 결과 선택에 쓰던 확인창(경유지 추가 / 새 목적지)과 같은
    // 방식이고, 안내 중이 아닐 땐 예전과 완전히 동일하게 바로 안내를 시작함. #문제시 원복
    private fun handleQuickSlotTap(existing: HistoryEntry) {
        if (isGuidanceRunningNow()) {
            PopupCard.showChoice(
                this, binding.root as ViewGroup, "경유지 추가", "경유지로 추가할까요?",
                "'${existing.name}'을(를) 지금 안내(${currentDestName})의 경유지로 추가할까요, 아니면 새 목적지로 바꿀까요?",
                listOf(
                    PopupCard.Option("경유지 추가", true) { addWaypointToActiveGuidance(existing) },
                    PopupCard.Option("새 목적지로") { startGuidanceToQuickSlot(existing) }
                )
            )
        } else {
            startGuidanceToQuickSlot(existing)
        }
    }

    /** 즐겨찾기 칸의 장소로 새 안내 시작(예전 동작 그대로). #문제시 원복 */
    private fun startGuidanceToQuickSlot(existing: HistoryEntry) {
        // v13.5: 재억 지적 - 저장 안 됐으면 매번 물어보되 그 선택은 이번 한 번만,
        // 자동 저장 안 함. #문제시 원복
        if (existing.routePriorityName == null) {
            showRoutePriorityDialog(existing)
        } else {
            val saved = try {
                KNRoutePriority.valueOf(existing.routePriorityName)
            } catch (e: Exception) {
                KNRoutePriority.KNRoutePriority_Recommand
            }
            applyRouteOption(saved, existing.routeAvoidOption)
            KakaoRouteDataRepository.reset()
            activeWaypoints.clear()
            syncWaypointsToIntent()
            resolveCurrentPositionThenRequestRoute(existing.name, existing.lat, existing.lon, finishOnFailure = false)
        }
    }

    private fun wireTopBarQuickSlotButton(button: View?, slot: String) {
        button?.setOnClickListener {
            val existing = QuickSlotStore.get(this, slot)
            if (existing != null) {
                handleQuickSlotTap(existing)
            } else {
                pendingQuickSlotRegistration = slot
                showInPlaceSearchDialog()
            }
        }
        button?.setOnLongClickListener {
            showQuickSlotLongPressMenu(slot)
            true
        }
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
            // v: 재억 요청(2026-08-29) - "최근검색 더보기" 큰 목록에는 이미 있던 "경로추가"
            // (지금 안내 중인 목적지는 그대로 두고 경유지로 끼워넣기) 버튼이, 화면에 상시
            // 떠있는 이 작은 5줄짜리 패널에는 없었음. 동일한 스타일로 같이 추가. 안내 중이
            // 아니면(currentDestName 비어있음) 끼워넣을 대상 자체가 없으니 버튼을 숨김. #문제시 원복
            val rowContainer = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val tv = android.widget.TextView(this).apply {
                setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
                text = entry.name
                setTextColor(android.graphics.Color.parseColor("#DDDDDD"))
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(24, 20, 24, 20)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                setOnClickListener {
                    // v2.5: 재검색이 아니라 저장된 좌표로 바로 길안내 시작
                    // v4.17: 최근목적지 패널에서 "이미 있는" 항목을 다시 탭했을 때도
                    // save()를 안 불러서 순서가 안 바뀌던 문제(사용자 요청: 최신순 정렬) -
                    // 다시 탭해도 맨 위로 올라오게 재저장. #문제시 원복
                    SearchHistoryStore.save(this@NaverNaviActivity, entry)
                    renderRecentDestinationsPanel()
                    // v: 재억 재지적(2026-08-28) - 길안내 화면 안의 "최근 목적지" 상시 패널도
                    // MapActivity의 동일 패널과 똑같이 팝업을 안 거치고 있었음. #문제시 원복
                    showRoutePriorityDialog(entry)
                }
            }
            rowContainer.addView(tv)
            if (isGuidanceRunningNow()) {
                val addBtn = android.widget.TextView(this).apply {
                    setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
                    text = "추가"
                    textSize = 12f
                    setTextColor(android.graphics.Color.parseColor("#A0E8B0"))
                    setBackgroundResource(R.drawable.bg_chip_addroute_rounded)
                    setPadding(20, 10, 20, 10)
                    val marginParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    marginParams.marginStart = 12
                    marginParams.marginEnd = 12
                    layoutParams = marginParams
                    setOnClickListener {
                        addWaypointToActiveGuidance(entry)
                    }
                }
                rowContainer.addView(addBtn)
            }
            rowsContainer.addView(rowContainer)
        }
        binding.btnMoreHistory?.setOnClickListener {
            // v1.8: 여기서 finishGuidance()를 불러서 MapActivity로 돌아가 다이얼로그를 띄웠는데,
            // 그러면 진행 중이던 카카오 안내 자체가 끝나버림(사용자 지적 2번: "더보기 누르면
            // 안내 중 뒤로 나옴"). 안내를 끊지 않고 이 화면 안에서 그대로 전체 이력을 보여줌. #문제시 원복
            showFullSearchHistoryDialog()
        }
    }

    // v: 재억 요청(2026-08-22) - 티맵 화면(MapActivity)에만 있던 "즐겨찾기 저장" 기능을
    // 카카오 화면에도 이식. 티맵 쪽처럼 카드+그리드로 꾸미는 대신, 목록형으로 간단히
    // 구현(같은 QuickSlotStore를 공유하므로 저장 결과는 동일). #문제시 원복
    private fun showKakaoQuickSlotPickerForSave(entry: HistoryEntry) {
        // v: 재억 요청(2026-09-02) - 즐겨찾기 10칸까지 동적 생성. #문제시 원복
        val favoriteCount = QuickSlotStore.favoriteCount(this)
        val slotLabels = mutableListOf("집" to QuickSlotStore.SLOT_HOME, "회사" to QuickSlotStore.SLOT_WORK)
        val favoriteSlots = QuickSlotStore.favoriteSlots(favoriteCount)
            .mapIndexed { index, slot -> "즐겨찾기 ${index + 1}" to slot }
        slotLabels.addAll(favoriteSlots)

        val items = slotLabels.map { (label, slot) ->
            val existing = QuickSlotStore.get(this, slot)
            if (existing != null) "$label (현재: ${existing.name})" else "$label (비어있음)"
        }.toTypedArray()

        android.app.AlertDialog.Builder(this, R.style.RoundedDialogTheme)
            .setTitle("어디에 저장할까요? - ${entry.name}")
            .setItems(items) { _, which ->
                val (_, slot) = slotLabels[which]
                QuickSlotStore.save(this, slot, entry)
                Toast.makeText(this, "'${entry.name}' 저장됨", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showFullSearchHistoryDialog() {
        var history = SearchHistoryStore.get(this)
        if (history.isEmpty()) {
            Toast.makeText(this, "최근 목적지가 없습니다", Toast.LENGTH_SHORT).show()
            return
        }

        lateinit var dialog: PopupCard.CardDialog
        lateinit var listView: android.widget.ListView
        // v13.9: MapActivity와 동일 - 시간 계산으로 줄 높이가 바뀌면서 목록이 다시
        // 그려지고, 다시 그려질 때마다 계산을 새로 시작하는 게 끝없이 반복되던 문제
        // (재억 지적, 영상으로 확인 - 느린 안내/느린 검색의 진짜 원인). 계산 결과를
        // 저장해뒀다가 재사용해서 반복을 끊음. #문제시 원복
        // v14.1: MapActivity와 동일 - 캐시를 "몇 번째 줄인지"(position)로 저장하면, 검색을
        // 새로 해서 목록 순서가 바뀔 때 엉뚱한 목적지의 값이 잘못 표시됨(재억 지적, 스샷으로
        // 확인 - "노블워시가 2시간 48분이었다가 22분으로 바뀜"). 순번이 아니라 목적지
        // 좌표를 키로 써서, 순서가 바뀌어도 항상 그 목적지 고유의 값만 표시되게 함. #문제시 원복
        fun etaCacheKey(entry: HistoryEntry) = "${entry.lat},${entry.lon}"
        val etaResultCache = HashMap<String, String?>()
        // v13.10: MapActivity와 동일 - 목록이 처음 뜰 때 보이는 줄 전부가 동시에 계산
        // 요청을 던지던 문제(재억 지적, 영상+로그로 재확인). 동시 최대 2개까지만 나가도록
        // 대기열을 둠. #문제시 원복
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
                val (curLat, curLon) = resolveCurrentWgs84LatLonForSearch()
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
                timeoutHandler.postDelayed(timeoutRunnable, 8000L)
                NaverEta.computeEta(this, curLat, curLon, entry.lat, entry.lon) { minutes, _ ->
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
                val row = android.widget.LinearLayout(this@NaverNaviActivity).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    // v19.3.61: Tmap 화면과 동일 - 줄마다 따로 불투명 배경 씌우던 것 투명으로. #문제시 원복
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setPadding(24, 20, 16, 20)
                }
                val nameText = android.widget.TextView(this@NaverNaviActivity).apply {
                    setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
                    text = if (entry.addr.isNotBlank()) "${entry.name}\n${entry.addr}" else entry.name
                    setTextColor(android.graphics.Color.WHITE)
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                }
                // v13.8: MapActivity와 동일 - 시간이 흰색으로만 나오던 문제 수정(재억 지적). #문제시 원복
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
                // v: 재억 요청(2026-08-22) - 이 화면(카카오)에는 원래 없었던 "저장"
                // 버튼(티맵 화면 v14.4에만 있었음)을 이식하고, 새로 "경로추가"(지금 안내
                // 중인 목적지는 그대로 두고 경유지로 끼워넣기) 버튼도 같이 추가. #문제시 원복
                val saveText = android.widget.TextView(this@NaverNaviActivity).apply {
                    setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
                    text = "저장"
                    textSize = 14f
                    setTextColor(android.graphics.Color.parseColor("#A0C8F0"))
                    setBackgroundResource(R.drawable.bg_chip_save_rounded)
                    setPadding(36, 20, 36, 20)
                    val marginParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    marginParams.marginStart = 16
                    layoutParams = marginParams
                    setOnClickListener {
                        showKakaoQuickSlotPickerForSave(entry)
                    }
                }
                val addWaypointText = android.widget.TextView(this@NaverNaviActivity).apply {
                    setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
                    text = "경로추가"
                    textSize = 14f
                    setTextColor(android.graphics.Color.parseColor("#A0E8B0"))
                    setBackgroundResource(R.drawable.bg_chip_addroute_rounded)
                    setPadding(36, 20, 36, 20)
                    val marginParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    marginParams.marginStart = 16
                    layoutParams = marginParams
                    setOnClickListener {
                        dialog.dismiss()
                        addWaypointToActiveGuidance(entry)
                    }
                }
                // v10.9-5: MapActivity와 동일 - "✕" 작은 글자 대신 배경 있는 "삭제" 버튼으로
                // 바꾸고 누르는 영역도 넓힘(재억 지적). #문제시 원복
                val deleteText = android.widget.TextView(this@NaverNaviActivity).apply {
                    setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
                    text = "삭제"
                    textSize = 14f
                    setTextColor(android.graphics.Color.parseColor("#F0A0A0"))
                    setBackgroundResource(R.drawable.bg_chip_delete_rounded)
                    setPadding(36, 20, 36, 20)
                    val marginParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    marginParams.marginStart = 16
                    layoutParams = marginParams
                    setOnClickListener {
                        SearchHistoryStore.delete(this@NaverNaviActivity, entry)
                        renderRecentDestinationsPanel()
                        history = SearchHistoryStore.get(this@NaverNaviActivity)
                        if (history.isEmpty()) {
                            dialog.dismiss()
                        } else {
                            listView.adapter = buildAdapter()
                        }
                    }
                }
                PopupCard.arrangeHistoryRow(row, nameText, listOf(saveText, addWaypointText, deleteText), PopupCard.isCompact(this@NaverNaviActivity))
                return row
            }
        }

        listView = android.widget.ListView(this)
        listView.adapter = buildAdapter()
        listView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        listView.divider = android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#333333"))
        listView.dividerHeight = 1

        val titleView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_dialog_title_top_rounded)
            setPadding(24, 24, 24, 20)
            addView(android.widget.TextView(this@NaverNaviActivity).apply {
                setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
                text = "최근 목적지"
                textSize = 18f
                setTextColor(android.graphics.Color.WHITE)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })
        }

        // v19.3.79: 재억 요청 - 검색이력 창도 다른 팝업들과 같은 카드 형식으로. #문제시 원복
        dialog = PopupCard.CardDialog(this, binding.root as ViewGroup).apply {
            setCustomTitle(titleView)
            setContent(listView)
            setButton(PopupCard.CardDialog.BUTTON_POSITIVE, "전체 삭제", destructive = true) {
                android.app.AlertDialog.Builder(this@NaverNaviActivity, R.style.RoundedDialogTheme)
                    .setTitle("최근 목적지 전체 삭제")
                    .setMessage("최근 목적지를 전부 삭제할까요?")
                    .setPositiveButton("삭제") { _, _ ->
                        SearchHistoryStore.clear(this@NaverNaviActivity)
                        renderRecentDestinationsPanel()
                    }
                    .setNegativeButton("취소", null)
                    .show()
                    .let { PanelDragHelper.tintDestructivePositiveButton(it) }
            }
            setButton(PopupCard.CardDialog.BUTTON_NEGATIVE, "닫기")
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val picked = history[position]
            dialog.dismiss()
            // v2.5: 재검색이 아니라 저장된 좌표로 바로 길안내 시작
            // v4.17: 다시 탭해도 최신순 맨 위로 올라오게. #문제시 원복
            // v13.6: MapActivity와 동일 - 최근검색 항목도 경로 선택 팝업을 거치도록 함. #문제시 원복
            SearchHistoryStore.save(this, picked)
            showRoutePriorityDialog(picked)
        }
        dialog.show()
    }

    // v19.3.80: 재억 요청 - 검색이력 창 제목 옆의 좁은 즐겨찾기 줄을 떼어내서, 끌어 옮길 수
    // 있는 "즐겨찾기" 아이콘을 누르면 뜨는 카드로 분리. #문제시 원복
    private fun showFavoritesCard() {
        lateinit var dialog: PopupCard.CardDialog
        // v11.3: MapActivity와 동일 - 집/회사/즐겨찾기1/2/3 다섯 칸 빠른등록 아이콘 행. #문제시 원복
        fun buildQuickSlotButton(slot: String, emoji: String): Pair<View, android.widget.TextView> {
            val etaText = android.widget.TextView(this).apply {
                setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
                textSize = 9f
                gravity = android.view.Gravity.CENTER
                setTextColor(android.graphics.Color.parseColor("#FFD54F"))
                // v13.0-3: MapActivity와 동일 - 시간 잘리던 문제(재억 지적), 최대 2줄로
                // 자연스럽게 줄바꿈, 말줄임표 없음. #문제시 원복
                maxLines = 2
            }
            // v12.2: MapActivity와 동일 - 등록된 즐겨찾기 칸은 하트 대신 등록된 장소
            // 이름을 보여줌(재억 요청). #문제시 원복
            val registeredEntry = QuickSlotStore.get(this@NaverNaviActivity, slot)
            val iconText = android.widget.TextView(this).apply {
                setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
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
                setBackgroundResource(R.drawable.bg_quickslot_chip_rounded)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginEnd = 12 }
                setPadding(8, 20, 8, 20)
                addView(iconText)
                addView(etaText)
                setOnClickListener {
                    val existing = QuickSlotStore.get(this@NaverNaviActivity, slot)
                    dialog.dismiss()
                    if (existing != null) {
                        // v: 재억 제보(2026-09-02) - 상단바 버튼과 동일하게, 안내 중이면
                        // "경유지 추가 / 새 목적지"를 먼저 물어봄. #문제시 원복
                        handleQuickSlotTap(existing)
                    } else {
                        pendingQuickSlotRegistration = slot
                        showInPlaceSearchDialog()
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
        // v13.0-4: MapActivity와 동일 - 설정에서 정한 개수(0~5)만큼만 보여줌(재억 요청). #문제시 원복
        // v: \uC7AC\uC5B5 \uC694\uCCAD(2026-09-02) - \uCD5C\uB300 5\uAC1C \uD558\uB4DC\uCF54\uB529\uC744 10\uAC1C\uAE4C\uC9C0 \uB3D9\uC801 \uC0DD\uC131\uC73C\uB85C \uBCC0\uACBD. #\uBB38\uC81C\uC2DC \uC6D0\uBCF5
        val favoriteCount = QuickSlotStore.favoriteCount(this)
        val quickSlotButtons = QuickSlotStore.favoriteSlots(favoriteCount)
            .map { slot -> slot to buildQuickSlotButton(slot, "\u2764\uFE0F") }
        // v11.9: MapActivity와 동일 - 집/회사는 상단바로 빠져서 즐겨찾기 3칸만 남음,
        // 제목과 같은 줄 오른쪽에 고정폭으로 배치(재억 요청). #문제시 원복
        // v11.4: MapActivity와 동일 - 팝업이 뜨자마자 등록된 칸들만 조용히 카카오
        // 경로계산을 돌려서 "OO분"으로 채움(재억 요청). #문제시 원복
        // v14.1: MapActivity와 동일 - 세 군데(즐겨찾기/이력/검색결과)를 전부 "동시 최대
        // 2개" + "8초 타임아웃"으로 통일(재억 지적, 로그+스샷으로 재확인). #문제시 원복
        val quickSlotEntries = quickSlotButtons.mapNotNull { (slot, pair) ->
            val entry = QuickSlotStore.get(this, slot) ?: return@mapNotNull null
            Pair(pair.second, entry)
        }
        val (quickSlotCurLat, quickSlotCurLon) = resolveCurrentWgs84LatLonForSearch()
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
                timeoutHandler.postDelayed(timeoutRunnable, 8000L)
                NaverEta.computeEta(this, quickSlotCurLat, quickSlotCurLon, entry.lat, entry.lon) { minutes, _ ->
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
        val cols = 4
        val grid = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        quickSlotButtons.chunked(cols).forEachIndexed { r, rowItems ->
            val row = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
            for (i in 0 until cols) {
                val cell = rowItems.getOrNull(i)?.second?.first ?: android.widget.Space(this)
                cell.layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = if (i < cols - 1) PopupCard.dp(this@NaverNaviActivity, 8) else 0
                }
                row.addView(cell)
            }
            grid.addView(row, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (r > 0) topMargin = PopupCard.dp(this@NaverNaviActivity, 8) })
        }
        val scroll = android.widget.ScrollView(this).apply { addView(grid) }
        dialog = PopupCard.CardDialog(this, binding.root as ViewGroup).apply {
            setTitle("즐겨찾기")
            // 즐겨찾기 줄 수에 맞춰 카드 높이를 딱 맞게(빈 공간 제거). #문제시 원복
            setContent(scroll, maxDp = ((quickSlotButtons.size + 3) / 4).coerceAtLeast(1) * 70 + (((quickSlotButtons.size + 3) / 4).coerceAtLeast(1) - 1) * 8 + 8, reserveDp = 160)
            setButton(PopupCard.CardDialog.BUTTON_NEGATIVE, "닫기")
        }
        dialog.show()
    }

    // v1.6: 검색 버튼 누르면 화면이 티맵으로 나갔다 다시 들어오던 문제 - 굳이 MapActivity로
    // 안 돌아가고 이 화면 안에서 그대로 재검색하도록 함(Kakao 로컬 검색 API를 MapActivity와
    // 동일한 방식으로 여기서도 직접 호출). #문제시 원복
    private val searchHttpClient by lazy { OkHttpClient.Builder().addInterceptor(KakaoToTmapInterceptor(applicationContext)).build() }
    // v13.1-2: 재억 요청 - 검색결과에서 고른 경로 우선순위/회피옵션을 실제 안내 시작
    // 시점(guideNewDestinations)까지 들고 있기 위한 클래스 필드. #문제시 원복
    private var activeRoutePriority: KNRoutePriority = KNRoutePriority.KNRoutePriority_Recommand
    private var activeRouteAvoidOption: Int = 0

    // v: 재억 제보(2026-09-04) - "무료도로로 가던 안내가 추천 경로로 바뀐다". 지금 고른
    // 경로 방식은 위 두 변수에만 들고 있었는데, 이 값은 화면이 다시 만들어지면 같이
    // 사라짐. 그리고 onCreate는 매번 "이 화면을 처음 열 때 넘겨받은 값"(인텐트)에서
    // 경로 방식을 다시 읽기 때문에, 안내 도중에 무료도로로 바꿔놨어도 화면이 다시
    // 만들어지는 순간 처음 값(보통 추천)으로 되돌아갔음. 방식을 바꿀 때마다 인텐트에도
    // 같이 적어둬서, 화면이 다시 만들어져도 고른 방식 그대로 이어가게 함. #문제시 원복
    private fun applyRouteOption(priority: KNRoutePriority, avoidOption: Int) {
        activeRoutePriority = priority
        activeRouteAvoidOption = avoidOption
        intent.putExtra("route_priority_name", priority.name)
        intent.putExtra("route_avoid_option", avoidOption)
    }
    // v: 재억 요청(2026-08-22) - 안내 중 경유지 추가 기능. 현재 목적지 좌표/이름을
    // guideNewDestinations() 호출 시 그대로 재사용해야 해서 클래스 필드로 보관. #문제시 원복
    private var currentDestName: String = ""
    // v: 신규기능(주변검색 진행/역방향 표시) - Tmap 화면과 동일. #문제시 원복
    private var lastKnownBearing: Float? = null
    // v: 재억 요청(2026-09-06) - 경유지 통과 판정을 카카오 API가 아닌 거리 기반으로
    // 바꾸면서 필요해진 값. 티맵도 목적지 도착을 반경 기준으로 판단하는 것과 동일한
    // 방식(재억님 확인). 50m로 잡음 - 너무 넓으면 아직 안 도착했는데 통과 처리될 수
    // 있고, 너무 좁으면 GPS 오차로 안 잡힐 수 있어 절충. #문제시 원복
    private val WAYPOINT_ARRIVAL_RADIUS_M = 50.0

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // 지구 반지름(m)
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    // v: 신규기능(경유지 취소 버튼) - 경유지가 추가돼 있는지, 그 경유지 정보가 뭔지 기억.
    // 취소 버튼은 이 목록이 비어있지 않을 때만 보임.
    // v: 재억 요청(2026-09-02) - 예전엔 단일 변수(activeWaypoint)라서 경유지를 하나만
    // 들고 있었고, 두 번째 경유지를 추가하면 첫 번째가 조용히 사라졌음(makeTripWithStart에
    // 새 경유지 1개만 넘겼기 때문). 목록으로 바꿔서 추가할 때마다 기존 경유지 뒤에
    // 이어붙이고, 지나간 것만 자동으로 앞에서 지움. #문제시 원복
    private val activeWaypoints: MutableList<HistoryEntry> = mutableListOf()

    // v: 경유지 목록도 화면이 다시 만들어지면 사라지던 값 - 경로 방식과 같은 방식으로
    // 인텐트에 적어둬서(분할화면 전환 등으로) 화면이 다시 만들어져도 경유지를 그대로
    // 이어감. 목록이 바뀌는 곳마다 이 함수를 불러줌. #문제시 원복
    // 재억 요청(2026-09-30, 내 폰 시험): 경유지를 지나도 카카오 지도의 경유 표시가 남던 것을
    // 카카오 trip의 경유지 목록에서 직접 빼서 지워봄. 결과는 [경유지표시제거] 로그로 확인. #문제시 원복
    private fun removePassedViaFromKakaoTrip(name: String) {
        // 네이버 안내: 지나간 경유지는 안내 엔진의 경유지 목록에서도 빼서, 이후 재탐색 때 다시 돌아가지 않게 한다.
        NaverNavigator.dropPassedWaypoint()
        NavLogger.d(this, "[경유지] '$name' 통과 - 안내 엔진 경유지 목록에서 제거")
    }

    private fun syncWaypointsToIntent() {
        val arr = org.json.JSONArray()
        activeWaypoints.forEach {
            arr.put(
                org.json.JSONObject()
                    .put("name", it.name)
                    .put("addr", it.addr)
                    .put("lat", it.lat)
                    .put("lon", it.lon)
            )
        }
        intent.putExtra("active_waypoints_json", arr.toString())
    }

    private fun restoreWaypointsFromIntent() {
        val raw = intent.getStringExtra("active_waypoints_json") ?: return
        try {
            val arr = org.json.JSONArray(raw)
            activeWaypoints.clear()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                activeWaypoints.add(
                    HistoryEntry(o.optString("name"), o.optString("addr"), o.optDouble("lat"), o.optDouble("lon"))
                )
            }
        } catch (e: Exception) {
            NavLogger.e(this, "[경유지] 저장해둔 경유지 목록 읽기 실패: ${e.message}")
        }
    }
    private var currentDestLat: Double = Double.NaN
    private var currentDestLon: Double = Double.NaN
    // v11.3: MapActivity와 동일 - 집/회사/즐겨찾기 칸 등록용 검색을 여는 중이면 어느 칸인지 담아둠. #문제시 원복
    private var pendingQuickSlotRegistration: String? = null

    // v: 재억 요청(2026-08-22) - 안내 중 경유지 추가. 검색 결과 선택 시 목적지를
    // 갈아끼우는(resolveCurrentPositionThenRequestRoute) 대신, 기존 목적지는 그대로 두고
    // "현재위치 -> 경유지 -> 기존목적지" 순서로 경로를 다시 짜서 guideNewDestinations()로
    // 갈아끼움. 기존 "재탐색" 메커니즘을 그대로 재사용하는 거라 안전함. #문제시 원복
    private var pendingWaypointAddition: Boolean = false

    private fun setupWaypointAddButton() {
        binding.btnAddWaypoint?.setOnClickListener {
            pendingWaypointAddition = true
            showWaypointSearchModeChooser()
        }
        binding.btnCancelWaypoint?.setOnClickListener {
            // v: 재억 요청(2026-09-02) - 경유지가 여러 개일 수 있으므로, 하나만 있을 땐
            // 예전처럼 바로 물어보고, 여러 개면 어느 걸 뺄지(또는 전부 뺄지) 고르게 함. #문제시 원복
            when {
                activeWaypoints.isEmpty() -> {
                    Toast.makeText(this, "등록된 경유지가 없습니다", Toast.LENGTH_SHORT).show()
                }
                activeWaypoints.size == 1 -> {
                    android.app.AlertDialog.Builder(this, R.style.RoundedDialogTheme)
                        .setTitle("경유지 취소")
                        .setMessage("'${activeWaypoints[0].name}'를 경로에서 뺄까요?")
                        .setPositiveButton("취소하기") { _, _ -> rebuildRouteWithWaypoints(emptyList(), "경유지취소") }
                        .setNegativeButton("아니요", null)
                        .show()
                }
                else -> {
                    val labels = activeWaypoints.mapIndexed { i, w -> "${i + 1}. ${w.name} 빼기" } + "경유지 전부 빼기"
                    android.app.AlertDialog.Builder(this, R.style.RoundedDialogTheme)
                        .setTitle("경유지 취소 (${activeWaypoints.size}개)")
                        .setItems(labels.toTypedArray()) { _, which ->
                            if (which == activeWaypoints.size) {
                                rebuildRouteWithWaypoints(emptyList(), "경유지취소")
                            } else {
                                val remaining = activeWaypoints.filterIndexed { i, _ -> i != which }
                                rebuildRouteWithWaypoints(remaining, "경유지취소")
                            }
                        }
                        .setNegativeButton("아니요", null)
                        .show()
                }
            }
        }
    }

    // v: 신규기능(주변 카테고리 검색) - 경유지 버튼 옆 카테고리 버튼. NearbyCategoryPopup
    // 공용 헬퍼를 그대로 씀. 안내 중일 때는 경유지 추가/목적지 교체 중 고르게 확인창을
    // 띄움(재억 요청) - 이미 있는 addWaypointToActiveGuidance()를 그대로 재사용. #문제시 원복
    private fun setupNearbyCategoryButton() {
        binding.btnNearbyCategory?.setOnClickListener {
            val restKey = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                .getString("kakao_rest_api_key", null) ?: return@setOnClickListener
            val (curLat, curLon) = resolveCurrentWgs84LatLonForSearch()
            if (curLat == null || curLon == null) {
                Toast.makeText(this, "현재 위치를 확인할 수 없습니다", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // v: 재억 제보(2026-08-26) - 안내 중일 때 진행/역방향 표시가 안 뜬다는 지적 -
            // lastKnownBearing이 실제로 채워지는지 확인용 로그. #문제시 원복
            NavLogger.d(this, "[주변카테고리검색] 카카오화면 열림 - lastKnownBearing=$lastKnownBearing")
            // v: 재억 재지적(2026-08-28) - 카테고리(주변) 검색 결과를 골랐을 때도 팝업 없이
            // 곧바로 안내가 시작되고 있었음. #문제시 원복
            NearbyCategoryPopup.show(this, searchHttpClient, restKey, curLat, curLon, lastKnownBearing) { picked ->
                if (isGuidanceRunningNow()) {
                    PopupCard.showChoice(
                        this, binding.root as ViewGroup, "경유지 추가", "경유지로 추가할까요?",
                        "'${picked.name}'을(를) 지금 안내(${currentDestName})의 경유지로 추가할까요, 아니면 새 목적지로 바꿀까요?",
                        listOf(
                            PopupCard.Option("경유지 추가", true) { addWaypointToActiveGuidance(picked) },
                            PopupCard.Option("새 목적지로") { showRoutePriorityDialog(picked) }
                        )
                    )
                } else {
                    showRoutePriorityDialog(picked)
                }
            }
        }
    }

    // v: 재억 요청(2026-08-22) - 경유지 추가할 때 텍스트 입력창부터 뜨지 말고, "음성으로
    // 찾기 / 텍스트로 찾기" 중 먼저 고르게 함. 운전 중엔 음성이 더 편해서 그쪽을 앞에 둠. #문제시 원복
    private fun showWaypointSearchModeChooser() {
        // v: 재억 요청(2026-08-29) - 경유지 검색이 음성/텍스트 두 가지뿐이라, 최근에 갔던
        // 곳을 경유지로 넣고 싶어도 매번 다시 검색해야 했음. "최근 검색" 옵션을 추가해서
        // 기존 "최근검색 더보기" 목록으로 바로 이동 - 그 목록엔 줄마다 이미 "경로추가"
        // 버튼이 따로 있어서(addWaypointToActiveGuidance 직접 호출), 그걸 누르면 확실하게
        // 경유지로 들어감. #문제시 원복
        val history = SearchHistoryStore.get(this)
        val items = if (history.isNotEmpty()) {
            arrayOf("음성으로 찾기", "텍스트로 찾기", "최근 검색에서 찾기")
        } else {
            arrayOf("음성으로 찾기", "텍스트로 찾기")
        }
        // v19.3.79: 재억 요청 - 목록형 AlertDialog 대신, 목적지 정보 팝업과 같은 반투명 카드
        // 스타일(같은 위치·드래그 이동·위치 자동저장 공유)로 바꿈. 바깥을 눌러 닫는 개념이
        // 없어져서, 어떤 방식으로 닫히든 취소 버튼 하나로 pendingWaypointAddition을 정리함. #문제시 원복
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val root = binding.root as ViewGroup
        activeChoiceCard?.let { root.removeView(it) }
        activeChoiceScrim?.let { root.removeView(it) }

        // v19.3.79: 재억 요청 - 카드 바깥 아무 곳이나 눌러도 닫히게(취소와 동일). 카드 뒤에
        // 화면 전체를 덮는 투명한 뷰를 깔아서 바깥 터치를 받음. #문제시 원복
        val scrim = View(this).apply { isClickable = true }
        root.addView(scrim, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ))
        activeChoiceScrim = scrim

        val card = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#B328282C"))
                cornerRadius = dp(20).toFloat()
            }
            setPadding(dp(20), dp(18), dp(20), dp(16))
        }
        fun closeCard() {
            root.removeView(card)
            root.removeView(scrim)
            if (activeChoiceCard === card) activeChoiceCard = null
            if (activeChoiceScrim === scrim) activeChoiceScrim = null
        }
        scrim.setOnClickListener {
            closeCard()
            pendingWaypointAddition = false
        }
        card.addView(android.widget.TextView(this).apply {
            text = "경유지 추가"
            setTextColor(AppAccent.color(this@NaverNaviActivity))
            textSize = 12f
            setPadding(0, 0, 0, dp(4))
        })
        card.addView(android.widget.TextView(this).apply {
            text = "경유지 검색 방법"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 17f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(14))
        })
        items.forEachIndexed { which, label ->
            card.addView(android.widget.TextView(this).apply {
                text = label
                gravity = android.view.Gravity.CENTER
                textSize = 14f
                setPadding(0, dp(13), 0, dp(13))
                if (which == 0) {
                    setTextColor(android.graphics.Color.parseColor("#212121"))
                    setTypeface(null, android.graphics.Typeface.BOLD)
                } else {
                    setTextColor(android.graphics.Color.parseColor("#DDDDDD"))
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor(if (which == 0) "#03C75A" else "#1AFFFFFF"))
                    cornerRadius = dp(12).toFloat()
                }
                isClickable = true
                setOnClickListener {
                    closeCard()
                    when (which) {
                        0 -> startVoiceSearch()
                        1 -> showInPlaceSearchDialog()
                        2 -> {
                            // v: 재억 요청(2026-08-29) - 이 경로(최근검색 더보기)는 pickEntry를
                            // 안 거치고 목록의 전용 "경로추가" 버튼으로 바로 처리되니, 여기서
                            // 플래그를 안 꺼주면 나중에 무관한 음성/텍스트 검색까지 경유지
                            // 모드로 잘못 처리될 수 있음. #문제시 원복
                            pendingWaypointAddition = false
                            showFullSearchHistoryDialog()
                        }
                    }
                }
            }, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
        }
        card.addView(android.widget.TextView(this).apply {
            text = "취소"
            gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.parseColor("#DDDDDD"))
            textSize = 14f
            setPadding(0, dp(13), 0, dp(13))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#1AFFFFFF"))
                cornerRadius = dp(12).toFloat()
            }
            isClickable = true
            setOnClickListener {
                closeCard()
                pendingWaypointAddition = false
            }
        })

        val cardWidth = minOf(dp(360), (resources.displayMetrics.widthPixels * 0.42).toInt())
        val frameParams = android.widget.FrameLayout.LayoutParams(cardWidth, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            marginStart = dp(170)
            topMargin = dp(76)
        }
        root.addView(card, frameParams)
        activeChoiceCard = card
        attachPopupCardDrag(card, root)
    }

    private var activeChoiceCard: View? = null
    private var activeChoiceScrim: View? = null

    // v: 재억 요청(2026-09-02) - 경유지 여러 개 지원. 예전엔 "경유지 추가"와 "경유지 취소"가
    // 거의 똑같은 코드를 각자 들고 있으면서 via 목록만 (새 경유지 1개) / (비움)으로 달랐고,
    // 그래서 두 번째 경유지를 추가하면 첫 번째가 조용히 사라졌음. 이제 "지금 유지할 경유지
    // 목록 전체"를 받아서 한 번에 경로를 다시 짜는 함수 하나로 합침 - 추가는 기존 목록 +
    // 새 경유지, 취소는 뺄 것만 제외한 목록을 넘기면 됨. #문제시 원복
    // 경로 요청을 실제로 보냈으면 true. 위치를 아직 못 잡아 시작조차 못 한 경우 false를
    // 돌려줘서, 화면 재생성처럼 자동으로 부르는 쪽이 다른 방법으로 이어갈 수 있게 함. #문제시 원복
    private fun rebuildRouteWithWaypoints(
        waypoints: List<HistoryEntry>,
        logTag: String,
        addedName: String? = null
    ): Boolean {
        if (currentDestLat.isNaN() || currentDestLon.isNaN()) {
            NavLogger.e(this, "[$logTag] 현재 목적지 정보가 없어 취소")
            Toast.makeText(this, "$logTag 실패: 목적지 정보 없음", Toast.LENGTH_SHORT).show()
            return false
        }
        // 네이버 길찾기(Directions 5)는 경유지를 5개까지만 받는다.
        if (waypoints.size > 5) {
            Toast.makeText(this, "경유지는 최대 5개까지 넣을 수 있어요", Toast.LENGTH_SHORT).show()
            return false
        }
        val from = currentStartLonLat()
        if (from == null) {
            Toast.makeText(this, "GPS 확인 중입니다. 잠시 후 다시 시도해주세요", Toast.LENGTH_SHORT).show()
            return false
        }

        val routeDesc = (listOf("현재위치") + waypoints.map { it.name } + currentDestName).joinToString(" -> ")
        NavLogger.d(this, "[$logTag] 요청: $routeDesc (경유지 ${waypoints.size}개)")
        if (addedName != null) {
            Toast.makeText(this, "'$addedName' 경유지로 추가 중...", Toast.LENGTH_SHORT).show()
        }

        val option = naverOptionFor(activeRoutePriority, activeRouteAvoidOption)
        val vias = waypoints.map { LonLat(it.lon, it.lat) }
        val to = LonLat(currentDestLon, currentDestLat)
        Thread {
            val res = NaverDirectionsClient.requestRoute(this, from, to, vias, option)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val route = res.routes.firstOrNull()
                if (!res.ok || route == null) {
                    NavLogger.e(this, "[$logTag] 경로 재계산 실패: code=${res.code} ${res.message}")
                    Toast.makeText(this, "$logTag 실패(${res.code}): ${res.message}", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                try {
                    activeWaypoints.clear()
                    activeWaypoints.addAll(waypoints)
                    syncWaypointsToIntent()
                    startNaverGuidance(route, currentDestName, currentDestLat, currentDestLon)
                    NavLogger.d(this, "[$logTag] 성공 (경유지 ${waypoints.size}개)")
                    Toast.makeText(
                        this,
                        when {
                            addedName != null -> "'$addedName' 경유지로 추가됨 (총 ${waypoints.size}개)"
                            waypoints.isEmpty() -> "경유지가 취소됐습니다"
                            else -> "경유지가 ${waypoints.size}개 남았습니다"
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                    if (waypoints.isEmpty()) {
                        binding.btnCancelWaypoint?.visibility = View.GONE
                        return@runOnUiThread
                    }
                    // v: 재억 재제보(2026-08-30, "경유지 도착 전인데 벌써 없어졌다") -
                    // 경유지가 남아있는 이 순간 명시적으로 false로 잡아둬서, 진짜로 통과했다는 게
                    // 확인될 때까지는 취소 버튼이 안 지워지게 함. #문제시 원복
                    KakaoRouteDataRepository.headingToFinalDestination = false
                    KakaoRouteDataRepository.passedViaCount = -1
                    val showCancelBtn = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                        .getBoolean("show_cancel_waypoint_button", true)
                    binding.btnCancelWaypoint?.visibility = if (showCancelBtn) View.VISIBLE else View.GONE
                    if (showCancelBtn) {
                        binding.btnCancelWaypoint?.post {
                            QuickIconGrid.restore(this, binding.btnCancelWaypoint!!)
                        }
                    }
                } catch (e: Exception) {
                    NavLogger.e(this, "[$logTag] 안내 시작 예외: ${e.message}")
                    Toast.makeText(this, "$logTag 실패(화면 반영 오류)", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
        return true
    }

    // ---- 내 차 위치 보기(카카오 화면) ----
    // 지도에 차 위치(주황 "차")와 내 위치(파란 점)를 보여주고, GPS 버튼으로
    // [현재 위치 보기] -> [따라가기] 전환. GPS 버튼은 끌어서 옮기면 위치 저장.
    // 카카오 길안내 중에는 위치 전달을 끊어야 하는 기능이라 막음. #문제시 원복
    private var parkedActive = false
    private var parkedLaunchedFromTmap = false
    private var parkedCloseButton: android.widget.TextView? = null
    private var parkedBackCallback: androidx.activity.OnBackPressedCallback? = null
    private var parkedGpsButton: android.widget.TextView? = null
    private var parkedGpsStep = 0 // 0=차 위치 보는 중, 1=현재 위치 보는 중, 2=따라가는 중
    private val parkedHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var parkedRefresh: Runnable? = null

    // 네이버 지도는 WGS84 좌표를 그대로 쓰므로 변환이 필요 없다(카카오 때의 KATEC 변환 자리).
    private fun parkedPoint(lat: Double, lon: Double): Pair<Double, Double> = lat to lon

    // 카카오 지도 화면이 아직 준비 안 됐으면 0.3초마다 다시 시도(최대 20번)
    // 네이버 지도가 아직 준비 안 됐으면 준비되는 즉시 실행
    private fun startParkedCarViewWhenReady(lat: Double, lon: Double, savedAt: Long, tries: Int) {
        if (isFinishing || isDestroyed) return
        naverMap.whenReady { if (!isFinishing && !isDestroyed) startParkedCarView(lat, lon, savedAt) }
    }

    private fun startParkedCarView(lat: Double, lon: Double, savedAt: Long) {
        if (isGuidanceRunningNow()) {
            Toast.makeText(this, "길안내 중에는 쓸 수 없어요", Toast.LENGTH_SHORT).show()
            return
        }
        closeParkedCarView(finishIfFromTmap = false)
        parkedActive = true
        // 차 위치를 보는 동안은 안내용 자동 따라가기를 쉬고 진북 고정 2D로 보여준다.
        naverMap.northUp2D()
        naverMap.showParkedCar(lat, lon, "내 차")
        val root = binding.root as ViewGroup

        val timeText = java.text.SimpleDateFormat("M월 d일 a h:mm", java.util.Locale.KOREAN).format(java.util.Date(savedAt))
        Toast.makeText(this, "내 차 위치 ($timeText 저장)", Toast.LENGTH_LONG).show()
        val (btnW, btnH) = parkedButtonSize()
        val close = android.widget.TextView(this).apply {
            text = "닫기"; textSize = 14f; gravity = android.view.Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor("#DDDDDD"))
            background = parkedButtonBackground("#CC28282C")
        }
        parkedCloseButton = close
        root.addView(close, android.widget.FrameLayout.LayoutParams(btnW, btnH).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            marginEnd = PopupCard.dp(this@NaverNaviActivity, 84) + btnW + PopupCard.dp(this@NaverNaviActivity, 12)
            bottomMargin = PopupCard.dp(this@NaverNaviActivity, 84)
        })
        PopupCard.attachDrag(this, close, root, "parkedCloseButton") { closeParkedCarView(finishIfFromTmap = true) }
        val back = object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { closeParkedCarView(finishIfFromTmap = true) }
        }
        parkedBackCallback = back
        onBackPressedDispatcher.addCallback(this, back)

        addParkedGpsButton()
        parkedHandler.postDelayed({ if (parkedActive && parkedGpsStep == 0) moveParkedCamera(lat, lon) }, 300L)
    }

    // 대상 지점을 화면 가운데로, 화면 세로가 대략 300m 정도 보이게 확대해서 이동
    private fun moveParkedCamera(lat: Double, lon: Double) {
        // 화면 세로가 대략 300m 정도 보이는 확대 수준으로 이동
        naverMap.moveTo(lat, lon, 16.8, animated = false)
    }

    private fun addParkedGpsButton() {
        if (parkedGpsButton != null) return
        parkedGpsStep = 0
        val root = binding.root as ViewGroup
        val (btnW, btnH) = parkedButtonSize()
        val gps = android.widget.TextView(this).apply {
            text = "GPS"; textSize = 14f; gravity = android.view.Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        parkedGpsButton = gps
        root.addView(gps, android.widget.FrameLayout.LayoutParams(btnW, btnH).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            marginEnd = PopupCard.dp(this@NaverNaviActivity, 84)
            bottomMargin = PopupCard.dp(this@NaverNaviActivity, 84)
        })
        // 짧게 누르면 GPS 동작, 끌면 옮겨지고 위치가 저장됨
        PopupCard.attachDrag(this, gps, root, "parkedGpsButton") { onParkedGpsPressed() }
        updateParkedGpsStyle()
    }

    // 즐겨찾기/주변 버튼과 같은 크기(못 재면 기본값)와 같은 둥근 모서리
    private fun parkedButtonSize(): Pair<Int, Int> {
        // 즐겨찾기/주변 버튼은 QuickIconGrid가 이 기준 뷰 크기에 맞춰 잡으므로 같은 뷰를 따라감
        val ref = binding.tvConnectionStatus?.parent?.parent as? View
        val w = ref?.width ?: 0
        val h = ref?.height ?: 0
        return if (w > 0 && h > 0) Pair(w, h) else Pair(PopupCard.dp(this, 96), PopupCard.dp(this, 46))
    }

    private fun parkedButtonBackground(colorHex: String): android.graphics.drawable.Drawable =
        PopupCard.roundedFill(this, colorHex)

    private fun removeParkedGpsButton() {
        parkedGpsButton?.let { (binding.root as ViewGroup).removeView(it) }
        parkedGpsButton = null
    }

    private fun updateParkedGpsStyle() {
        val gps = parkedGpsButton ?: return
        gps.background = parkedButtonBackground(if (parkedGpsStep == 2) "#03C75A" else "#CC28282C")
        gps.setTextColor(android.graphics.Color.parseColor(when (parkedGpsStep) {
            2 -> "#212121"
            1 -> AppAccent.hex(this)
            else -> "#DDDDDD"
        }))
    }

    // 1번 누름: 현재 위치로 이동해서 보기 / 2번 누름: 움직임을 따라가기(카카오 원래 추적 모드 복원)
    // 1번 누름: 현재 위치로 이동해서 보기 / 2번 누름: 움직임을 따라가기
    private fun onParkedGpsPressed() {
        if (parkedGpsButton == null) return
        if (parkedGpsStep == 1) {
            parkedGpsStep = 2
            naverMap.resumeFollow()
            NaverNavigator.lastLocation?.let { naverMap.follow(it) }
            Toast.makeText(this, "내 위치를 따라가요", Toast.LENGTH_SHORT).show()
        } else {
            val (la, lo) = resolveCurrentWgs84LatLonForSearch()
            if (la == null || lo == null) {
                Toast.makeText(this, "현재 위치를 아직 못 받았어요", Toast.LENGTH_SHORT).show()
                return
            }
            parkedGpsStep = 1
            naverMap.northUp2D()
            parkedHandler.postDelayed({ if (parkedActive && parkedGpsStep == 1) moveParkedCamera(la, lo) }, 300L)
            Toast.makeText(this, "현재 위치로 이동", Toast.LENGTH_SHORT).show()
        }
        updateParkedGpsStyle()
    }

    private fun closeParkedCarView(finishIfFromTmap: Boolean) {
        parkedActive = false
        parkedRefresh?.let { parkedHandler.removeCallbacks(it) }
        parkedRefresh = null
        val root = binding.root as ViewGroup
        parkedCloseButton?.let { root.removeView(it) }
        parkedBackCallback?.remove()
        removeParkedGpsButton()
        parkedCloseButton = null; parkedBackCallback = null
        naverMap.clearParkedCar()
        if (finishIfFromTmap && parkedLaunchedFromTmap) finish()
    }

    // v19.3.72: 신규기능(재억 요청 2026-09-18) - 검색 결과를 고르면 추천/고속/무료
    // 팝업이 뜨기 전에, 그 목적지 위치에 지도 핀을 찍고 카메라를 그쪽으로 이동시켜
    // "여기 맞아?" 확인할 수 있게 함. 카카오 SDK(KNMapView)가 addMarker/moveCamera를
    // 공개 API로 제공해서 가능(aar 안에서 확인). 좌표는 다른 곳과 동일하게
    // KNSDK.convertWGS84ToKATEC로 변환. #문제시 원복
    private fun showDestinationPinOnMap(lat: Double, lon: Double) {
        try {
            // 경로를 고르기 전에 목적지가 실제로 지도 어디인지 핀으로 먼저 보여준다(진북 고정 2D).
            naverMap.clearAllRoutes()
            naverMap.northUp2D()
            naverMap.showPin(lat, lon, currentDestName)
            naverMap.moveTo(lat, lon, 15.5, animated = false)
            addParkedGpsButton()
        } catch (e: Exception) {
            NavLogger.e(this, "[목적지핀] 표시 실패: ${e.message}")
        }
    }

    // v19.3.80: 재억 요청(2026-09-19) - 출발~목적지 전체가 화면 중앙에 꽉 차게 보이도록
    // 카메라를 맞춤. fitTo/거리 계산은 카카오 내부 규칙을 몰라 어제 계속 어긋났으니, 대신
    // "카메라를 움직이고 → 두 점이 화면 어디 찍히는지(katecToScreen) 실측 → 줌 보정"을
    // 몇 번 반복해서 실제 화면 기준으로 맞춤. #문제시 원복
    private fun fitViewToEndpoints(
        startLat: Double, startLon: Double, goalLat: Double, goalLon: Double,
        stillActive: () -> Boolean
    ) {
        if (!stillActive()) return
        // 후보 경로가 이미 받아져 있으면 경로 전체를, 아니면 출발·목적지 두 점이 보이게 맞춘다.
        naverMap.fitTo(listOf(com.naver.maps.geometry.LatLng(startLat, startLon), com.naver.maps.geometry.LatLng(goalLat, goalLon)))
    }

    // v19.3.72: 신규기능(재억 요청 2026-09-18) - "목적지 고르면 지도 위에 핀 찍고, 그
    // 아래에 우리 앱 기존 다이얼로그 스타일(반투명 검정 카드 #28282C 70%, 20dp 라운드,
    // 골드 강조색)로 경로 선택 카드가 뜨게" 만든 새 오버레이. AlertDialog 목록 대신
    // naviView 위에 코드로 뷰를 직접 얹는 방식이라 레이아웃 xml은 안 건드림. #문제시 원복
    private fun showRouteChoicePanel(
        picked: HistoryEntry,
        optionLabels: List<String>,
        minutesArr: Array<Int?>,
        costArr: Array<Int?>,
        routesArr: Array<Any?>,
        startLat: Double,
        startLon: Double,
        goDirectly: (Int) -> Unit,
        // v19.3.78: 재억 요청 - 경유지 추가 흐름도 이 카드를 그대로 재사용하게 되면서,
        // "안내 시작" 버튼 문구와 위쪽 라벨을 상황에 맞게 바꿀 수 있게 함. #문제시 원복
        startButtonLabel: String = "안내 시작",
        topLabel: String? = null
    ): () -> Unit {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        // v: 재억 재제보(2026-09-19) - "계산 중" 카운트다운 문제 수정용 추적 변수. #문제시 원복
        var countdownStartedForIndex = -1
        // 재억 요청(2026-09-28): 이동방식 칸을 하나라도 직접 누르면 그 뒤로는 자동 시작 안 함
        // (비교 중인데 저절로 출발하면 안 되므로). 아무것도 안 눌렀을 때만 10초 후 자동 시작. #문제시 원복
        var userTouched = false
        var startBtnRef: android.widget.TextView? = null
        val root = binding.root as ViewGroup
        // v19.3.72: 재억 요청(2026-09-18) - fitTo 자동 맞춤을 포기하고 거리 기반 줌
        // 계산으로 바꿨으니, 카드 위치가 지도 표시 영역 모양에 영향을 주는 이유가 없어짐.
        // 원래 요청대로 왼쪽 위(경유지 버튼 오른쪽/GPS 아래)로 되돌림. #문제시 원복
        val cardWidthForFit = minOf(dp(360), (resources.displayMetrics.widthPixels * 0.42).toInt())
        val panelMarginStart = dp(170).toFloat()
        val panelMarginTop = dp(76).toFloat()

        // 칸 자리 순서(저장됨). 첫 칸의 방식이 처음 선택되고 자동 시작 때 쓰임.
        // 네이버 길찾기에 없는 방식(최단거리·선호경로)은 카드에서 뺀다. 저장된 칸 순서는 유지한다.
        val naverSlots = NaverRouteOptions.OPTION_BY_SLOT.indices.filter { NaverRouteOptions.OPTION_BY_SLOT[it] != null }
        val displayOrder = RouteChoiceOptions.loadOrder(this).filter { it in naverSlots }.toMutableList()
        var layoutTabs: () -> Unit = {}
        var selectedIndex = displayOrder[0]
        var panelView: View? = null
        // v19.3.72: 재억 실기기 제보 - 계산 결과가 옵션별로 하나씩 도착할 때마다 매번
        // fitTo로 카메라를 다시 움직였더니, 전환이 끝나기 전에 또 새로 움직이는 일이
        // 반복돼서 화면이 어중간한 상태로 찍히는 경우가 있었음("목적지가 잘려 보인다").
        // 같은 옵션에 대해선 카메라를 한 번만 맞추도록 기록. #문제시 원복
        val fittedIndices = mutableSetOf<Int>()
        val tabViews = mutableListOf<android.widget.TextView>()
        lateinit var timeText: android.widget.TextView
        lateinit var etaText: android.widget.TextView
        lateinit var distText: android.widget.TextView

        fun removePanel() {
            panelView?.let { root.removeView(it) }
            panelView = null
            activeRouteChoicePanel = null
            activeRouteChoicePanelCancel = null
        }

        // v19.3.78: 새 카드를 만들기 전에, 아직 안 지워진 이전 카드가 있으면 먼저 지우고
        // 그 카드의 카운트다운 타이머도 같이 멈춤. (이전 카드 위치를 그대로 이어받게
        // 해봤는데, 카드가 화면에 자리잡기도 전에 위치값을 읽어버려서 오히려 왼쪽 위로
        // 튀는 문제가 생겨 그 부분은 뺌 - 재억 확인) #문제시 원복
        activeRouteChoicePanelCancel?.invoke()
        activeRouteChoicePanel?.let { root.removeView(it) }

        // v19.3.74: 신규기능(재억 요청 2026-09-18) - 티맵 순정 화면처럼, 이 패널이 뜬
        // 채로 아무것도 안 누르면 일정 시간 뒤 자동으로 안내가 시작되게 함(티맵은 15초,
        // 재억 요청대로 10초로). 탭을 눌러 방식을 바꾸면 그 사이엔 급하게 시작되면 안
        // 되니 다시 10초로 리셋됨. #문제시 원복
        val countdownHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var countdownRunnable: Runnable? = null
        lateinit var startClick: () -> Unit
        lateinit var startCountdown: () -> Unit

        fun stopCountdown() {
            countdownRunnable?.let { countdownHandler.removeCallbacks(it) }
            countdownRunnable = null
        }

        // v19.3.72: 재억 요청(2026-09-18) - "전체 경로를 화면에 자동으로 맞추기"는
        // 완전히 폐기함. fitTo/거리기반 zoomTo 둘 다 카카오 비공식 API 특성상 예측 가능한
        // 결과를 못 만들어서(로그로 여러 번 확인) 계속 튀는 값이 나왔음. 이제 목적지
        // 핀(showDestinationPinOnMap에서 이미 안정적으로 찍고 카메라도 그쪽으로 이동시켜
        // "여기 맞아?" 확인하는 기능)만 남기고, 경로 전체를 화면에 맞추는 시도는 하지
        // 않음 - 필요하면 사용자가 손으로 확대/축소. #문제시 원복
        // v19.3.80: 재억 요청 - 경로선은 다시 그려주되(카메라는 안 건드림), 화면 맞춤은 위
        // fitViewToEndpoints가 따로 담당. 선택한 탭의 경로가 아직 계산 전이면 그리지 않음. #문제시 원복
        fun drawRouteAndFit() {
            try {
                // 받아둔 후보 경로를 네이버 지도에 그리고, 지금 고른 칸의 경로만 진하게 보여준다.
                val routes = routesArr.filterIsInstance<NaverRoute>()
                if (routes.isEmpty()) return
                val sel = routesArr.getOrNull(selectedIndex) as? NaverRoute
                naverMap.showPreview(routes, { naverColorFor(it.option) }, sel)
            } catch (e: Exception) {
                NavLogger.e(this, "[경로선] 표시 실패: ${e.message}")
            }
        }

        // 재억 요청(2026-10-05): 추천 경로와 시간·거리가 똑같은 방식 칸은 흐리게 표시(고르나 마나 같은 길). #문제시 원복
        fun routeKey(r: Any?): Pair<Long, Int>? {
            val nr = r as? NaverRoute ?: return null
            return nr.durationMs to nr.distanceMeters
        }
        fun sameAsRecommend(i: Int): Boolean {
            if (i == 0) return false
            val base = routeKey(routesArr.getOrNull(0)) ?: return false
            return routeKey(routesArr.getOrNull(i)) == base
        }
        fun applySameDim() {
            tabViews.forEachIndexed { i, tv -> tv.alpha = if (i != selectedIndex && sameAsRecommend(i)) 0.45f else 1f }
        }

        fun updateSelection() {
            tabViews.forEachIndexed { i, tv ->
                // v19.3.74: 재억 제보 - setBackgroundColor()가 처음에 만들어둔 둥근 모서리
                // GradientDrawable을 각진 배경으로 통째로 덮어써서, 탭만 계속 각진 모서리로
                // 보였음. 배경 드로어블은 그대로 두고 색만 바꾸도록 수정. #문제시 원복
                val bg = tv.background as android.graphics.drawable.GradientDrawable
                if (i == selectedIndex) {
                    bg.setColor(android.graphics.Color.parseColor("#03C75A"))
                    tv.setTextColor(android.graphics.Color.parseColor("#212121"))
                    tv.setTypeface(null, android.graphics.Typeface.BOLD)
                } else {
                    bg.setColor(android.graphics.Color.parseColor("#14FFFFFF"))
                    tv.setTextColor(android.graphics.Color.parseColor("#CCCCCC"))
                    tv.setTypeface(null, android.graphics.Typeface.NORMAL)
                }
            }
            // 재억 요청(2026-10-05): 계산이 실패/시간초과된 칸(-1)은 "계산 중..."에 계속 머물지 않고 "계산 실패"로 표시. #문제시 원복
            val rawMinutes = minutesArr[selectedIndex]
            val unsupported = rawMinutes == NaverRouteOptions.UNSUPPORTED
            val failed = rawMinutes != null && rawMinutes < 0 && !unsupported
            val minutes = if (failed || unsupported) null else rawMinutes
            val etaLine = SearchRanking.formatEtaMinutes(minutes)
            // v19.3.72: 재억 실기기 제보 - "계산실패?" - 실제로는 실패한 게 아니라, 거리가
            // 멀어서(평택-대구 등) 계산이 3초 넘게 걸린 것뿐이었는데 "계산 실패"라고 써놔서
            // 영영 안 되는 것처럼 보였음. 아직 값이 안 왔을 때는 "계산 중..."으로 바꾸고,
            // 값이 도착하면(아래 refresh 참고) 다시 그려서 실제 값으로 바뀌게 함. #문제시 원복
            timeText.text = etaLine ?: if (unsupported) "네이버 안내엔 없는 방식" else if (failed) "계산 실패" else "계산 중..."
            etaText.text = if (minutes != null && sameAsRecommend(selectedIndex)) "추천 경로와 같음" else ""
            // v: 재억 재제보(2026-09-19) - "계산 중..."인 동안에도 안내시작 카운트다운이
            // 이미 돌고 있었음(패널 열리자마자 무조건 10초 시작). 이 탭의 실제 소요시간이
            // 도착(minutes != null)했을 때 딱 한 번만 시작하도록 바꿈 - 계산 끝나기 전엔
            // 카운트다운 자체를 시작 안 함. countdownStartedForIndex로 같은 탭에 대해
            // 중복 시작 안 하게 막음(다른 탭 ETA가 나중에 도착해도 여기서 재시작 안 됨). #문제시 원복
            if (minutes != null && countdownStartedForIndex != selectedIndex && !userTouched) {
                countdownStartedForIndex = selectedIndex
                startCountdown()
            }
            val toll = costArr.getOrNull(selectedIndex)?.takeIf { it > 0 }
            distText.text = when {
                minutes == null -> ""
                toll != null -> "통행료 %,d원".format(toll)
                else -> "통행료 무료"
            }
            drawRouteAndFit()
            applySameDim()
        }

        val card = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                // v19.3.74: 재억 요청 - 다른 팝업들과 통일성 있게 다시 70% 반투명(#B3)으로.
                setColor(android.graphics.Color.parseColor("#B328282C"))
                cornerRadius = dp(20).toFloat()
            }
            setPadding(dp(20), dp(18), dp(20), dp(16))
        }

        if (topLabel != null) {
            card.addView(android.widget.TextView(this).apply {
                text = topLabel
                setTextColor(AppAccent.color(this@NaverNaviActivity))
                textSize = 12f
                setPadding(0, 0, 0, dp(4))
            })
        }
        card.addView(android.widget.TextView(this).apply {
            text = picked.name
            setTextColor(android.graphics.Color.WHITE)
            textSize = 17f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        if (picked.addr.isNotBlank()) {
            card.addView(android.widget.TextView(this).apply {
                text = picked.addr
                setTextColor(android.graphics.Color.parseColor("#BBBBBB"))
                textSize = 12f
                setPadding(0, dp(2), 0, dp(14))
            })
        } else {
            card.addView(View(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(0, dp(10))
            })
        }

        val tabsRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        val tabsRow2 = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        // 끌어서 자리 바꿀 때 들린 칸이 자기 줄 밖(다른 줄 위)으로 나가도 잘리지 않고 보이게 함. #문제시 원복
        tabsRow.clipChildren = false
        tabsRow2.clipChildren = false
        card.clipChildren = false
        card.clipToPadding = false
        optionLabels.forEachIndexed { i, label ->
            val tab = android.widget.TextView(this).apply {
                text = label
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                setPadding(dp(4), dp(9), dp(4), dp(9))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                }
                isClickable = true
                // 재억 요청(2026-09-28): 칸을 꾹 누른 채 다른 칸 위로 끌어다 놓으면 서로 자리가 바뀌고,
                // 그 순서는 저장돼서 다음에도 유지됨. #문제시 원복
                val longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
                var lifting = false
                var downRawX = 0f
                var downRawY = 0f
                val liftRunnable = Runnable {
                    lifting = true
                    userTouched = true
                    stopCountdown()
                    startBtnRef?.text = startButtonLabel
                    elevation = dp(8).toFloat()
                    (parent as? View)?.elevation = dp(8).toFloat() // 다른 줄 위로 지나갈 때 위에 그려지게
                    animate().scaleX(1.12f).scaleY(1.12f).setDuration(120).start()
                    alpha = 1f
                    (parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                }
                setOnTouchListener { v, e ->
                    when (e.action) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            downRawX = e.rawX
                            downRawY = e.rawY
                            lifting = false
                            longPressHandler.postDelayed(liftRunnable, 450L)
                            false
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            if (!lifting) {
                                if (Math.hypot((e.rawX - downRawX).toDouble(), (e.rawY - downRawY).toDouble()) > dp(10)) {
                                    longPressHandler.removeCallbacks(liftRunnable)
                                }
                                false
                            } else {
                                v.translationX = e.rawX - downRawX
                                v.translationY = e.rawY - downRawY
                                // 놓으면 자리가 바뀔 칸을 흐리게 표시
                                tabViews.forEachIndexed { idx, tv ->
                                    if (idx == i) return@forEachIndexed
                                    val loc = IntArray(2)
                                    tv.getLocationOnScreen(loc)
                                    val over = e.rawX >= loc[0] && e.rawX <= loc[0] + tv.width &&
                                        e.rawY >= loc[1] && e.rawY <= loc[1] + tv.height
                                    tv.alpha = if (over) 0.4f else 1f
                                }
                                true
                            }
                        }
                        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                            longPressHandler.removeCallbacks(liftRunnable)
                            if (lifting) {
                                lifting = false
                                // 놓는 순간 모든 칸의 화면 위치를 기억해뒀다가, 자리가 정리된 뒤 그 위치에서
                                // 새 자리로 미끄러지듯 움직이게 함(끌린 칸은 놓은 곳에서 제자리로). #문제시 원복
                                val before = tabViews.map { tv -> IntArray(2).also { tv.getLocationOnScreen(it) } }
                                v.translationX = 0f
                                v.translationY = 0f
                                v.scaleX = 1f
                                v.scaleY = 1f
                                v.alpha = 1f
                                v.elevation = 0f
                                (v.parent as? View)?.elevation = 0f
                                tabViews.forEach { it.alpha = 1f }
                                v.isPressed = false
                                val target = tabViews.indices.firstOrNull { idx ->
                                    if (idx == i) return@firstOrNull false
                                    before[idx][0].let { lx ->
                                        e.rawX >= lx && e.rawX <= lx + tabViews[idx].width &&
                                            e.rawY >= before[idx][1] && e.rawY <= before[idx][1] + tabViews[idx].height
                                    }
                                }
                                if (target != null && e.action == android.view.MotionEvent.ACTION_UP) {
                                    val a = displayOrder.indexOf(i)
                                    val b = displayOrder.indexOf(target)
                                    displayOrder[a] = target
                                    displayOrder[b] = i
                                    RouteChoiceOptions.saveOrder(this@NaverNaviActivity, displayOrder + (0 until RouteChoiceOptions.count).filter { it !in displayOrder })
                                    layoutTabs()
                                }
                                applySameDim()
                                card.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                                    override fun onPreDraw(): Boolean {
                                        card.viewTreeObserver.removeOnPreDrawListener(this)
                                        tabViews.forEachIndexed { idx, tv ->
                                            val now = IntArray(2)
                                            tv.getLocationOnScreen(now)
                                            val dx = before[idx][0] - now[0]
                                            val dy = before[idx][1] - now[1]
                                            if (dx != 0 || dy != 0) {
                                                tv.translationX = dx.toFloat()
                                                tv.translationY = dy.toFloat()
                                                tv.animate().translationX(0f).translationY(0f).setDuration(220).start()
                                            }
                                        }
                                        return true
                                    }
                                })
                                true
                            } else false
                        }
                        else -> false
                    }
                }
                setOnClickListener {
                    selectedIndex = i
                    // v: 재억 재제보(2026-09-19, "계산 중일 때도 카운트가 이미 가고 있다") -
                    // 탭을 누르면 그 탭의 계산이 아직 안 끝났어도 무조건 카운트다운을 다시
                    // 시작했음. countdownStartedForIndex를 초기화해서, updateSelection()이
                    // 실제로 그 탭의 소요시간(minutes)이 도착했을 때만 시작하도록 넘김. #문제시 원복
                    countdownStartedForIndex = -1
                    userTouched = true
                    stopCountdown()
                    startBtnRef?.text = startButtonLabel
                    updateSelection()
                }
            }
            tabViews.add(tab)
        }
        // 2줄 x 3칸: 저장된 순서(displayOrder)의 앞 3개는 위 줄, 나머지 3개는 아래 줄
        layoutTabs = {
            tabsRow.removeAllViews()
            tabsRow2.removeAllViews()
            displayOrder.forEachIndexed { slot, optionIndex ->
                val lp = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                if (slot % 3 > 0) lp.marginStart = dp(8)
                (if (slot < 3) tabsRow else tabsRow2).addView(tabViews[optionIndex], lp)
            }
        }
        layoutTabs()
        card.addView(tabsRow, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })
        card.addView(tabsRow2, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(14) })

        // v19.3.76: 재억 실기기 제보(세로모드) - distText가 metaRow 안에서 weight로 남는
        // 폭만 억지로 나눠 받다 보니, 화면이 좁아지면(세로모드) 그 남는 폭이 글자 하나보다도
        // 작아져서 "통행료 8,200원"이 한 글자씩 세로로 쪼개져 보였음. 시간 줄과 통행료 줄을
        // 아예 분리된 두 줄로 나눠서, 어떤 화면 폭에서도 각자 필요한 만큼만 차지하고 줄바꿈
        // 없이 한 줄로 표시되게 함. #문제시 원복
        val metaCol = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        val timeRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        timeText = android.widget.TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            maxLines = 1
        }
        etaText = android.widget.TextView(this).apply {
            setTextColor(AppAccent.color(this@NaverNaviActivity))
            textSize = 13f
            setPadding(dp(8), 0, 0, 0)
            maxLines = 1
        }
        distText = android.widget.TextView(this).apply {
            setTextColor(android.graphics.Color.parseColor("#FFB74D"))
            textSize = 13f
            setPadding(0, dp(4), 0, 0)
            maxLines = 1
        }
        timeRow.addView(timeText)
        timeRow.addView(etaText)
        metaCol.addView(timeRow)
        metaCol.addView(distText)
        card.addView(metaCol, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(16) })

        val btnRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        val cancelBtn = android.widget.TextView(this).apply {
            text = "취소"
            gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.parseColor("#DDDDDD"))
            textSize = 14f
            setPadding(0, dp(13), 0, dp(13))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#1AFFFFFF"))
                cornerRadius = dp(12).toFloat()
            }
            isClickable = true
            setOnClickListener {
                stopCountdown()
                removePanel()
                clearDestinationPin()
                // v19.3.72: 재억 요청(2026-09-18) - "취소 누르면 다시 티맵으로 돌아오기".
                // isFirstTimeDestinationChoice로 판단 - 이미 안내 중이던 걸 "새 목적지로
                // 바꿀까요?"에서 취소한 경우는 기존 안내를 그대로 이어가야 하므로 안 나감.
                // v19.3.72(2차): 재억 실기기 제보 - 그냥 finish()만 하니 ResumeGuidanceStore/
                // KakaoRouteDataRepository에 "안내 중" 상태가 안 지워진 채로 남아서, 다음에
                // 목적지를 다시 검색하면 "경유지 추가/새 목적지로" 팝업이 엉뚱하게 떴음.
                // 원래 "안내종료" 버튼이 쓰는 finishGuidance()로 정식으로 정리하고 나가도록
                // 교체(이 시점엔 아직 실제 안내가 시작 전이라 stop()을 불러도 안전함). #문제시 원복
                if (isFirstTimeDestinationChoice) {
                    finishGuidance()
                }
            }
        }
        val startBtn = android.widget.TextView(this).apply {
            text = startButtonLabel
            gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.parseColor("#212121"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            textSize = 14f
            setPadding(0, dp(13), 0, dp(13))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#03C75A"))
                cornerRadius = dp(12).toFloat()
            }
            isClickable = true
        }
        startBtnRef = startBtn
        startClick = {
            stopCountdown()
            removePanel()
            // 전체 경로가 보이던 화면에서 안내 화면으로 한 번에 확 넘어가지 않게, 먼저 내 위치로
            // 부드럽게 확대한 다음 안내를 시작함. #문제시 원복
            zoomToMyPositionThen(1500L) {
                clearDestinationPin()
                goDirectly(selectedIndex)
            }
        }
        startBtn.setOnClickListener { startClick() }

        startCountdown = {
            stopCountdown()
            var secondsLeft = 10
            val runnable = object : Runnable {
                override fun run() {
                    if (secondsLeft <= 0) {
                        startClick()
                        return
                    }
                    startBtn.text = "$startButtonLabel ($secondsLeft)"
                    secondsLeft--
                    countdownHandler.postDelayed(this, 1000L)
                }
            }
            countdownRunnable = runnable
            countdownHandler.post(runnable)
        }
        btnRow.addView(cancelBtn, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        btnRow.addView(startBtn, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
        card.addView(btnRow)

        // v19.3.72: 재억 요청(2026-09-18) - 왼쪽 위(경유지 버튼 오른쪽/GPS 아래)로 원복. #문제시 원복
        val frameParams = android.widget.FrameLayout.LayoutParams(cardWidthForFit, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            marginStart = panelMarginStart.toInt()
            topMargin = panelMarginTop.toInt()
        }
        root.addView(card, frameParams)
        panelView = card
        activeRouteChoicePanel = card
        activeRouteChoicePanelCancel = { stopCountdown() }
        attachPopupCardDrag(card, root)
        updateSelection()
        // 재억 요청(2026-10-05): 25초가 지나도 안 온 칸은 실패로 보고 "계산 실패" 표시(계속 "계산 중..."이던 문제). #문제시 원복
        countdownHandler.postDelayed({
            if (activeRouteChoicePanel === card) {
                var changed = false
                for (i in minutesArr.indices) if (minutesArr[i] == null) { minutesArr[i] = -1; changed = true }
                if (changed) updateSelection()
            }
        }, 25_000L)
        // v19.3.80: 패널이 뜬 뒤 화면 크기가 잡히면 출발~목적지 전체를 중앙에 맞춤. #문제시 원복
        card.post {
            fitViewToEndpoints(startLat, startLon, picked.lat, picked.lon) { activeRouteChoicePanel === card }
        }
        // v19.3.74: 패널이 처음 뜬 시점에 카운트다운 시작 - 단, v19.3.89부터는 그 탭의
        // 소요시간이 실제로 도착했을 때만 시작하도록 updateSelection() 안으로 옮김(바로 위
        // updateSelection() 최초 호출에서 이미 시도됨. 아직 계산 중이면 여기선 아무 것도
        // 안 하고, 나중에 refresh()로 값이 도착하면 그때 시작됨). #문제시 원복
        return ::updateSelection
    }

    // v19.3.79: 재억 요청 - 팝업 카드를 끌어서 옮겨놓으면 위치를 자동 저장(공용 PopupCard). #문제시 원복
    private fun attachPopupCardDrag(card: View, root: ViewGroup) {
        PopupCard.attachDrag(this, card, root, "kakaoPopupCard")
    }

    // v19.3.79: 재억 제보 - 안내 종료 후 새 목적지를 찾는데 "경유지로 추가할까요?"가 뜸.
    // 원인: 카카오 화면은 열리는 순간 currentDestName이 채워지기 때문에, 이 값만 보고
    // "안내 중"으로 착각하는 곳(주변검색 결과, 최근목적지 "추가" 칩)이 있었음. 실제로 안내가
    // 돌아가고 있는지(KakaoRouteDataRepository)까지 같이 확인. 터널 등으로 갱신이 잠깐
    // 끊겨도 안내 중으로 보도록 여유(30초)를 둠 - 안내가 끝나면 reset()으로 바로 꺼짐. #문제시 원복
    private fun isGuidanceRunningNow(): Boolean =
        currentDestName.isNotBlank() && KakaoRouteDataRepository.isFresh(30_000L)

    // 지금 보이는 지도(전체 경로)에서 내 위치까지 durationMs 동안 부드럽게 확대한 뒤 action 실행.
    // 못 하면 바로 action 실행. 안내 배율(약 1.3)보다 조금 넓게(3)까지만 - 나머지는 카카오가 이어받음.
    private fun zoomToMyPositionThen(durationMs: Long, action: () -> Unit) {
        // 전체 경로 화면에서 안내 화면으로 한 번에 확 넘어가지 않게, 내 위치로 먼저 부드럽게 확대한 뒤 안내를 시작한다.
        val loc = NaverNavigator.lastLocation
        if (loc == null) { action(); return }
        naverMap.moveTo(loc.latitude, loc.longitude, 16.0, animated = true)
        naviView.postDelayed({ if (!isFinishing && !isDestroyed) action() }, durationMs.coerceAtMost(800L))
    }

    private fun clearDestinationPin() {
        if (!parkedActive) removeParkedGpsButton()
        naverMap.clearPin()
        naverMap.clearPreview()
    }

    // 안내 중 경유지 추가 - 기존 경유지는 그대로 두고 맨 뒤에 이어붙임. #문제시 원복
    // v: 재억 요청(2026-09-02) - "경유지로 추가할 때는 저장된 방식이 있어도 그냥 물어보게
    // 할 수 있나?" -> 가능. 즐겨찾기에 저장된 경로 방식은 "그 목적지로 새로 갈 때" 쓰라고
    // 저장해둔 것이고, 경유지를 끼워넣는 건 경로 전체를 다시 짜는 일이라 그때그때 다를 수
    // 있음. 그래서 경유지 추가할 때는 저장값과 무관하게 매번 물어봄.
    //
    // 참고(카카오 SDK 한계): 경로 방식은 guideNewDestinations(trip, priority, avoidOption)처럼
    // **경로 전체에 하나만** 지정할 수 있음. "A까지는 고속도로, B까지는 무료도로"처럼 구간별로
    // 다르게는 카카오가 지원하지 않아서, 여기서 고르는 값도 경로 전체에 적용됨. #문제시 원복
    // v19.3.78: 신규기능(재억 요청 2026-09-18) - 경유지 추가할 때도 검색/즐겨찾기로 목적지
    // 고를 때와 똑같이, 목록형 AlertDialog 대신 지도 위 지도+경로선택 카드(showRouteChoicePanel,
    // 소요시간/통행료/자동시작 카운트다운/드래그 이동 다 포함)를 그대로 재사용. #문제시 원복
    private fun addWaypointToActiveGuidance(picked: HistoryEntry) {
        val optionLabels = RouteChoiceOptions.labels
        val optionPriorities = RouteChoiceOptions.priorities
        val optionAvoidOptions = RouteChoiceOptions.avoidOptions

        showDestinationPinOnMap(picked.lat, picked.lon)

        fun goDirectly(index: Int) {
            applyRouteOption(optionPriorities[index], optionAvoidOptions[index])
            NavLogger.d(this, "[경유지추가] 경로 방식 선택: ${optionLabels[index]}")
            rebuildRouteWithWaypoints(activeWaypoints + picked, "경유지추가", addedName = picked.name)
        }

        val (curLat, curLon) = resolveCurrentWgs84LatLonForSearch()
        if (curLat == null || curLon == null) {
            clearDestinationPin()
            goDirectly(0)
            return
        }

        val minutesArr = arrayOfNulls<Int>(RouteChoiceOptions.count)
        val costArr = arrayOfNulls<Int>(RouteChoiceOptions.count)
        val routesArr = arrayOfNulls<Any>(RouteChoiceOptions.count)
        val refresh = showRouteChoicePanel(picked, optionLabels, minutesArr, costArr, routesArr, curLat, curLon, ::goDirectly, startButtonLabel = "경유지 추가", topLabel = "경유지로 추가")
        // 경유지를 끼운 전체 경로(현재 위치 → 기존 경유지들 → 새 경유지 → 최종 목적지)를 네이버로 계산한다.
        val goal = LonLat(currentDestLon, currentDestLat)
        val vias = (activeWaypoints + picked).map { LonLat(it.lon, it.lat) }
        computeNaverOptions(LonLat(curLon, curLat), goal, vias, minutesArr, costArr, routesArr, refresh)
    }

    /** 이동방식 카드의 칸별 소요시간·통행료·경로를 네이버로 구해 채운다(결과가 올 때마다 [refresh]). */
    private fun computeNaverOptions(
        from: LonLat, to: LonLat, vias: List<LonLat>,
        minutesArr: Array<Int?>, costArr: Array<Int?>, routesArr: Array<Any?>,
        refresh: () -> Unit
    ) {
        NaverRouteOptions.compute(this, from, to, vias) { slot, route, supported ->
            if (isFinishing || isDestroyed) return@compute
            when {
                !supported -> minutesArr[slot] = NaverRouteOptions.UNSUPPORTED
                route == null -> minutesArr[slot] = -1
                else -> {
                    minutesArr[slot] = Math.round(route.durationMs / 60000.0).toInt().coerceAtLeast(1)
                    costArr[slot] = route.tollFare
                    routesArr[slot] = route
                }
            }
            refresh()
        }
    }

    /** 후보 경로를 지도에 그릴 때 방식별로 쓰는 색(카드 색과 맞춘다). */
    private fun naverColorFor(option: String): Int = when (option) {
        "traoptimal" -> 0xFF2E7DFF.toInt()
        "trafast" -> 0xFF00A86B.toInt()
        "traavoidtoll" -> 0xFF8E24AA.toInt()
        "tracomfort" -> 0xFFFF8F00.toInt()
        else -> 0xFF607D8B.toInt()
    }

    private fun showInPlaceSearchDialog() {
        // v1.7: 기본 AlertDialog.Builder(this)는 앱 라이트 테마를 상속해서 다이얼로그
        // 배경이 밝은데 입력창 글자색은 흰색으로 박아놔서 "흰 배경에 흰 글씨"로 안 보이던
        // 문제였음(사용자 지적 7번). Theme_Material_Dialog_Alert(다크)로 통일해서 해결. #문제시 원복
        val input = android.widget.EditText(this).apply {
            hint = "목적지를 입력하세요 (예: 서울역)"
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.parseColor("#AAAAAA"))
        }
        android.app.AlertDialog.Builder(this, R.style.RoundedDialogTheme)
            .setTitle(if (pendingWaypointAddition) "경유지 검색" else "목적지 재검색")
            .setView(input)
            .setPositiveButton("검색") { _, _ ->
                val q = input.text.toString().trim()
                if (q.isNotEmpty()) performInPlaceSearch(q)
            }
            // v: 재억 요청(2026-08-22) - 운전 중엔 타이핑보다 음성이 편해서, 특히 경유지
            // 추가할 때 유용함. 이미 있던 상단바 음성검색(startVoiceSearch)을 그대로 재사용 -
            // 인식 결과는 voiceSearchLauncher -> performInPlaceSearch -> pickEntry() 순으로
            // 흘러가는데, pickEntry()가 이미 pendingWaypointAddition 플래그를 보고 "경유지
            // 추가"인지 "목적지 변경"인지 알아서 구분하므로 별도 처리 없이도 맞게 동작함. #문제시 원복
            .setNeutralButton("음성으로 검색") { _, _ -> startVoiceSearch() }
            .setNegativeButton("취소", null)
            .show()
    }

    // v1.7: 검색결과 목록을 다크 테마 다이얼로그로 보여주고 사용자가 직접 고르게 함. #문제시 원복
    // v12.9: MapActivity와 동일 - "· N분" 부분을 SpannableString으로 색을 다르게
    // 입힐 수 있도록 String -> CharSequence로 확장(재억 요청). #문제시 원복
    private fun darkTextAdapter(items: List<CharSequence>): android.widget.ArrayAdapter<CharSequence> {
        return object : android.widget.ArrayAdapter<CharSequence>(
            this, android.R.layout.simple_list_item_1, android.R.id.text1, items
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val tv = view.findViewById<android.widget.TextView>(android.R.id.text1)
                tv.setTextColor(android.graphics.Color.WHITE)
                tv.setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
                tv.setPadding(24, 20, 24, 20)
                return view
            }
        }
    }

    // v13.2-2: MapActivity와 동일 - 즐겨찾기/집/회사 등록된 칸을 눌렀을 때도 검색 결과와
    // 동일하게 "추천/고속도로우선/무료도로우선" 경로 선택 팝업이 뜨도록 함(재억 지적). #문제시 원복
    // v13.6: MapActivity와 동일 - 무료도로 우선이 추천 경로랑 거리가 똑같으면 톨게이트가
    // 아예 없는 구간으로 보고 안 물어보고 바로 감(재억 요청). #문제시 원복
    private fun showRoutePriorityDialog(picked: HistoryEntry, saveToSlot: String? = null) {
        // v: (2026-09-05) 경유지 추가 모드면 이 선택창을 띄우지 않고 바로
        // addWaypointToActiveGuidance로 넘김 - 그쪽이 자체적으로 경로 방식을 물어보므로
        // 여기서도 물으면 두 번 묻게 됨. #문제시 원복
        if (pendingWaypointAddition && saveToSlot == null) {
            pendingWaypointAddition = false
            addWaypointToActiveGuidance(picked)
            return
        }
        // v14.2: MapActivity와 동일 - 배경 계산 그만두기(재억 아이디어). #문제시 원복
        etaQueueGeneration++
        // v19.3.72: 신규기능(재억 요청 2026-09-18) - 팝업에서 방식을 고르기 전에, 지금
        // 고른 목적지가 실제로 지도 어디인지 먼저 핀으로 보여줌. saveToSlot으로 "이동방식만
        // 저장"하러 들어온 경우는 실제로 그 목적지로 가는 게 아니라서 핀을 찍지 않음. #문제시 원복
        if (saveToSlot == null) {
            showDestinationPinOnMap(picked.lat, picked.lon)
        }
        val optionLabels = RouteChoiceOptions.labels
        val optionPriorities = RouteChoiceOptions.priorities
        val optionAvoidOptions = RouteChoiceOptions.avoidOptions

        // v: 재억 요청(2026-08-22) - 티맵 화면(MapActivity)에만 있던 "저장된 방식 삭제"
        // 메뉴를 카카오 화면에도 이식. #문제시 원복
        val CLEAR_OPTION_INDEX = RouteChoiceOptions.count

        fun goDirectly(index: Int) {
            if (index == CLEAR_OPTION_INDEX && saveToSlot != null) {
                QuickSlotStore.clearRoutePreference(this, saveToSlot)
                Toast.makeText(this, "${picked.name}의 저장된 이동방식을 지웠어요.", Toast.LENGTH_SHORT).show()
                return
            }
            if (saveToSlot != null) {
                // v13.10: MapActivity와 동일 - "이동방식 저장" 메뉴로 들어왔을 때도 옵션을
                // 고르면 저장과 동시에 무조건 경로 변경까지 실행해버렸음(재억 지적). 저장
                // 전용으로 들어온 경우엔 저장만 하고 지금 안내 중인 경로는 그대로 둠. #문제시 원복
                QuickSlotStore.updateRoutePreference(this, saveToSlot, optionPriorities[index].name, optionAvoidOptions[index])
                Toast.makeText(this, "${picked.name}의 이동방식을 \"${optionLabels[index]}\"로 저장했어요.", Toast.LENGTH_SHORT).show()
                return
            }
            applyRouteOption(optionPriorities[index], optionAvoidOptions[index])
            KakaoRouteDataRepository.reset()
            resolveCurrentPositionThenRequestRoute(picked.name, picked.lat, picked.lon, finishOnFailure = false)
        }

        // v19.3.72: showRouteChoicePanel이 전체 경로를 보여주려면 출발점 좌표가 필요해서,
        // 원래 뒤쪽에 있던 이 계산을 goDirectly 정의 뒤 · showPickerWithResults 정의
        // 앞으로 끌어올림(코틀린은 지역함수가 자기보다 뒤에 선언된 지역변수를 못 씀). #문제시 원복
        val (curLat, curLon) = resolveCurrentWgs84LatLonForSearch()
        if (curLat == null || curLon == null) {
            clearDestinationPin()
            goDirectly(0)
            return
        }

        // v19.3.72: 신규기능(재억 요청 2026-09-18) - "목적지 고르면 지도+경로선택이 한
        // 화면에 같이 보여야 하는 거 아니냐"는 요청으로, 실제로 그 목적지로 안내를 시작하는
        // 경우(saveToSlot == null)는 목록형 AlertDialog 대신 지도 위에 뜨는 반투명 카드형
        // 패널로 대체함. v19.3.72(2차): 처음엔 3초 계산을 기다렸다가 한 번에 그렸는데,
        // 거리가 먼 목적지(평택-대구 등, 재억 실기기 제보)는 3초 안에 계산이 다 안 끝나서
        // "계산 실패"로 굳어버리는 문제가 있었음 - 이제 계산을 기다리지 않고 핀 찍자마자
        // 바로 패널부터 띄우고, 옵션별 계산 결과가 하나씩 도착할 때마다 그 값으로 다시
        // 그려서(refresh) 자연스럽게 채워지게 함. "이동방식 저장" 전용 메뉴(saveToSlot != null,
        // 실제로 안 감)는 굳이 지도가 필요없어서 기존 목록 팝업을 그대로 둠. #문제시 원복
        if (saveToSlot == null) {
            val minutesArr = arrayOfNulls<Int>(RouteChoiceOptions.count)
            val costArr = arrayOfNulls<Int>(RouteChoiceOptions.count)
            val routesArr = arrayOfNulls<Any>(RouteChoiceOptions.count)
            val refresh = showRouteChoicePanel(picked, optionLabels, minutesArr, costArr, routesArr, curLat, curLon, ::goDirectly)
            computeNaverOptions(
                LonLat(curLon, curLat), LonLat(picked.lon, picked.lat),
                activeWaypoints.map { LonLat(it.lon, it.lat) }, minutesArr, costArr, routesArr, refresh
            )
            return
        }

        fun showPickerWithResults(minutesArr: Array<Int?>, costArr: Array<Int?> = arrayOfNulls(RouteChoiceOptions.count)) {
            val labels = optionLabels.mapIndexed { i, label ->
                // v: 신규기능(예상 통행료 표시, 재억 요청 2026-09-15) - 통행료 값을 못 구했으면
                // (SDK가 안 주거나 무료도로라 0원인 경우 포함) 그냥 시간만 보여주고 생략. #문제시 원복
                val etaLine = SearchRanking.formatEtaMinutes(minutesArr[i]) ?: "계산 실패"
                val costLine = costArr.getOrNull(i)?.takeIf { it > 0 }?.let { " · 통행료 %,d원".format(it) } ?: ""
                "$label\n$etaLine$costLine"
            }.toMutableList()
            // v: 재억 요청(2026-08-22) - MapActivity와 동일 - "경로 방식 변경" 메뉴로
            // 들어왔을 때만 맨 아래에 "저장된 방식 삭제" 추가. #문제시 원복
            labels.add("저장된 방식 삭제")
            val listView = android.widget.ListView(this)
            val adapter = darkTextAdapter(ArrayList<CharSequence>(labels))
            listView.adapter = adapter
            val routeDialog = android.app.AlertDialog.Builder(this, R.style.RoundedDialogTheme)
                .setTitle("${picked.name}\n어떻게 갈까요?")
                .setView(listView)
                .setNegativeButton("취소", null)
                .create()
            listView.setOnItemClickListener { _, _, position, _ ->
                routeDialog.dismiss()
                goDirectly(position)
            }
            routeDialog.show()
            routeDialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_212121_rounded)
        }

        val minutesArr = arrayOfNulls<Int>(RouteChoiceOptions.count)
        val distArr = arrayOfNulls<Int>(RouteChoiceOptions.count)
        val costArr = arrayOfNulls<Int>(RouteChoiceOptions.count)
        var receivedCount = 0
        // v: 재억 제보(2026-08-31, 실기기로 확인 - "길안내 중 경유지 추가할 때 추천/고속/
        // 무료 목록이 안 뜨고 취소 버튼만 덩그러니 있다") - 이 선택창은 3개 옵션의 예상
        // 시간 계산이 전부(receivedCount==3) 끝나야만 목록을 채워 보여주는 구조였음.
        // 이미 안내 중인 상태에서 경유지를 추가할 땐 이 계산이 실패하거나 3개를 다 못
        // 채우는 경우가 있어서, showPickerWithResults가 아예 호출되지 않고 제목/취소만
        // 있는 빈 껍데기가 떴던 것. 재억님 지적대로 "A는 고속, 경유지 B는 무료도로"처럼
        // 경유지마다 이동방식을 새로 고를 수 있어야 의미가 있으므로, 계산이 실패하거나
        // 3초 안에 안 끝나도 목록은 무조건 뜨도록 안전장치 추가(예상 시간만 "계산 실패"로
        // 표시되고 선택 자체는 정상 동작). #문제시 원복
        var pickerShown = false
        fun showPickerOnce() {
            if (pickerShown) return
            pickerShown = true
            showPickerWithResults(minutesArr, costArr)
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!pickerShown) {
                NavLogger.d(this, "[경로선택] ETA 계산이 3초 안에 안 끝나 목록을 먼저 표시(받은개수=$receivedCount)")
                showPickerOnce()
            }
        }, 3000L)
        // v13.6: MapActivity와 동일 - "출발-도착 연결"을 한 번만 하고 그 위에서 3개
        // 우선순위만 각각 계산(재억 지적 - 계산 느림). #문제시 원복
        NaverRouteOptions.compute(this, LonLat(curLon, curLat), LonLat(picked.lon, picked.lat)) { index, route, supported ->
            if (isFinishing || isDestroyed) return@compute
            minutesArr[index] = when {
                !supported -> null
                route == null -> null
                else -> Math.round(route.durationMs / 60000.0).toInt().coerceAtLeast(1)
            }
            distArr[index] = route?.distanceMeters
            costArr[index] = route?.tollFare
            receivedCount++
            if (receivedCount == RouteChoiceOptions.count) {
                showPickerOnce()
            }
        }
    }

    // v12.9: MapActivity와 동일 - "· N분"(소요시간) 부분만 파란색으로 강조. #문제시 원복
    private fun highlightEta(label: String, etaText: String?): CharSequence {
        if (etaText.isNullOrBlank()) return label
        val marker = " · $etaText"
        val start = label.lastIndexOf(marker)
        if (start < 0) return label
        val spannable = android.text.SpannableString(label)
        spannable.setSpan(
            android.text.style.ForegroundColorSpan(AppAccent.color(this@NaverNaviActivity)),
            start + 3, start + marker.length,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return spannable
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
        // 안내 엔진이 받고 있는 최신 위치를 우선 쓰고, 없으면 최근 2분 안의 위치만 쓴다(몇 주 전 위치는 버림).
        val loc = NaverNavigator.currentFix() ?: com.tmap.nda.naver.FreshLocation.lastFresh(this)
        return if (loc != null) Pair(loc.latitude, loc.longitude) else Pair(null, null)
    }

    private fun performInPlaceSearch(query: String, page: Int = 1, accumulatedDocuments: MutableList<JSONObject> = mutableListOf()) {
        val restKey = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
            .getString("kakao_rest_api_key", "") ?: ""
        if (restKey.isBlank()) {
            Toast.makeText(this, "검색을 시작할 수 없어요. 앱을 다시 시작해줘.", Toast.LENGTH_LONG).show()
            return
        }
        if (page == 1) {
            Toast.makeText(this, "검색 중: $query", Toast.LENGTH_SHORT).show()
        }
        // v9.5: 재억 요청 - MapActivity(Tmap화면)와 동일한 이유로 현재 위치 기준
        // 거리순 정렬 추가. 위치를 못 구하면 좌표 없이 기존처럼 검색. #문제시 원복
        // v9.9: radius=20000이 검색 "범위"까지 20km로 제한해버려서 먼 지역이
        // 아예 검색 결과에서 빠지는 문제 발견. radius 제거하여 전국 대상으로 검색.
        // v9.9-2: sort=distance만 쓰면 이름 일치 여부와 무관하게 가까운 순으로만 나열돼서
        // 정작 검색한 이름의 장소가 목록 아래로 밀리는 문제(재억 지적) - sort=accuracy로
        // 바꾸고, 이름 일치+거리 기반 재정렬은 아래에서 처리.
        val (curLat, curLon) = resolveCurrentWgs84LatLonForSearch()

        // v10.9-4: MapActivity와 동일 - "편의점", "주유소"처럼 종류로 찾는 검색은 카카오
        // 종류 전용 검색으로 대체(재억 요청). #문제시 원복
        if (page == 1) {
            val categoryCode = SearchRanking.categoryGroupCodeFor(query)
            if (categoryCode != null && curLat != null && curLon != null) {
                performInPlaceCategorySearch(categoryCode, restKey, curLat, curLon)
                return
            }
        }

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
        searchHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                NavLogger.e(this@NaverNaviActivity, "인라인 재검색 실패: ${e.message}")
                runOnUiThread { Toast.makeText(this@NaverNaviActivity, "검색 실패: ${e.message}", Toast.LENGTH_SHORT).show() }
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        runOnUiThread { Toast.makeText(this@NaverNaviActivity, "검색 실패(${it.code})", Toast.LENGTH_SHORT).show() }
                        return@use
                    }
                    val json = JSONObject(it.body?.string() ?: "{}")
                    val documents = json.optJSONArray("documents")
                    if ((documents == null || documents.length() == 0) && accumulatedDocuments.isEmpty()) {
                        NavLogger.d(this@NaverNaviActivity, "카카오 키워드검색 결과 없음, 주소검색으로 재시도: query=$query")
                        performAddressSearchFallback(query, restKey)
                        return@use
                    }

                    // v10.9-4: MapActivity와 동일 - 카카오가 한 번에 최대 15건만 주는데,
                    // 원하는 곳이 16번째 이후 순위면 후보에도 못 들어오던 문제(재억 지적) -
                    // 더 있으면 최대 4쪽(최대 60건)까지 자동으로 더 받아와서 합침. #문제시 원복
                    if (documents != null) {
                        for (i in 0 until documents.length()) {
                            accumulatedDocuments.add(documents.getJSONObject(i))
                        }
                    }
                    val meta = json.optJSONObject("meta")
                    val isEnd = meta?.optBoolean("is_end", true) ?: true
                    if (!isEnd && page < 4) {
                        performInPlaceSearch(query, page + 1, accumulatedDocuments)
                        return@use
                    }
                    // v2.5: 이력은 검색 시도가 아니라 실제로 고른 결과에만 저장(아래 클릭 시). #문제시 원복
                    // v1.8: "성심당 검색하면 성심당 본점/대전역점/케익부띠끄/롯데백화점점 처럼
                    // 여러 지점이 나와야 하는데 documents[0]으로 바로 안내가 시작됨" 지적(3번) -
                    // 결과 목록을 다이얼로그로 보여주고 사용자가 직접 골라서 시작하도록 변경. #문제시 원복
                    // v10.9-4: MapActivity와 동일 - 정렬 규칙은 SearchRanking.kt로 모아서
                    // 공통으로 씀(완전일치/부속시설류/순서만지키며포함/그외 4단계 + DT 우선).
                    // #문제시 원복
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
                    NavLogger.d(this@NaverNaviActivity, "인라인 재검색 결과 ${hits.size}건: query=$query")
                    runOnUiThread { showInPlaceSearchResultsDialog(hits) }
                }
            }
        })
    }

    // v10.9-4: MapActivity와 동일 - 카카오 종류 전용 검색. 이미 sort=distance로 가까운
    // 순 정렬돼서 오므로 재정렬 없이 그대로 보여줌. #문제시 원복
    private fun performInPlaceCategorySearch(categoryCode: String, restKey: String, lat: Double, lon: Double) {
        val url = "https://dapi.kakao.com/v2/local/search/category.json?category_group_code=$categoryCode" +
            "&x=$lon&y=$lat&radius=20000&sort=distance"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "KakaoAK $restKey")
            .build()
        searchHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                NavLogger.e(this@NaverNaviActivity, "카카오 종류검색 요청 실패: ${e.message}")
                runOnUiThread { Toast.makeText(this@NaverNaviActivity, "검색 실패: ${e.message}", Toast.LENGTH_SHORT).show() }
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        runOnUiThread { Toast.makeText(this@NaverNaviActivity, "검색 실패(${it.code})", Toast.LENGTH_SHORT).show() }
                        return@use
                    }
                    val json = JSONObject(it.body?.string() ?: "{}")
                    val documents = json.optJSONArray("documents")
                    if (documents == null || documents.length() == 0) {
                        runOnUiThread { Toast.makeText(this@NaverNaviActivity, "검색 결과 없음", Toast.LENGTH_SHORT).show() }
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
                    NavLogger.d(this@NaverNaviActivity, "카카오 종류검색 결과 ${hits.size}건: category=$categoryCode")
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
                NavLogger.e(this@NaverNaviActivity, "카카오 주소검색 요청 실패: ${e.message}")
                runOnUiThread { Toast.makeText(this@NaverNaviActivity, "검색 결과 없음: $query", Toast.LENGTH_SHORT).show() }
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        NavLogger.e(this@NaverNaviActivity, "카카오 주소검색 실패 code=${it.code}")
                        runOnUiThread { Toast.makeText(this@NaverNaviActivity, "검색 결과 없음: $query", Toast.LENGTH_SHORT).show() }
                        return@use
                    }
                    val json = JSONObject(it.body?.string() ?: "{}")
                    val documents = json.optJSONArray("documents")
                    if (documents == null || documents.length() == 0) {
                        NavLogger.d(this@NaverNaviActivity, "카카오 주소검색도 결과 없음: query=$query")
                        runOnUiThread { Toast.makeText(this@NaverNaviActivity, "검색 결과 없음: $query", Toast.LENGTH_SHORT).show() }
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
                    NavLogger.d(this@NaverNaviActivity, "카카오 주소검색 결과 ${hits.size}건: query=$query")
                    runOnUiThread { showInPlaceSearchResultsDialog(hits) }
                }
            }
        })
    }

    // v11.2: MapActivity와 동일 - 10개씩 페이지로 끊어서 보여주고 "이전/다음/취소" 버튼으로
    // 넘기도록 함(재억 요청). #문제시 원복
    private fun showInPlaceSearchResultsDialog(hits: List<HistoryEntry>) {
        val pageSize = 10
        var currentPage = 0
        val lastPage = (hits.size - 1) / pageSize

        val listView = android.widget.ListView(this@NaverNaviActivity)
        listView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        listView.divider = android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#333333"))
        listView.dividerHeight = 1

        // v19.3.79: 재억 요청 - 검색결과 목록도 카드 형식으로. 이전/다음은 눌러도 안 닫힘. #문제시 원복
        val pickDialog = PopupCard.CardDialog(this@NaverNaviActivity, binding.root as ViewGroup).apply {
            setContent(listView)
            setButton(PopupCard.CardDialog.BUTTON_NEGATIVE, "취소")
            setButton(PopupCard.CardDialog.BUTTON_NEUTRAL, "이전", autoClose = false)
            setButton(PopupCard.CardDialog.BUTTON_POSITIVE, "다음", autoClose = false)
        }

        fun pickEntry(picked: HistoryEntry) {
            pickDialog.dismiss()
            // v: 재억 요청(2026-08-22) - 경유지 추가 모드였으면 목적지를 갈아끼우지 않고
            // 경유지로만 추가. 다른 분기(즐겨찾기 등록 등)보다 먼저 체크. #문제시 원복
            if (pendingWaypointAddition) {
                pendingWaypointAddition = false
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
                addWaypointToActiveGuidance(picked)
                return
            }
            // v11.3: MapActivity와 동일 - 집/회사/즐겨찾기 칸 등록 목적이었으면 저장만 하고
            // 안내는 시작하지 않음(재억 지적 - 등록할 땐 안내까지 필요 없음). #문제시 원복
            val registeringSlot = pendingQuickSlotRegistration
            if (registeringSlot != null) {
                QuickSlotStore.save(this@NaverNaviActivity, registeringSlot, picked)
                pendingQuickSlotRegistration = null
                Toast.makeText(this@NaverNaviActivity, "'${picked.name}' 등록 완료", Toast.LENGTH_SHORT).show()
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
            SearchHistoryStore.save(this@NaverNaviActivity, picked)
            renderRecentDestinationsPanel()
            // v: 재억 지적(2026-08-28) - "추천/고속도로/무료도로 고르는 팝업이 왜 안 뜨고
            // 바로 추천 경로로 안내가 시작되냐" - 티맵 화면(MapActivity)에서 검색했을 땐
            // showRoutePriorityDialog를 거치는데, 길안내 화면(KakaoNaviActivity)에서 직접
            // 검색했을 땐(주행 중 목적지 변경 등) 이 팝업 함수 자체는 있으면서도 정작
            // 여기서 안 부르고 곧바로 경로 요청을 해버리고 있었음. MapActivity와 동일하게
            // 팝업을 거치도록 수정. #문제시 원복
            showRoutePriorityDialog(picked)
        }

        fun renderPage() {
            val start = currentPage * pageSize
            val end = minOf(start + pageSize, hits.size)
            val pageHits = hits.subList(start, end)
            // v11.1: MapActivity와 동일 - 검색 결과 각 항목 옆에 거리도 같이 보여줌(재억 요청).
            // v12.7: MapActivity와 동일 - 거리 옆에 소요시간도 같이 표시(재억 요청).
            // 지금 페이지에 보이는 것만 계산. #문제시 원복
            fun buildLabel(h: HistoryEntry, etaText: String?): String {
                val distanceText = SearchRanking.formatDistance(h.distanceMeters)
                val distanceAndEta = listOfNotNull(distanceText, etaText).joinToString(" · ")
                val nameWithExtra = if (distanceAndEta.isNotEmpty()) "${h.name} · $distanceAndEta" else h.name
                return if (h.addr.isNotBlank()) "$nameWithExtra\n${h.addr}" else nameWithExtra
            }
            val currentLabels: MutableList<CharSequence> = pageHits.map { buildLabel(it, "검색 중") as CharSequence }.toMutableList()
            // v12.8: MapActivity와 동일 - ArrayAdapter가 원본 리스트를 그대로 참조해서
            // adapter.clear()가 currentLabels까지 같이 비워버려 크래시나던 문제(재억
            // 지적). 복사본(toList())을 넘겨서 분리. #문제시 원복
            // v13.0-2: MapActivity와 동일 - .toList()가 0/1개일 때 수정불가 리스트를
            // 돌려줘서 크래시나던 문제(재억 지적). ArrayList(...)로 감싸서 방지. #문제시 원복
            val adapter = darkTextAdapter(ArrayList(currentLabels))
            listView.adapter = adapter
            listView.setOnItemClickListener { _, _, position, _ -> pickEntry(pageHits[position]) }
            // v: 재억 요청(2026-08-22) - 검색 결과에서도 길게 누르면 목적지를 갈아끼우지
            // 않고 바로 경유지로 추가. pendingWaypointAddition 모드로 들어와 있었으면
            // (이미 경유지 검색 중이었으면) 짧게 눌러도 되니 굳이 롱프레스 안내 불필요. #문제시 원복
            listView.setOnItemLongClickListener { _, _, position, _ ->
                val target = pageHits[position]
                pickDialog.dismiss()
                addWaypointToActiveGuidance(target)
                true
            }
            pickDialog.setTitle("검색 결과 ${hits.size}건 (${start + 1}-$end) - 탭:목적지 선택 / 길게누르기:경유지 추가")
            pickDialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.isEnabled = currentPage > 0
            pickDialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.isEnabled = currentPage < lastPage

            val (curLat, curLon) = resolveCurrentWgs84LatLonForSearch()
            if (curLat != null && curLon != null) {
                // v14.1: MapActivity와 동일 - 검색 결과 목록도 즐겨찾기/이력과 같은
                // 문제였음(재억 지적, 로그로 재확인). 동시 최대 2개까지만 요청. #문제시 원복
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
                                NavLogger.e(this@NaverNaviActivity, "[검색결과소요시간] 8초 타임아웃 - 포기하고 다음으로: ${h.name}")
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
                        timeoutHandler.postDelayed(timeoutRunnable, 8000L)
                        NaverEta.computeEta(this@NaverNaviActivity, curLat, curLon, h.lat, h.lon) { minutes, _ ->
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

        pickDialog.show()
        pickDialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            if (currentPage > 0) {
                currentPage--
                renderPage()
            }
        }
        pickDialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            if (currentPage < lastPage) {
                currentPage++
                renderPage()
            }
        }
        renderPage()
    }

    // v12.9: 안내 이어가기 - 정상 도착(SDK가 스스로 안내종료를 알려줄 때)일 때만
    // 저장된 이어가기 목적지를 지움. 사용자가 "안내종료" 버튼으로 중간에 끈 경우는
    // 계속 남겨둬서 다음에 앱 열 때 "이어서 안내할까요?" 물어볼 수 있게 함(재억 요청). #문제시 원복
    // v12.9-2: 재억 지적 - "안내종료" 버튼으로 끈 것도 명백히 "그만 가겠다"는 의사라서,
    // 정상 도착이든 수동 종료든 상관없이 finishGuidance()가 불리는 모든 경우에 이어가기
    // 저장값을 지움. 앱이 강제종료/업데이트로 finishGuidance()를 거치지 않고 죽는
    // 경우에만 저장값이 남아서 다음에 "이어서 안내할까요?"가 뜸(이게 진짜 의도한
    // 시나리오). #문제시 원복
    // v14.4: 재억 요청 - 재안내(경로 변경) 시 3초간 문구를 보여줬다 자동으로 감춤.
    // 배너를 여러 번 빠르게 다시 띄우면(연속 재안내) 먼저 걸어둔 숨김 타이머가
    // 나중 타이머보다 먼저 실행돼 배너가 일찍 꺼져버릴 수 있어서, 이전 타이머는
    // 취소하고 새로 하나만 건다. #문제시 원복
    private var rerouteBannerHideRunnable: Runnable? = null
    private fun showRerouteBanner() {
        val banner = findViewById<android.widget.TextView?>(
            resources.getIdentifier("tvRerouteBanner", "id", packageName)
        ) ?: return
        runOnUiThread {
            rerouteBannerHideRunnable?.let { banner.removeCallbacks(it) }
            banner.visibility = View.VISIBLE
            val hideRunnable = Runnable { banner.visibility = View.GONE }
            rerouteBannerHideRunnable = hideRunnable
            banner.postDelayed(hideRunnable, 3000L)
        }
    }

    private var finishGuidanceRunning = false
    private fun finishGuidance() {
        if (finishGuidanceRunning) return
        finishGuidanceRunning = true
        ResumeGuidanceStore.clear(this)
        KakaoRouteDataRepository.reset()
        cancelNavNotification()
        try {
            NaverNavigator.stop()
        } catch (e: Exception) {
            NavLogger.e(this, "네이버 안내 중지 예외: ${e.message}")
        }
        if (::guideOverlay.isInitialized) guideOverlay.hide()
        if (::naverMap.isInitialized) naverMap.clearAllRoutes()
        if (!isFinishing) finish()
    }

    override fun onStart() {
        super.onStart()
        if (::naverMap.isInitialized) naverMap.onStart()
        NavLogger.d(this, "[lifecycle] onStart")
        NavOverlayManager.activityStarted()
    }

    // v2.4: MapActivity와 동일한 이유 - mute 순간에만 볼륨을 캡처하던 구조라 사용자가
    // mute 없이 볼륨만 바꾸면 저장이 안 되고 예전 값으로 복원되던 문제. 실제 주행 중엔
    // 이 화면(카카오 안내)이 주로 떠있으므로 여기서도 실시간 캡처가 특히 중요함. #문제시 원복
    private val volumeChangeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val muted = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                .getBoolean("tmap_muted", false)
            if (!muted) {
                VolumeHelper.captureCurrentVolumePercent(this@NaverNaviActivity)
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
    private var topBarDrag: PanelDragHelper.TopBarLongPressDrag? = null
    private var topBarSlotLabelListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null


    // 핀치 줌: 엔미러 같은 환경에선 두 손가락 터치가 자주 끊겨서 지도 내장 핀치로는 줌이 조금씩만 먹혔다.
    // 그래서 지도 내장 줌 제스처는 끄고, 두 손가락 사이 거리의 비율을 직접 계산해 줌에 반영한다.
    // 매번 "핀치 시작 시점"의 거리·줌을 기준으로 계산하므로 오차가 쌓여 줌이 튀지 않는다. 손을 뗀 뒤에도
    // 일정 시간 줌을 유지한다(지도 컨트롤러의 zoomHoldUntil). #문제시 원복
    private var pinchLastSpan = 0f
    private var pinchStartSpan = 0f
    private var pinchStartZoom = 0.0
    private var pinchZoomGestureDisabled = false

    private fun pinchSpan(ev: android.view.MotionEvent): Float {
        if (ev.pointerCount < 2) return 0f
        val dx = ev.getX(0) - ev.getX(1)
        val dy = ev.getY(0) - ev.getY(1)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun handlePinch(ev: android.view.MotionEvent) {
        val m = naverMap.map ?: return
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount == 2) {
                    pinchLastSpan = pinchSpan(ev)
                    pinchStartSpan = pinchLastSpan
                    pinchStartZoom = m.cameraPosition.zoom
                    naverMap.holdZoom()
                }
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (ev.pointerCount < 2 || pinchLastSpan < 1f) return
                val span = pinchSpan(ev)
                if (span < 1f) return
                // 손 떨림 수준(4px 미만)의 변화는 무시
                if (kotlin.math.abs(span - pinchLastSpan) < 4f) return
                pinchLastSpan = span
                if (pinchStartSpan < 1f) { pinchStartSpan = span; pinchStartZoom = m.cameraPosition.zoom }
                val factor = span / pinchStartSpan
                // 네이버 지도의 줌은 2배 확대 = +1 인 로그 단위
                naverMap.setZoom((pinchStartZoom + Math.log(factor.toDouble()) / Math.log(2.0)).coerceIn(5.0, 20.0))
                naverMap.holdZoom()
            }
            android.view.MotionEvent.ACTION_POINTER_UP -> {
                if (ev.pointerCount == 2) {
                    pinchLastSpan = 0f
                    naverMap.holdZoom()
                }
            }
        }
    }

    private fun ensurePinchZoomBridge() {
        if (pinchZoomGestureDisabled) return
        naverMap.whenReady { it.uiSettings.isZoomGesturesEnabled = false }
        pinchZoomGestureDisabled = true
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        // v: 재억 제보(2026-09-13, 크래시 로그) - 화면 회전으로 액티비티가 재구성되는
        // 타이밍에 이전 인스턴스로 터치 이벤트가 마저 전달되면서 binding이 아직
        // 초기화되기 전에 접근해 UninitializedPropertyAccessException으로 강제종료됨.
        // 그 타이밍의 터치는 어차피 곧 사라질 화면에 대한 것이니 그냥 무시. #문제시 원복
        if (!::binding.isInitialized) return super.dispatchTouchEvent(ev)
        ensurePinchZoomBridge()
        if (pinchZoomGestureDisabled) {
            handlePinch(ev)
            if (ev.pointerCount >= 2) return true
        }
        if (topBarDrag?.dispatch(ev) { super@NaverNaviActivity.dispatchTouchEvent(it) } == true) return true
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


    // 재억 요청(2026-10-05): 길안내 중 지도 중심(내 차 위치)을 화면 가운데가 아니라 운전자 쪽(왼쪽)으로 약간 치우치게.
    // 카카오 SDK가 정한 위치(왼쪽 정보패널 오른쪽 영역의 가운데, 약 0.56)를 카메라 앵커로 덮어씀. 가로 화면·분할화면 아님·
    // 길안내 중일 때만 적용하고, SDK가 앵커를 되돌리면 1초 안에 다시 맞춤. #문제시 원복(이 블록과 onResume/onPause 호출부만 지우면 됨)
    // 2026-10-09 재억 요청: 0.45 -> 0.32 (더 운전자 쪽으로)
    private val DRIVER_SIDE_ANCHOR_X = 0.32f
    // 재억 제보: 1초마다 확인하면 SDK가 되돌린 뒤 0.6초쯤 "갔다가 빠졌다가" 보임 -> 매 프레임 확인해서 한 프레임 안에 바로 되돌림.
    private val driverAnchorFrame = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            applyDriverSideAnchor()
            android.view.Choreographer.getInstance().postFrameCallback(this)
        }
    }
    private fun applyDriverSideAnchor() {
        if (isFinishing || isDestroyed || !::naverMap.isInitialized) return
        // 길안내 중 + 가로 화면 + 전체 화면일 때만 차를 운전자 쪽(왼쪽)으로 치우쳐 보이게 한다.
        val active = isGuidanceRunningNow() && activeRouteChoicePanel == null && !parkedActive &&
            resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
            !(android.os.Build.VERSION.SDK_INT >= 24 && isInMultiWindowMode)
        naverMap.anchorXFraction = if (active) DRIVER_SIDE_ANCHOR_X else null
    }

    override fun onResume() {
        android.view.Choreographer.getInstance().removeFrameCallback(driverAnchorFrame); android.view.Choreographer.getInstance().postFrameCallback(driverAnchorFrame)
        super.onResume()
        if (::naverMap.isInitialized) naverMap.onResume()
        NavLogger.d(this, "[lifecycle] onResume")
        // v: 재억 제보(2026-09-02) - 티맵 화면에서 즐겨찾기를 눌러 "경유지 추가"를 고른 경우,
        // 그쪽엔 경로를 다시 짜는 코드가 없어서 요청만 남기고 화면을 닫음. 이 화면이 다시
        // 올라오는 지금 그걸 집어서 실제 경유지 추가를 수행. #문제시 원복
        // v: (2026-09-05) v19.3.11에서 여기에 showRoutePriorityDialog를 한 번 더 띄우도록
        // 했었는데, addWaypointToActiveGuidance가 이미 자체적으로 경로 방식을 물어보므로
        // 중복이라 되돌림. 목록이 안 보이던 진짜 원인은 그쪽의 setMessage+setItems
        // 동시 사용 문제였음. #문제시 원복
        PendingWaypointRequest.take()?.let { pending ->
            NavLogger.d(this, "[경유지추가] 티맵 화면에서 넘어온 요청 처리: ${pending.name}")
            addWaypointToActiveGuidance(pending)
        }
        // v: 재억 제보(2026-08-26) - "lateinit property binding has not been initialized"
        // 크래시 발생. KNSDK 초기화(비동기)가 끝나서 setupContentAndStart()가 binding을
        // 실제로 만들기 전에 onResume이 먼저 호출될 수 있는데, 이 코드가 그 순간 즉시
        // binding에 접근해서 죽었음. isInitialized로 방어. #문제시 원복
        if (::binding.isInitialized) {
            val showWaypointButton = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                .getBoolean("show_waypoint_button", true)
            val showCategoryButton = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                .getBoolean("show_category_button", true)
            val showCancelWaypointButton = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                .getBoolean("show_cancel_waypoint_button", true)
            binding.btnAddWaypoint?.visibility = if (showWaypointButton) View.VISIBLE else View.GONE
            binding.btnNearbyCategory?.visibility = if (showCategoryButton) View.VISIBLE else View.GONE
            binding.btnFavorites?.visibility = if (getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                    .getBoolean("show_favorites_button", true)) View.VISIBLE else View.GONE
            syncMenuButtonDetach()
            binding.btnCancelWaypoint?.visibility = if (showCancelWaypointButton && activeWaypoints.isNotEmpty()) View.VISIBLE else View.GONE
            if (showCancelWaypointButton && activeWaypoints.isNotEmpty()) {
                binding.btnCancelWaypoint?.post {
                    QuickIconGrid.restore(this, binding.btnCancelWaypoint!!)
                }
            }
        }
        // v: 재억 요청(2026-08-22) - MapActivity와 동일한 패턴. 카카오 화면이 앞으로
        // 오는 순간 최신 차선정보를 바로 그려주고, 이후 새 데이터가 들어올 때마다
        // 곧바로 반영되도록 "지금 활성화된 화면" 자리에 이 화면을 등록. #문제시 원복
        LaneSignalRepository.activeRenderer = {
            renderLaneSignalBar(this@NaverNaviActivity, binding.llLaneSignalBar, binding.llLaneBoxes, binding.tvTrafficLightCountdown, "kakao")
        }
        LaneSignalRepository.notifyChanged()
        AccidentAlertRepository.activeRenderer = {
            renderAlertBanners(this@NaverNaviActivity, binding.llAccidentAlert, binding.tvAccidentAlert, binding.llEmergencyAlert, binding.tvEmergencyAlert)
        }
        EmergencyAlertRepository.activeRenderer = AccidentAlertRepository.activeRenderer
        AccidentAlertRepository.notifyChanged()
        // v: 재억 제보(2026-08-30) - MapActivity와 대칭으로, 이 화면이 다시 보일 때마다
        // (예: 티맵 화면에서 잠깐 설정을 열었다 닫는 등으로 이 화면이 일시정지-재개될
        // 때) 미니플레이어를 이 화면 것으로 재부착. #문제시 원복
        if (::binding.isInitialized) {
            binding.flMiniPlayerContainer?.let { outer ->
                com.tmap.nda.miniplayer.MiniPlayerManager.attach(
                    this, outer, binding.llMiniPlayer!!,
                    binding.ivMiniPlayerArt, binding.tvMiniPlayerTitle, binding.tvMiniPlayerArtist,
                    binding.btnMiniPlayerPlayPause, binding.btnMiniPlayerPrev, binding.btnMiniPlayerNext,
                    binding.btnMiniPlayerConfirm, binding.vMiniPlayerResizeHandle,
                    "llMiniPlayer", resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                )
            }
        }
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
        android.view.Choreographer.getInstance().removeFrameCallback(driverAnchorFrame)
        if (::naverMap.isInitialized) naverMap.onPause()
        super.onPause()
        NavLogger.d(this, "[lifecycle] onPause")
        LaneSignalRepository.activeRenderer = null
        AccidentAlertRepository.activeRenderer = null
        EmergencyAlertRepository.activeRenderer = null
        try {
            unregisterReceiver(volumeChangeReceiver)
        } catch (e: Exception) {
            // 등록 안 된 상태에서 해제 시도하면 예외 - 무시해도 안전
        }
    }

    override fun onStop() {
        if (::naverMap.isInitialized) naverMap.onStop()
        super.onStop()
        NavLogger.d(this, "[lifecycle] onStop")
        NavOverlayManager.activityStopped(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        NavLogger.d(this, "[lifecycle] onWindowFocusChanged hasFocus=$hasFocus")
        // v: 재억 요청(2026-09-03) - 여기서 화면 전체 뷰트리를 통째로 덤프하고 있었는데
        // 한 번에 4.7KB짜리 한 줄이라, 앱을 들락날락할 때마다 로그가 급격히 불어났음
        // (실기기 로그 1MB 중 38KB를 이 네 줄이 차지). 뷰트리 덤프는 길안내 시작 시점
        // 3곳에만 남기고 포커스 전환 때는 생략. #문제시 원복
    }

    // v: 재억 제보(2026-09-03, 사진) - 분할화면을 쓰다가 전체화면으로 돌아오면 지도 안의
    // 글자/표지판이 통째로 확대돼 겹쳐 보이던 문제. 자세한 원인은 MapSurfaceRefresher 참고.
    // #문제시 원복: 아래 두 함수만 지우면 됨
    // 가로용/세로용 배치 파일이 따로라, 실제로 가로<->세로가 바뀐 경우에만 화면을 다시
    // 만들어 올바른 배치를 쓰게 함(자세한 설명은 MapActivity의 같은 함수 주석 참고). #문제시 원복
    private var inflatedOrientation = 0

    // v: 재억 제보(2026-09-04, 사진) - v19.3.4에서 "분할화면일 때는 다시 만들지 않음"으로
    // 바꿨더니, 카카오 SDK가 전체화면 때 만들어둔 큰 배치를 좁은 창에서 그대로 쓰는 바람에
    // 설정 메뉴의 전체경로/다른경로/경로취소 줄이 화면 밖으로 밀려 아예 안 보였음.
    // 다시 만드는 원래 방식으로 되돌리고, 대신 지금 안내 중인 목적지/경로 방식/경유지를
    // 인텐트에 계속 적어둬서 다시 만들어도 같은 안내를 그대로 이어가게 함. #문제시 원복
    // v19.3.33: MapActivity와 동일 - 폴드4 외부화면(Discord 자동 크래시 제보)에서 이 재생성이
    // 몇 초~몇십 초 간격으로 반복되는 게 확인됨(티맵 화면 쪽에서 그 재생성 겹침이
    // "Fragment already added" 크래시까지 일으킴). 카카오 화면은 Fragment를 안 써서 같은
    // 크래시는 안 나지만, 재생성이 겹칠 이유가 없으니 똑같이 디바운스 적용. #문제시 원복
    companion object {
        private var lastOrientationRecreateAtMs = 0L
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation != inflatedOrientation) {
            val now = System.currentTimeMillis()
            val sinceLast = now - lastOrientationRecreateAtMs
            if (sinceLast < 1500) {
                NavLogger.e(this, "[화면방향] 가로<->세로 변경 감지했지만 직전 재생성 후 ${sinceLast}ms밖에 안 지나서 무시함(연속 오탐 의심)")
                return
            }
            lastOrientationRecreateAtMs = now
            NavLogger.d(this, "[화면방향] 가로<->세로가 바뀌어 화면 배치를 다시 만듦")
            recreate()
            return
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        NavLogger.d(this, "[lifecycle] 분할화면 ${if (isInMultiWindowMode) "진입" else "해제"}")
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
    // v: 재억 재제보 - 카카오 화면 자체 경고음(checkOverSpeedWarning, 300~500m/100m 반복
    // "띵띵")은 judgeOverSpeedAlert를 다시 켜서 카카오 SDK가 직접 음성 안내를 하게 되면서
    // 중복이라 지웠음. MapActivity(티맵 화면)에는 동명의 함수가 그대로 남아있음(카카오 SDK가
    // 없는 화면이라 대체 수단이 없어서 유지). #문제시 원복

    // v14.4: GPS 위치가 들어올 때마다(제한 없이, 최대한 빠르게) 매번 새로 메서드를
    // 찾던 것을 한 번만 찾아서 저장해두고 재사용하도록 캐싱. MapActivity.kt의
    // sdkManagerCompanion/getInstanceMethod 캐싱 방식과 동일한 패턴. #문제시 원복

    // 길안내 시작 후 GPS가 처음 잡히기까지 걸린 시간을 재기 위한 기록용(동작에는 영향 없음). #문제시 원복
    private var gpsWatchStartAt = 0L
    private var gpsFirstAnyLogged = false
    private var gpsFirstGoodLogged = false
    private var gpsRejectedBeforeGood = 0

    private fun startRealtimeGpsForwarding() {
        gpsWatchStartAt = android.os.SystemClock.elapsedRealtime()
        gpsFirstAnyLogged = false
        gpsFirstGoodLogged = false
        gpsRejectedBeforeGood = 0
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
            if (!gpsFirstAnyLogged) {
                gpsFirstAnyLogged = true
                NavLogger.d(this, "[GPS시작지연] 구독 후 ${android.os.SystemClock.elapsedRealtime() - gpsWatchStartAt}ms에 첫 위치 수신: " +
                    "provider=${location.provider} accuracy=${if (location.hasAccuracy()) location.accuracy else -1f}m")
            }
            if (location.provider != LocationManager.GPS_PROVIDER) {
                // 나중에 카메라/경로 오탐 분석용으로는 남겨두되, 매번 찍히면 로그가
                // 금방 커지니 몇 초에 한 번만 기록. #문제시 원복
                NavLogger.dThrottled(this, "gps_provider_ignored", 5000L, "[GPS] provider=${location.provider} 무시(GPS_PROVIDER만 사용)")
                return
            }
            if (location.hasAccuracy() && location.accuracy > 50f) {
                if (!gpsFirstGoodLogged) gpsRejectedBeforeGood++
                NavLogger.dThrottled(this, "gps_low_accuracy", 5000L, "[GPS] 정확도 낮아 무시: accuracy=${location.accuracy}m")
                return
            }
            if (!gpsFirstGoodLogged) {
                gpsFirstGoodLogged = true
                NavLogger.d(this, "[GPS시작지연] 구독 후 ${android.os.SystemClock.elapsedRealtime() - gpsWatchStartAt}ms에 쓸 만한 GPS 확보: " +
                    "accuracy=${if (location.hasAccuracy()) location.accuracy else -1f}m (그 전에 정확도 때문에 버린 횟수=$gpsRejectedBeforeGood)")
            }
            val speedKph = (location.speed * 3.6).toInt()
            binding.tvCurrentSpeed?.text = speedKph.toString()
            // v: 재억 재제보 - judgeOverSpeedAlert를 다시 켜서 카카오 자체 음성 안내(+ 카카오
            // 자체 경고음)가 살아났으므로, 우리 자체 경고음(checkOverSpeedWarning, 300~500m/100m
            // "띵띵" 반복 톤)을 카카오 화면에서 같이 울리면 중복이라 꺼둠(호출 자체를 뺌). 티맵
            // 화면(MapActivity) 쪽 checkOverSpeedWarning은 그대로 유지(카카오 SDK가 없어 대체
            // 수단이 없음). #문제시 원복
            if (location.hasBearing() && location.speed > 0.5f) { // v: 화면이 자주 재시작돼서 bearing이 쌓일 시간이 부족했음 - 조건 완화(1.0->0.5) #문제시 원복
                lastKnownBearing = location.bearing
            }
            binding.btnGpsStatus?.text = "GOOD (정확도 ${location.accuracy.toInt()}m)"
            binding.btnGpsStatus?.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
        } catch (e: Exception) {
            NavLogger.e(this, "[GPS] 위치 처리 예외: ${e.message}")
        }
    }

    // v: 신규기능(물리 볼륨버튼으로 안내음량 조절) - 카카오 안내 화면에서만 가로챔.
    // (티맵 화면에서는 재억 요청으로 가로채지 않고 음악 볼륨이 조절됨)
    // v: 재억 제보(2026-09-02) - 길게 누르고 있을 때(event.repeatCount > 0) 더 촘촘하게
    // 움직이도록 반복 여부를 같이 넘김. #문제시 원복
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
        ) {
            VolumeHelper.adjustGuideVolumeByHardwareKey(
                this,
                keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP,
                isRepeat = (event?.repeatCount ?: 0) > 0
            )
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        // v4.24: MapActivity와 동일 이유로 추가. #문제시 원복
        NavLogger.d(this, "[KakaoNaviActivity lifecycle] onDestroy (isFinishing=$isFinishing, isChangingConfigurations=$isChangingConfigurations)")
        try { voiceAssistant.shutdown() } catch (e: Exception) { }
        // v: 재억 지시(2026-09-27, GPS끊김워치독 추가하면서) - 델리게이트가 자체 Handler
        // 루프를 갖게 됐으니, 화면 종료 시 반드시 멈춰야 액티비티 재생성될 때마다 중복으로
        // 계속 도는 걸 막을 수 있음. #문제시 원복
        cancelNavNotification()
        parkedRefresh?.let { parkedHandler.removeCallbacks(it) }
        hudPollHandler.removeCallbacksAndMessages(null)
        // v: 재억 요청(2026-09-02, A안) - 화면이 사라지면 카카오 음량 적용 함수도 해제.
        // (이미 없어진 naviView를 붙잡고 있으면 안 됨) #문제시 원복
        VolumeHelper.kakaoGuideVolumeApplier = null
        VolumeHelper.guideVolumeIndicator = null
        guideVolumeIndicatorHandler.removeCallbacksAndMessages(null)
        com.tmap.nda.miniplayer.MiniPlayerManager.detach()
        try {
            locationManager?.removeUpdates(this)
        } catch (e: Exception) {
            NavLogger.e(this, "[GPS] LocationManager 구독 해제 예외: ${e.message}")
        }
        try {
            val sharedPref = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
            val muted = sharedPref.getBoolean("tmap_muted", false)
            val vol = if (muted) 0 else VolumeHelper.guideVolumePercent(this)
            TmapUISDK.setVolume(this, vol)
            KakaoSdkState.lastAppliedTmapVolume = vol
            NavLogger.d(this, "[음소거] KakaoNaviActivity 종료 - 티맵 볼륨 복원(muted=$muted, vol=$vol)")
        } catch (e: Exception) {
            NavLogger.e(this, "티맵 볼륨 복원 예외: ${e.message}")
        }
        // 안내 엔진(NaverNavigator)은 화면과 별개로 계속 돌 수 있어서(티맵 화면으로 돌아가도 콤마·HUD 전송 유지)
        // 멈추지 않고, 이 화면이 연결해 둔 리스너와 지도만 정리한다. 더 새 화면이 이미 리스너를 갈아 끼웠다면 건드리지 않는다.
        NaverNavigator.clearListener(naverGuidanceListener)
        PopupCard.onPopupVisibilityChanged = null
        try { naverMap.onDestroy() } catch (e: Exception) { NavLogger.e(this, "지도 정리 예외: ${e.message}") }
        if (!NaverNavigator.isRunning) NaverNavigator.releaseLocationIfIdle()
    }
}
