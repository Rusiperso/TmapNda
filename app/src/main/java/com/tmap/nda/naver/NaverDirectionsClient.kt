package com.tmap.nda.naver

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 네이버 클라우드 Directions 5/15 호출. 키는 사용자가 설정 화면에서 직접 넣은 것을 쓴다.
 * 호출 횟수는 하루/한 달 단위로 센다 - 월 3,000건 무료 한도를 넘기지 않게 화면에 보여주고 막기 위함.
 */
object NaverDirectionsClient {

    private const val URL_DIR5 = "https://maps.apigw.ntruss.com/map-direction/v1/driving"
    private const val URL_DIR15 = "https://maps.apigw.ntruss.com/map-direction-15/v1/driving"
    private const val KEY_ENDPOINT = "directions_version"
    private const val PREFS = "TndaNaverPrefs"
    private const val KEY_ID = "ncp_key_id"
    private const val KEY_SECRET = "ncp_key_secret"
    private const val KEY_USAGE_MONTH = "usage_month"
    private const val KEY_USAGE_COUNT = "usage_count"
    private const val KEY_USAGE_DAY = "usage_day"
    private const val KEY_USAGE_DAY_COUNT = "usage_day_count"

    /** 이 횟수를 넘기면 호출 자체를 막는다(무료 한도 보호). 설정에서 바꿀 수 있게 해둠. */
    const val DEFAULT_MONTHLY_CAP = 2800

    fun saveKeys(context: Context, keyId: String, keySecret: String) {
        prefs(context).edit().putString(KEY_ID, keyId.trim()).putString(KEY_SECRET, keySecret.trim()).apply()
    }

    fun keyId(context: Context): String = prefs(context).getString(KEY_ID, "").orEmpty()
    fun keySecret(context: Context): String = prefs(context).getString(KEY_SECRET, "").orEmpty()

    fun hasKeys(context: Context): Boolean {
        val p = prefs(context)
        return !p.getString(KEY_ID, "").isNullOrBlank() && !p.getString(KEY_SECRET, "").isNullOrBlank()
    }

    fun monthlyCount(context: Context): Int {
        val p = prefs(context)
        return if (p.getString(KEY_USAGE_MONTH, "") == monthTag()) p.getInt(KEY_USAGE_COUNT, 0) else 0
    }

    fun todayCount(context: Context): Int {
        val p = prefs(context)
        return if (p.getString(KEY_USAGE_DAY, "") == dayTag()) p.getInt(KEY_USAGE_DAY_COUNT, 0) else 0
    }

    /** start/goal은 (경도,위도). waypoints는 순서대로. option은 "traoptimal:trafast" 처럼 최대 3개. */
    fun requestRoute(
        context: Context,
        start: LonLat,
        goal: LonLat,
        waypoints: List<LonLat> = emptyList(),
        option: String = "traoptimal",
        monthlyCap: Int = DEFAULT_MONTHLY_CAP
    ): NaverDirectionsResult {
        val p = prefs(context)
        val id = p.getString(KEY_ID, "").orEmpty()
        val secret = p.getString(KEY_SECRET, "").orEmpty()
        if (id.isBlank() || secret.isBlank()) return NaverDirectionsResult(-10, "네이버 키가 입력되지 않았어요", emptyList())
        if (monthlyCount(context) >= monthlyCap) {
            return NaverDirectionsResult(-11, "이번 달 호출 한도($monthlyCap)에 도달했어요", emptyList())
        }

        // 내 Application에 등록된 쪽(Directions 5 또는 15)으로 부른다. 처음 쓰는 쪽이 허용 안 된 API면(429/403)
        // 반대쪽으로 한 번 더 시도하고, 성공한 쪽을 기억해둔다.
        val first = if (p.getString(KEY_ENDPOINT, "5") == "15") "15" else "5"
        var res = callOnce(context, first, id, secret, start, goal, waypoints, option)
        if (res.code == -429 || res.code == -403) {
            val other = if (first == "5") "15" else "5"
            val retry = callOnce(context, other, id, secret, start, goal, waypoints, option)
            if (retry.ok) { p.edit().putString(KEY_ENDPOINT, other).apply(); res = retry }
            else if (retry.code != -429 && retry.code != -403) res = retry
        } else if (res.ok) {
            p.edit().putString(KEY_ENDPOINT, first).apply()
        }
        return res
    }

    private fun callOnce(
        context: Context, version: String, id: String, secret: String,
        start: LonLat, goal: LonLat, waypoints: List<LonLat>, option: String
    ): NaverDirectionsResult {
        val url = buildUrl(version, start, goal, waypoints, option, carTypeCode(context))
        val tag = "[네이버길찾기 v$version $option]"
        var conn: HttpURLConnection? = null
        val result = try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 8000
                setRequestProperty("x-ncp-apigw-api-key-id", id)
                setRequestProperty("x-ncp-apigw-api-key", secret)
            }
            countCall(context)
            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val parsed = if (body.isNotBlank()) NaverDirectionsParser.parse(body) else null
                NaverDirectionsResult(
                    code = parsed?.code?.takeIf { it > 0 } ?: -status,
                    message = parsed?.message?.ifBlank { "HTTP $status" } ?: "HTTP $status",
                    routes = emptyList()
                )
            } else {
                NaverDirectionsParser.parse(body)
            }
        } catch (e: Exception) {
            NaverDirectionsResult(-12, "네트워크 오류: ${e.message}", emptyList())
        } finally {
            conn?.disconnect()
        }
        // 진단용 로그(키는 URL에 없어서 안전): 어떤 경로 요청이 어떻게 끝났는지 남긴다.
        com.tmap.nda.NavLogger.d(context, "$tag 시작=${fmt(start)} 도착=${fmt(goal)} 경유지=${waypoints.size}개 → code=${result.code} 경로=${result.routes.size}개 ${if (result.ok) "" else result.message}")
        return result
    }

    /** 설정의 차종을 네이버 통행료 등급(1~6)으로 바꾼다. 이륜차 등 대응이 없는 것은 1종. */
    private fun carTypeCode(context: Context): Int {
        val name = com.tmap.nda.CarFuelSettings.getCarType(context).name
        return Regex("""KNCarType_(d)""").find(name)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 6) ?: 1
    }

    internal fun buildUrl(version: String, start: LonLat, goal: LonLat, waypoints: List<LonLat>, option: String, carType: Int = 1): String {
        val sb = StringBuilder(if (version == "15") URL_DIR15 else URL_DIR5)
        sb.append("?start=").append(fmt(start)).append("&goal=").append(fmt(goal))
        if (waypoints.isNotEmpty()) {
            val max = if (version == "15") 15 else 5
            sb.append("&waypoints=").append(waypoints.take(max).joinToString("|") { fmt(it) })
        }
        sb.append("&option=").append(option).append("&cartype=").append(carType)
        return sb.toString()
    }

    private fun fmt(p: LonLat) = String.format(Locale.US, "%.6f,%.6f", p.lon, p.lat)

    private fun countCall(context: Context) {
        val p = prefs(context)
        val month = monthTag()
        val day = dayTag()
        val m = if (p.getString(KEY_USAGE_MONTH, "") == month) p.getInt(KEY_USAGE_COUNT, 0) else 0
        val d = if (p.getString(KEY_USAGE_DAY, "") == day) p.getInt(KEY_USAGE_DAY_COUNT, 0) else 0
        p.edit()
            .putString(KEY_USAGE_MONTH, month).putInt(KEY_USAGE_COUNT, m + 1)
            .putString(KEY_USAGE_DAY, day).putInt(KEY_USAGE_DAY_COUNT, d + 1)
            .apply()
    }

    private fun kst(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Seoul")
    }.format(Date())

    private fun monthTag() = kst("yyyyMM")
    private fun dayTag() = kst("yyyyMMdd")

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
