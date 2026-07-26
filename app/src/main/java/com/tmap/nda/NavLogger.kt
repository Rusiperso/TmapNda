package com.tmap.nda

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter
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

    private fun appendToFile(context: Context, level: String, message: String) {
        try {
            val file = logFile(context)
            if (file.exists() && file.length() > MAX_LOG_SIZE_BYTES) {
                val oldFile = File(file.parentFile, "tmapnda_log_prev.txt")
                oldFile.delete()
                file.renameTo(oldFile)
            }
            val line = "${timeFormat.format(Date())} [$level] $message\n"
            FileWriter(logFile(context), true).use { it.write(line) }
        } catch (e: Exception) {
            Log.e(TAG, "NavLogger appendToFile error: ${e.message}")
        }
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

    /** 로그 파일을 이메일로 공유하는 Intent 생성 (jaeeok.cho@icloud.com 으로 수신자 프리필) */
    fun buildShareIntent(context: Context): Intent? {
        val file = logFile(context)
        if (!file.exists() || file.length() == 0L) return null

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, arrayOf("jaeeok.cho@icloud.com"))
            putExtra(Intent.EXTRA_SUBJECT, "TmapNda 로그 ($FILE_NAME)")
            putExtra(Intent.EXTRA_TEXT, "TmapNda 앱에서 자동 첨부된 로그입니다.")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun clear(context: Context) {
        try {
            logFile(context).delete()
        } catch (e: Exception) {
            Log.e(TAG, "NavLogger clear error: ${e.message}")
        }
    }
}
