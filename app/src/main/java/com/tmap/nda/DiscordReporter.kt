package com.tmap.nda

import android.content.Context
import android.os.Build
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 크래시/나브디 연결끊김 같은 문제를 사람 조작 없이 자동 보고. 기존 "로그 보내기"(이메일,
 * NavLogger.buildShareIntent)는 그대로 두고 완전히 별도 경로로 동작함. #문제시 원복
 *
 * v: 재억 요청(2026-09-22) - 원래 디스코드 웹훅으로 보내던 걸 파이어베이스로 옮김. 디스코드
 * 웹훅이 서버 관리 권한 탈취로 반복 삭제당하는 문제(9/17, 9/22 두 번) 때문에, 재억이 크래시
 * 자동보고/사용자 집계 채널 자체를 없애기로 함. "사용현황" 실시간 신호는 이미 FirebasePresence로
 * 옮겨져 있어서(v19.4.05), 여기서는 크래시류 이벤트만 파이어베이스 realtime database의
 * crashes 노드로 씀 - 함수 이름/시그니처는 그대로 둬서 호출부(다른 파일들)는 안 건드림. #문제시 원복
 */
object DiscordReporter {

    private const val PREF_NAME = "TmapNdaPrefs"
    private const val KEY_ENABLED = "auto_report_enabled"
    private const val KEY_NICKNAME = "report_nickname"
    private const val KEY_INSTALL_ID = "report_install_id"
    private const val KEY_INSTALL_REPORTED = "install_reported"

    // 크래시 로그를 파이어베이스 realtime database 문자열 값으로 저장하므로(파일 첨부가 아님),
    // 예전 디스코드 첨부(7MB)보다 훨씬 작게 잡음 - 문제 원인은 대부분 로그 끝부분에 있어서
    // 200KB(최근 수백~수천 줄)면 충분하고, 큰 노드는 realtime database 성능에 안 좋음. #문제시 원복
    private const val MAX_LOG_CHARS = 200_000L

    // 같은 종류의 문제가 짧은 시간에 반복돼도(예: 나브디가 몇 초 간격으로 끊겼다 붙었다)
    // 도배되지 않도록 종류별 최소 간격을 둠.
    private const val THROTTLE_MS = 3 * 60 * 1000L

    private val lastSentAt = ConcurrentHashMap<String, Long>()

    // v: 재억 재제보(2026-09-03) - 워치독 멈춤 보고가 메인 스레드에서 그대로 호출되고 있었고,
    // 그 안에서 로그 파일을 읽는 게 동기적으로 실행됨 - 멈춤을 보고하려는 행위 자체가 메인
    // 스레드를 더 오래 묶어서 다음 멈춤을 더 키우는 악순환이 있었을 것으로 보임. 파일 읽기+전송을
    // 전용 백그라운드 스레드로 옮김. #문제시 원복
    private val reportExecutor = Executors.newSingleThreadExecutor()

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

    private fun ensureAnonymousAuth(): Boolean = try {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) Tasks.await(auth.signInAnonymously(), 5, TimeUnit.SECONDS)
        true
    } catch (e: Exception) {
        false
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

        val appContextSafe = context.applicationContext
        reportExecutor.execute {
            try {
                if (!ensureAnonymousAuth()) return@execute
                val id = installId(appContextSafe)
                FirebaseDatabase.getInstance().getReference("installs/$id").setValue(
                    mapOf(
                        "nickname" to getNickname(appContextSafe).ifBlank { "(미입력)" },
                        "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
                        "appVersion" to appVersion(appContextSafe),
                        "ts" to ServerValue.TIMESTAMP
                    )
                )
            } catch (e: Exception) {
                // 조용히 무시
            }
        }
    }

    fun installId(context: Context): String {
        val p = prefs(context)
        p.getString(KEY_INSTALL_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString().take(8)
        p.edit().putString(KEY_INSTALL_ID, id).apply()
        return id
    }

    fun appVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

    /** 로그 파일 끝부분(MAX_LOG_CHARS 이내)만 잘라 텍스트로 반환. 파일 없으면 빈 문자열. */
    fun tailOfLogFile(file: java.io.File): String {
        if (!file.exists() || file.length() == 0L) return ""
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                val start = if (len > MAX_LOG_CHARS) len - MAX_LOG_CHARS else 0L
                raf.seek(start)
                val buf = ByteArray((len - start).toInt())
                raf.readFully(buf)
                String(buf, StandardCharsets.UTF_8)
            }
        } catch (e: Exception) { "" }
    }

    private fun commonFields(context: Context): Map<String, String> = mapOf(
        "기기" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "앱 버전" to appVersion(context),
        "닉네임" to getNickname(context).ifBlank { "(미입력)" },
        "설치ID" to installId(context)
    )

    private fun send(
        context: Context,
        throttleKey: String,
        title: String,
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
            try {
                if (!ensureAnonymousAuth()) return@Runnable
                val logText = try { tailOfLogFile(NavLogger.activeLogFile(appContextSafe)) } catch (e: Exception) { "" }
                val fields = commonFields(appContextSafe) + extraFields.associate { it.first to it.second.take(1000) }
                val ref = FirebaseDatabase.getInstance().getReference("crashes").push()
                Tasks.await(
                    ref.setValue(
                        mapOf(
                            "title" to title,
                            "fields" to fields,
                            "log" to logText,
                            "ts" to ServerValue.TIMESTAMP
                        )
                    ),
                    6, TimeUnit.SECONDS
                )
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
            context, throttleKey = "crash", title = "🔴 앱 크래시",
            extraFields = listOf("스레드" to threadName, "에러" to stackTrace.take(500)),
            blocking = true
        )
    }

    /**
     * Context 없이도 호출 가능(블루투스 스레드 등) - NavLogger.appContext를 재사용.
     *
     * v: 재억 요청(2026-09-04) - 나브디 끊김 보고가 올 때마다 매번 로그를 뒤져서 "이 사람이
     * 나브디 직접연결과 nMirror를 같이 켜둔 건지"를 손으로 확인해야 했음(실제로 그 조합이
     * 원인인 제보가 반복됨). 그 두 가지 설정 상태와 판정을 보고서에 바로 적어서, 한 줄만 보고
     * 바로 구분할 수 있게 함. #문제시 원복
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
            context, throttleKey = "navdy_disconnect", title = "나브디 연결 끊김",
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
            context, throttleKey = "vehicle_disconnect", title = "차량(openpilot) 연결 끊김",
            extraFields = listOf("사유" to reason.take(300))
        )
    }

    /** 전역 워치독이 메인 스레드 멈춤(응답없음)을 감지했을 때. */
    fun reportMainThreadFreeze(context: Context, freezeMs: Long) {
        send(
            context, throttleKey = "main_thread_freeze", title = "🧊 화면 멈춤(응답없음)",
            extraFields = listOf("멈춘 시간" to "${freezeMs}ms")
        )
    }

    /** 카카오 경로/소요시간 계산이 실패했을 때. */
    fun reportRouteCalcFailure(context: Context, reason: String) {
        send(
            context, throttleKey = "kakao_route_calc_fail", title = "경로 계산 실패",
            extraFields = listOf("사유" to reason.take(300))
        )
    }

    /** 자동 업데이트 확인/다운로드/설치가 실패했을 때. */
    fun reportUpdateFailure(context: Context, reason: String) {
        send(
            context, throttleKey = "auto_update_fail", title = "자동 업데이트 실패",
            extraFields = listOf("사유" to reason.take(300))
        )
    }
}
