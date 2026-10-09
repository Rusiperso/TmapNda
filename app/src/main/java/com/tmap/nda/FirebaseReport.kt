package com.tmap.nda

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener

/**
 * v: 재억 요청(2026-09-22) - Board에서 "로그 요청" 버튼을 누르면 이 기기가 지금 가진 로그를
 * 통째로(회전 안 된 최신 파일 기준, 최근 최대 200KB) 파이어베이스로 올림. NavLogger는 항상
 * 켜져서 계속 기록만 하고 있으므로(따로 켜고 끄는 스위치가 없음) "로그 요청" 시점은 새로
 * 기록을 시작하는 게 아니라 이미 쌓여 있던 걸 그 시점에 퍼올리는 것. #문제시 원복
 */
object FirebaseReport {

    fun watchLogRequests(context: Context) {
        if (!DiscordReporter.isEnabled(context)) return
        val appContextSafe = context.applicationContext
        try {
            val auth = FirebaseAuth.getInstance()
            fun afterAuth() = attachListener(appContextSafe)
            if (auth.currentUser != null) afterAuth()
            else auth.signInAnonymously().addOnSuccessListener { afterAuth() }
        } catch (e: Exception) {
            // 조용히 무시
        }
    }

    // v: 재억 요청(2026-09-22) - 로그 요청 때마다 logs/<설치ID>에 200KB짜리가 계속 쌓여서
    // 저장소가 차는 문제 - 최신 KEEP_LOGS개만 남기고 나머지는 지움. push 키는 시간순이라
    // 키 순서 = 올린 순서. #문제시 원복
    private const val KEEP_LOGS = 3

    // 2026-10-09 파이어베이스 다운로드 한도 절약 - 예전엔 정리할 때마다 logs/<ID> 전체(최대 4.5MB)를
    // 내려받아 키를 셌음. 이제는 내가 올린 키를 폰에 기억해두고, 오래된 것만 키로 바로 지움(다운로드 0).
    // 이 방식 도입 전에 올라가 있던 옛 로그(최대 3개)는 폰이 모르니 그대로 남음. #문제시 원복
    private fun rememberAndTrim(context: Context, db: FirebaseDatabase, id: String, newKey: String) {
        try {
            val prefs = context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
            val keys = (prefs.getString("uploaded_log_keys", "") ?: "").split(",").filter { it.isNotBlank() } + newKey
            keys.dropLast(KEEP_LOGS).forEach { db.getReference("logs/$id/$it").removeValue() }
            prefs.edit().putString("uploaded_log_keys", keys.takeLast(KEEP_LOGS).joinToString(",")).apply()
        } catch (e: Exception) {
            // 조용히 무시
        }
    }

    // v: 재억 요청(2026-09-29) - Board에서 "테스트 배포"를 누르면 testUpdate/<설치ID>에
    // {tag, url}이 써짐. 값이 생기면 AutoUpdater에 담아두고(다음 업데이트 확인 때 창으로 뜸),
    // 값이 지워지면 비움. 일반 릴리즈와 무관. #문제시 원복
    private fun attachTestUpdateListener(appContextSafe: Context) {
        try {
            val id = DiscordReporter.installId(appContextSafe)
            FirebaseDatabase.getInstance().getReference("testUpdate/$id")
                .addValueEventListener(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        AutoUpdater.setPendingTestUpdate(
                            snapshot.child("tag").getValue(String::class.java),
                            snapshot.child("url").getValue(String::class.java)
                        )
                    }

                    override fun onCancelled(error: DatabaseError) {
                        // 조용히 무시
                    }
                })
        } catch (e: Exception) {
            // 조용히 무시
        }
    }

    // 2026-10-09 재억 지시: 보관 중인 로그(4일치)를 통째로 원격 요청으로 받기. 새 경로는 파이어베이스 규칙상
    // 막혀 있어서 logs/<ID> 아래에 조각(part)으로 올림. 파일별로 gzip(여러 조각을 이어붙여도 유효한 gzip)
    // 후 base64 -> 90만 글자씩. 최신 파일부터 담아 총 8MB 압축분까지만(넘으면 오래된 것 생략).
    // 마지막에 평소 형식 항목(log=끝부분)을 한 번 더 올려서 기존 Board의 "최신 1건" 읽기는 그대로 동작.
    // 이전 조각 묶음은 폰이 키를 기억해 바로 지움(다운로드 0). #문제시 원복
    private const val FULL_GZ_LIMIT = 8 * 1024 * 1024
    private const val FULL_PART_CHARS = 900_000

    private fun uploadFullLogs(context: Context, db: FirebaseDatabase, id: String) {
        val files = NavLogger.allLogFilesChronological(context)
        val picked = ArrayList<ByteArray>()
        var total = 0
        var rawTotal = 0L
        for (f in files.reversed()) {
            val bos = java.io.ByteArrayOutputStream()
            java.util.zip.GZIPOutputStream(bos).use { gz -> f.inputStream().use { it.copyTo(gz) } }
            val bytes = bos.toByteArray()
            if (total + bytes.size > FULL_GZ_LIMIT && picked.isNotEmpty()) break
            picked.add(bytes); total += bytes.size; rawTotal += f.length()
        }
        picked.reverse()
        val all = java.io.ByteArrayOutputStream().also { o -> picked.forEach { o.write(it) } }.toByteArray()
        val b64 = android.util.Base64.encodeToString(all, android.util.Base64.NO_WRAP)
        val parts = b64.chunked(FULL_PART_CHARS)
        val prefs = context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
        (prefs.getString("uploaded_full_keys", "") ?: "").split(",").filter { it.isNotBlank() }
            .forEach { db.getReference("logs/$id/$it").removeValue() }
        val setId = System.currentTimeMillis().toString()
        val keys = ArrayList<String>()
        parts.forEachIndexed { i, chunk ->
            val r = db.getReference("logs/$id").push()
            r.key?.let { keys.add(it) }
            r.setValue(mapOf("log" to "", "gz" to chunk, "part" to i + 1, "parts" to parts.size, "setId" to setId,
                "files" to picked.size, "totalFiles" to files.size, "rawBytes" to rawTotal, "ts" to ServerValue.TIMESTAMP))
        }
        prefs.edit().putString("uploaded_full_keys", keys.joinToString(",")).apply()
        val tail = try { DiscordReporter.tailOfLogFile(NavLogger.activeLogFile(context)) } catch (e: Exception) { "" }
        val last = db.getReference("logs/$id").push()
        last.setValue(mapOf("log" to tail, "appVersion" to DiscordReporter.appVersion(context), "fullSet" to setId,
            "fullParts" to parts.size, "ts" to ServerValue.TIMESTAMP))
            .addOnCompleteListener { last.key?.let { k -> rememberAndTrim(context, db, id, k) } }
    }

    private fun attachListener(appContextSafe: Context) {
        attachTestUpdateListener(appContextSafe)
        try {
            val id = DiscordReporter.installId(appContextSafe)
            val db = FirebaseDatabase.getInstance()
            val reqRef = db.getReference("logRequests/$id")
            reqRef.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    // true = 평소처럼 최근 로그 끝부분, "full" = 보관 중인 4일치 전부(압축해서 조각으로 올림).
                    val raw = snapshot.value
                    val full = raw == "full"
                    if (raw != true && !full) return
                    reqRef.setValue(false)
                    if (full) {
                        Thread { try { uploadFullLogs(appContextSafe, db, id) } catch (e: Exception) { } }.start()
                        return
                    }
                    val logText = try { DiscordReporter.tailOfLogFile(NavLogger.activeLogFile(appContextSafe)) } catch (e: Exception) { "" }
                    val logRef = db.getReference("logs/$id").push()
                    val logKey = logRef.key
                    logRef.setValue(
                        mapOf(
                            "log" to logText,
                            "appVersion" to DiscordReporter.appVersion(appContextSafe),
                            "ts" to ServerValue.TIMESTAMP
                        )
                    ).addOnCompleteListener { if (logKey != null) rememberAndTrim(appContextSafe, db, id, logKey) }
                }

                override fun onCancelled(error: DatabaseError) {
                    // 조용히 무시
                }
            })
        } catch (e: Exception) {
            // 조용히 무시
        }
    }
}
