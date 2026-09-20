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
    // v: 재억 제보(2026-09-20) - 위 검은 창(오버레이)은 엔미러가 폰 화면을 통째로 캡처할 때 같이
    // 찍혀서 차량 인포화면도 까맣게 됐음. 루팅된 기기에선 SurfaceFlinger 색 변환(야간모드와
    // 같은 출력 단계 곱셈)을 전부 0으로 놓아 폰 패널만 까맣게 하고, 캡처에는 영향이 없게 함
    // (DHU로 확인: 폰은 까맣고 차 화면은 정상). 루트가 없으면 예전 오버레이 방식으로 폴백. #문제시 원복

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showOverlay()
            ACTION_HIDE -> hideOverlay()
        }
        return START_NOT_STICKY
    }

    private fun runRoot(cmd: String): Boolean = try {
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        proc.waitFor(4, java.util.concurrent.TimeUnit.SECONDS) && proc.exitValue() == 0
    } catch (e: Exception) {
        false
    }

    private fun showOverlay() {
        if (overlayView != null || rootModeActive) return
        // 색 변환(전부 0) + 앱 프로세스가 죽으면 자동으로 원래대로 돌리는 감시 프로세스(안전장치)
        val zeros = "f 0 ".repeat(15) + "f 1"
        val on = "touch $ROOT_FLAG; service call SurfaceFlinger 1015 i32 1 $zeros; " +
            "nohup sh -c 'while [ -f $ROOT_FLAG ] && kill -0 ${android.os.Process.myPid()} 2>/dev/null; do sleep 2; done; " +
            "service call SurfaceFlinger 1015 i32 0; rm -f $ROOT_FLAG' </dev/null >/dev/null 2>&1 &"
        if (runRoot(on)) {
            rootModeActive = true
            showNotification()
            NavLogger.d(this, "[화면블랙] 차량 연결 감지 - 폰 패널만 블랙(루트 색 변환, 차량 화면 영향 없음)")
            return
        }
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

    private fun resetRootBlack() {
        // 서비스가 시스템에 의해 종료됐다 다시 만들어져도(인스턴스가 바뀌어도) 해제되도록 앱 전체 상태로 관리
        if (!rootModeActive) return
        rootModeActive = false
        runRoot("rm -f $ROOT_FLAG; service call SurfaceFlinger 1015 i32 0")
        NavLogger.d(this, "[화면블랙] 차량 연결 해제 감지 - 폰 패널 블랙(루트 색 변환) 해제")
    }

    private fun hideOverlay() {
        resetRootBlack()
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
        @Volatile private var rootModeActive = false
        private const val ROOT_FLAG = "/data/local/tmp/tmapnda_black"
    }
}
