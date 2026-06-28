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

    fun checkForUpdates(context: Context) {
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
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update check failed", e)
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
        AlertDialog.Builder(context)
            .setTitle("새로운 업데이트 발견")
            .setMessage("최신 버전($newVersion)이 등록되었습니다.\n지금 업데이트 하시겠습니까?")
            .setPositiveButton("업데이트") { _, _ ->
                downloadAndInstall(context, downloadUrl, newVersion)
            }
            .setNegativeButton("나중에") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
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
        }
    }
}
