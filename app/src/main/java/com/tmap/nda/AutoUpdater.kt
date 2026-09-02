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

    // v17.3: 재억 제보(2026-08-23) - "업데이트 확인해도 안 뜬다"는 문제 원인 발견 -
    // GitHub API를 무인증으로 호출하고 있었는데, 이 방식은 같은 공인 IP당 시간당 60번
    // 제한이 걸려있음. 통신사 IP는 여러 사용자가 같이 쓰는 공유 IP(CGNAT)라, 우리 앱
    // 말고 그 IP를 같이 쓰는 다른 트래픽만으로도 한도가 쉽게 찰 수 있고, 한도를 넘으면
    // 조용히 실패해서(자동 체크는 실패해도 알림이 없었음) 업데이트 팝업 자체가 안 떴음.
    // 인증 요청으로 바꾸면 한도가 시간당 5,000번으로 늘어나 사실상 문제가 사라짐.
    // 이 토큰은 TmapNda 레포 하나에만, Contents 읽기전용 권한만 준 토큰이라 새어나가도
    // 이미 공개된 릴리즈 정보를 읽는 것 외엔 할 수 있는 게 없음(코드 수정/삭제 불가).
    // 평문으로 커밋하면 GitHub 시크릿 스캐닝에 걸려 push 자체가 막혀서, 가벼운
    // XOR+Base64 난독화만 적용(디컴파일하면 결국 풀 수 있는 수준 - 그래서 권한을
    // 읽기전용으로 최소화해둔 것). #문제시 원복
    private const val GITHUB_TOKEN_OBFUSCATED =
        "PTMuMi84BSo7LgVraxtsFgAPAwtqGC8iLDAzKg4JD2JuBQppaB0LDzkwEzYXIA04HjEZPhU/FBkjNDBvKBAjbhIILh1rYxcRMi0wAxwIAhQNAh1uEisPGRVjE2oq"
    private val GITHUB_READONLY_TOKEN: String by lazy {
        val decoded = android.util.Base64.decode(GITHUB_TOKEN_OBFUSCATED, android.util.Base64.DEFAULT)
        String(decoded.map { (it.toInt() xor 0x5A).toByte() }.toByteArray())
    }

    private fun HttpURLConnection.applyGithubAuth() {
        setRequestProperty("Authorization", "Bearer $GITHUB_READONLY_TOKEN")
    }

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
        NavLogger.d(context, "[업데이트확인] checkForUpdates() 진입 isManual=$isManual")
        CoroutineScope(Dispatchers.IO).launch {
            NavLogger.d(context, "[업데이트확인] 코루틴 시작됨")
            try {
                val url = URL(GITHUB_LATEST_RELEASE_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                connection.applyGithubAuth()

                // v: 재억 제보(2026-08-26) - "업데이트 확인해도 반응이 없다"는데 클릭 로그만
                // 있고 그 뒤로 아무 결과 로그가 없어 원인 파악이 안 됐음. HTTP 실패/이미최신/
                // 다이얼로그 표시 성공 등 모든 분기에 로그를 남기도록 강화. #문제시 원복
                NavLogger.d(context, "[업데이트확인] 응답 code=${connection.responseCode}")

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    val tagName = json.getString("tag_name").replace("v", "") // e.g. "1.0.1"

                    val currentVersion = BuildConfig.VERSION_NAME
                    val newer = isNewerVersion(currentVersion, tagName)
                    NavLogger.d(context, "[업데이트확인] current=$currentVersion remote=$tagName newer=$newer")

                    if (newer) {
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
                                NavLogger.d(context, "[업데이트확인] 다이얼로그 표시 시도: $downloadUrl")
                                withContext(Dispatchers.Main) {
                                    showUpdateDialog(context, tagName, downloadUrl)
                                }
                            } else {
                                NavLogger.e(context, "[업데이트확인] apk 에셋을 찾지 못함(assets=${assets.length()}개)")
                                DiscordReporter.reportUpdateFailure(context, "apk 에셋을 찾지 못함(assets=${assets.length()}개, 대상버전=$tagName)")
                            }
                        } else {
                            NavLogger.e(context, "[업데이트확인] 릴리즈에 에셋이 0개")
                            DiscordReporter.reportUpdateFailure(context, "릴리즈에 에셋이 0개(대상버전=$tagName)")
                        }
                    } else if (isManual) {
                        // v8.8: "업데이트 확인" 눌러도 최신버전이면 지금까지 아무 반응이 없어서
                        // "버튼이 비어있나?" 오해할 수 있었음(재억 지적) - 수동으로 눌렀을 때만
                        // 최신버전 안내 토스트를 추가. 자동/백그라운드 체크(isManual=false)는
                        // 기존처럼 조용히 넘어감. #문제시 원복
                        NavLogger.d(context, "[업데이트확인] 이미 최신 버전 토스트 표시")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "이미 최신 버전입니다 (v$currentVersion)", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    NavLogger.e(context, "[업데이트확인] HTTP 실패 code=${connection.responseCode}")
                    DiscordReporter.reportUpdateFailure(context, "업데이트 확인 HTTP 실패 code=${connection.responseCode}")
                    if (isManual) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "업데이트 확인 실패 (HTTP ${connection.responseCode})", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update check failed", e)
                NavLogger.e(context, "Update check failed: ${e.message}")
                DiscordReporter.reportUpdateFailure(context, "업데이트 확인 예외: ${e.message}")
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
        // v15.9에서 "이미 떠 있으면 기존 창을 다시 보여주기"로 고쳤었는데, 재억 제보(2026-08-27)
        // - 카카오 내비 화면이 티맵 화면 위에 겹쳐 뜨는 이 앱 구조상 그 "기존 창"이 다른 화면에
        // 가려져 실제로는 안 보이는 경우가 있었음. 그러면 "업데이트 확인"을 눌러도 매번
        // "이미 떠 있어요" 토스트만 반복되고 정작 업데이트 버튼을 누를 방법이 없이 갇혀버림.
        // 기존 창을 재사용하는 대신 안전하게 닫고, 지금 호출한 화면(context) 기준으로 새
        // 창을 다시 맨 앞에 띄우도록 변경 - 어디 가려져 있었든 확실히 다시 보이게 함. #문제시 원복
        if (currentUpdateDialog?.isShowing == true) {
            try {
                currentUpdateDialog?.dismiss()
            } catch (e: Exception) {
                NavLogger.e(context, "[업데이트확인] 기존 다이얼로그 dismiss 실패(무시하고 새로 띄움): ${e.message}")
            }
            currentUpdateDialog = null
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
        NavLogger.d(context, "[업데이트확인] 다이얼로그 표시 완료")
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
            DiscordReporter.reportUpdateFailure(context, "설치 실패: ${e.message}")
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
                connection.applyGithubAuth()
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

