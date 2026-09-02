package com.tmap.nda

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * TmapNav 관련 로그를 logcat에 찍는 동시에 파일에도 누적 저장.
 * 저장 위치: <externalFilesDir>/logs/tmapnda_log.txt (FileProvider paths의 external-files-path와 매칭)
 */
object NavLogger {

    private const val TAG = "TmapNav"
    private const val FILE_NAME = "tmapnda_log.txt"
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    private fun logFile(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "logs")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    // 로그 파일에 회전/크기제한이 없어서 리플렉션 전체 필드 덤프(세션마다 반복) +
    // 주행 중 매 틱마다 rgData 전체 필드 덤프가 계속 append되어 로그 파일이 무한정 커지는 문제가
    // 있었음(실사용 며칠 만에 40~48MB, 앱 데이터 용량 문제의 주요 원인으로 확인됨).
    // 10MB 넘으면 이전 로그를 밀어내고(1개만 보관) 새로 시작.
    private const val MAX_LOG_SIZE_BYTES = 10L * 1024 * 1024

    // 이전엔 회전될 때마다 prev 파일 하나만 남기고 그 이전 건 덮어써서 지워버렸음.
    // "로그 보내기"를 오래 안 누르면 회전된 로그들이 통째로 유실되던 문제 -> 타임스탬프 파일로
    // 각각 보관하고, 공유할 때 한꺼번에 다 보낸 뒤(공유 성공 시) 삭제하는 방식으로 변경. #문제시 원복
    // v: 로그 파일 자체에는 앱 버전이 전혀 안 남아있어서, 로그만 보고는 이 로그가
    // 정확히 어느 빌드에서 난 건지 알 방법이 없었음(공유 시 이메일 본문에만 버전이
    // 적혔는데, 그 본문 텍스트를 안 보고 로그 txt 파일만 따로 보면 버전을 알 수 없음).
    // 사용자 요청으로 매 로그 줄마다 버전을 찍도록 변경 - PackageManager 호출은 비용이
    // 있으니 최초 1회만 조회해서 캐싱. #문제시 원복
    // v: 재억 제보(2026-08-26) - 앱이 자동업데이트로 새 버전을 받아 실제로는 최신 코드가
    // 돌고 있는데도, 로그엔 그 프로세스가 맨 처음 시작됐을 때의 옛날 버전이 계속 찍혔음
    // (한 번 캐싱하면 그 프로세스 생명주기 내내 다신 안 바뀌는 구조라서). PackageManager
    // 조회 비용이 크지 않으니 캐싱 자체를 없애고 매번 조회해서 항상 실제 버전을 정확히
    // 반영하도록 함. #문제시 원복
    private fun versionTag(context: Context): String {
        return try {
            "v" + (context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?")
        } catch (e: Exception) {
            "v?"
        }
    }

    // v: 재억 재제보(2026-09-03) - 워치독이 "메인스레드 멈춤"을 감지할 때마다 그걸 로그로
    // 남기고 디스코드로 보고하는 과정이 전부 메인 스레드에서(동기적으로 디스크 IO까지)
    // 실행되고 있었음. 멈춤이 한 번 생기면 → 그걸 보고하려고 메인 스레드가 또 파일 IO로
    // 묶여서 → 다음 감지 때 더 심한 멈춤으로 이어지는 악순환 가능성이 실제 로그 패턴
    // (1초대 → 25초까지 점점 심해짐)과 들어맞음. 파일 쓰기를 전용 백그라운드 스레드로
    // 옮겨서, 로그를 남기는 행위 자체가 메인 스레드를 묶지 않도록 함. #문제시 원복
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private fun appendToFile(context: Context, level: String, message: String) {
        val appContextSafe = context.applicationContext
        ioExecutor.execute {
            try {
                val file = logFile(appContextSafe)
                if (file.exists() && file.length() > MAX_LOG_SIZE_BYTES) {
                    val rotatedName = "tmapnda_log_${System.currentTimeMillis()}.txt"
                    file.renameTo(File(file.parentFile, rotatedName))
                }
                val line = "${timeFormat.format(Date())} [${versionTag(appContextSafe)}] [$level] $message\n"
                // 플랫폼 기본 charset에 의존하던 FileWriter 대신 UTF-8을 명시함.
                // 예전엔 로그 공유/전송 과정에서 한글이 mojibake(占쏙옙 패턴)로 영구 손상되는 문제가 있었음. #문제시 원복
                OutputStreamWriter(FileOutputStream(logFile(appContextSafe), true), StandardCharsets.UTF_8).use { it.write(line) }
            } catch (e: Exception) {
                Log.e(TAG, "NavLogger appendToFile error: ${e.message}")
            }
        }
    }

    /** 현재 쓰고 있는 로그 파일(회전 안 된 최신 파일). 디스코드 자동 보고 등 외부에서 첨부용으로 씀. */
    fun activeLogFile(context: Context): File = logFile(context)

    /** logs 디렉토리에 있는 tmapnda_log*.txt 전부 (현재 쓰는 파일 + 회전되어 보관중인 파일들) */
    private fun allLogFiles(context: Context): List<File> {
        val dir = File(context.getExternalFilesDir(null), "logs")
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.startsWith("tmapnda_log") && f.name.endsWith(".txt") }
            ?.filter { it.length() > 0 }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    // Context를 직접 받을 수 없는 곳(예: 오디오 포커스 후킹 등)에서도 로그를 남길 수 있도록
    // 앱 시작 시점에 한 번 세팅해두는 전역 컨텍스트
    @Volatile
    var appContext: Context? = null

    fun d(context: Context, message: String) {
        Log.d(TAG, message)
        appendToFile(context, "D", message)
    }

    fun e(context: Context, message: String) {
        Log.e(TAG, message)
        appendToFile(context, "E", message)
    }

    /** Context 없이 호출하는 버전. appContext가 세팅 안 돼있으면 logcat에만 남고 파일에는 못 남음. */
    fun d(message: String) {
        Log.d(TAG, message)
        appContext?.let { appendToFile(it, "D", message) }
    }

    fun e(message: String) {
        Log.e(TAG, message)
        appContext?.let { appendToFile(it, "E", message) }
    }

    // v: 재억 요청(2026-09-02) - "로그를 계속 쌓지 말고 상태가 바뀔 때만 남게 정리하자".
    // 실기기 로그 5.6만 줄을 세어보니 70%가 "같은 상태를 반복해서 알리는 줄"이었음
    // (예: "비콘 타임아웃" 10,766줄, "전송됨" 7,639줄, "워치독 정상" 1,092줄).
    // 값이 실제로 바뀌는 순간만 남기면 정보는 그대로면서 용량만 줄어듦.
    //
    // dIfChanged: 같은 key로 들어온 직전 메시지와 내용이 같으면 생략.
    //             (상태가 다시 바뀌면 그때 다시 남으므로 변화는 절대 놓치지 않음)
    // dThrottled: 값이 매번 달라지는 로그(좌표 등)를 최소 간격 이상일 때만 남김.
    // #문제시 원복: 각 호출부를 NavLogger.d(...)로 되돌리면 됨
    private val lastLoggedMessage = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val lastLoggedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun dIfChanged(context: Context, key: String, message: String) {
        if (lastLoggedMessage.put(key, message) == message) return
        d(context, message)
    }

    fun dIfChanged(key: String, message: String) {
        if (lastLoggedMessage.put(key, message) == message) return
        d(message)
    }

    // v: 재억 질문(2026-09-02) - "끊겼을 때 왜?만 남기면 앞뒤 상황을 몰라서 또 안 되나?"
    // 정확한 지적. 원인이 그 한 줄에 다 담기는 경우(권한 없음, 블루투스 꺼짐)는 괜찮지만,
    // "연결이 끊겼다" 같은 건 **직전에 뭘 하다 끊겼는지**가 곧 원인이라 한 줄로는 부족함.
    //
    // 그래서 평소에는 파일에 안 쓰고 메모리에만 최근 기록을 들고 있다가(trace),
    // 문제가 터지는 순간 그 직전 상황까지 한꺼번에 파일에 남김(flushTrace).
    // 평소 용량은 0이고, 문제 순간에만 앞뒤 맥락이 남음.
    // #문제시 원복: trace() 호출을 d()로 바꾸면 예전처럼 전부 기록됨
    private const val TRACE_MAX = 40
    private val traceBuffers = java.util.concurrent.ConcurrentHashMap<String, java.util.ArrayDeque<String>>()

    /** 평소엔 파일에 안 남기고 메모리에만 쌓아둠(최근 40줄). */
    fun trace(key: String, message: String) {
        val buf = traceBuffers.getOrPut(key) { java.util.ArrayDeque() }
        synchronized(buf) {
            buf.addLast("${timestamp()} $message")
            while (buf.size > TRACE_MAX) buf.removeFirst()
        }
    }

    /** 문제가 생긴 순간 호출 - 직전 상황을 한꺼번에 파일에 남기고 버퍼를 비움. */
    fun flushTrace(context: Context, key: String, reason: String) {
        val buf = traceBuffers[key]
        val lines = synchronized(buf ?: java.util.ArrayDeque<String>()) {
            buf?.toList().orEmpty().also { buf?.clear() }
        }
        e(context, reason)
        if (lines.isEmpty()) return
        e(context, "  ↑ 직전 상황 ${lines.size}줄:")
        lines.forEach { e(context, "  | $it") }
    }

    fun flushTrace(key: String, reason: String) {
        val ctx = appContext
        if (ctx != null) {
            flushTrace(ctx, key, reason)
        } else {
            e(reason)
        }
    }

    private fun timestamp(): String =
        java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.KOREA).format(java.util.Date())

    fun dThrottled(context: Context, key: String, minIntervalMs: Long, message: String) {
        val now = System.currentTimeMillis()
        val last = lastLoggedAt[key] ?: 0L
        if (now - last < minIntervalMs) return
        lastLoggedAt[key] = now
        d(context, message)
    }

    /** 로그 파일(들)을 이메일로 공유하는 Intent 생성. 회전되어 쌓여있던 로그가 있으면 전부 한번에 첨부함.
     *  v10.1: 공유 화면에서 돌아오면 MapActivity/KakaoNaviActivity의 shareLogLauncher가
     *  deleteAllLogFiles를 호출해서 삭제함(재억 재요청으로 원복). #문제시 원복 */
    fun buildShareIntent(context: Context): Pair<Intent, List<String>>? {
        val files = allLogFiles(context)
        if (files.isEmpty()) return null

        val uris = ArrayList(files.map { file ->
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        })

        // v14.7: 재억 요청 - 10MB 기준으로 주소를 갈라서 보내던 방식을 없애고, 두 주소
        // 모두에 동시에 발송되도록 함. EXTRA_EMAIL에 배열로 둘 다 넣으면 메일 앱이
        // "받는사람" 칸에 두 주소를 나란히 채워줌(이름 없이 주소만 그대로 노출). #문제시 원복
        val recipients = arrayOf("jaeeok.cho@icloud.com", "choksa55@gmail.com")

        // v5.2: 로그 전송 양식 개편(사용자 요청) - "로그 보낸 메일 주소" 항목 제거,
        // "현재 사용 중인 버전"(TmapNda 자체 앱 버전) 항목 추가. "브렌치 이름"은 openpilot
        // 자체 브랜치명을 UDP로 안 받고 있어서 정확히는 못 채우지만, openpilot이 보내는
        // 자체 버전 문자열(Carrot2 필드, 화면에 "통신중" 옆에 표시되는 그 값)이 가장
        // 가까운 대체 정보라 그걸로 자동 채움 - 연결 안 돼있으면 빈 채로 남음. #문제시 원복
        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: Exception) { "" }
        val opBranchGuess = OpenpilotStateRepository.state.value?.carrot2?.takeIf { it.isNotBlank() && it != "-" } ?: ""

        // v: 재억 요청(2026-09-02) - "자동 오류 보고"에 넣어둔 닉네임을 여기 이메일 양식에도
        // 그대로 재활용 - 저장해뒀으면 매번 다시 안 적어도 되게 함(설정 안 했으면 빈칸 유지). #문제시 원복
        val savedNickname = DiscordReporter.getNickname(context)

        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, recipients)
            putExtra(Intent.EXTRA_SUBJECT, "TmapNda 로그 (${files.size}개 파일)")
            putExtra(
            Intent.EXTRA_TEXT,
            """
            1. 디스코드 닉네임: $savedNickname
            2. 브렌치 이름: $opBranchGuess
            3. 브렌치 주소:
            4. 현재 사용 중인 버전: $appVersion
            5. 오류 증상:

            첨부 로그: 총 ${files.size}개
            """.trimIndent()
        )
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return intent to files.map { it.absolutePath }
    }

    /** 앱 종료 시 호출 - logs 디렉토리의 로그 파일(현재 파일 + 회전되어 보관중이던 파일) 전부 삭제.
     *  실행 중엔 계속 쌓이다가(10MB 넘으면 회전) 종료할 때만 비워지는 방식으로 변경. #문제시 원복 */
    fun deleteAllLogFiles(context: Context) {
        // v4.23: "로그 전체 삭제 눌러도 안 지워지는 거 같다"(사용자 8번) 확인용 - 실제로
        // 몇 개를 지우려 시도했고 몇 개가 성공했는지 로그로 남김. #문제시 원복
        val targets = allLogFiles(context)
        var success = 0
        for (file in targets) {
            try {
                if (file.delete()) success++
            } catch (e: Exception) {
                Log.e(TAG, "NavLogger deleteAllLogFiles error: ${e.message}")
            }
        }
        Log.i(TAG, "[로그삭제] 대상 ${targets.size}개 중 $success 개 삭제 성공")
    }

    fun clear(context: Context) {
        try {
            logFile(context).delete()
        } catch (e: Exception) {
            Log.e(TAG, "NavLogger clear error: ${e.message}")
        }
    }
}
