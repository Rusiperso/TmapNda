package com.tmap.nda

import android.content.Context
import android.location.LocationManager
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * 지도 낮/밤 모드. 설정값(자동/항상 낮/항상 밤)을 읽고, "자동"이면 폰 위치로 오늘의
 * 해 뜨는 시각·지는 시각을 계산해서 지금이 밤인지 알려줌. 위치를 못 얻으면 마지막으로
 * 저장해둔 위치, 그것도 없으면 서울 좌표로 계산. #문제시 원복
 */
object DayNightHelper {
    const val KEY_MODE = "map_daynight_mode"
    const val MODE_AUTO = "auto"
    const val MODE_DAY = "day"
    const val MODE_NIGHT = "night"
    private const val KEY_LAT = "daynight_last_lat"
    private const val KEY_LON = "daynight_last_lon"

    fun mode(context: Context): String =
        context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
            .getString(KEY_MODE, MODE_AUTO) ?: MODE_AUTO

    fun isNight(context: Context): Boolean = when (mode(context)) {
        MODE_DAY -> false
        MODE_NIGHT -> true
        else -> isNightBySun(context)
    }

    private fun isNightBySun(context: Context): Boolean {
        val pref = context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
        var lat = pref.getFloat(KEY_LAT, 37.5665f).toDouble()
        var lon = pref.getFloat(KEY_LON, 126.9780f).toDouble()
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (loc != null) {
                lat = loc.latitude
                lon = loc.longitude
                pref.edit().putFloat(KEY_LAT, lat.toFloat()).putFloat(KEY_LON, lon.toFloat()).apply()
            }
        } catch (e: Exception) {
            // 위치 권한이 없거나 실패하면 저장된 값/서울 좌표 그대로 사용
        }
        return try {
            val cal = Calendar.getInstance()
            val tzOffsetMin = TimeZone.getDefault().getOffset(cal.timeInMillis) / 60000.0
            val doy = cal.get(Calendar.DAY_OF_YEAR)
            val gamma = 2.0 * Math.PI / 365.0 * (doy - 1)
            val eqTime = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
                0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
            val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) -
                0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma) -
                0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
            val latRad = Math.toRadians(lat)
            val cosHa = cos(Math.toRadians(90.833)) / (cos(latRad) * cos(decl)) - tan(latRad) * tan(decl)
            if (cosHa >= 1.0) return true    // 하루 종일 밤(극지방)
            if (cosHa <= -1.0) return false  // 하루 종일 낮
            val haDeg = Math.toDegrees(acos(cosHa))
            val sunriseLocal = norm(720 - 4 * (lon + haDeg) - eqTime + tzOffsetMin)
            val sunsetLocal = norm(720 - 4 * (lon - haDeg) - eqTime + tzOffsetMin)
            val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND) / 60.0
            val isDay = if (sunriseLocal <= sunsetLocal) now in sunriseLocal..sunsetLocal
            else now >= sunriseLocal || now <= sunsetLocal
            !isDay
        } catch (e: Exception) {
            val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            h >= 19 || h < 6
        }
    }

    private fun norm(min: Double): Double = ((min % 1440.0) + 1440.0) % 1440.0
}
