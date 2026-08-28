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
 * v: 재억 요청(2026-08-28, 2차) - 실기기로 확인해보니 카드가 너무 작다는 지적. 카드를
 * 길게 누르면 "위치 이동"/"크기 조절" 메뉴가 뜨고, 각각 확정(체크) 버튼을 눌러야
 * 저장되는 방식으로 만듦(기존 "UI 편집" 모드 확정 버튼과 동일 패턴 재사용). 이동은
 * 기존 PanelDragHelper와 별개의 독립 로직 - 전역 편집모드(PanelDragHelper.isEditMode)를
 * 건드리면 다른 상단바/버튼들까지 같이 편집모드로 들어가버리는 부작용이 있어서. #문제시 원복
 */
object MiniPlayerManager {
    const val PREF_KEY_ENABLED = "miniplayer_enabled"

    private const val MIN_SCALE = 0.75f
    private const val MAX_SCALE = 2.0f
    private const val PREFS = "TmapNdaPrefs"

    private var sessionManager: MediaSessionManager? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var activeController: MediaController? = null
    private var activeCallback: MediaController.Callback? = null

    private enum class EditMode { NONE, MOVE, RESIZE }
    private var editMode = EditMode.NONE

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
     * @param outerContainer flMiniPlayerContainer - 화면상 위치/표시여부를 갖는 바깥 껍데기(드래그 대상).
     * @param card llMiniPlayer - 실제 카드. 크기 조절(scaleX/Y) 대상.
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

        val savedScale = prefs.getFloat("${keyPrefix}_scale_$suffix", 1.0f)
        card.scaleX = savedScale
        card.scaleY = savedScale

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
        }

        card.setOnLongClickListener {
            showEditMenu(activity, it, confirmBtn, resizeHandle)
            true
        }
        setupMoveTouch(activity, outerContainer, keyPrefix, suffix)
        setupResizeTouch(activity, resizeHandle, card, keyPrefix, suffix)
        confirmBtn.setOnClickListener {
            editMode = EditMode.NONE
            confirmBtn.visibility = View.GONE
            resizeHandle.visibility = View.GONE
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

    private fun showEditMenu(activity: Activity, anchor: View, confirmBtn: View, resizeHandle: View) {
        val popup = PopupMenu(activity, anchor)
        popup.menu.add(0, 1, 0, "위치 이동")
        popup.menu.add(0, 2, 1, "크기 조절")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    editMode = EditMode.MOVE
                    confirmBtn.visibility = View.VISIBLE
                    resizeHandle.visibility = View.GONE
                    true
                }
                2 -> {
                    editMode = EditMode.RESIZE
                    confirmBtn.visibility = View.VISIBLE
                    resizeHandle.visibility = View.VISIBLE
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun setupMoveTouch(activity: Activity, outerContainer: ViewGroup, keyPrefix: String, suffix: String) {
        var dX = 0f
        var dY = 0f
        outerContainer.setOnTouchListener { v, event ->
            if (editMode != EditMode.MOVE) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = v.x - event.rawX
                    dY = v.y - event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val parent = v.parent as? View
                    var newX = event.rawX + dX
                    var newY = event.rawY + dY
                    if (parent != null) {
                        val maxX = (parent.width - v.width).toFloat().coerceAtLeast(0f)
                        val maxY = (parent.height - v.height).toFloat().coerceAtLeast(0f)
                        newX = newX.coerceIn(0f, maxX)
                        newY = newY.coerceIn(0f, maxY)
                    }
                    v.x = newX
                    v.y = newY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putFloat("${keyPrefix}_x_$suffix", v.x)
                        .putFloat("${keyPrefix}_y_$suffix", v.y)
                        .apply()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupResizeTouch(activity: Activity, resizeHandle: View, card: View, keyPrefix: String, suffix: String) {
        var startRawX = 0f
        var startScale = 1f
        resizeHandle.setOnTouchListener { _, event ->
            if (editMode != EditMode.RESIZE) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startScale = card.scaleX
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // 핸들이 카드 왼쪽 아래(확정버튼 바로 밑)에 있으니, 왼쪽으로 끌수록
                    // (rawX 감소) 커지게. #문제시 원복
                    val dx = event.rawX - startRawX
                    val scale = (startScale - dx / 300f).coerceIn(MIN_SCALE, MAX_SCALE)
                    card.scaleX = scale
                    card.scaleY = scale
                    true
                }
                MotionEvent.ACTION_UP -> {
                    activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putFloat("${keyPrefix}_scale_$suffix", card.scaleX)
                        .apply()
                    true
                }
                else -> false
            }
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
        val picked = controllers?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
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
