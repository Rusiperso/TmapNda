package com.tmap.nda

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.skt.tmap.engine.navigation.SDKLocationProvider
import com.skt.tmap.engine.navigation.location.LocationProviderListener
import com.skt.tmap.engine.navigation.location.OnErrorListener

/**
 * 티맵 추측 주행(터널·지하차도 등 GPS가 끊길 때). 재억 요청(2026-11-21). #문제시 원복: MapActivity의 initialize 마지막 인자(LocationInitParam) 제거
 * 티맵 SDK는 위치를 밖에서 넣는 문이 없고 "위치 제공자"를 꽂는 방법뿐이라, 실제 GPS를 그대로 SDK에 전달하다가
 * GPS가 3초 넘게 끊기면 콤마 차량 속도 × 마지막 방향으로 만든 위치를 1초마다 대신 넣어준다. GPS가 돌아오면 바로 실제 위치로 교체.
 */
class TmapDrLocationProvider : SDKLocationProvider() {
    private var listener: LocationProviderListener? = null
    private var lm: LocationManager? = null
    private val main = Handler(Looper.getMainLooper())
    private var lastRealAt = 0L          // elapsedRealtime
    private var lastReal: Location? = null
    private var cur: Location? = null
    private var active = false
    private var lastTickAt = 0L
    private var lastBearing: Float? = null
    private var started = false

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            lastRealAt = SystemClock.elapsedRealtime()
            lastReal = Location(l)
            if (l.hasBearing() && l.speed > 1.0f) lastBearing = l.bearing
            if (active) {
                active = false
                NavLogger.d(context, "[티맵추측주행] GPS 복귀 - 실제 위치로 교체")
            }
            listener?.onLocationChanged(l)
        }
        @Deprecated("old api") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    private val tick = object : Runnable {
        override fun run() {
            try { dr() } catch (e: Exception) { try { NavLogger.e(context, "[티맵추측주행] 예외: ${e.message}") } catch (_: Throwable) {} }
            if (started) main.postDelayed(this, 500L)
        }
    }

    private companion object {
        const val DR_GAP_MS = 1_600L   // GPS가 한 번 건너뛴 정도(1초 간격 기준)에서 바로 이어감
        const val DR_EMIT_MS = 900L    // 추측 위치는 그대로 약 1초에 한 번씩만 넣음
    }

    private fun dr() {
        val real = lastReal ?: return
        val now = SystemClock.elapsedRealtime()
        val gap = now - lastRealAt
        if (gap < DR_GAP_MS || gap > 15 * 60_000L) { active = false; return }
        val speed = (OpenpilotStateRepository.freshVehicleSpeedMs()?.toFloat() ?: return).coerceAtMost(45f)
        val bearing = (if (real.hasBearing()) real.bearing else lastBearing) ?: return
        // v19.4.88: 재억 요청(2026-11-22) - GPS가 끊길 때 화면이 툭 멈췄다가 뒤늦게 따라가던 문제.
        // (1) 3초 기다리고 1초마다 확인하던 걸 1.6초·0.5초 단위로 줄여 멈춰 있는 시간을 줄이고,
        // (2) 시작할 때 첫 위치도 바로 넣고, 이미 끊겨 있던 시간만큼의 거리를 한 번에 앞으로 옮겨 시작해 뒤처지지 않게 함. #문제시 원복
        val d: Double
        if (!active) {
            if (speed < 3f) return
            active = true
            lastTickAt = now
            cur = Location(real)
            d = speed * (gap / 1000.0)
            NavLogger.d(context, "[티맵추측주행] GPS ${gap / 1000.0}초째 없음 - 속도 ${(speed * 3.6).toInt()}km/h, 방향 ${bearing.toInt()}도로 위치 이어감")
        } else {
            if (now - lastTickAt < DR_EMIT_MS) return
            d = speed * ((now - lastTickAt) / 1000.0)
            lastTickAt = now
        }
        val c = cur ?: return
        val rad = Math.toRadians(bearing.toDouble())
        val next = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = c.latitude + d * Math.cos(rad) / 111_320.0
            longitude = c.longitude + d * Math.sin(rad) / (111_320.0 * Math.cos(Math.toRadians(c.latitude)))
            this.speed = speed
            this.bearing = bearing
            accuracy = 20f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        cur = next
        listener?.onLocationChanged(next)
    }

    override fun initializeProvider(l: LocationProviderListener) {
        listener = l
        try {
            val mgr = context.getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
            lm = mgr
            mgr.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, gpsListener, Looper.getMainLooper())
            started = true
            main.removeCallbacks(tick)
            main.postDelayed(tick, 500L)
        } catch (e: SecurityException) {
            NavLogger.e(context, "[티맵추측주행] 위치 권한 없음: ${e.message}")
        } catch (e: Exception) {
            NavLogger.e(context, "[티맵추측주행] 시작 예외: ${e.message}")
        }
    }

    override fun getName(): String = "GpsProvider"

    override fun stop() {
        started = false
        active = false
        main.removeCallbacks(tick)
        try { lm?.removeUpdates(gpsListener) } catch (_: Exception) {}
    }

    override fun requestUpdateOnce() {}
    override fun requestUpdate(intervalTime: Int) {}
    override fun removeLocationUpdates() {}
    override fun turnOnGPS() {}
    override fun setErrorListener(l: OnErrorListener?) {}
}
