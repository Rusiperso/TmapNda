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
    // 길 따라가기: 활성화 시점의 현재 위치부터 앞쪽 경로선(위도,경도) 목록. null이면 직선 폴백. 재억 요청(2026-11-22). #문제시 원복: ahead 관련 코드 제거
    private var ahead: ArrayList<DoubleArray>? = null

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            lastRealAt = SystemClock.elapsedRealtime()
            lastReal = Location(l)
            if (l.hasBearing() && l.speed > 1.0f) lastBearing = l.bearing
            if (active) {
                active = false
                ahead = null
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

    private fun meters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dy = (bLat - aLat) * 111_320.0
        val dx = (bLon - aLon) * 111_320.0 * Math.cos(Math.toRadians(aLat))
        return Math.hypot(dx, dy)
    }

    private fun brg(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dy = (bLat - aLat) * 111_320.0
        val dx = (bLon - aLon) * 111_320.0 * Math.cos(Math.toRadians(aLat))
        return (Math.toDegrees(Math.atan2(dx, dy)) + 360.0) % 360.0
    }

    /** 티맵 엔진의 현재 경로 꼭짓점 중, 현재 위치에서 가장 가까운 선분 이후만 골라 [현재위치, 다음 꼭짓점...] 목록으로 만든다. 못 만들면 null. */
    private fun buildAhead(real: Location, bearing: Float): ArrayList<DoubleArray>? {
        try {
            val arr = com.skt.tmap.engine.navigation.TmapNavigation.getInstance().vertexArray ?: return null
            if (arr.size < 2) return null
            val lat0 = real.latitude
            val lon0 = real.longitude
            // 좌표 모양 확인(경도 124~132, 위도 33~39 → 위경도). 아니면 쓰지 않음.
            val first = arr[0]
            if (first.x !in 124.0..132.0 || first.y !in 33.0..39.0) {
                NavLogger.d(context, "[티맵추측주행] 경로 좌표가 위경도가 아님(x=${first.x}, y=${first.y}, type=${first.coordType}) - 직선으로 진행")
                return null
            }
            var bestI = -1
            var bestD = Double.MAX_VALUE
            var bestT = 0.0
            val cosLat = Math.cos(Math.toRadians(lat0))
            for (i in 0 until arr.size - 1) {
                val ax = (arr[i].x - lon0) * 111_320.0 * cosLat
                val ay = (arr[i].y - lat0) * 111_320.0
                val bx = (arr[i + 1].x - lon0) * 111_320.0 * cosLat
                val by = (arr[i + 1].y - lat0) * 111_320.0
                val dx = bx - ax
                val dy = by - ay
                val l2 = dx * dx + dy * dy
                if (l2 < 0.01) continue
                val t = (-(ax * dx + ay * dy) / l2).coerceIn(0.0, 1.0)
                val d = Math.hypot(ax + t * dx, ay + t * dy)
                if (d < bestD) { bestD = d; bestI = i; bestT = t }
            }
            if (bestI < 0 || bestD > 60.0) {
                NavLogger.d(context, "[티맵추측주행] 현재 위치가 경로에서 ${bestD.toInt()}m 떨어져 있어 직선으로 진행")
                return null
            }
            val segB = brg(arr[bestI].y, arr[bestI].x, arr[bestI + 1].y, arr[bestI + 1].x)
            var diff = Math.abs(segB - bearing) % 360.0
            if (diff > 180.0) diff = 360.0 - diff
            if (diff > 70.0) {
                NavLogger.d(context, "[티맵추측주행] 경로 방향(${segB.toInt()}도)과 차 방향(${bearing.toInt()}도)이 달라 직선으로 진행")
                return null
            }
            val out = ArrayList<DoubleArray>()
            out.add(doubleArrayOf(arr[bestI].y + bestT * (arr[bestI + 1].y - arr[bestI].y), arr[bestI].x + bestT * (arr[bestI + 1].x - arr[bestI].x)))
            for (i in bestI + 1 until arr.size) out.add(doubleArrayOf(arr[i].y, arr[i].x))
            return out
        } catch (e: Throwable) {
            NavLogger.e(context, "[티맵추측주행] 경로 읽기 실패: ${e.message} - 직선으로 진행")
            return null
        }
    }

    /** ahead를 distM만큼 전진시키고 (위도, 경도, 방향)을 돌려준다. 경로 끝이면 끝점에 머무름. */
    private fun advanceAhead(path: ArrayList<DoubleArray>, distM: Double): DoubleArray {
        var left = distM
        while (path.size >= 2) {
            val a = path[0]
            val b = path[1]
            val len = meters(a[0], a[1], b[0], b[1])
            if (left < len || path.size == 2 && left >= len) {
                val t = if (len < 0.01) 1.0 else (left / len).coerceAtMost(1.0)
                val lat = a[0] + t * (b[0] - a[0])
                val lon = a[1] + t * (b[1] - a[1])
                path[0] = doubleArrayOf(lat, lon)
                return doubleArrayOf(lat, lon, brg(a[0], a[1], b[0], b[1]))
            }
            left -= len
            path.removeAt(0)
        }
        val a = path[0]
        return doubleArrayOf(a[0], a[1], lastBearing?.toDouble() ?: 0.0)
    }

    private fun dr() {
        val real = lastReal ?: return
        val now = SystemClock.elapsedRealtime()
        val gap = now - lastRealAt
        if (gap < DR_GAP_MS || gap > 15 * 60_000L) { active = false; ahead = null; return }
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
            ahead = buildAhead(real, bearing)
            NavLogger.d(context, "[티맵추측주행] 경로 따라가기=${ahead != null}")
            d = speed * (gap / 1000.0)
            NavLogger.d(context, "[티맵추측주행] GPS ${gap / 1000.0}초째 없음 - 속도 ${(speed * 3.6).toInt()}km/h, 방향 ${bearing.toInt()}도로 위치 이어감")
        } else {
            if (now - lastTickAt < DR_EMIT_MS) return
            d = speed * ((now - lastTickAt) / 1000.0)
            lastTickAt = now
        }
        val c = cur ?: return
        val path = ahead
        val next = Location(LocationManager.GPS_PROVIDER)
        if (path != null && path.size >= 1) {
            val r = advanceAhead(path, d)
            next.latitude = r[0]
            next.longitude = r[1]
            next.bearing = r[2].toFloat()
        } else {
            val rad = Math.toRadians(bearing.toDouble())
            next.latitude = c.latitude + d * Math.cos(rad) / 111_320.0
            next.longitude = c.longitude + d * Math.sin(rad) / (111_320.0 * Math.cos(Math.toRadians(c.latitude)))
            next.bearing = bearing
        }
        next.speed = speed
        next.accuracy = 20f
        next.time = System.currentTimeMillis()
        next.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
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
        ahead = null
        main.removeCallbacks(tick)
        try { lm?.removeUpdates(gpsListener) } catch (_: Exception) {}
    }

    override fun requestUpdateOnce() {}
    override fun requestUpdate(intervalTime: Int) {}
    override fun removeLocationUpdates() {}
    override fun turnOnGPS() {}
    override fun setErrorListener(l: OnErrorListener?) {}
}
