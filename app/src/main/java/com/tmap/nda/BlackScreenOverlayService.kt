package com.tmap.nda

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat

// v: 재억 요청(2026-09-15) - 차량(AA)에 USB로 연결되면 폰 자체 화면은 검게 가려서
// 밝은 곳에서도 안 보이게 함. 엔미러의 overwrite_brightness(밝기 0)는 백라이트만
// 낮추는 거라 밝은 곳에서 내용이 비쳐 보였음 - 이 오버레이는 폰의 기본 디스플레이 위에만
// 그려지고, 차량 쪽으로 미러링되는 화면(별도 캡처/가상 디스플레이)에는 안 보임.
// 기본은 꺼짐(TmapNdaPrefs의 black_screen_on_usb_connect) - 다른 사용자에게 영향 없게
// opt-in. 켜져서 실행될 땐 항상 알림을 띄워서, 오작동으로 화면이 안 풀릴 때도 알림
// 탭 한 번으로 강제 해제할 수 있게 함(안전장치).
class BlackScreenOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showOverlay()
            ACTION_HIDE -> hideOverlay()
        }
        return START_NOT_STICKY
    }

    private fun showOverlay() {
        if (overlayView != null) return
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = View(this).apply { setBackgroundColor(Color.BLACK) }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.OPAQUE
            )
            wm.addView(view, params)
            windowManager = wm
            overlayView = view
            showNotification()
            NavLogger.d(this, "[화면블랙] 차량 연결 감지 - 폰 화면 블랙 오버레이 표시")
        } catch (e: Exception) {
            NavLogger.e(this, "[화면블랙] 오버레이 표시 실패: ${e.message}")
        }
    }

    private fun hideOverlay() {
        val wm = windowManager
        val view = overlayView
        if (wm != null && view != null) {
            try { wm.removeView(view) } catch (_: Exception) {}
            NavLogger.d(this, "[화면블랙] 차량 연결 해제 감지 - 폰 화면 블랙 오버레이 제거")
        }
        windowManager = null
        overlayView = null
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        stopSelf()
    }

    private fun showNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "TmapNda 화면 블랙 처리", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val hideIntent = Intent(this, BlackScreenOverlayService::class.java).apply { action = ACTION_HIDE }
        val hidePendingIntent = PendingIntent.getService(
            this, 0, hideIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("폰 화면 블랙 처리 중")
            .setContentText("탭하면 해제됩니다")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(hidePendingIntent)
            .setOngoing(true)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val ACTION_SHOW = "com.tmap.nda.action.BLACK_SCREEN_SHOW"
        const val ACTION_HIDE = "com.tmap.nda.action.BLACK_SCREEN_HIDE"
        private const val CHANNEL_ID = "TmapNdaBlackScreenChannel"
        private const val NOTIFICATION_ID = 2
    }
}
