package com.tmap.nda

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.gson.JsonParser
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * 인터넷이 없을 때 쓰는 오프라인 길찾기·검색 자료(도로망, 장소 목록, 주소 목록)를 폰에 받아 둔다.
 * 받는 곳은 설정 화면에 직접 적는다(코드에 주소·열쇠를 넣지 않는다).
 *  - 일반 주소: "주소/파일이름" 으로 받는다(예: NAS, 내 서버)
 *  - GitHub 릴리즈 주소(https://api.github.com/repos/소유자/저장소/releases/tags/태그): 릴리즈에 붙은 파일을 찾아서 받는다. 비공개 저장소면 토큰을 같이 적는다.
 * 파일은 gzip으로 압축돼 있고 받으면서 바로 풀어서 저장한다.
 */
object OfflineDataDownload {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var cancelled = false

    private const val PREFS = "TmapNdaPrefs"
    private const val KEY_BASE = "offline_data_base"
    // 기본 받는 곳(공개 정보뿐, 열쇠는 들어 있지 않다). 설정 화면에서 바꿀 수 있다.
    private const val DEFAULT_BASE = "https://api.github.com/repos/Rusiperso/TmapNda/releases/tags/offline-data-20261025"
    private const val KEY_TOKEN = "offline_data_token"

    /** 받을 파일: (서버 파일 이름, 폰에 저장될 이름, 설명) */
    private val FILES = listOf(
        Triple("valhalla-tiles.tar.gz", "tiles.tar", "도로망"),
        Triple("poi.sqlite.gz", "poi.sqlite", "장소 목록"),
        Triple("juso.sqlite.gz", "juso.sqlite", "주소 목록")
    )

    val isRunning: Boolean get() = running

    fun dir(context: Context) = File(context.getExternalFilesDir(null), "valhalla")

    private fun mb(n: Long) = (n / 1048576).toString() + "MB"

    fun statusText(context: Context): String {
        val d = dir(context)
        val parts = FILES.map { (_, local, label) ->
            val f = File(d, local)
            if (f.exists() && f.length() > 0) "$label ${mb(f.length())}" else "$label 없음"
        }
        val ready = FILES.all { File(d, it.second).let { f -> f.exists() && f.length() > 0 } } && File(d, "valhalla.json").exists()
        return (if (ready) "받아둠 · " else "") + parts.joinToString(" / ")
    }

    private class Counting(input: InputStream, val onBytes: (Long) -> Unit) : FilterInputStream(input) {
        var count = 0L
        override fun read(): Int { val r = super.read(); if (r >= 0) { count++; onBytes(count) }; return r }
        override fun read(b: ByteArray, off: Int, len: Int): Int { val r = super.read(b, off, len); if (r > 0) { count += r; onBytes(count) }; return r }
    }

    private fun open(url: String, token: String, octet: Boolean): HttpURLConnection {
        var u = url
        var withAuth = token.isNotBlank()
        repeat(6) {
            val c = (URL(u).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("User-Agent", "TmapNda")
                if (octet) setRequestProperty("Accept", "application/octet-stream")
                if (withAuth) setRequestProperty("Authorization", "Bearer $token")
            }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IllegalStateException("이동 주소가 없어요")
                c.disconnect()
                u = URL(URL(u), loc).toString()
                withAuth = false   // 다른 서버(저장소 파일 서버)로 넘어갈 때는 열쇠를 보내지 않는다
                return@repeat
            }
            if (code !in 200..299) { c.disconnect(); throw IllegalStateException("서버 응답 $code") }
            return c
        }
        throw IllegalStateException("이동이 너무 많아요")
    }

    /** 파일 이름 → 받을 주소 */
    private fun resolveUrls(base: String, token: String): Map<String, Pair<String, Boolean>> {
        val b = base.trim().trimEnd('/')
        if (b.startsWith("https://api.github.com/repos/") && b.contains("/releases/")) {
            val c = open(b, token, false)
            val json = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            val assets = JsonParser.parseString(json).asJsonObject.getAsJsonArray("assets")
            val out = HashMap<String, Pair<String, Boolean>>()
            for (a in assets) {
                val o = a.asJsonObject
                out[o.get("name").asString] = ("https://api.github.com" + java.net.URI(o.get("url").asString).path) to true
            }
            return out
        }
        return FILES.associate { it.first to ("$b/${it.first}" to false) }
    }

    fun start(context: Context, onProgress: (String) -> Unit, onDone: (String) -> Unit): Boolean {
        if (running) return false
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val base = p.getString(KEY_BASE, "").orEmpty().ifBlank { DEFAULT_BASE }
        val token = p.getString(KEY_TOKEN, "").orEmpty()
        if (base.isBlank()) { onDone("받을 주소를 먼저 적어주세요"); return false }
        running = true; cancelled = false
        Thread {
            var msg: String
            try {
                val d = dir(context).also { it.mkdirs() }
                val urls = resolveUrls(base, token)
                for ((idx, f) in FILES.withIndex()) {
                    val (serverName, local, label) = f
                    val target = File(d, local)
                    if (target.exists() && target.length() > 0) continue
                    val (url, octet) = urls[serverName] ?: throw IllegalStateException("$serverName 파일을 못 찾았어요")
                    main.post { onProgress("$label 받는 중 (${idx + 1}/${FILES.size})") }
                    val c = open(url, token, octet)
                    val total = c.contentLengthLong
                    val part = File(d, "$local.part")
                    var lastPct = -1
                    val counting = Counting(c.inputStream) { n ->
                        if (cancelled) throw java.io.IOException("중지")
                        if (total > 0) {
                            val pct = (n * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; main.post { onProgress("$label 받는 중 $pct% (${idx + 1}/${FILES.size})") } }
                        }
                    }
                    GZIPInputStream(counting, 1 shl 16).use { gz -> part.outputStream().buffered(1 shl 16).use { o -> gz.copyTo(o, 1 shl 16) } }
                    c.disconnect()
                    if (target.exists()) target.delete()
                    if (!part.renameTo(target)) throw IllegalStateException("$label 저장 실패")
                    NavLogger.d(context, "[오프라인자료] $label 받음 ${mb(target.length())}")
                }
                // 길 계산 설정 파일은 폰 안에서 경로만 맞춰 만든다
                val tpl = context.assets.open("valhalla_template.json").bufferedReader().use { it.readText() }
                File(d, "valhalla.json").writeText(tpl.replace("__DIR__", d.absolutePath))
                msg = "다 받았어요"
            } catch (e: Throwable) {
                NavLogger.e(context, "[오프라인자료] 받기 실패: ${e.javaClass.simpleName} ${e.message}")
                msg = if (cancelled) "중지했어요" else "받기 실패: ${e.message}"
            } finally {
                running = false
            }
            main.post { onDone(msg) }
        }.start()
        return true
    }

    fun stop() { cancelled = true }

    fun buildViews(context: Context): List<View> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun label(text: String, color: String = "#FFFFFF", size: Float = 14f, bottom: Int = 4) = TextView(context).apply {
            setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
            this.text = text
            setTextColor(android.graphics.Color.parseColor(color))
            textSize = size
            setPadding(40, 10, 40, bottom)
        }
        val title = label("오프라인 길찾기·검색 자료 받기")
        val hint = label("인터넷이 없을 때 폰 안에서 길을 찾고 장소·주소를 검색하는 자료예요(약 400MB 받아서 풀면 1GB). 와이파이에서 받아주세요.\n도로망·장소: © OpenStreetMap contributors / 주소: 행정안전부 위치정보요약DB", "#E0E0E0", 13f)
        fun field(hintText: String, key: String, secret: Boolean) = EditText(context).apply {
            this.hint = hintText
            setText(p.getString(key, "").orEmpty().ifBlank { if (key == KEY_BASE) DEFAULT_BASE else "" })
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.parseColor("#DADADA"))
            textSize = 12f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or (if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI)
            setPadding(40, 8, 40, 8)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { p.edit().putString(key, s?.toString()?.trim().orEmpty()).apply() }
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            })
        }
        val baseField = field("받는 곳 주소 (예: https://내서버/자료  또는 GitHub 릴리즈 주소)", KEY_BASE, false)
        val tokenField = field("접속 열쇠(비공개일 때만)", KEY_TOKEN, true)
        // 열쇠는 긴 글자라 손으로 치기 어렵다. 복사해 둔 글자를 한 번에 넣는 버튼.
        val pasteButton = Button(context).apply {
            text = "복사한 열쇠 붙여넣기"
            setOnClickListener {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val txt = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
                if (txt.isBlank()) {
                    android.widget.Toast.makeText(context, "복사해 둔 글자가 없어요", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    tokenField.setText(txt)
                    android.widget.Toast.makeText(context, "열쇠를 넣었어요", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        val status = TextView(context).apply {
            setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
            text = statusText(context)
            setTextColor(android.graphics.Color.WHITE)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val button = Button(context).apply { text = if (running) "중지" else "받기" }
        button.setOnClickListener {
            if (running) { stop(); return@setOnClickListener }
            button.text = "중지"
            start(context, { status.text = it }) { msg ->
                button.text = "받기"
                status.text = "$msg · ${statusText(context)}"
            }
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(40, 0, 40, 24)
            addView(status)
            addView(button)
        }
        return listOf(title, hint, baseField, tokenField, pasteButton, row)
    }
}
