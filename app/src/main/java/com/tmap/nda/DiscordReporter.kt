package com.tmap.nda

import android.content.Context
import android.os.Build
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 크래시/나브디 연결끊김 같은 문제를 사람 조작 없이 디스코드 채널로 자동 전송.
 * 기존 "로그 보내기"(이메일, NavLogger.buildShareIntent)는 그대로 두고 완전히 별도 경로로 동작함. #문제시 원복
 *
 * v: 재억 요청(2026-09-02) - "나스도 서버도 없는데 크래시/연결끊김을 자동으로 받을 수 있나"에 대한 답.
 * 디스코드 웹훅 주소로 그냥 HTTP 요청 한 번 던지면 디스코드가 채널 메시지로 그려주므로
 * 서버 운영이 전혀 필요 없음.
 */
object DiscordReporter {

    // 이 웹훅 주소는 앱 소스(커뮤니티에 공개되는 저장소)에 그대로 들어가므로 사실상
    // 공개된 값과 같음. 디스코드 웹훅은 그 채널에 메시지를 "보내기"만 할 수 있고 채널을
    // 읽거나 서버 다른 곳을 건드릴 수 없어서, 악용돼도 피해는 스팸 메시지 정도로 한정됨 -
    // 도배되면 디스코드에서 이 웹훅을 지우고 새로 만들어서 주소만 바꾸면 됨(다음 배포부터 반영). #문제시 원복
    private const val WEBHOOK_URL =
        "https://discord.com/api/webhooks/1544715651824877649/R1dUxez_f5QNPMnXkx9f-546M_J4iwJzDbJJUvv_LBtnshCUwwjkTzL7JStDrAX3wj20"

    private const val PREF_NAME = "TmapNdaPrefs"
    private const val KEY_ENABLED = "auto_report_enabled"
    private const val KEY_NICKNAME = "report_nickname"
    private const val KEY_INSTALL_ID = "report_install_id"

    // 디스코드 웹훅 첨부파일 용량 제한(일반 서버 기준 8MB)보다 여유있게 잡음. 로그 파일이
    // 이보다 크면 뒷부분(가장 최근 기록)만 잘라서 보냄 - 문제 원인은 대부분 끝부분에 있음.
    private const val MAX_ATTACHMENT_BYTES = 7L * 1024 * 1024

    // 같은 종류의 문제가 짧은 시간에 반복돼도(예: 나브디가 몇 초 간격으로 끊겼다 붙었다)
    // 채널이 도배되지 않도록 종류별 최소 간격을 둠.
    private const val THROTTLE_MS = 3 * 60 * 1000L

    private const val COLOR_CRASH = 0xED4245L
    private const val COLOR_DISCONNECT = 0xF0A020L
    private const val COLOR_FREEZE = 0xE74C3CL
    private const val COLOR_VEHICLE_DISCONNECT = 0xF1C40FL
    private const val COLOR_FEATURE_FAIL = 0x5865F2L

    private val lastSentAt = ConcurrentHashMap<String, Long>()

    // v: 재억 재제보(2026-09-03) - 워치독 멈춤 보고가 메인 스레드에서 그대로 호출되고 있었고,
    // 그 안에서 로그 파일을 최대 7MB까지 읽는 tailOfLogFile()이 동기적으로 실행됨 - 멈춤을
    // 보고하려는 행위 자체가 메인 스레드를 더 오래 묶어서 다음 멈춤을 더 키우는 악순환이
    // 있었을 것으로 보임. 파일 읽기+요청 조립을 전용 백그라운드 스레드로 옮김. #문제시 원복
    private val reportExecutor = Executors.newSingleThreadExecutor()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .writeTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getNickname(context: Context): String =
        prefs(context).getString(KEY_NICKNAME, "") ?: ""

    fun setNickname(context: Context, nickname: String) {
        prefs(context).edit().putString(KEY_NICKNAME, nickname.trim()).apply()
    }

    private fun installId(context: Context): String {
        val p = prefs(context)
        p.getString(KEY_INSTALL_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString().take(8)
        p.edit().putString(KEY_INSTALL_ID, id).apply()
        return id
    }

    private fun appVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

    /** 로그 파일 끝부분(MAX_ATTACHMENT_BYTES 이내)만 잘라 첨부용 바이트로 반환. 파일 없으면 null. */
    private fun tailOfLogFile(file: File): ByteArray? {
        if (!file.exists() || file.length() == 0L) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                val start = if (len > MAX_ATTACHMENT_BYTES) len - MAX_ATTACHMENT_BYTES else 0L
                raf.seek(start)
                val buf = ByteArray((len - start).toInt())
                raf.readFully(buf)
                buf
            }
        } catch (e: Exception) { null }
    }

    private fun commonFields(context: Context): List<Pair<String, String>> = listOf(
        "기기" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "앱 버전" to appVersion(context),
        "닉네임" to getNickname(context).ifBlank { "(미입력)" },
        "설치ID" to installId(context)
    )

    private fun buildPayload(title: String, color: Long, fields: List<Pair<String, String>>): String {
        val embed = JSONObject().apply {
            put("title", title)
            put("color", color)
            put("timestamp", Instant.now().toString())
            put("fields", JSONArray().apply {
                fields.forEach { (name, value) ->
                    put(JSONObject().apply {
                        put("name", name)
                        put("value", value.ifBlank { "-" }.take(1000))
                        put("inline", true)
                    })
                }
            })
        }
        return JSONObject().apply {
            put("username", "TmapNda 알리미")
            put("embeds", JSONArray().put(embed))
        }.toString()
    }

    private fun send(
        context: Context,
        throttleKey: String,
        title: String,
        color: Long,
        extraFields: List<Pair<String, String>>,
        blocking: Boolean = false
    ) {
        if (!isEnabled(context)) return
        val now = System.currentTimeMillis()
        val last = lastSentAt[throttleKey] ?: 0L
        if (now - last < THROTTLE_MS) return
        lastSentAt[throttleKey] = now

        val appContextSafe = context.applicationContext
        val task = Runnable {
            val payload = buildPayload(title, color, extraFields + commonFields(appContextSafe))
            val logBytes = try { tailOfLogFile(NavLogger.activeLogFile(appContextSafe)) } catch (e: Exception) { null }

            val bodyBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("payload_json", payload)
            if (logBytes != null) {
                bodyBuilder.addFormDataPart(
                    "files[0]", "tmapnda_log_tail.txt",
                    logBytes.toRequestBody("text/plain".toMediaTypeOrNull())
                )
            }

            val request = Request.Builder().url(WEBHOOK_URL).post(bodyBuilder.build()).build()
            try {
                client.newCall(request).execute().close()
            } catch (e: Exception) {
                // 자동 보고 자체의 실패가 앱 동작에 영향을 주면 안 되므로 조용히 무시
            }
        }

        if (blocking) {
            // 크래시 직후처럼 프로세스가 곧 죽을 수 있는 상황 - 완료(또는 타임아웃)까지
            // 기다렸다가 넘어감. 단, 대기 자체는 호출부 스레드에서 하되 실제 파일 IO/네트워크는
            // 백그라운드 스레드(reportExecutor)에서 실행되므로 그 스레드가 메인 스레드일 때도
            // 최소 작업(대기)만 하게 됨.
            try {
                reportExecutor.submit(task).get(7, TimeUnit.SECONDS)
            } catch (e: Exception) {
            }
        } else {
            reportExecutor.execute(task)
        }
    }

    /** 전역 크래시 핸들러에서 호출 - 프로세스 종료 전에 최대한 전송을 시도함(blocking). */
    fun reportCrash(context: Context, threadName: String, stackTrace: String) {
        send(
            context, throttleKey = "crash", title = "🔴 앱 크래시", color = COLOR_CRASH,
            extraFields = listOf("스레드" to threadName, "에러" to stackTrace.take(500)),
            blocking = true
        )
    }

    /** Context 없이도 호출 가능(블루투스 스레드 등) - NavLogger.appContext를 재사용. */
    fun reportNavdyDisconnect(reason: String) {
        val context = NavLogger.appContext ?: return
        send(
            context, throttleKey = "navdy_disconnect", title = "나브디 연결 끊김", color = COLOR_DISCONNECT,
            extraFields = listOf("사유" to reason.take(300))
        )
    }

    /** 폰-차량(openpilot) 간 기본 UDP 통신(비콘)이 끊겼을 때. 나브디(HUD) 연결과는 별개. */
    fun reportVehicleDisconnect(context: Context, reason: String) {
        send(
            context, throttleKey = "vehicle_disconnect", title = "차량(openpilot) 연결 끊김", color = COLOR_VEHICLE_DISCONNECT,
            extraFields = listOf("사유" to reason.take(300))
        )
    }

    /** 전역 워치독이 메인 스레드 멈춤(응답없음)을 감지했을 때. */
    fun reportMainThreadFreeze(context: Context, freezeMs: Long) {
        send(
            context, throttleKey = "main_thread_freeze", title = "🧊 화면 멈춤(응답없음)", color = COLOR_FREEZE,
            extraFields = listOf("멈춘 시간" to "${freezeMs}ms")
        )
    }

    /** 카카오 경로/소요시간 계산이 실패했을 때. */
    fun reportRouteCalcFailure(context: Context, reason: String) {
        send(
            context, throttleKey = "kakao_route_calc_fail", title = "경로 계산 실패", color = COLOR_FEATURE_FAIL,
            extraFields = listOf("사유" to reason.take(300))
        )
    }

    /** 자동 업데이트 확인/다운로드/설치가 실패했을 때. */
    fun reportUpdateFailure(context: Context, reason: String) {
        send(
            context, throttleKey = "auto_update_fail", title = "자동 업데이트 실패", color = COLOR_FEATURE_FAIL,
            extraFields = listOf("사유" to reason.take(300))
        )
    }
}
