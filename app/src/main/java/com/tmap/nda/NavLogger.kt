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
    private fun appendToFile(context: Context, level: String, message: String) {
        try {
            val file = logFile(context)
            if (file.exists() && file.length() > MAX_LOG_SIZE_BYTES) {
                val rotatedName = "tmapnda_log_${System.currentTimeMillis()}.txt"
                file.renameTo(File(file.parentFile, rotatedName))
            }
            val line = "${timeFormat.format(Date())} [$level] $message\n"
            // 플랫폼 기본 charset에 의존하던 FileWriter 대신 UTF-8을 명시함.
            // 예전엔 로그 공유/전송 과정에서 한글이 mojibake(占쏙옙 패턴)로 영구 손상되는 문제가 있었음. #문제시 원복
            OutputStreamWriter(FileOutputStream(logFile(context), true), StandardCharsets.UTF_8).use { it.write(line) }
        } catch (e: Exception) {
            Log.e(TAG, "NavLogger appendToFile error: ${e.message}")
        }
    }

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

    /** 로그 파일(들)을 이메일로 공유하는 Intent 생성. 회전되어 쌓여있던 로그가 있으면 전부 한번에 첨부함.
     *  공유해도 파일은 삭제되지 않음 - 로그는 앱 실행 중 계속 쌓이고(10MB 넘으면 회전),
     *  앱 종료(deleteAllLogFiles) 시에만 전체 삭제됨. */
    fun buildShareIntent(context: Context): Pair<Intent, List<String>>? {
        val files = allLogFiles(context)
        if (files.isEmpty()) return null

        val uris = ArrayList(files.map { file ->
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        })

        // v5.2: 로그 전송 양식 개편(사용자 요청) - "로그 보낸 메일 주소" 항목 제거,
        // "현재 사용 중인 버전"(TmapNda 자체 앱 버전) 항목 추가. "브렌치 이름"은 openpilot
        // 자체 브랜치명을 UDP로 안 받고 있어서 정확히는 못 채우지만, openpilot이 보내는
        // 자체 버전 문자열(Carrot2 필드, 화면에 "통신중" 옆에 표시되는 그 값)이 가장
        // 가까운 대체 정보라 그걸로 자동 채움 - 연결 안 돼있으면 빈 채로 남음. #문제시 원복
        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: Exception) { "" }
        val opBranchGuess = OpenpilotStateRepository.state.value?.carrot2?.takeIf { it.isNotBlank() && it != "-" } ?: ""

        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, arrayOf("jaeeok.cho@icloud.com"))
            putExtra(Intent.EXTRA_SUBJECT, "TmapNda 로그 (${files.size}개 파일)")
            putExtra(
            Intent.EXTRA_TEXT,
            """
            1. 오픈 카톡 닉네임:
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
