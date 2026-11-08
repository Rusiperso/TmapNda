package com.tmap.nda

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 재억 요청(2026-11-08) - 앱이 뒤로 가 있어도(다른 앱을 보고 있어도) 앞쪽 사고가 잡히면 팝업으로 알린다.
 * 설정 스위치(PREF_BG)를 켜면 이 서비스가 떠서 위치를 직접 받고, 화면이 없을 때만 IncidentRepository를 돌린다
 * (화면이 보이는 동안은 화면이 돌리고 기존 가운데 경고창이 뜬다). #문제시 원복
 */
class IncidentWatchService : Service() {
    private var lm: LocationManager? = null
    @Volatile private var last: Location? = null
    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) { last = l }
        @Deprecated("old API") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            NavLogger.d(this, "[돌발백그라운드] 위치 권한 없음 - 서비스 종료")
            stopSelf(); return START_NOT_STICKY
        }
        if (lm == null) {
            try {
                val m = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                lm = m
                for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                    if (m.isProviderEnabled(p)) m.requestLocationUpdates(p, 3000L, 0f, listener, Looper.getMainLooper())
                }
                last = m.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: m.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } catch (e: SecurityException) {
                NavLogger.d(this, "[돌발백그라운드] 위치 시작 실패: ${e.message}")
                stopSelf(); return START_NOT_STICKY
            }
            IncidentRepository.startBackground(this,
                location = {
                    val l = last
                    if (l == null || System.currentTimeMillis() - l.time > 60_000L) null
                    else Triple(l.latitude, l.longitude, if (l.hasBearing() && l.speed > 2f) l.bearing else null)
                },
                callback = { text, sound ->
                    val shown = IncidentAlertPopup.showOverlay(applicationContext, text, sound)
                    NavLogger.d(this, "[돌발백그라운드] 알림 '$text' 오버레이=$shown")
                })
            NavLogger.d(this, "[돌발백그라운드] 서비스 시작")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        IncidentRepository.stopBackground()
        try { lm?.removeUpdates(listener) } catch (_: Exception) { }
        lm = null
        NavLogger.d(this, "[돌발백그라운드] 서비스 종료")
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm?.createNotificationChannel(NotificationChannel(CHANNEL, "사고 알림 감시", NotificationManager.IMPORTANCE_LOW))
        }
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("앞쪽 사고 알림 켜짐")
            .setContentText("다른 앱을 쓰는 중에도 앞쪽 사고를 팝업으로 알려요.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTI_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else startForeground(NOTI_ID, n)
        } catch (e: Exception) {
            NavLogger.d(this, "[돌발백그라운드] startForeground 실패: ${e.message}")
            try { startForeground(NOTI_ID, n) } catch (_: Exception) { stopSelf() }
        }
    }

    companion object {
        private const val CHANNEL = "incident_watch"
        private const val NOTI_ID = 4712

        /** 설정(PREF_BG)에 맞춰 서비스를 켜거나 끈다. 앱 화면이 보이는 동안(포그라운드)에 불러야 안드로이드가 허용한다. */
        fun sync(ctx: Context) {
            val app = ctx.applicationContext
            try {
                val i = Intent(app, IncidentWatchService::class.java)
                if (IncidentRepository.isBgEnabled(app) && IncidentRepository.isEnabled(app)) ContextCompat.startForegroundService(app, i)
                else app.stopService(i)
            } catch (e: Exception) {
                NavLogger.d(app, "[돌발백그라운드] 켜기/끄기 실패: ${e.message}")
            }
        }
    }
}
