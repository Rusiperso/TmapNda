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
    private val curve = DrCurve()
    private var curveBearing = 0.0
    private var curveDeg = 0.0
    // v19.4.90 부드럽게 이어가기(재억 요청 2026-11-26). #문제시 원복: 이 블록의 변수·함수와 dr()/gpsListener의 관련 줄 제거
    private var preempting = false       // 터널 입구 앞에서 GPS가 살아 있어도 미리 우리 계산 위치로 넘겨받은 상태
    private var preemptStartAt = 0L
    private var catchUp = 0.0            // 뒤처진 거리(m) - 한 번에 더하지 않고 조금씩 따라잡음
    private var blendLat = 0.0           // GPS 복귀 시 (추측 위치 - 실제 위치) 차이. 몇 초에 걸쳐 0으로 줄임
    private var blendLon = 0.0
    private var blendStartAt = 0L
    private var tunChkAt = 0L
    private var tunLastLogKey = ""

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            lastRealAt = SystemClock.elapsedRealtime()
            lastReal = Location(l)
            curve.record(l)
            if (l.hasBearing() && l.speed > 1.0f) lastBearing = l.bearing
            if (preempting) return   // 미리 넘겨받은 동안은 실제 위치를 SDK에 안 넣음(우리 위치로만 이어감)
            if (active) {
                beginBlend(l)
                active = false
                ahead = null
                NavLogger.d(context, "[티맵추측주행] GPS 복귀 - 실제 위치로 서서히 합침")
            }
            listener?.onLocationChanged(applyBlend(l))
        }
        @Deprecated("old api") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    private val tick = object : Runnable {
        override fun run() {
            try { dr() } catch (e: Exception) { try { NavLogger.e(context, "[티맵추측주행] 예외: ${e.message}") } catch (_: Throwable) {} }
            if (started) main.postDelayed(this, TICK_MS)
        }
    }

    private companion object {
        const val DR_GAP_MS = 1_200L   // GPS가 한 번 건너뛴 정도(1초 간격 기준)에서 바로 이어감
        const val TICK_MS = 250L       // 추측 위치를 약 0.25초마다 넣음(예전 1초). #문제시 원복: 500L / 900L
        const val DR_EMIT_MS = 230L
        const val PRE_M = 100.0        // 터널 입구 이 거리(경로 기준) 안에 들어오면 미리 넘겨받음
        const val PRE_MAX_MS = 12_000L // 미리 넘겨받은 뒤에도 GPS가 계속 살아 있으면(터널이 아니었음) 이 시간 뒤 원복
        const val BLEND_MS = 3_000L    // GPS 복귀 후 위치 차이를 줄이는 시간
    }

    /** 티맵 엔진이 알려주는 앞쪽 터널 입구 (위도, 경도). 없으면 null. 처음 보이거나 바뀔 때만 로그. */
    private fun readTunnelStart(): DoubleArray? {
        try {
            val nav = com.skt.tmap.engine.navigation.TmapNavigation.getInstance() ?: return null
            val info = nav.javaClass.getMethod("getTunnelInfo").invoke(nav) ?: return null
            val c = info.javaClass
            val sLat = c.getField("startLat").getDouble(info)
            val sLon = c.getField("startLon").getDouble(info)
            val key = "$sLat,$sLon"
            if (key != tunLastLogKey) {
                tunLastLogKey = key
                NavLogger.d(context, "[티맵터널] 입구=($sLat,$sLon) 출구=(${c.getField("endLat").getDouble(info)},${c.getField("endLon").getDouble(info)}) " +
                    "길이=${c.getField("tunnelLength").getInt(info)}m 남은거리=${c.getField("remainDistance").getInt(info)}m 터널안=${c.getField("isTunnel").getBoolean(info)}")
            }
            if (sLon !in 124.0..132.0 || sLat !in 33.0..39.0) return null
            return doubleArrayOf(sLat, sLon)
        } catch (e: Throwable) {
            return null
        }
    }

    /** path[0]=현재 위치부터 경로를 따라 (lat,lon)에 가장 가까운 지점까지의 거리(m). 40m 넘게 벗어나면 -1. */
    private fun pathDistTo(path: ArrayList<DoubleArray>, lat: Double, lon: Double): Double {
        var cum = 0.0
        var best = Double.MAX_VALUE
        var bestCum = -1.0
        val cosLat = Math.cos(Math.toRadians(lat))
        for (i in 0 until path.size - 1) {
            val a = path[i]
            val b = path[i + 1]
            val len = meters(a[0], a[1], b[0], b[1])
            val dx = (b[1] - a[1]) * 111_320.0 * cosLat
            val dy = (b[0] - a[0]) * 111_320.0
            val px = (lon - a[1]) * 111_320.0 * cosLat
            val py = (lat - a[0]) * 111_320.0
            val l2 = dx * dx + dy * dy
            val t = if (l2 < 0.01) 0.0 else ((px * dx + py * dy) / l2).coerceIn(0.0, 1.0)
            val d = Math.hypot(px - t * dx, py - t * dy)
            if (d < best) { best = d; bestCum = cum + t * len }
            cum += len
            if (cum > 600.0) break
        }
        return if (best < 40.0) bestCum else -1.0
    }

    private fun shouldPreempt(real: Location, bearing: Float, speed: Float, now: Long): Boolean {
        if (speed < 3f || now - tunChkAt < 1000L) return false
        tunChkAt = now
        val s = readTunnelStart() ?: return false
        if (meters(real.latitude, real.longitude, s[0], s[1]) > 250.0) return false
        val path = buildAhead(real, bearing) ?: return false
        val dist = pathDistTo(path, s[0], s[1])
        if (dist < 0.0 || dist > PRE_M) return false
        NavLogger.d(context, "[티맵추측주행] 터널 입구 ${dist.toInt()}m 앞 - GPS가 살아 있어도 미리 경로 따라 이어감")
        return true
    }

    private fun beginBlend(real: Location) {
        val c = cur ?: return
        val offM = meters(c.latitude, c.longitude, real.latitude, real.longitude)
        if (offM > 150.0) { blendStartAt = 0L; return }   // 너무 크게 어긋났으면 합치지 않고 바로 실제 위치로
        blendLat = c.latitude - real.latitude
        blendLon = c.longitude - real.longitude
        blendStartAt = SystemClock.elapsedRealtime()
    }

    private fun applyBlend(l: Location): Location {
        if (blendStartAt == 0L) return l
        val f = 1.0 - (SystemClock.elapsedRealtime() - blendStartAt).toDouble() / BLEND_MS
        if (f <= 0.0) { blendStartAt = 0L; return l }
        val o = Location(l)
        o.latitude = l.latitude + blendLat * f
        o.longitude = l.longitude + blendLon * f
        return o
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
            if (bestI < 0 || bestD > 40.0) {
                NavLogger.d(context, "[티맵추측주행] 현재 위치가 경로에서 ${bestD.toInt()}m 떨어져 있어 직선으로 진행")
                return null
            }
            val segB = brg(arr[bestI].y, arr[bestI].x, arr[bestI + 1].y, arr[bestI + 1].x)
            var diff = Math.abs(segB - bearing) % 360.0
            if (diff > 180.0) diff = 360.0 - diff
            // 경로에서 15m 넘게 떨어져 있으면 방향이 30도 안으로 거의 같을 때만 경로를 따라간다. 재억 요청(2026-11-26)
            if (bestD > 15.0 && diff > 30.0) {
                NavLogger.d(context, "[추측주행] 경로에서 ${bestD.toInt()}m 떨어져 있고 방향도 ${diff.toInt()}도 달라 직선으로 진행")
                return null
            }
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
        if (gap > 15 * 60_000L) { active = false; preempting = false; ahead = null; return }
        val speed = (OpenpilotStateRepository.freshVehicleSpeedMs()?.toFloat() ?: return).coerceAtMost(45f)
        val bearing = (if (real.hasBearing()) real.bearing else lastBearing) ?: return
        // v19.4.88: 재억 요청(2026-11-22) - GPS가 끊길 때 화면이 툭 멈췄다가 뒤늦게 따라가던 문제.
        // v19.4.90: 재억 요청(2026-11-26) - 그래도 시작할 때 훌쩍 뛰던 것을 (1) 터널 입구 앞에서 미리 넘겨받고
        // (2) 뒤처진 거리를 조금씩 따라잡고 (3) 0.25초마다 위치를 넣고 (4) GPS 복귀 때 서서히 합쳐 없앰. #문제시 원복
        var d: Double
        val dtSec: Double
        if (!active) {
            val gpsLost = gap >= DR_GAP_MS
            var pre = false
            if (!gpsLost) {
                pre = shouldPreempt(real, bearing, speed, now)
                if (!pre) return
            }
            if (speed < 3f) return
            active = true
            preempting = pre
            preemptStartAt = now
            blendStartAt = 0L
            lastTickAt = now
            cur = Location(real)
            ahead = buildAhead(real, bearing)
            curveBearing = bearing.toDouble()
            curveDeg = curve.degPerMeter()
            NavLogger.d(context, "[티맵추측주행] 경로 따라가기=${ahead != null}")
            catchUp = speed * (gap / 1000.0)   // 한 번에 더하지 않고 아래에서 조금씩 따라잡음
            d = 0.0
            dtSec = DR_EMIT_MS / 1000.0
            NavLogger.d(context, "[티맵추측주행] ${if (pre) "미리 시작" else "GPS ${gap / 1000.0}초째 없음"} - 속도 ${(speed * 3.6).toInt()}km/h, 방향 ${bearing.toInt()}도로 위치 이어감")
        } else {
            if (preempting) {
                if (gap >= DR_GAP_MS) {
                    preempting = false   // 터널에서 진짜로 GPS가 끊김 - 이후는 일반 추측주행
                } else if (speed < 3f || now - preemptStartAt > PRE_MAX_MS) {
                    // 터널이 아니었거나 GPS가 계속 살아 있음 - 실제 위치로 서서히 복귀
                    preempting = false
                    beginBlend(real)
                    active = false
                    ahead = null
                    NavLogger.d(context, "[티맵추측주행] 미리 시작 종료 - GPS가 살아 있어 실제 위치로 서서히 복귀")
                    return
                }
            }
            if (now - lastTickAt < DR_EMIT_MS) return
            dtSec = (now - lastTickAt) / 1000.0
            d = speed * dtSec
            lastTickAt = now
        }
        if (catchUp > 0.0) {
            var take = catchUp * (1.0 - Math.exp(-dtSec / 0.5))
            if (catchUp - take < 0.3) take = catchUp
            d += take
            catchUp -= take
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
            // 따라갈 경로선이 없으면 끊기기 직전의 휘어짐(커브/직선)을 그대로 이어서 간다
            val r = DrCurve.step(c.latitude, c.longitude, curveBearing, d, curveDeg)
            curveBearing = r[2]
            next.latitude = r[0]
            next.longitude = r[1]
            next.bearing = r[2].toFloat()
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
        preempting = false
        blendStartAt = 0L
        catchUp = 0.0
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
