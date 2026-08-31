package com.tmap.nda.miniplayer

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import com.tmap.nda.NavLogger

/**
 * 신규기능(미니 플레이어) - 재억 요청(2026-08-28).
 *
 * "다른 앱(유튜브뮤직 등)의 화면을 통째로 가져오는" 건 안드로이드가 어떤 앱에도 허용하지
 * 않아서 못 만들지만, "지금 재생 중인 곡 정보"는 MediaSession이라는 표준 API로 시스템이
 * 공식 공개하고 있음 - 이걸 구독해서 TmapNda 자체 스타일로 작게 그려주는 기능. 정품
 * 티맵/카카오내비 하단의 "재생 중: OOO"와 같은 원리.
 *
 * 이 정보를 읽으려면 "알림 접근" 권한이 필요함(런타임 요청이 아니라 시스템 설정 화면에서
 * 사용자가 직접 허용해야 하는 특수 권한). 권한이 없으면 조용히 숨김 처리만 하고 넘어감.
 *
 * v: 재억 제보(2026-08-28, 3차, 실기기 영상으로 확인) - 두 가지 문제를 다시 고침:
 * 1) 크기 조절이 View.scaleX/scaleY(순수 렌더링 확대 - 실제 레이아웃 크기는 그대로 두고
 *    그려진 그림만 확대경으로 보듯 키우는 것)로 되어 있었음. 이러면 카드 배경/자식들이
 *    시각적으로는 커 보여도 "실제 차지하는 공간"은 그대로라 이상하게 보이고 터치 판정도
 *    어긋남. 앨범아트/글자/버튼 각각의 실제 크기(layoutParams, textSize)를 배율만큼 직접
 *    다시 계산해서 넣는 방식으로 교체 - 목업(HTML/CSS)과 동일한 접근.
 * 2) 위치 이동이 outerContainer(FrameLayout)에 터치리스너를 걸었는데 그 안의 card가
 *    OnLongClickListener 때문에 clickable해져서 터치를 먼저 가로채버려 부모까지 이벤트가
 *    안 넘어갔음(안드로이드 터치 디스패치 기본 동작 - 자식이 소비하면 부모는 못 받음).
 *    리스너를 card 자신에 걸고, 이동/크기조절 모드 진입 시 재생 버튼들을 잠깐
 *    비활성화해서 카드 어디를 눌러도 확실히 드래그가 먹히게 함. #문제시 원복
 */
object MiniPlayerManager {
    const val PREF_KEY_ENABLED = "miniplayer_enabled"

    private const val MIN_SCALE = 0.75f
    // v: 재억 요청(2026-08-29) - "3배"처럼 임의로 정해둔 상한을 없애고, 화면에 실제로
    // 들어가는 만큼까지는 마음대로 키울 수 있게 함. 숫자 자체의 상한은 극단적인 값(글자
    // 깨짐 등)만 막는 안전장치일 뿐, 실사용에서 걸릴 일은 없음 - 진짜 한계는 아래
    // setupResizeTouch에서 화면 크기를 넘지 않도록 위치를 잡아주는 로직임. #문제시 원복
    private const val MAX_SCALE = 20.0f
    private const val PREFS = "TmapNdaPrefs"

    private var sessionManager: MediaSessionManager? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var activeController: MediaController? = null
    private var activeCallback: MediaController.Callback? = null
    // v: 재억 제보(2026-08-30, 실기기로 확인 - "같은 순간인데 티맵 화면이랑 카카오 길안내
    // 화면 미니플레이어가 서로 다른 곡을 보여준다") - 화면 전환마다(detach→attach) 완전히
    // 처음부터 다시 골라서, 같은 순간에도 두 화면이 서로 다른 세션을 고를 수 있었음
    // (여러 세션이 거의 동시에 "재생중"으로 보일 때 어느 쪽을 고르든 매번 새로 판단하니
    // 화면마다 결과가 갈릴 수 있음). detach()로도 안 지워지는 이 값에 "마지막으로 고른
    // 세션의 앱 패키지명"을 저장해뒀다가, 그 세션이 여전히 유효하면 화면이 바뀌어도
    // 계속 우선적으로 그걸 다시 고르도록 함. #문제시 원복
    private var lastPickedPackageName: String? = null
    // v: 재억 요청(2026-08-31) - "확정버튼이 상태표시줄에 가려진다" - 엣지투엣지 화면이라
    // Y=0이 상태표시줄 뒤쪽(화면 맨 꼭대기)이라서 생긴 문제. MapActivity/KakaoNaviActivity의
    // 인셋 리스너가 이미 계산하는 상단 안전영역 값을 여기 공유해두고, 확정버튼/손잡이
    // 위치를 계산할 때 최소 Y로 사용해서 상태표시줄 아래로는 절대 안 올라가게 함. #문제시 원복
    @Volatile var topSafeInsetPx: Int = 0
    private var lastPickDiagLogTime = 0L
    private val periodicRefreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var periodicRefreshRunnable: Runnable? = null

    private enum class EditMode { NONE, MOVE, RESIZE }
    private var editMode = EditMode.NONE

    // v: 재억 제보(2026-08-29, 4차) - 확정 버튼/크기조절 핸들을 카드 밖으로 튀어나오게
    // 하려고 flMiniPlayerContainer "안에" 두고 음수 여백을 줬더니, 그려지기는 해도 눌리지
    // 않았음: clipChildren=false는 "그림이 밖으로 나가는 것"만 허용할 뿐, 안드로이드가
    // "이 터치를 어느 자식에게 넘길지" 판단하는 기준은 여전히 컨테이너의 원래(카드) 크기라서
    // 밖으로 나간 부분은 애초에 터치가 전달조차 안 됐음. 그래서 이 두 버튼을 컨테이너 밖,
    // 화면 전체(루트)의 직속 자식으로 옮기고, 위치는 카드(outerContainer)의 현재 좌표를
    // 기준으로 이 값(카드 밖으로 튀어나올 거리)만큼 코드에서 직접 계산해서 잡아줌 -
    // 화면 전체는 항상 터치를 받을 수 있는 크기라 이 문제 자체가 생길 수 없음. #문제시 원복
    private const val CONFIRM_OVERHANG_DP = 36f
    private const val RESIZE_HANDLE_OVERHANG_DP = 32f

    private fun dpToPx(activity: Activity, dp: Float): Float = dp * activity.resources.displayMetrics.density

    /** 확정 버튼/크기조절 핸들을 카드(outerContainer)의 현재 좌표 기준으로 재배치. */
    private fun repositionOverlayButtons(activity: Activity, outerContainer: ViewGroup, confirmBtn: View, resizeHandle: View) {
        val confirmOverhangPx = dpToPx(activity, CONFIRM_OVERHANG_DP)
        val handleOverhangPx = dpToPx(activity, RESIZE_HANDLE_OVERHANG_DP)
        confirmBtn.x = (outerContainer.x - confirmOverhangPx).coerceAtLeast(0f)
        confirmBtn.y = (outerContainer.y - confirmOverhangPx).coerceAtLeast(topSafeInsetPx.toFloat())
        resizeHandle.x = (outerContainer.x - handleOverhangPx).coerceAtLeast(0f)
        resizeHandle.y = outerContainer.y + outerContainer.height
    }

    private data class BaseSizes(
        val artPx: Int,
        val titlePx: Float,
        val artistPx: Float,
        val prevPx: Int,
        val playPausePx: Int,
        val nextPx: Int,
        val titleMaxWidthPx: Int,
        val artistMaxWidthPx: Int
    )

    fun hasNotificationAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_KEY_ENABLED, true)

    fun openNotificationAccessSettings(activity: Activity) {
        try {
            activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            NavLogger.e(activity, "[미니플레이어] 알림 접근 설정 화면 이동 실패: ${e.message}")
        }
    }

    /**
     * Activity onCreate에서 1회 호출.
     * @param outerContainer flMiniPlayerContainer - 화면상 위치/표시여부를 갖는 바깥 껍데기(이동 대상).
     * @param card llMiniPlayer - 실제 카드. 길게 누르면 편집 메뉴가 뜸.
     * @param confirmBtn 편집모드일 때만 보이는 확정(체크) 버튼.
     * @param resizeHandle "크기 조절" 선택했을 때만 보이는 리사이즈 핸들.
     */
    fun attach(
        activity: Activity,
        outerContainer: ViewGroup,
        card: View,
        art: ImageView,
        title: TextView,
        artist: TextView,
        playPause: ImageButton,
        prev: ImageButton,
        next: ImageButton,
        confirmBtn: View,
        resizeHandle: View,
        keyPrefix: String,
        isLandscape: Boolean
    ) {
        val suffix = if (isLandscape) "land" else "port"
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // v: 재억 제보(2026-08-30, "UI 크기가 지 멋대로 커진다", 실기기로 확인 - 카카오
        // 갔다 돌아올 때마다 미니플레이어가 점점 거대해짐) - onResume에서도 attach()를
        // 다시 부르게 만든 게(미니플레이어 재부착 수정) 원인이었음. base(원본 크기)를
        // "지금 뷰의 현재 크기"에서 매번 다시 재는데, attach()가 두 번째 불릴 땐 그
        // "현재 크기"가 이미 저장된 배율이 한 번 적용된 뒤였음 - 그 위에 배율을 또
        // 곱해버려서 화면 돌아올 때마다 크기가 기하급수적으로 불어남. 이 뷰 세트에
        // 크기/위치/리스너 설정을 이미 한 번 했으면(태그로 표시) 그 부분은 건너뛰고,
        // 아래 세션 재구독 부분만 다시 하도록 분리. #문제시 원복
        val alreadyInitialized = outerContainer.tag == "miniplayer_initialized"
        if (!alreadyInitialized) {
            outerContainer.tag = "miniplayer_initialized"
            // v: 재억 제보(2026-08-31, 실기기 스크린샷 - "제목 따라 크기 바뀌고 대각선으로만
            // 조절됨"이 여전히 재현) - 원인은 세로용 레이아웃(layout/)만 고정폭으로 고쳐지고
            // 가로용(layout-land/) 두 파일이 wrap_content로 남아있었던 것. 그 경우
            // layoutParams.width가 실제 픽셀이 아니라 음수 상수(-2)로 잡혀서, 이 값을 기준
            // 삼는 가로 크기조절 계산 전체가 무의미해지고(음수 폭) 세로 방향만 먹혀
            // "대각선으로만 커지는" 것처럼 보였음. 레이아웃 네 파일을 모두 고정폭으로
            // 맞췄고, 여기에도 같은 실수가 재발하면 조용히 깨지는 대신 안전한 기본값
            // (220dp - 레이아웃과 동일)으로 버티도록 방어를 둠. #문제시 원복
            val fallbackTextWidthPx = dpToPx(activity, 220f).toInt()
            val base = BaseSizes(
                artPx = art.layoutParams.width,
                titlePx = title.textSize,
                artistPx = artist.textSize,
                prevPx = prev.layoutParams.width,
                playPausePx = playPause.layoutParams.width,
                nextPx = next.layoutParams.width,
                titleMaxWidthPx = title.layoutParams.width.takeIf { it > 0 } ?: fallbackTextWidthPx,
                artistMaxWidthPx = artist.layoutParams.width.takeIf { it > 0 } ?: fallbackTextWidthPx
            )
            val savedScaleX = prefs.getFloat("${keyPrefix}_scaleX_$suffix", 1.0f)
            val savedScaleY = prefs.getFloat("${keyPrefix}_scaleY_$suffix", 1.0f)
            applyScale(savedScaleX, savedScaleY, base, art, title, artist, playPause, prev, next)

            outerContainer.post {
                val x = prefs.getFloat("${keyPrefix}_x_$suffix", -1f)
                val y = prefs.getFloat("${keyPrefix}_y_$suffix", -1f)
                if (x != -1f && y != -1f) {
                    val parent = outerContainer.parent as? View
                    if (parent != null) {
                        val maxX = (parent.width - outerContainer.width).toFloat().coerceAtLeast(0f)
                        val maxY = (parent.height - outerContainer.height).toFloat().coerceAtLeast(0f)
                        outerContainer.x = x.coerceIn(0f, maxX)
                        outerContainer.y = y.coerceIn(0f, maxY)
                    }
                }
                repositionOverlayButtons(activity, outerContainer, confirmBtn, resizeHandle)
            }

            card.setOnLongClickListener {
                repositionOverlayButtons(activity, outerContainer, confirmBtn, resizeHandle)
                showEditMenu(activity, it, confirmBtn, resizeHandle, prev, playPause, next)
                true
            }
            setupMoveTouch(activity, card, outerContainer, confirmBtn, resizeHandle, keyPrefix, suffix)
            setupResizeTouch(activity, outerContainer, confirmBtn, resizeHandle, base, art, title, artist, playPause, prev, next, keyPrefix, suffix)
            confirmBtn.setOnClickListener {
                editMode = EditMode.NONE
                confirmBtn.visibility = View.GONE
                resizeHandle.visibility = View.GONE
                setChildrenClickable(true, prev, playPause, next)
            }
        }

        // v: 재억 제보(2026-08-30/31, "카카오 종료 후 티맵에서 간헐적으로 터치가 안
        // 먹힌다") - 로그로 확인: 버튼 클릭 자체(진단 로그)는 계속 찍히는데, 그 순간
        // activeController가 비어있으면 아무 반응 없이 조용히 끝나버렸음(짧은 시간에
        // "이전곡"이 8번 연달아 눌린 걸로 봐서 반응이 없어 계속 다시 누르신 것으로
        // 보임). 클릭 시점에 대상이 비어있으면, 포기하지 않고 그 자리에서 즉시 세션을
        // 다시 찾아보고 그걸로 바로 조작. #문제시 원복
        fun ensureController(): MediaController? {
            activeController?.let { return it }
            val comp = ComponentName(activity, MiniPlayerNotificationListener::class.java)
            val fresh = try { sessionManager?.getActiveSessions(comp) } catch (e: Exception) { null }
            val recovered = fresh?.maxByOrNull { it.playbackState?.lastPositionUpdateTime ?: 0L }
            if (recovered != null) {
                activeController = recovered
                lastPickedPackageName = recovered.packageName
            }
            return recovered
        }

        playPause.setOnClickListener {
            // v: 재억 제보(2026-08-30, "카카오 화면은 되는데 티맵 화면에서는 미니플레이어
            // 버튼 터치가 아예 안 된다") - 코드상으론 두 화면 호출부가 완전히 동일해서
            // 원인을 못 짚었음. 이 로그가 안 찍히면 "터치가 버튼까지 아예 안 닿는다"는
            // 뜻(다른 뷰가 가로챔), 찍히는데도 안 움직이면 다른 원인. #문제시 원복
            NavLogger.d(activity, "[미니플레이어][진단] 재생/일시정지 버튼 클릭됨")
            val controller = ensureController() ?: return@setOnClickListener
            val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
            if (playing) controller.transportControls.pause() else controller.transportControls.play()
        }
        prev.setOnClickListener {
            NavLogger.d(activity, "[미니플레이어][진단] 이전곡 버튼 클릭됨")
            ensureController()?.transportControls?.skipToPrevious()
        }
        next.setOnClickListener {
            NavLogger.d(activity, "[미니플레이어][진단] 다음곡 버튼 클릭됨")
            ensureController()?.transportControls?.skipToNext()
        }

        refresh(activity, outerContainer, art, title, artist, playPause)
    }

    private fun showEditMenu(
        activity: Activity, anchor: View, confirmBtn: View, resizeHandle: View,
        prev: ImageButton, playPause: ImageButton, next: ImageButton
    ) {
        val popup = PopupMenu(activity, anchor)
        popup.menu.add(0, 1, 0, "위치 이동")
        popup.menu.add(0, 2, 1, "크기 조절")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    editMode = EditMode.MOVE
                    confirmBtn.visibility = View.VISIBLE
                    resizeHandle.visibility = View.GONE
                    setChildrenClickable(false, prev, playPause, next)
                    true
                }
                2 -> {
                    editMode = EditMode.RESIZE
                    confirmBtn.visibility = View.VISIBLE
                    resizeHandle.visibility = View.VISIBLE
                    setChildrenClickable(false, prev, playPause, next)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    /** 편집모드 진입 시 재생 컨트롤 버튼들이 터치를 가로채지 않도록 잠깐 비활성화. */
    private fun setChildrenClickable(clickable: Boolean, vararg views: View) {
        views.forEach {
            it.isClickable = clickable
            it.isEnabled = clickable
        }
    }

    /**
     * v: 재억 제보(2026-08-28, 3차) - 터치리스너를 outerContainer가 아니라 card 자신에
     * 걸어야 함(위 클래스 주석 2번 참고). 실제로 옮기는 대상은 outerContainer. #문제시 원복
     */
    private fun setupMoveTouch(
        activity: Activity, card: View, outerContainer: ViewGroup,
        confirmBtn: View, resizeHandle: View, keyPrefix: String, suffix: String
    ) {
        var dX = 0f
        var dY = 0f
        card.setOnTouchListener { _, event ->
            if (editMode != EditMode.MOVE) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = outerContainer.x - event.rawX
                    dY = outerContainer.y - event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val parent = outerContainer.parent as? View
                    var newX = event.rawX + dX
                    var newY = event.rawY + dY
                    if (parent != null) {
                        // 이동 중엔 확정(✓) 버튼이 카드 왼쪽 위로 튀어나와 있으니, 그만큼은
                        // 화면 가장자리에 남겨둬서 버튼이 화면 밖으로 밀려나지 않게 함.
                        val overhangPx = dpToPx(activity, CONFIRM_OVERHANG_DP)
                        val minX = overhangPx
                        val minY = overhangPx
                        val maxX = (parent.width - outerContainer.width).toFloat().coerceAtLeast(minX)
                        val maxY = (parent.height - outerContainer.height).toFloat().coerceAtLeast(minY)
                        newX = newX.coerceIn(minX, maxX)
                        newY = newY.coerceIn(minY, maxY)
                    }
                    outerContainer.x = newX
                    outerContainer.y = newY
                    repositionOverlayButtons(activity, outerContainer, confirmBtn, resizeHandle)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putFloat("${keyPrefix}_x_$suffix", outerContainer.x)
                        .putFloat("${keyPrefix}_y_$suffix", outerContainer.y)
                        .apply()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * v: 재억 제보(2026-08-29) - 카드 안쪽(앨범아트/글자/버튼)만 커지고 바깥 틀
     * (outerContainer)은 신경쓰지 않았더니, outerContainer가 XML의 layout_gravity="top|end"
     * 규칙 때문에 커질 때마다 "오른쪽 끝 고정, 왼쪽으로 넓히기"로 자동 재배치되면서 마치
     * 카드가 "커지는" 게 아니라 "움직이는" 것처럼 보였음(스크린샷으로 확인).
     * 이제 크기 조절을 시작하는 순간 카드의 오른쪽 위 모서리 좌표를 고정해두고, 커질
     * 때마다 그 모서리 좌표를 그대로 유지한 채 outerContainer.x/y를 직접 계산해서
     * 왼쪽/아래로만 자라도록 함 - 목업(HTML/CSS)의 "앵커 고정 리사이즈"와 동일한 방식. #문제시 원복
     *
     * v: 재억 지적(2026-08-31) - 핸들을 어느 방향으로 끌든 dx 하나로 가로/세로/글자/아이콘을
     * 전부 같은 비율로 키워서 "대각선으로만" 커지는 것처럼 보였음. 가로(dx)는 글자칸 너비
     * (scaleX)만, 세로(dy)는 앨범아트/버튼/글자 크기(scaleY)만 담당하도록 분리해서 가로/세로를
     * 각각 원하는 만큼 따로 늘릴 수 있게 함. #문제시 원복
     */
    private fun setupResizeTouch(
        activity: Activity, outerContainer: ViewGroup, confirmBtn: View, resizeHandle: View, base: BaseSizes,
        art: ImageView, title: TextView, artist: TextView, playPause: ImageButton, prev: ImageButton, next: ImageButton,
        keyPrefix: String, suffix: String
    ) {
        var startRawX = 0f
        var startRawY = 0f
        var startScaleX = 1f
        var startScaleY = 1f
        var anchorRight = 0f
        var anchorTop = 0f
        resizeHandle.setOnTouchListener { _, event ->
            if (editMode != EditMode.RESIZE) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startScaleX = title.layoutParams.width.toFloat() / base.titleMaxWidthPx.toFloat()
                    startScaleY = art.layoutParams.width.toFloat() / base.artPx.toFloat()
                    anchorRight = outerContainer.x + outerContainer.width
                    anchorTop = outerContainer.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // 핸들이 카드 왼쪽 아래(확정버튼 바로 밑)에 있으니, 왼쪽으로 끌수록
                    // (rawX 감소) 가로로 커지고, 아래로 끌수록(rawY 증가) 세로로 커지게 -
                    // 두 방향을 독립적으로 계산. #문제시 원복
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    val scaleX = (startScaleX - dx / 300f).coerceIn(MIN_SCALE, MAX_SCALE)
                    val scaleY = (startScaleY + dy / 300f).coerceIn(MIN_SCALE, MAX_SCALE)
                    applyScale(scaleX, scaleY, base, art, title, artist, playPause, prev, next)
                    outerContainer.post {
                        // 오른쪽 위 모서리(anchor)를 기준으로 왼쪽/아래로 키우되, 화면
                        // 밖으로 나가려 하면 그 이상은 화면 가장자리에 걸리게 함(임의의
                        // 숫자가 아니라 실제 화면 크기가 진짜 한계가 되도록). #문제시 원복
                        // 크기 조절 중엔 확정 버튼(카드 왼쪽 위)과 크기조절 핸들(카드 왼쪽
                        // 아래)이 둘 다 튀어나와 있으니, 왼쪽은 두 버튼 중 더 많이 튀어나온
                        // 쪽 기준, 위/아래는 각 버튼 기준으로 여유를 남겨둠.
                        val parent = outerContainer.parent as? View
                        val leftOverhangPx = dpToPx(activity, maxOf(CONFIRM_OVERHANG_DP, RESIZE_HANDLE_OVERHANG_DP))
                        val topOverhangPx = dpToPx(activity, CONFIRM_OVERHANG_DP)
                        val bottomOverhangPx = dpToPx(activity, RESIZE_HANDLE_OVERHANG_DP)
                        var newX = anchorRight - outerContainer.width
                        var newY = anchorTop
                        if (parent != null) {
                            newX = newX.coerceAtLeast(leftOverhangPx)
                            val maxY = (parent.height - outerContainer.height - bottomOverhangPx).coerceAtLeast(topOverhangPx)
                            newY = newY.coerceIn(topOverhangPx, maxY)
                        }
                        outerContainer.x = newX
                        outerContainer.y = newY
                        repositionOverlayButtons(activity, outerContainer, confirmBtn, resizeHandle)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val scaleX = title.layoutParams.width.toFloat() / base.titleMaxWidthPx.toFloat()
                    val scaleY = art.layoutParams.width.toFloat() / base.artPx.toFloat()
                    activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putFloat("${keyPrefix}_scaleX_$suffix", scaleX)
                        .putFloat("${keyPrefix}_scaleY_$suffix", scaleY)
                        .putFloat("${keyPrefix}_x_$suffix", outerContainer.x)
                        .putFloat("${keyPrefix}_y_$suffix", outerContainer.y)
                        .apply()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 앨범아트/글자/버튼 각각의 실제 크기를 배율만큼 다시 계산해서 반영 - 진짜로 커짐.
     *
     * v: 재억 지적(2026-08-31) - 가로(scaleX)는 글자칸 고정폭만, 세로(scaleY)는
     * 앨범아트/버튼/글자 크기만 따로 담당해서 가로/세로를 독립적으로 조절 가능. #문제시 원복
     */
    private fun applyScale(
        scaleX: Float, scaleY: Float, base: BaseSizes,
        art: ImageView, title: TextView, artist: TextView, playPause: ImageButton, prev: ImageButton, next: ImageButton
    ) {
        art.layoutParams = art.layoutParams.apply {
            width = (base.artPx * scaleY).toInt()
            height = (base.artPx * scaleY).toInt()
        }
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, base.titlePx * scaleY)
        title.layoutParams = title.layoutParams.apply { width = (base.titleMaxWidthPx * scaleX).toInt() }
        // v: 재억 요청(2026-08-31) - "제목 길이에 따라 창 크기가 바뀐다, 폭 고정하고
        // 넘치면 흘러가게(마퀴) 해달라" - layout_width를 고정폭으로 바꾸고 여기서
        // isSelected=true를 줘야 마퀴가 실제로 움직임(안드로이드 마퀴는 선택된 뷰에서만
        // 애니메이션됨). #문제시 원복
        title.isSelected = true
        artist.setTextSize(TypedValue.COMPLEX_UNIT_PX, base.artistPx * scaleY)
        artist.layoutParams = artist.layoutParams.apply { width = (base.artistMaxWidthPx * scaleX).toInt() }
        artist.isSelected = true
        prev.layoutParams = prev.layoutParams.apply {
            width = (base.prevPx * scaleY).toInt()
            height = (base.prevPx * scaleY).toInt()
        }
        playPause.layoutParams = playPause.layoutParams.apply {
            width = (base.playPausePx * scaleY).toInt()
            height = (base.playPausePx * scaleY).toInt()
        }
        next.layoutParams = next.layoutParams.apply {
            width = (base.nextPx * scaleY).toInt()
            height = (base.nextPx * scaleY).toInt()
        }
    }

    /** 설정에서 스위치를 바꾼 직후 등, 활성 세션 구독을 다시 걸어야 할 때 호출. */
    fun refresh(
        activity: Activity,
        outerContainer: ViewGroup,
        art: ImageView,
        title: TextView,
        artist: TextView,
        playPause: ImageButton
    ) {
        detachSession()

        if (!isEnabled(activity)) {
            outerContainer.visibility = View.GONE
            return
        }
        if (!hasNotificationAccess(activity)) {
            outerContainer.visibility = View.GONE
            NavLogger.d(activity, "[미니플레이어] 알림 접근 권한 없음 - 표시 건너뜀")
            return
        }

        try {
            val manager = activity.getSystemService(MediaSessionManager::class.java)
            sessionManager = manager
            val component = ComponentName(activity, MiniPlayerNotificationListener::class.java)
            val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
                pickController(activity, controllers, outerContainer, art, title, artist, playPause)
            }
            sessionsListener = listener
            manager.addOnActiveSessionsChangedListener(listener, component)
            pickController(activity, manager.getActiveSessions(component), outerContainer, art, title, artist, playPause)
            // v: 재억 제보(2026-08-30) - MediaSession의 onMetadataChanged 콜백이 앱에 따라
            // 안 오거나 늦게 오는 경우가 있어서(NotificationMediaCache는 갱신됐는데 화면은
            // 안 바뀜), 2초마다 활성 컨트롤러 기준으로 다시 그려서 알림 캐시 갱신을 놓치지
            // 않고 반영되게 함. 화면(Activity) 전환/detach 시 자동으로 멈춤. #문제시 원복
            startPeriodicRefresh(outerContainer, art, title, artist, playPause)
        } catch (e: Exception) {
            NavLogger.e(activity, "[미니플레이어] 세션 구독 실패: ${e.message}")
            outerContainer.visibility = View.GONE
        }
    }

    private fun pickController(
        activity: Activity,
        controllers: List<MediaController>?,
        outerContainer: ViewGroup, art: ImageView, title: TextView, artist: TextView, playPause: ImageButton
    ) {
        // v: 재억 제보(2026-08-30, 실기기로 확인 - "오른쪽 진짜 위젯엔 라붐 노래가
        // 재생중인데 미니플레이어는 엄한 노래") - "재생중" 상태인 세션 중 그냥 목록에서
        // 맨 처음 찾은 걸 골랐음. 백그라운드에 예전에 재생하다 만 다른 앱 세션이 여전히
        // STATE_PLAYING으로 남아있으면(흔한 경우), 그게 실제 재생 중인 세션보다 먼저
        // 잡혀서 엉뚱한 곡이 뜰 수 있었음. "재생중"인 것들 중에서도 재생 위치가 가장
        // 최근에 갱신된(=진짜 지금 재생 중인) 세션을 고르도록 수정. #문제시 원복
        // v: 재억 재제보(2026-08-30, 실기기로 확인 - "정답은 카카오 화면(라붐, 일시정지
        // 상태)이었다") - "재생중(STATE_PLAYING)" 세션만 후보로 삼던 게 근본적으로
        // 틀렸음. 실제 정답이 일시정지 상태였다는 건, 여러 앱 세션 중 "재생중"이라고
        // 표시된 게 오히려 배경에 남은 stale 세션이고, 진짜 지금 사용자가 만지고 있는
        // 세션은 일시정지 상태일 수도 있다는 뜻. 재생/일시정지 여부로 거르지 말고,
        // 상태와 무관하게 가장 최근에 실제로 갱신된 세션(lastPositionUpdateTime)을
        // 그대로 신뢰하도록 변경. #문제시 원복
        val stickyPick = lastPickedPackageName?.let { pkg -> controllers?.firstOrNull { it.packageName == pkg } }
        val mostRecentlyUpdated = controllers?.maxByOrNull { it.playbackState?.lastPositionUpdateTime ?: 0L }
        // 이전에 고르던 세션이 여전히 존재하고, 그게 여전히 "가장 최근 갱신"이기도 하면
        // 화면 전환 시 판단이 안 흔들리게 그대로 유지. 다른 세션이 더 최근에 갱신됐으면
        // (진짜로 다른 걸 조작 중이라는 뜻) 그쪽으로 바꿈. #문제시 원복
        val picked = if (stickyPick != null && stickyPick == mostRecentlyUpdated) stickyPick else mostRecentlyUpdated
        lastPickedPackageName = picked?.packageName

        // v: 재억 재제보(2026-08-30, "카카오 화면엔 정확한데 티맵 화면에선 여전히 엉뚱한
        // 곡") - 여러 차례 로직을 고쳤는데도 재현돼서, 이번엔 로직을 또 추측으로 고치는
        // 대신 실제 후보 목록/판단 근거를 전부 로그로 남김(15초 스로틀). 다음 재현 때
        // 이 로그로 후보가 몇 개였는지, 각각 상태/최근갱신시각이 어땠는지, 최종적으로
        // 뭘 왜 골랐는지 정확히 확인 가능. #문제시 원복
        if (System.currentTimeMillis() - lastPickDiagLogTime > 15000L) {
            lastPickDiagLogTime = System.currentTimeMillis()
            val candList = controllers?.joinToString(" | ") { c ->
                val st = c.playbackState?.state
                val upd = c.playbackState?.lastPositionUpdateTime ?: 0L
                "${c.packageName}(state=$st, upd=$upd, title=${c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)})"
            } ?: "null"
            NavLogger.d(activity, "[미니플레이어][진단] 후보=[$candList] sticky=${stickyPick?.packageName} 최종선택=${picked?.packageName}")
        }

        if (picked == activeController) {
            updateUi(outerContainer, art, title, artist, playPause, picked)
            return
        }

        activeCallback?.let { cb -> try { activeController?.unregisterCallback(cb) } catch (_: Exception) {} }
        activeController = picked

        if (picked == null) {
            activeCallback = null
            updateUi(outerContainer, art, title, artist, playPause, null)
            return
        }

        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                updateUi(outerContainer, art, title, artist, playPause, picked)
            }
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                updateUi(outerContainer, art, title, artist, playPause, picked)
            }
            override fun onSessionDestroyed() {
                refresh(activity, outerContainer, art, title, artist, playPause)
            }
        }
        activeCallback = callback
        picked.registerCallback(callback)
        updateUi(outerContainer, art, title, artist, playPause, picked)
    }

    private fun updateUi(
        outerContainer: ViewGroup, art: ImageView, title: TextView, artist: TextView, playPause: ImageButton,
        controller: MediaController?
    ) {
        val metadata = controller?.metadata
        if (controller == null || metadata == null) {
            outerContainer.visibility = View.GONE
            return
        }

        outerContainer.visibility = View.VISIBLE
        // v: 재억 제보(2026-08-30) - MediaSession 메타데이터(공식 API)가 앱/시스템 쪽
        // 지연으로 곡이 바뀐 뒤에도 한동안 옛 정보를 물고 있는 걸 확인함. 알림창에서
        // 직접 읽은 값(NotificationMediaCache, 훨씬 즉각적)이 있으면 그걸 우선 사용하고,
        // 없으면 기존 MediaSession 메타데이터로 폴백. #문제시 원복
        val notifEntry = NotificationMediaCache.get(controller.packageName)
        title.text = notifEntry?.title
            ?: metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: "재생 중"
        artist.text = notifEntry?.text
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        // v: 재억 요청(2026-08-31) - 곡이 바뀔 때마다 마퀴가 확실히 처음부터 다시
        // 흐르도록 재설정. #문제시 원복
        title.isSelected = true
        artist.isSelected = true

        val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
        art.setImageBitmap(bitmap)

        val isPlaying = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        playPause.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
    }

    private fun startPeriodicRefresh(
        outerContainer: ViewGroup, art: ImageView, title: TextView, artist: TextView, playPause: ImageButton
    ) {
        periodicRefreshRunnable?.let { periodicRefreshHandler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                activeController?.let { updateUi(outerContainer, art, title, artist, playPause, it) }
                periodicRefreshHandler.postDelayed(this, 2000L)
            }
        }
        periodicRefreshRunnable = runnable
        periodicRefreshHandler.postDelayed(runnable, 2000L)
    }

    private fun detachSession() {
        sessionsListener?.let { listener ->
            try { sessionManager?.removeOnActiveSessionsChangedListener(listener) } catch (_: Exception) {}
        }
        activeCallback?.let { cb -> try { activeController?.unregisterCallback(cb) } catch (_: Exception) {} }
        periodicRefreshRunnable?.let { periodicRefreshHandler.removeCallbacks(it) }
        periodicRefreshRunnable = null
        sessionsListener = null
        activeCallback = null
        activeController = null
    }

    fun detach() {
        detachSession()
        editMode = EditMode.NONE
    }
}
