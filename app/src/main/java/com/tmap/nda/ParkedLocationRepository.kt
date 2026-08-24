package com.tmap.nda

import android.content.Context

/**
 * 콤마(오픈파일럿) 연결이 끊기는 순간(=시동 꺼짐으로 추정)의 마지막 GPS 위치를
 * 자동으로 저장해두는 저장소. "내 차 어디 세워뒀지?" 조회용.
 *
 * 저장 방식: 연결이 살아있는 동안 계속 최신 위치를 임시 버퍼(lastKnownLat/Lon)에 갱신해두다가,
 * 연결이 끊기는 그 즉시(지연 없이) 임시 버퍼를 확정 저장. 안드로이드가 백그라운드 서비스를
 * 강제 종료시킬 수 있는 폰 사용자 환경(엔미러 상시설치와 달리)에서도, "끊긴 뒤 기다렸다
 * 저장"이 아니라 "끊기는 즉시 저장"이라 서비스가 죽기 전에 저장이 이미 끝나 있음.
 *
 * 일시적 연결 끊김(터널 등) 오탐 방지: 저장 직후가 아니라 조회 시점에 걸러냄 - 저장된 지
 * 얼마 안 돼서(예: 5분 이내) 다시 연결이 돌아오면, 그 기록은 진짜 주차가 아니었던 것으로
 * 보고 자동 폐기.
 */
object ParkedLocationRepository {
    private const val PREF_NAME = "TmapNdaParkedLocation"
    private const val KEY_LAT = "parked_lat"
    private const val KEY_LON = "parked_lon"
    private const val KEY_TIME = "parked_time"
    private const val KEY_PENDING_CONFIRM = "parked_pending_confirm"

    // 재연결 시 오탐 판정 유예시간 - 이 안에 다시 연결되면 방금 저장한 위치를 자동 폐기
    private const val FALSE_TRIGGER_WINDOW_MS = 5 * 60 * 1000L

    // 연결이 살아있는 동안 실시간으로 갱신되는 임시 버퍼(메모리만, 저장 아님)
    @Volatile private var lastKnownLat: Double = 0.0
    @Volatile private var lastKnownLon: Double = 0.0
    @Volatile private var hasLastKnown: Boolean = false

    fun updateLiveLocation(lat: Double, lon: Double) {
        lastKnownLat = lat
        lastKnownLon = lon
        hasLastKnown = true
    }

    /** 연결 끊김이 감지된 그 즉시 호출 - 지연 없이 바로 확정 저장. */
    fun onConnectionLost(context: Context) {
        if (!hasLastKnown) return
        try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putFloat(KEY_LAT, lastKnownLat.toFloat())
                .putFloat(KEY_LON, lastKnownLon.toFloat())
                .putLong(KEY_TIME, System.currentTimeMillis())
                .putBoolean(KEY_PENDING_CONFIRM, true) // 아직 오탐 여부 미확정
                .apply()
            NavLogger.d(context, "[주차위치] 연결 끊김 감지 - 위치 저장: lat=$lastKnownLat lon=$lastKnownLon")
        } catch (e: Exception) {
            NavLogger.e(context, "[주차위치] 저장 실패: ${e.message}")
        }
    }

    /** 재연결됐을 때 호출 - 방금 저장한 게 일시적 끊김이었으면(=유예시간 내 재연결) 폐기. */
    fun onConnectionRestored(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_PENDING_CONFIRM, false)) return
            val savedTime = prefs.getLong(KEY_TIME, 0L)
            val elapsed = System.currentTimeMillis() - savedTime
            if (elapsed in 0..FALSE_TRIGGER_WINDOW_MS) {
                NavLogger.d(context, "[주차위치] ${elapsed}ms만에 재연결됨 - 일시적 끊김으로 판단, 저장된 위치 폐기")
                prefs.edit().clear().apply()
            } else {
                // 유예시간 지나서 재연결된 거면 진짜 주차였다가 다시 탄 것 - 확정으로 남김
                prefs.edit().putBoolean(KEY_PENDING_CONFIRM, false).apply()
            }
        } catch (e: Exception) {
            NavLogger.e(context, "[주차위치] 재연결 처리 실패: ${e.message}")
        }
    }

    data class ParkedLocation(val lat: Double, val lon: Double, val savedAt: Long)

    /** "내 차 위치" 조회용. 유예시간이 아직 안 지나 확정 안 된 기록은 null 반환(오탐 가능성 있음). */
    fun getConfirmedLocation(context: Context): ParkedLocation? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val lat = prefs.getFloat(KEY_LAT, 0f)
        val lon = prefs.getFloat(KEY_LON, 0f)
        val time = prefs.getLong(KEY_TIME, 0L)
        if (time == 0L) return null
        val pending = prefs.getBoolean(KEY_PENDING_CONFIRM, false)
        val elapsed = System.currentTimeMillis() - time
        if (pending && elapsed < FALSE_TRIGGER_WINDOW_MS) return null // 아직 오탐 판정 유예 중
        return ParkedLocation(lat.toDouble(), lon.toDouble(), time)
    }
}
