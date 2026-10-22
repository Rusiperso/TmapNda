package com.tmap.nda

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 재억 지시(2026-10-13) - 로그를 NAS에 날짜별 txt로 5분마다 올림(최신=오늘 1일치, 지난=7일치, 7일 지난 건 삭제).
 * 처음엔 암호화해서 올렸으나 재억 지시로 일반 txt로 바꿈(옛 .tnl 파일은 7일 지나면 정리 대상). 접속 정보(NAS_*)가 비면 아무것도 안 함. #문제시 원복
 */
object NasLogUploader {
    private const val KEEP_DAYS = 7
    private const val PREFS = "TmapNdaPrefs"

    private val executor = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        if (BuildConfig.NAS_HOST.isBlank() || BuildConfig.NAS_USER.isBlank() || BuildConfig.NAS_PASS.isBlank()) return
        started = true
        val app = context.applicationContext
        executor.scheduleWithFixedDelay({
            try {
                if (DiscordReporter.isEnabled(app)) uploadNow(app)
            } catch (e: Exception) {
                try { NavLogger.d(app, "NasLogUploader 실패: ${e.javaClass.simpleName} ${e.message}") } catch (_: Exception) {}
            }
        }, 90, 5 * 60L, TimeUnit.SECONDS)
    }

    private fun recentDays(): Set<String> {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val c = Calendar.getInstance()
        return (0 until KEEP_DAYS).map { fmt.format(c.time).also { c.add(Calendar.DAY_OF_YEAR, -1) } }.toSet()
    }

    // 재억 지시(2026-10-14): NAS에는 "닉네임이 있고, 보드가 디코 멤버로 확인했고, 보드에서 제거하지 않은" 기기만 올림(내 기기 닉네임은 항상 허용).
    // 디코 멤버 닉네임 목록·제거 목록은 보드가 파이어베이스 boardShared/disc에 올려둔 것을 읽음(1시간 캐시). 못 읽으면 이번 회차는 안 올리고 다음에 다시 시도. #문제시 원복
    private val OWNER_NICKS = setOf("주인장", "주인장1", "주인장2")
    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace(Regex("\\s+"), "")

    private fun fetchDiscList(): String? = try {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) Tasks.await(auth.signInAnonymously(), 5, TimeUnit.SECONDS)
        val snap = Tasks.await(FirebaseDatabase.getInstance().getReference("boardShared/disc").get(), 10, TimeUnit.SECONDS)
        if (!snap.exists()) null else {
            val matched = snap.child("matched").children.mapNotNull { it.getValue(String::class.java) }
            val hidden = snap.child("hidden").children.mapNotNull { it.key }
            JSONObject().put("matched", JSONArray(matched)).put("hidden", JSONArray(hidden)).toString()
        }
    } catch (e: Exception) { null }

    private fun isAllowed(context: Context, id: String): Boolean {
        val nick = DiscordReporter.getNickname(context).trim()
        if (nick.isEmpty()) return false
        if (nick in OWNER_NICKS) return true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var json = prefs.getString("nas_disc_json", null)
        if (json == null || System.currentTimeMillis() - prefs.getLong("nas_disc_at", 0L) > 60 * 60 * 1000L) {
            fetchDiscList()?.let { json = it; prefs.edit().putString("nas_disc_json", it).putLong("nas_disc_at", System.currentTimeMillis()).apply() }
        }
        val j = JSONObject(json ?: return false)
        fun has(arr: JSONArray, v: String) = (0 until arr.length()).any { arr.optString(it) == v }
        if (has(j.optJSONArray("hidden") ?: JSONArray(), "i_" + id.replace(Regex("[^A-Za-z0-9_-]"), "_"))) return false
        val n = norm(nick)
        val m = j.optJSONArray("matched") ?: JSONArray()
        return (0 until m.length()).any { m.optString(it) == n }
    }

    private fun uploadNow(context: Context) {
        if (!isAllowed(context, DiscordReporter.installId(context))) return
        val days = recentDays()
        val dateRe = Regex("^(\\d{4}-\\d{2}-\\d{2}) ")
        val byDay = HashMap<String, StringBuilder>()
        for (f in NavLogger.allLogFilesChronological(context)) {
            var cur: String? = null
            f.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    dateRe.find(line)?.let { cur = it.groupValues[1] }
                    val d = cur ?: continue
                    if (d in days) byDay.getOrPut(d) { StringBuilder() }.append(line).append('\n')
                }
            }
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val todo = byDay.filter { (d, sb) -> prefs.getInt("nas_txt_sig_$d", -1) != sb.length }
        val id = DiscordReporter.installId(context)
        val root = BuildConfig.NAS_DIR.ifBlank { "/tndalogs" }.trimEnd('/')
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
        // 올릴 게 없고 오늘 폴더 확인·정리도 끝났으면 NAS에 접속하지 않음.
        if (todo.isEmpty() && prefs.getString("nas_cleaned", null) == today && prefs.getString("nas_folder_checked", null) == today) return
        DiscordReporter.syncNicknameToBoard(context)
        val sid = login()
        try {
            // 5분마다 도는 만큼 NAS 호출을 줄임: 닉네임이 그대로면 저장해둔 폴더를 그대로 쓰고, 폴더 확인은 하루 한 번만 함.
            val desired = folderName(context, id)
            val cachedFolder = prefs.getString("nas_folder", null)
            val base = if (cachedFolder != null && prefs.getString("nas_folder_nick", null) == desired && prefs.getString("nas_folder_checked", null) == today) {
                "$root/$cachedFolder"
            } else {
                resolveFolder(context, sid, root, id, desired).also {
                    prefs.edit().putString("nas_folder_nick", desired).putString("nas_folder_checked", today).apply()
                }
            }
            for ((d, sb) in todo) {
                upload(sid, base, "$d.txt", sb.toString().toByteArray(Charsets.UTF_8))
                prefs.edit().putInt("nas_txt_sig_$d", sb.length).apply()
            }
            if (prefs.getString("nas_cleaned", null) != today) { deleteOld(sid, base, days); prefs.edit().putString("nas_cleaned", today).apply() }
        } finally {
            try { logout(sid) } catch (_: Exception) {}
        }
    }

    // 폴더 이름 = 닉네임(없으면 설치ID).
    private fun folderName(context: Context, id: String): String {
        val nick = DiscordReporter.getNickname(context).replace(Regex("[\\\\/:*?\"<>|,]"), "").trim().take(30)
        return if (nick.isEmpty()) id else nick
    }

    private fun listDir(sid: String, path: String): List<Pair<String, Boolean>>? {
        val r = postForm(mapOf("api" to "SYNO.FileStation.List", "version" to "2", "method" to "list", "folder_path" to path, "_sid" to sid))
        if (!r.optBoolean("success")) return null
        val files = r.getJSONObject("data").optJSONArray("files") ?: return emptyList()
        return (0 until files.length()).map { files.getJSONObject(it).let { f -> f.optString("name") to f.optBoolean("isdir") } }
    }

    // 재억 지시(2026-10-13): 폴더 이름 = 닉네임. 같은 이름 폴더가 이미 있으면 그걸 그대로 써서 날짜 파일을 덮어씀(새 폴더를 따로 만들지 않음).
    // 이름이 바뀐 경우에만 저장해둔 예전 폴더 이름을 새 이름으로 바꿈. 표식 파일/번호 붙이기는 폐지(주인장2 같은 폴더가 생겨서). #문제시 원복
    private fun resolveFolder(context: Context, sid: String, root: String, id: String, desired: String): String {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val top = listDir(sid, root) ?: return "$root/" + (prefs.getString("nas_folder", null) ?: desired)
            val dirs = top.filter { it.second && !it.first.startsWith("#") && !it.first.startsWith("@") }.map { it.first }
            dirs.firstOrNull { it.equals(desired, ignoreCase = true) }?.let {
                prefs.edit().putString("nas_folder", it).apply()
                return "$root/$it"
            }
            // 같은 이름 폴더 없음: 예전에 쓰던 폴더(내가 저장해둔 이름, 또는 옛 방식의 설치ID/"닉네임_설치ID")가 있으면 이름만 바꿈
            val old = listOfNotNull(prefs.getString("nas_folder", null)).firstOrNull { it in dirs }
                ?: dirs.firstOrNull { it == id || it.endsWith("_$id") }
            if (old != null) {
                val rn = postForm(mapOf("api" to "SYNO.FileStation.Rename", "version" to "2", "method" to "rename",
                    "path" to "$root/$old", "name" to desired, "_sid" to sid))
                val name = if (rn.optBoolean("success")) desired else old
                prefs.edit().putString("nas_folder", name).apply()
                return "$root/$name"
            }
            prefs.edit().putString("nas_folder", desired).apply()
        } catch (_: Exception) {}
        return "$root/$desired"
    }

    // ---- 시놀로지 File Station 통신 ----
    private fun api(): String = "https://" + BuildConfig.NAS_HOST.removePrefix("https://").trimEnd('/') + "/webapi/entry.cgi"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun readBody(c: HttpURLConnection): JSONObject {
        val text = (if (c.responseCode in 200..299) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
        return JSONObject(text)
    }

    private fun postForm(params: Map<String, String>): JSONObject {
        val c = URL(api()).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 30000; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(params.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }.toByteArray()) }
        return readBody(c)
    }

    private fun login(): String {
        val r = postForm(mapOf("api" to "SYNO.API.Auth", "version" to "6", "method" to "login",
            "account" to BuildConfig.NAS_USER, "passwd" to BuildConfig.NAS_PASS, "session" to "FileStation", "format" to "sid"))
        if (!r.optBoolean("success")) throw IllegalStateException("NAS 로그인 실패 code=${r.optJSONObject("error")?.optInt("code")}")
        return r.getJSONObject("data").getString("sid")
    }

    private fun logout(sid: String) {
        postForm(mapOf("api" to "SYNO.API.Auth", "version" to "6", "method" to "logout", "session" to "FileStation", "_sid" to sid))
    }

    private fun upload(sid: String, dir: String, name: String, data: ByteArray) {
        val boundary = "----tnl" + System.currentTimeMillis()
        val c = URL(api() + "?api=SYNO.FileStation.Upload&version=2&method=upload&_sid=" + enc(sid)).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 120000; c.doOutput = true
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        val head = StringBuilder()
        fun field(k: String, v: String) { head.append("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n") }
        field("path", dir); field("create_parents", "true"); field("overwrite", "true")
        head.append("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\nContent-Type: application/octet-stream\r\n\r\n")
        val headB = head.toString().toByteArray(Charsets.UTF_8)
        val tailB = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        c.setFixedLengthStreamingMode(headB.size + data.size + tailB.size)
        DataOutputStream(c.outputStream).use { it.write(headB); it.write(data); it.write(tailB) }
        val r = readBody(c)
        if (!r.optBoolean("success")) throw IllegalStateException("NAS 업로드 실패 code=${r.optJSONObject("error")?.optInt("code")}")
    }

    // 7일 지난 날짜 파일 삭제. 폴더 안 목록을 받아 "yyyy-MM-dd.txt(예전 .tnl 포함)"만 대상으로 함(다른 파일은 안 건드림).
    private fun deleteOld(sid: String, base: String, keep: Set<String>) {
        val r = postForm(mapOf("api" to "SYNO.FileStation.List", "version" to "2", "method" to "list",
            "folder_path" to base, "_sid" to sid))
        if (!r.optBoolean("success")) return
        val files = r.getJSONObject("data").optJSONArray("files") ?: return
        val nameRe = Regex("^(\\d{4}-\\d{2}-\\d{2})\\.(tnl|txt)$")
        val old = ArrayList<String>()
        for (i in 0 until files.length()) {
            val n = files.getJSONObject(i).optString("name")
            val m = nameRe.find(n) ?: continue
            if (m.groupValues[1] !in keep) old.add("$base/$n")
        }
        if (old.isEmpty()) return
        postForm(mapOf("api" to "SYNO.FileStation.Delete", "version" to "1", "method" to "delete",
            "path" to old.joinToString(","), "_sid" to sid))
    }
}
