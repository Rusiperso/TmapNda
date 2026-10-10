package com.tmap.nda.naver

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * "마지막으로 알던 위치" 중 최근 것만 돌려준다.
 * 시스템의 getLastKnownLocation(GPS)은 몇 주 전 위치를 그대로 줄 수 있어서(예: 다른 도시), 그걸 현재 위치로 쓰면
 * 길찾기 출발점이 엉뚱한 곳이 된다. 그래서 [maxAgeMs]보다 오래된 값은 버리고, 남은 것 중 가장 정확한 값을 쓴다.
 */
object FreshLocation {
    fun lastFresh(context: Context, maxAgeMs: Long = 120_000L): Location? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val nowNs = SystemClock.elapsedRealtimeNanos()
        return lm.getProviders(true)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { (nowNs - it.elapsedRealtimeNanos) / 1_000_000L <= maxAgeMs }
            .minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }
    }

    // 길안내 시작 때 GPS·저장된 위치가 둘 다 비어 있으면, 위치를 직접 한 번 요청해서 받아둔다.
    @Volatile private var quickFix: Location? = null
    private var quickFixListener: LocationListener? = null

    fun startQuickFix(context: Context) {
        if (quickFixListener != null) return
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                quickFix = location
                if (location.provider == LocationManager.GPS_PROVIDER) stopQuickFix(lm)
            }
            @Deprecated("API 29 이하 호환용")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        quickFixListener = l
        runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, l, Looper.getMainLooper()) }
        runCatching { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0f, l, Looper.getMainLooper()) }
        Handler(Looper.getMainLooper()).postDelayed({ stopQuickFix(lm) }, 60_000L)
    }

    private fun stopQuickFix(lm: LocationManager) {
        quickFixListener?.let { runCatching { lm.removeUpdates(it) } }
        quickFixListener = null
    }

    /** 위치를 직접 요청해 받아둔 값 중 [maxAgeMs] 이내인 것(없으면 null). */
    fun recentQuickFix(maxAgeMs: Long = 600_000L): Location? {
        val q = quickFix ?: return null
        return if ((SystemClock.elapsedRealtimeNanos() - q.elapsedRealtimeNanos) / 1_000_000L <= maxAgeMs) q else null
    }
}
