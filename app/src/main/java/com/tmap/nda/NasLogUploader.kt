package com.tmap.nda

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * 재억 지시(2026-10-13) - 로그를 NAS에 날짜별로 매일 올림(최신=오늘 1일치, 지난=7일치, 7일 지난 건 삭제).
 * 파일은 재억만 열 수 있게 암호화해서 올림: 파일마다 새 AES-256-GCM 키로 잠그고, 그 키를 앱에 든 공개키(RSA)로 다시 잠금.
 * 푸는 열쇠(비밀키)는 재억 PC에만 있음 -> NAS 주인/앱을 뜯어본 사람도 내용은 못 읽음.
 * 파일 형식: "TNL1" | 열쇠길이(2바이트) | RSA로 잠근 AES키 | IV(12) | AES-GCM(gzip(로그 글)).
 * 접속 정보(NAS_*)가 비어 있으면 아무것도 안 함. #문제시 원복
 */
object NasLogUploader {
    private const val PUBLIC_KEY_B64 = "MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEAyYgPsc9z6JrkDEXFKicz012xlLMZdNdoZpTrusy1Su4Fj/kwr5dn42Uvuxboq5eXSkSGJmnOP0p/ijyVXrFPbumfC3acxEVFLtt/dWxNt1bOC0A/BVTrNzL7HGdxSG9eygyYDS7qNLf9rUyj2SgFMjrrwvRp6gZpXw0K5P3r+jvCIwxP5VPhWNqVeo17qJT76JFNsr6EbhNZkGo9pLlgPDVHnqv2IQ70JOvhxqYCzNL2k5HUT/c95IfI3ovOSELK5/pfs3DlvhUoxrCDUIluAOrsUJd8G1J2ZpLjxtTe4+nyYPPuLIrAdGdc6+2rU228IUlwBMxJzz6Jp0nYnwl8ZY/6+6y36NxFUpbgyxNywyCNDx8ySsYmhidTMjvfdOnLna1u2uQ2e7uKP6YqHPVNtjR0TpfUU4ghMAQZ8SX9+qCm1Dt7lCwrbrbi5qdLEh80k3vkZTIGXPheKO/KIIwDJ4l8SYvGssi1j48b5cMWMq+sSPMU3vDOG5NEmkMgBFLbAgMBAAE="
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
        }, 90, 3 * 60 * 60L, TimeUnit.SECONDS)
    }

    private fun recentDays(): Set<String> {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val c = Calendar.getInstance()
        return (0 until KEEP_DAYS).map { fmt.format(c.time).also { c.add(Calendar.DAY_OF_YEAR, -1) } }.toSet()
    }

    private fun uploadNow(context: Context) {
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
        val todo = byDay.filter { (d, sb) -> prefs.getInt("nas_sig_$d", -1) != sb.length }
        val id = DiscordReporter.installId(context)
        val base = BuildConfig.NAS_DIR.ifBlank { "/tndalogs" }.trimEnd('/') + "/" + id
        val sid = login()
        try {
            for ((d, sb) in todo) {
                upload(sid, base, "$d.tnl", encrypt(sb.toString().toByteArray(Charsets.UTF_8)))
                prefs.edit().putInt("nas_sig_$d", sb.length).apply()
            }
            deleteOld(sid, base, days)
        } finally {
            try { logout(sid) } catch (_: Exception) {}
        }
    }

    // ---- 암호화 ----
    private fun encrypt(plain: ByteArray): ByteArray {
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(plain) } }.toByteArray()
        val aesKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val aes = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv)) }
        val body = aes.doFinal(gz)
        val pub = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.decode(PUBLIC_KEY_B64, Base64.DEFAULT)))
        val rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").apply {
            init(Cipher.ENCRYPT_MODE, pub, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
        }
        val wrapped = rsa.doFinal(aesKey.encoded)
        return ByteArrayOutputStream().also { o ->
            o.write("TNL1".toByteArray()); o.write(wrapped.size shr 8); o.write(wrapped.size and 0xFF)
            o.write(wrapped); o.write(iv); o.write(body)
        }.toByteArray()
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

    // 7일 지난 날짜 파일 삭제. 폴더 안 목록을 받아 "yyyy-MM-dd.tnl"만 대상으로 함(다른 파일은 안 건드림).
    private fun deleteOld(sid: String, base: String, keep: Set<String>) {
        val r = postForm(mapOf("api" to "SYNO.FileStation.List", "version" to "2", "method" to "list",
            "folder_path" to base, "_sid" to sid))
        if (!r.optBoolean("success")) return
        val files = r.getJSONObject("data").optJSONArray("files") ?: return
        val nameRe = Regex("^(\\d{4}-\\d{2}-\\d{2})\\.tnl$")
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
