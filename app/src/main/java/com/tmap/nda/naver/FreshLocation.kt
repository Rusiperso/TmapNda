package com.tmap.nda.naver

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
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
}
