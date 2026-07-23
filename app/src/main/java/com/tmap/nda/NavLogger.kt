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

    private fun appendToFile(context: Context, level: String, message: String) {
        try {
            val line = "${timeFormat.format(Date())} [$level] $message\n"
            FileWriter(logFile(context), true).use { it.write(line) }
        } catch (e: Exception) {
            Log.e(TAG, "NavLogger appendToFile error: ${e.message}")
        }
    }

    fun d(context: Context, message: String) {
        Log.d(TAG, message)
        appendToFile(context, "D", message)
    }

    fun e(context: Context, message: String) {
        Log.e(TAG, message)
        appendToFile(context, "E", message)
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
