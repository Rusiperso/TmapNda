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
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import com.tmap.nda.NavLogger
import com.tmap.nda.PanelDragHelper

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
 * #문제시 원복
 */
object MiniPlayerManager {
    const val PREF_KEY_ENABLED = "miniplayer_enabled"

    private var sessionManager: MediaSessionManager? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var activeController: MediaController? = null
    private var activeCallback: MediaController.Callback? = null

    fun hasNotificationAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
            .getBoolean(PREF_KEY_ENABLED, true)

    fun openNotificationAccessSettings(activity: Activity) {
        try {
            activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            NavLogger.e(activity, "[미니플레이어] 알림 접근 설정 화면 이동 실패: ${e.message}")
        }
    }

    /** Activity onCreate에서 1회 호출 - 드래그 가능하게 만들고 위치 복원, 컨트롤 버튼 연결. */
    fun attach(
        activity: Activity,
        container: ViewGroup,
        art: ImageView,
        title: TextView,
        artist: TextView,
        playPause: ImageButton,
        prev: ImageButton,
        next: ImageButton,
        keyPrefix: String,
        isLandscape: Boolean
    ) {
        PanelDragHelper.makeDraggable(activity, container, keyPrefix, isLandscape, emptyList())
        container.post {
            PanelDragHelper.restorePosition(activity, container, keyPrefix, isLandscape, emptyList())
        }

        playPause.setOnClickListener {
            val controller = activeController ?: return@setOnClickListener
            val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
            if (playing) controller.transportControls.pause() else controller.transportControls.play()
        }
        prev.setOnClickListener { activeController?.transportControls?.skipToPrevious() }
        next.setOnClickListener { activeController?.transportControls?.skipToNext() }

        refresh(activity, container, art, title, artist, playPause)
    }

    /** 설정에서 스위치를 바꾼 직후 등, 활성 세션 구독을 다시 걸어야 할 때 호출. */
    fun refresh(
        activity: Activity,
        container: ViewGroup,
        art: ImageView,
        title: TextView,
        artist: TextView,
        playPause: ImageButton
    ) {
        detachSession()

        if (!isEnabled(activity)) {
            container.visibility = View.GONE
            return
        }
        if (!hasNotificationAccess(activity)) {
            container.visibility = View.GONE
            NavLogger.d(activity, "[미니플레이어] 알림 접근 권한 없음 - 표시 건너뜀")
            return
        }

        try {
            val manager = activity.getSystemService(MediaSessionManager::class.java)
            sessionManager = manager
            val component = ComponentName(activity, MiniPlayerNotificationListener::class.java)
            val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
                pickController(activity, controllers, container, art, title, artist, playPause)
            }
            sessionsListener = listener
            manager.addOnActiveSessionsChangedListener(listener, component)
            pickController(activity, manager.getActiveSessions(component), container, art, title, artist, playPause)
        } catch (e: Exception) {
            NavLogger.e(activity, "[미니플레이어] 세션 구독 실패: ${e.message}")
            container.visibility = View.GONE
        }
    }

    private fun pickController(
        activity: Activity,
        controllers: List<MediaController>?,
        container: ViewGroup, art: ImageView, title: TextView, artist: TextView, playPause: ImageButton
    ) {
        val picked = controllers?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers?.firstOrNull()

        if (picked == activeController) {
            updateUi(container, art, title, artist, playPause, picked)
            return
        }

        activeCallback?.let { cb -> try { activeController?.unregisterCallback(cb) } catch (_: Exception) {} }
        activeController = picked

        if (picked == null) {
            activeCallback = null
            updateUi(container, art, title, artist, playPause, null)
            return
        }

        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                updateUi(container, art, title, artist, playPause, picked)
            }
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                updateUi(container, art, title, artist, playPause, picked)
            }
            override fun onSessionDestroyed() {
                refresh(activity, container, art, title, artist, playPause)
            }
        }
        activeCallback = callback
        picked.registerCallback(callback)
        updateUi(container, art, title, artist, playPause, picked)
    }

    private fun updateUi(
        container: ViewGroup, art: ImageView, title: TextView, artist: TextView, playPause: ImageButton,
        controller: MediaController?
    ) {
        val metadata = controller?.metadata
        if (controller == null || metadata == null) {
            container.visibility = View.GONE
            return
        }

        container.visibility = View.VISIBLE
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
    }
}
