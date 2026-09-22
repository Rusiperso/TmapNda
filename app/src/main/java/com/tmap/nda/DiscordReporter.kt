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

    // v: 재억 요청(2026-09-22) - 웹훅 주소를 소스에 그대로 박아두니(공개 저장소) 깃허브를
    // 긁는 스팸봇이 찾아내 도배 → 디스코드가 웹훅을 자동 삭제하는 일이 반복됨(9/17, 9/22).
    // 이제 소스엔 값을 안 남기고 BuildConfig(빌드 시점 GitHub Actions 시크릿 주입, build.gradle.kts
    // 참고)로만 받는다. 로컬 빌드처럼 시크릿이 없으면 빈 문자열이 들어오고, 그 경우 아래 send
    // 함수들이 조용히 건너뜀. #문제시 원복
    private val WEBHOOK_URL get() = BuildConfig.CRASH_WEBHOOK_URL

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

    // v: 재억 요청(2026-09-15) - "지금 몇 명이나 쓰고 있는지 알 방법 없나" - 사용자에게
    // 새로 뭘 공개하지 않고(README 문구도 그대로), 이미 있는 "자동 오류 보고" 설치ID를
    // 재사용해서 하루 한 번만 "그냥 살아있다"는 익명 신호를 같은 웹훅으로 보냄. 크래시
    // 로그 첨부 없이 설치ID/기기/버전만 담김 - 자동 오류 보고를 꺼둔 사람은 이것도 안 감
    // (isEnabled 그대로 재사용, 새 동의를 따로 받을 필요 없음). #문제시 원복
    // v: 재억 요청(2026-09-15) - 크래시 보고 채널과 섞이면 재억이 그 채널을 볼 때마다 매번
    // 보이니까, 완전히 조용한 전용 채널("사용현황")을 따로 만들어서 거기로만 보냄. 클로드는
    // 이 채널을 먼저 언급하지 않고, 재억이 "몇 명이나 써?"라고 물어볼 때만 확인해서 답함. #문제시 원복
    private val HEARTBEAT_WEBHOOK_URL get() = BuildConfig.USAGE_WEBHOOK_URL
    private const val KEY_HB_MSG_ID = "usage_heartbeat_message_id"
    private const val KEY_HB_MSG_DAY = "usage_heartbeat_message_day"
    private const val HEARTBEAT_INTERVAL_MIN = 5L
    private const val COLOR_HEARTBEAT = 0x57F287L
    private const val KEY_INSTALL_REPORTED = "install_reported"
    private const val COLOR_INSTALL = 0x3498DBL

    // v: 재억 요청(2026-09-21) - "지금 켜져 있는 사람"을 알 수 있게, 앱이 떠 있는 동안 5분마다
    // 신호를 보냄(폰이 꺼지거나 앱 프로세스가 죽으면 저절로 멈춤). 5분마다 새 메시지를 올리면
    // 사용현황 채널이 도배되고 일일 집계가 깨지므로, 하루에 메시지를 하나만 만들고 그 메시지를
    // 5분마다 고쳐씀(웹훅 메시지 수정). 하루 첫 신호는 예전처럼 새 메시지라 집계 방식은 그대로. #문제시 원복
    private val heartbeatStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    private val heartbeatScheduler by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "usage-heartbeat").apply { isDaemon = true }
        }
    }

    fun reportHeartbeatIfDue(context: Context) {
        if (!isEnabled(context)) return
        if (!heartbeatStarted.compareAndSet(false, true)) return
        val appContextSafe = context.applicationContext
        heartbeatScheduler.scheduleWithFixedDelay({
            try {
                if (isEnabled(appContextSafe)) sendHeartbeatTick(appContextSafe)
            } catch (e: Exception) {
                // 조용히 무시 - 이 신호 실패가 앱 동작에 영향을 주면 안 됨
            }
        }, 0L, HEARTBEAT_INTERVAL_MIN, TimeUnit.MINUTES)
    }

    private fun sendHeartbeatTick(context: Context) {
        if (HEARTBEAT_WEBHOOK_URL.isBlank()) return
        val zone = java.time.ZoneId.of("Asia/Seoul")
        val now = java.time.ZonedDateTime.now(zone)
        val today = now.toLocalDate().toString()
        val fields = commonFields(context) + ("마지막 신호" to "%02d:%02d".format(now.hour, now.minute))
        val payload = buildPayload("[사용중]", COLOR_HEARTBEAT, fields)
        val p = prefs(context)
        val msgId = p.getString(KEY_HB_MSG_ID, null)

        if (msgId != null && p.getString(KEY_HB_MSG_DAY, null) == today) {
            val editBody = JSONObject(payload).apply { remove("username") }.toString()
                .toRequestBody("application/json".toMediaTypeOrNull())
            val edit = Request.Builder().url("$HEARTBEAT_WEBHOOK_URL/messages/$msgId").patch(editBody).build()
            client.newCall(edit).execute().use { res ->
                // 메시지가 지워졌으면(404) 아래에서 새로 만들고, 그 외 실패는 다음 주기에 다시 시도
                if (res.code != 404) return
            }
        }

        val body = payload.toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder().url("$HEARTBEAT_WEBHOOK_URL?wait=true").post(body).build()
        client.newCall(request).execute().use { res ->
            if (!res.isSuccessful) return
            val id = JSONObject(res.body?.string() ?: return).optString("id")
            if (id.isNotEmpty()) p.edit().putString(KEY_HB_MSG_ID, id).putString(KEY_HB_MSG_DAY, today).apply()
        }
    }

    /**
     * 설치 후 첫 실행 때 딱 한 번만 신호를 보냄 - 신규 설치 수 집계용.
     * 설치ID가 아직 없는 경우(=진짜 첫 실행)에만 보내야 하므로, installId()가 새 ID를
     * 만들어서 저장하기 전에 먼저 확인함 - 이미 쓰던 사람이 업데이트로 이 기능을 처음
     * 받는 경우(설치ID는 이미 있음)까지 "신규 설치"로 잘못 세는 걸 막기 위함.
     */
    fun reportInstallIfNew(context: Context) {
        if (!isEnabled(context)) return
        val p = prefs(context)
        if (p.contains(KEY_INSTALL_ID) || p.getBoolean(KEY_INSTALL_REPORTED, false)) return
        p.edit().putBoolean(KEY_INSTALL_REPORTED, true).apply()
        if (HEARTBEAT_WEBHOOK_URL.isBlank()) return

        val appContextSafe = context.applicationContext
        reportExecutor.submit {
            try {
                val payload = buildPayload("[신규설치]", COLOR_INSTALL, commonFields(appContextSafe))
                val body = payload.toRequestBody("application/json".toMediaTypeOrNull())
                val request = Request.Builder().url(HEARTBEAT_WEBHOOK_URL).post(body).build()
                client.newCall(request).execute().close()
            } catch (e: Exception) {
                // 조용히 무시
            }
        }
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
        if (WEBHOOK_URL.isBlank()) return
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

    /**
     * Context 없이도 호출 가능(블루투스 스레드 등) - NavLogger.appContext를 재사용.
     *
     * v: 재억 요청(2026-09-04) - 나브디 끊김 보고가 올 때마다 매번 로그를 뒤져서 "이 사람이
     * 나브디 직접연결과 nMirror를 같이 켜둔 건지"를 손으로 확인해야 했음(실제로 그 조합이
     * 원인인 제보가 반복됨). 그 두 가지 설정 상태와 판정을 보고서에 바로 적어서, 채널에서
     * 한 줄만 보고 바로 구분할 수 있게 함. #문제시 원복
     */
    fun reportNavdyDisconnect(reason: String) {
        val context = NavLogger.appContext ?: return
        val pref = prefs(context)
        val directConnectOn = pref.getBoolean("REQ_NAVDY", false)
        val nMirrorInstalled = com.tmap.nda.nmirror.NMirrorSender.isInstalled(context)
        val nMirrorRelayOn = com.tmap.nda.nmirror.NMirrorSender.isEnabled(context)

        val nMirrorState = when {
            !nMirrorInstalled -> "설치 안 됨"
            nMirrorRelayOn -> "설치됨 + 안내전달 켜짐"
            else -> "설치됨 (안내전달 꺼짐)"
        }
        // 나브디는 한 번에 앱 하나만 붙을 수 있어서, 이 둘이 같이 켜져 있으면 자리 경합이 확정임
        val verdict = if (directConnectOn && nMirrorInstalled) {
            "⚠️ 나브디 직접연결 + nMirror 동시 사용 - 서로 자리를 뺏는 상태(도움말대로 둘 중 하나만 켜야 함)"
        } else {
            "직접연결과 nMirror가 겹치지는 않음 - 다른 원인"
        }

        send(
            context, throttleKey = "navdy_disconnect", title = "나브디 연결 끊김", color = COLOR_DISCONNECT,
            extraFields = listOf(
                "사유" to reason.take(300),
                "나브디 직접연결" to if (directConnectOn) "켜짐" else "꺼짐",
                "nMirror" to nMirrorState,
                "판정" to verdict
            )
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
