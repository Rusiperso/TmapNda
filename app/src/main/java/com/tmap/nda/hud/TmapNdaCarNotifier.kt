package com.tmap.nda.hud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarPendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tmap.nda.KakaoRouteDataRepository
import com.tmap.nda.KakaoRouteSnapshot
import com.tmap.nda.NavLogger
import com.tmap.nda.R

private const val TAG = "TmapNdaCarNotifier"
private const val CAR_NOTI_CHANNEL_ID = "tmapnda_car_navigation"
private const val CAR_NOTI_ID = 2002

/**
 * [초안 - 미커밋] 카카오 길안내가 시작되면 CarAppExtender로 확장한 알림을 등록해서,
 * 안드로이드 오토가 스스로 TmapNdaCarAppService.startCarApp()을 호출하도록 유도한다.
 *
 * 순정 티맵 v11.2.3(CarrotNavi 패치판) 디컴파일 결과, TmapCarSession/
 * NavigationNotificationServiceKt가 정확히 이 패턴(NotificationCompat.Builder를
 * androidx.car.app.notification.CarAppExtender로 extend() 후 CarNotificationManager로
 * post)을 쓰고 있었음을 확인함. 이게 없으면 안드로이드 오토는 사용자가 차량
 * 런처에서 앱을 직접 열기 전까지 CarAppService를 절대 안 부른다
 * (TmapNdaHud.everConnected가 계속 false였던 원인과 일치).
 *
 * #검증필요: CarPendingIntent.getCarApp()의 intent 파라미터 구성(특히 컴포넌트
 * 지정 방식)은 androidx.car.app:app 1.7.0 실제 API로 실기기 로그까지 확인 안 된
 * 상태 - 반드시 실차 로그(TmapNdaHud everConnected)로 검증 필요. #문제시 원복
 */
object TmapNdaCarNotifier {

    @Volatile private var isPosted = false
    @Volatile private var appContext: Context? = null

    private val routeListener: (KakaoRouteSnapshot) -> Unit = { value -> onSnapshot(value) }

    fun start(context: Context) {
        val app = context.applicationContext
        appContext = app
        createChannel(app)
        KakaoRouteDataRepository.addListener(routeListener)
        Log.i(TAG, "TmapNdaCarNotifier 시작됨")
        NavLogger.d(app, "[$TAG] 시작됨")
    }

    fun stop() {
        val app = appContext
        KakaoRouteDataRepository.removeListener(routeListener)
        if (app != null) {
            cancelNotification(app)
        }
        appContext = null
    }

    private fun onSnapshot(value: KakaoRouteSnapshot) {
        val context = appContext ?: return
        if (value.isActive) {
            postOrUpdateNotification(context, value)
        } else if (isPosted) {
            cancelNotification(context)
        }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CAR_NOTI_CHANNEL_ID,
                "TmapNda 차량 안내",
                NotificationManager.IMPORTANCE_LOW
            )
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun postOrUpdateNotification(context: Context, value: KakaoRouteSnapshot) {
        val startIntent = Intent(context, TmapNdaCarAppService::class.java)

        // v: CarPendingIntent로 감싸야 안드로이드 오토 쪽 CarAppNotificationBroadcastReceiver가
        // 이 알림을 "startCarApp 트리거"로 인식함(순정 라이브러리 문자열
        // "startCarApp from notification" 확인). 일반 PendingIntent.getService()로는 동작 안 함.
        val carPendingIntent: PendingIntent = CarPendingIntent.getCarApp(
            context,
            0,
            startIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = value.tbtMainText.ifBlank { "카카오 길안내" }
        val text = value.roadName.ifBlank { "안내 중" }

        val builder = NotificationCompat.Builder(context, CAR_NOTI_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(carPendingIntent)
            .extend(
                CarAppExtender.Builder()
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(carPendingIntent)
                    .setImportance(NotificationManager.IMPORTANCE_HIGH)
                    .build()
            )

        runCatching {
            NotificationManagerCompat.from(context).notify(CAR_NOTI_ID, builder.build())
            isPosted = true
        }.onFailure { e ->
            Log.e(TAG, "차량 알림 등록 실패", e)
            NavLogger.e(context, "[$TAG] 차량 알림 등록 실패: ${e.message}")
        }
    }

    private fun cancelNotification(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(CAR_NOTI_ID)
        }
        isPosted = false
    }
}
