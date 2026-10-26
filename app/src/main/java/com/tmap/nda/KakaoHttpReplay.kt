package com.tmap.nda

import android.app.Application
import android.content.Context
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * 카카오 SDK가 서버와 주고받는 통신(인증·설정·길찾기)을 몰래 옆에서 저장해 두었다가,
 * 인터넷이 없어서 요청이 실패하면 저장해 둔 마지막 응답을 대신 돌려준다.
 * 빌드할 때 buildSrc 플러그인이 SDK의 통신 클라이언트 만드는 자리에 [hook]을 끼워 넣는다.
 * 시험 단계 - 어떤 요청이 오가는지 [카카오통신] 로그로 남긴다.
 */
object KakaoHttpReplay {
    private const val MAX_BYTES = 3L * 1024 * 1024
    private const val KEEP_ROUTES = 6
    private const val MAX_TOTAL = 400L * 1024 * 1024

    @Volatile private var ctx: Context? = null

    fun init(context: Context) { ctx = context.applicationContext }

    private fun app(): Context? = ctx ?: try {
        (Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Application)
            ?.also { ctx = it }
    } catch (e: Throwable) { null }

    @JvmStatic
    fun hook(builder: OkHttpClient.Builder): OkHttpClient.Builder =
        try { builder.addInterceptor(ReplayInterceptor) } catch (e: Throwable) { builder }

    private fun dir(c: Context) = File(c.filesDir, "kakao_replay").also { it.mkdirs() }

    private fun sha1(b: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }.take(16)

    private fun requestBytes(req: Request): ByteArray? = try {
        val body = req.body
        if (body == null || body.isOneShot()) null
        else Buffer().also { body.writeTo(it) }.readByteArray()
    } catch (e: Throwable) { null }

    // 길찾기 요청에서 매번 달라지는 값(요청 번호, 출발 위치)을 빼고 목적지·회피옵션·우선순위·경유지만으로 열쇠를 만든다.
    private fun routeKey(b: ByteArray): String = try {
        val j = org.json.JSONObject(String(b, Charsets.UTF_8))
        val sb = StringBuilder()
        sb.append(j.optJSONObject("destination")?.let { "${it.opt("x")},${it.opt("y")}" })
        sb.append(j.optJSONObject("avoid")?.toString())
        j.keys().forEach { k -> if (k.contains("prior", true) || k.contains("via", true) || k.contains("waypoint", true)) sb.append(k).append(j.opt(k)) }
        sha1(sb.toString().toByteArray())
    } catch (e: Throwable) { sha1(b) }

    private fun printable(b: ByteArray?, n: Int = 400): String =
        if (b == null) "-" else String(b, 0, minOf(b.size, n), Charsets.UTF_8).replace(Regex("[\\p{Cntrl}&&[^\\n]]"), "?")

    private object ReplayInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val c = app()
            val host = req.url.host
            val path = req.url.encodedPath.trimStart('/')
            if (c == null || !host.contains("kakao")) return chain.proceed(req)

            val reqBytes = requestBytes(req)
            val key = path.replace('/', '_') + (req.url.encodedQuery?.let { "_q" + sha1(it.toByteArray()) } ?: "") + (if (path.contains("route-plan") && reqBytes != null) "_" + routeKey(reqBytes) else "")
            val t0 = System.currentTimeMillis()
            try {
                val resp = chain.proceed(req)
                val ms = System.currentTimeMillis() - t0
                var saved = ""
                if (resp.code == 200) {
                    val peek = resp.peekBody(MAX_BYTES + 1).bytes()
                    if (peek.size <= MAX_BYTES) {
                        try {
                            val f = File(dir(c), key)
                            f.writeBytes(peek)
                            File(dir(c), "$key.type").writeText(resp.header("Content-Type") ?: "")
                            saved = " 저장=${peek.size}B"
                            if (path.contains("route-plan")) trimRoutes(c)
                            trimTotal(c)
                        } catch (e: Throwable) { saved = " 저장실패=${e.message}" }
                    } else saved = " 너무커서저장안함"
                }
                NavLogger.d(c, "[카카오통신] ${req.method} $path → ${resp.code} ${ms}ms$saved" +
                    if (path.contains("route-plan")) " 요청본문=${printable(reqBytes)}" else "")
                return resp
            } catch (e: IOException) {
                // 길찾기 요청은 저장해 둔 옛 길 대신, 지금 위치에서 폰 안에서 새로 계산한 길로 대신한다.
                if (path.contains("route-plan")) {
                    val synth = KakaoRouteSynth.synthesize(c, reqBytes)
                    if (synth != null) {
                        NavLogger.e(c, "[카카오통신] ${req.method} $path 실패(${e.javaClass.simpleName}) → 폰 안에서 계산한 길로 대신함 ${synth.length}자")
                        return Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK (offline route)")
                            .body(synth.toByteArray(Charsets.UTF_8).toResponseBody("application/json".toMediaTypeOrNull()))
                            .build()
                    }
                }
                val f = File(dir(c), key)
                if (f.exists()) {
                    val type = try { File(dir(c), "$key.type").readText() } catch (t: Throwable) { "" }
                    NavLogger.e(c, "[카카오통신] ${req.method} $path 실패(${e.javaClass.simpleName}) → 저장된 응답으로 대신함 ${f.length()}B")
                    return Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK (replay)")
                        .body(f.readBytes().toResponseBody(type.toMediaTypeOrNull()))
                        .build()
                }
                NavLogger.e(c, "[카카오통신] ${req.method} $path 실패(${e.javaClass.simpleName}) 저장된 응답 없음 키=$key")
                throw e
            }
        }

        // 저장 용량이 넘치면 오래된 것부터 지운다(도로 데이터 조각이 계속 쌓이므로).
        private fun trimTotal(c: Context) {
            try {
                val files = dir(c).listFiles() ?: return
                var total = files.sumOf { it.length() }
                if (total <= MAX_TOTAL) return
                for (f in files.sortedBy { it.lastModified() }) {
                    if (total <= MAX_TOTAL * 3 / 4) break
                    total -= f.length(); f.delete()
                }
            } catch (e: Throwable) { }
        }

        private fun trimRoutes(c: Context) {
            try {
                val routes = dir(c).listFiles { f -> f.name.contains("route-plan") && !f.name.endsWith(".type") }
                    ?.sortedByDescending { it.lastModified() } ?: return
                routes.drop(KEEP_ROUTES).forEach { it.delete(); File(it.path + ".type").delete() }
            } catch (e: Throwable) { }
        }
    }
}
