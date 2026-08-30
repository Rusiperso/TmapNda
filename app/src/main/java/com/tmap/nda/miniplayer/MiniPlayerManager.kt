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
        confirmBtn.y = (outerContainer.y - confirmOverhangPx).coerceAtLeast(0f)
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

        val base = BaseSizes(
            artPx = art.layoutParams.width,
            titlePx = title.textSize,
            artistPx = artist.textSize,
            prevPx = prev.layoutParams.width,
            playPausePx = playPause.layoutParams.width,
            nextPx = next.layoutParams.width,
            titleMaxWidthPx = title.maxWidth,
            artistMaxWidthPx = artist.maxWidth
        )
        val savedScale = prefs.getFloat("${keyPrefix}_scale_$suffix", 1.0f)
        applyScale(savedScale, base, art, title, artist, playPause, prev, next)

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

        playPause.setOnClickListener {
            val controller = activeController ?: return@setOnClickListener
            val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
            if (playing) controller.transportControls.pause() else controller.transportControls.play()
        }
        prev.setOnClickListener { activeController?.transportControls?.skipToPrevious() }
        next.setOnClickListener { activeController?.transportControls?.skipToNext() }

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
     */
    private fun setupResizeTouch(
        activity: Activity, outerContainer: ViewGroup, confirmBtn: View, resizeHandle: View, base: BaseSizes,
        art: ImageView, title: TextView, artist: TextView, playPause: ImageButton, prev: ImageButton, next: ImageButton,
        keyPrefix: String, suffix: String
    ) {
        var startRawX = 0f
        var startScale = 1f
        var anchorRight = 0f
        var anchorTop = 0f
        resizeHandle.setOnTouchListener { _, event ->
            if (editMode != EditMode.RESIZE) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startScale = art.layoutParams.width.toFloat() / base.artPx.toFloat()
                    anchorRight = outerContainer.x + outerContainer.width
                    anchorTop = outerContainer.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // 핸들이 카드 왼쪽 아래(확정버튼 바로 밑)에 있으니, 왼쪽으로 끌수록
                    // (rawX 감소) 커지게. #문제시 원복
                    val dx = event.rawX - startRawX
                    val scale = (startScale - dx / 300f).coerceIn(MIN_SCALE, MAX_SCALE)
                    applyScale(scale, base, art, title, artist, playPause, prev, next)
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
                    val scale = art.layoutParams.width.toFloat() / base.artPx.toFloat()
                    activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putFloat("${keyPrefix}_scale_$suffix", scale)
                        .putFloat("${keyPrefix}_x_$suffix", outerContainer.x)
                        .putFloat("${keyPrefix}_y_$suffix", outerContainer.y)
                        .apply()
                    true
                }
                else -> false
            }
        }
    }

    /** 앨범아트/글자/버튼 각각의 실제 크기를 배율만큼 다시 계산해서 반영 - 진짜로 커짐. */
    private fun applyScale(
        scale: Float, base: BaseSizes,
        art: ImageView, title: TextView, artist: TextView, playPause: ImageButton, prev: ImageButton, next: ImageButton
    ) {
        art.layoutParams = art.layoutParams.apply {
            width = (base.artPx * scale).toInt()
            height = (base.artPx * scale).toInt()
        }
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, base.titlePx * scale)
        title.maxWidth = (base.titleMaxWidthPx * scale).toInt()
        artist.setTextSize(TypedValue.COMPLEX_UNIT_PX, base.artistPx * scale)
        artist.maxWidth = (base.artistMaxWidthPx * scale).toInt()
        prev.layoutParams = prev.layoutParams.apply {
            width = (base.prevPx * scale).toInt()
            height = (base.prevPx * scale).toInt()
        }
        playPause.layoutParams = playPause.layoutParams.apply {
            width = (base.playPausePx * scale).toInt()
            height = (base.playPausePx * scale).toInt()
        }
        next.layoutParams = next.layoutParams.apply {
            width = (base.nextPx * scale).toInt()
            height = (base.nextPx * scale).toInt()
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
        val playingControllers = controllers?.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val picked = playingControllers?.maxByOrNull { it.playbackState?.lastPositionUpdateTime ?: 0L }
            ?: controllers?.firstOrNull()

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
        title.text = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: "재생 중"
        artist.text = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""

        val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
        art.setImageBitmap(bitmap)

        val isPlaying = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        playPause.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
    }

    private fun detachSession() {
        sessionsListener?.let { listener ->
            try { sessionManager?.removeOnActiveSessionsChangedListener(listener) } catch (_: Exception) {}
        }
        activeCallback?.let { cb -> try { activeController?.unregisterCallback(cb) } catch (_: Exception) {} }
        sessionsListener = null
        activeCallback = null
        activeController = null
    }

    fun detach() {
        detachSession()
        editMode = EditMode.NONE
    }
}
