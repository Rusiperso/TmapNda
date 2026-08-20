package com.tmap.nda

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object AutoUpdater {
    private const val TAG = "AutoUpdater"
    private const val GITHUB_LATEST_RELEASE_URL = "https://api.github.com/repos/Rusiperso/TmapNda/releases/latest"

    // v8.8: 사용자 요청(재억) - 실행 중에도 새 업데이트가 뜨면 바로 반응하게. GitHub이
    // 우리한테 먼저 알려주는 방법(푸시)은 없어서, 5분 간격으로 조용히 폴링. 시간당
    // 12번이라 GitHub API 무인증 제한(시간당 60번)에도 여유 있음. #문제시 원복
    private const val POLL_INTERVAL_MS = 5 * 60 * 1000L
    private val pollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pollRunnable: Runnable? = null

    fun startPeriodicCheck(context: Context) {
        if (pollRunnable != null) return // 이미 돌고 있으면 중복 등록 방지
        val runnable = object : Runnable {
            override fun run() {
                checkForUpdates(context, isManual = false)
                pollHandler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
        pollRunnable = runnable
        pollHandler.postDelayed(runnable, POLL_INTERVAL_MS)
    }

    fun stopPeriodicCheck() {
        pollRunnable?.let { pollHandler.removeCallbacks(it) }
        pollRunnable = null
    }

    // v14.6: 재억 지적 - 5분마다 확인할 때마다 이전 창을 안 닫고 새 창을 계속 쌓아서
    // 띄우고 있었음. 지금 창이 떠 있으면 새로 안 띄우게 참조를 들고 있음. "나중에"를
    // 누르면 참조를 비워서 다음 5분 뒤엔 다시 정상적으로 알려줌(재억 확인 - 나중에
    // 누르면 5분 뒤 다시 알림 오는 방식 유지). #문제시 원복
    private var currentUpdateDialog: AlertDialog? = null

    fun checkForUpdates(context: Context, isManual: Boolean = false) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(GITHUB_LATEST_RELEASE_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    val tagName = json.getString("tag_name").replace("v", "") // e.g. "1.0.1"
                    
                    val currentVersion = BuildConfig.VERSION_NAME

                    if (isNewerVersion(currentVersion, tagName)) {
                        val assets = json.getJSONArray("assets")
                        if (assets.length() > 0) {
                            var downloadUrl = ""
                            for (i in 0 until assets.length()) {
                                val asset = assets.getJSONObject(i)
                                if (asset.getString("name").endsWith(".apk")) {
                                    downloadUrl = asset.getString("browser_download_url")
                                    break
                                }
                            }
                            
                            if (downloadUrl.isNotEmpty()) {
                                withContext(Dispatchers.Main) {
                                    showUpdateDialog(context, tagName, downloadUrl)
                                }
                            }
                        }
                    } else if (isManual) {
                        // v8.8: "업데이트 확인" 눌러도 최신버전이면 지금까지 아무 반응이 없어서
                        // "버튼이 비어있나?" 오해할 수 있었음(재억 지적) - 수동으로 눌렀을 때만
                        // 최신버전 안내 토스트를 추가. 자동/백그라운드 체크(isManual=false)는
                        // 기존처럼 조용히 넘어감. #문제시 원복
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "이미 최신 버전입니다 (v$currentVersion)", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else if (isManual) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "업데이트 확인 실패 (HTTP ${connection.responseCode})", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update check failed", e)
                NavLogger.e(context, "Update check failed: ${e.message}")
                if (isManual) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "업데이트 확인 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun isNewerVersion(current: String, remote: String): Boolean {
        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
        val remoteParts = remote.split(".").map { it.toIntOrNull() ?: 0 }
        
        for (i in 0 until maxOf(currentParts.size, remoteParts.size)) {
            val c = currentParts.getOrElse(i) { 0 }
            val r = remoteParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (c > r) return false
        }
        return false
    }

    private fun showUpdateDialog(context: Context, newVersion: String, downloadUrl: String) {
        // v8.8: 주기적 백그라운드 체크 결과가 늦게 돌아왔을 때 Activity가 이미 종료된
        // 상태면 다이얼로그를 못 띄우게(WindowLeaked 크래시 방지). #문제시 원복
        if (context is android.app.Activity && (context.isFinishing || context.isDestroyed)) {
            NavLogger.d(context, "showUpdateDialog: Activity 이미 종료됨 - 스킵")
            return
        }
        // v14.6: 이미 업데이트 창이 떠 있으면 또 띄우지 않고 넘어감(중복 방지). #문제시 원복
        if (currentUpdateDialog?.isShowing == true) {
            return
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle("새로운 업데이트 발견")
            .setMessage("최신 버전($newVersion)이 등록되었습니다.\n지금 업데이트 하시겠습니까?")
            .setPositiveButton("업데이트") { _, _ ->
                currentUpdateDialog = null
                downloadAndInstall(context, downloadUrl, newVersion)
            }
            .setNegativeButton("나중에") { dialog, _ ->
                currentUpdateDialog = null
                dialog.dismiss()
            }
            .setOnCancelListener {
                currentUpdateDialog = null
            }
            .setCancelable(false)
            .create()
        currentUpdateDialog = dialog
        dialog.show()
    }

    private fun downloadAndInstall(context: Context, downloadUrl: String, version: String) {
        Toast.makeText(context, "업데이트 다운로드를 시작합니다...", Toast.LENGTH_SHORT).show()

        val fileName = "TmapNda_$version.apk"
        val destinationDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val destinationFile = File(destinationDir, fileName)

        if (destinationFile.exists()) {
            destinationFile.delete()
        }

        val request = DownloadManager.Request(Uri.parse(downloadUrl))
            .setTitle("TmapNda 업데이트 다운로드 중")
            .setDescription("버전 $version 다운로드 중입니다.")
            .setDestinationUri(Uri.fromFile(destinationFile))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)

        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(ctxt: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    installApk(context, destinationFile)
                    try {
                        context.unregisterReceiver(this)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    private fun installApk(context: Context, apkFile: File) {
        if (!apkFile.exists()) return

        // Android 8(API 26) 이상은 "출처를 알 수 없는 앱" 설치 허용이 이 앱에 대해
        // 켜져 있어야 함. 꺼져 있으면 시스템이 차단 화면을 띄우는데, 그 전에
        // 미리 확인해서 우리가 직접 안내 + 설정 화면 바로가기를 제공함.
        // (한번 허용해두면 이후 업데이트부터는 이 단계 없이 바로 설치됨)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(context)
                .setTitle("설치 권한 필요")
                .setMessage("업데이트를 설치하려면 '알 수 없는 앱 설치' 허용이 필요합니다.\n다음 화면에서 허용으로 바꿔주세요. (한번만 설정하면 다음 업데이트부터는 다시 묻지 않습니다.)")
                .setPositiveButton("설정으로 이동") { _, _ ->
                    val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(settingsIntent)
                        Toast.makeText(context, "허용 후 다시 '업데이트 확인'을 눌러주세요.", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Log.e(TAG, "Settings intent failed", e)
                        NavLogger.e(context, "Settings intent failed: ${e.message}")
                    }
                }
                .setNegativeButton("취소", null)
                .show()
            return
        }

        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile),
                    "application/vnd.android.package-archive"
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "업데이트 설치에 실패했습니다.", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "Install Failed", e)
            NavLogger.e(context, "Install Failed: ${e.message}")
        }
    }

    // v3.10: "업데이트 내역"을 브라우저로 여는 대신 앱 안에서 간단한 팝업으로 바로
    // 보여줌 (사용자 지적: "인터넷 페이지로 보내지 말고 팝업창으로 간단하게"). GitHub
    // 릴리즈 body를 그대로 가져와서 스크롤 가능한 다이얼로그에 표시. #문제시 원복
    fun showChangelogDialog(context: android.app.Activity) {
        Toast.makeText(context, "업데이트 내역 불러오는 중...", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.IO).launch {
            var body = ""
            var tag = ""
            try {
                val url = URL(GITHUB_LATEST_RELEASE_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    tag = json.optString("tag_name", "")
                    body = json.optString("body", "").ifBlank { "(업데이트 내역이 없습니다)" }
                } else {
                    body = "불러오기 실패 (HTTP ${connection.responseCode})"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Changelog fetch failed", e)
                body = "불러오기 실패: ${e.message}"
            }
            withContext(Dispatchers.Main) {
                if (context.isFinishing || context.isDestroyed) return@withContext
                val scrollView = android.widget.ScrollView(context)
                val textView = android.widget.TextView(context).apply {
                    text = body
                    setTextColor(android.graphics.Color.parseColor("#DDDDDD"))
                    setPadding(40, 20, 40, 20)
                    textSize = 13f
                }
                scrollView.addView(textView)
                AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle(if (tag.isNotBlank()) "업데이트 내역 ($tag)" else "업데이트 내역")
                    .setView(scrollView)
                    .setPositiveButton("닫기", null)
                    .show()
            }
        }
    }
}
