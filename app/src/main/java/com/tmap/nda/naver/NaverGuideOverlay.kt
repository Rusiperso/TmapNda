package com.tmap.nda.naver

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 카카오 SDK 화면이 직접 그려주던 안내 박스를 대신 그린다. 네이버 지도 앱처럼
 * 파란 회전 카드(+다음 회전), 오른쪽 정체 막대, 아래 도착 정보 바, 내 위치로 돌아오기 버튼을 지도 위에 얹는다.
 * 상단바·버튼 같은 기존 UI는 건드리지 않는다.
 */
class NaverGuideOverlay(private val activity: Activity, private val root: FrameLayout) {

    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val white = 0xF2FFFFFF.toInt()
    private val ink = 0xFF1B1F27.toInt()

    private val arrowImg = ImageView(activity)
    private val dist = text(30f, Color.WHITE, true)
    private val instr = text(14f, Color.WHITE, false)
    private val secondImg = ImageView(activity)
    private val secondDist = text(20f, Color.WHITE, true)

    private val secondRow = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            setColor(0xFF1556B8.toInt())
            val r = dp(14).toFloat()
            cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
        }
        setPadding(dp(16), dp(6), dp(18), dp(8))
        addView(secondImg, LinearLayout.LayoutParams(dp(30), dp(30)))
        secondDist.setPadding(dp(10), 0, 0, 0); addView(secondDist)
        visibility = View.GONE
    }

    private val banner = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(NaverMapIcons.BLUE); cornerRadius = dp(14).toFloat() }
        visibility = View.GONE
        val top = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(18), dp(10))
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(arrowImg, LinearLayout.LayoutParams(dp(56), dp(56)))
            dist.setPadding(dp(12), 0, 0, 0); row.addView(dist)
            addView(row); addView(instr)
            instr.maxLines = 1; instr.ellipsize = android.text.TextUtils.TruncateAt.END; instr.maxWidth = dp(150)
        }
        addView(top); addView(secondRow)
    }

    private val bottomDest = text(13f, 0xFF5A6270.toInt(), false).apply { setShadowLayer(0f, 0f, 0f, 0) }
    private val bottomEta = TextView(activity).apply { setTextColor(ink) }

    private val bottom = LinearLayout(activity).apply {
        background = GradientDrawable().apply { setColor(white); cornerRadius = dp(18).toFloat(); setStroke(1, 0x22000000) }
        setPadding(dp(28), dp(8), dp(28), dp(10))
        minimumWidth = dp(360)
        visibility = View.GONE
        val center = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(bottomDest); addView(bottomEta)
        }
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(activity).apply {
            text = "⟳"; textSize = 28f; setTextColor(ink); setPadding(0, 0, dp(18), 0)
            setOnClickListener { rotateOnce(this); onRefresh?.invoke() }
        })
        addView(center)
        addView(TextView(activity).apply {
            text = "☰"; textSize = 26f; setTextColor(ink); setPadding(dp(18), 0, 0, 0)
            setOnClickListener { onMenu?.invoke() }
        })
    }

    /** 아래 바 왼쪽 새로고침(⟳)을 눌렀을 때 할 일(경로 다시 받기). */
    var onRefresh: (() -> Unit)? = null

    private fun rotateOnce(v: View) { v.animate().rotationBy(360f).setDuration(600).start() }

    /** 아래 바 첫 줄: 출발지 → 현재 있는 곳(도로) → 도착지를 3초마다 차례로 보여준다. */
    private var placeStep = 0
    private var originText = ""
    private var originTried = false
    private var curPlace = ""
    private var goalNameNow = ""
    private fun renderPlaceLine() {
        bottomDest.text = when (placeStep) {
            0 -> originText.ifBlank { "출발지" }
            1 -> curPlace.ifBlank { "현재 위치" }
            else -> goalNameNow.ifBlank { "도착지" }
        }
    }
    private val placeTick = object : Runnable {
        override fun run() {
            placeStep = (placeStep + 1) % 3
            renderPlaceLine()
            bottom.postDelayed(this, 3000L)
        }
    }

    /** 좌표 → 짧은 주소("천안시 서북구 두정로"). 카카오 로컬 키가 있으면 그것으로, 없으면 기기 지오코더로 찾는다. */
    private fun reverse(lat: Double, lon: Double, done: (String) -> Unit) {
        Thread {
            var text = ""
            try {
                val key = activity.getSharedPreferences("TmapNdaPrefs", android.content.Context.MODE_PRIVATE).getString("kakao_rest_api_key", null)
                if (!key.isNullOrBlank()) {
                    val u = java.net.URL(String.format(Locale.US, "https://dapi.kakao.com/v2/local/geo/coord2address.json?x=%.6f&y=%.6f", lon, lat))
                    val c = u.openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 3000; c.readTimeout = 4000
                    c.setRequestProperty("Authorization", "KakaoAK $key")
                    if (c.responseCode in 200..299) {
                        val doc = org.json.JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optJSONArray("documents")?.optJSONObject(0)
                        val road = doc?.optJSONObject("road_address")
                        val addr = doc?.optJSONObject("address")
                        text = if (road != null) listOf(road.optString("region_2depth_name"), road.optString("road_name")).filter { it.isNotBlank() }.joinToString(" ")
                        else listOf(addr?.optString("region_2depth_name").orEmpty(), addr?.optString("region_3depth_name").orEmpty()).filter { it.isNotBlank() }.joinToString(" ")
                    }
                    c.disconnect()
                }
                if (text.isBlank() && android.location.Geocoder.isPresent()) {
                    @Suppress("DEPRECATION")
                    val a = android.location.Geocoder(activity, Locale.KOREA).getFromLocation(lat, lon, 1)?.firstOrNull()
                    text = listOfNotNull(a?.locality ?: a?.subAdminArea, a?.subLocality, a?.thoroughfare).distinct().joinToString(" ")
                }
            } catch (e: Exception) { }
            bottom.post { done(text) }
        }.start()
    }

    /** 지금 있는 곳의 주소. 250m 이상 움직였을 때만 다시 찾는다. */
    private var lastGeoLat = 0.0; private var lastGeoLon = 0.0; private var geoBusy = false; private var geoText = ""
    fun onLocation(loc: android.location.Location) {
        val moved = Math.hypot((loc.latitude - lastGeoLat) * 111000.0, (loc.longitude - lastGeoLon) * 88000.0)
        if (geoBusy || (geoText.isNotEmpty() && moved < 250.0)) return
        geoBusy = true; lastGeoLat = loc.latitude; lastGeoLon = loc.longitude
        reverse(loc.latitude, loc.longitude) { text -> geoBusy = false; if (text.isNotBlank()) { geoText = text; renderPlaceLine() } }
    }

    /** 아래 바 오른쪽 메뉴(☰)를 눌렀을 때 할 일. */
    var onMenu: (() -> Unit)? = null

    private val recenter = ImageView(activity).apply {
        setImageBitmap(NaverMapIcons.recenter(dp(30)))
        setPadding(dp(11), dp(11), dp(11), dp(11))
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(1, 0x22000000) }
        elevation = dp(4).toFloat()
        visibility = View.GONE
        setOnClickListener { onRecenter?.invoke() }
    }

    /** "내 위치로 돌아오기" 버튼을 눌렀을 때 할 일(안내 화면이 정해준다). */
    var onRecenter: (() -> Unit)? = null
    /** 지도를 손으로 움직여 따라가기가 멈춘 상태인지 알려주는 함수. 0.7초마다 확인해 버튼을 켜고 끈다. */
    var pausedProvider: (() -> Boolean)? = null
    private val poll = object : Runnable {
        override fun run() {
            pausedProvider?.let { recenterWanted = it(); apply() }
            recenter.postDelayed(this, 700L)
        }
    }
    private var recenterWanted = false
    fun setRecenterVisible(v: Boolean) { recenterWanted = v; apply() }

    /** 경로 남은 구간의 정체 상태를 세로 막대로 보여준다(위=도착지, 아래=내 위치). */
    private val trafficBar = TrafficBar(activity)

    init {
        root.addView(trafficBar, FrameLayout.LayoutParams(dp(14), ViewGroup.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.END
            setMargins(0, dp(90), dp(6), dp(70))
        })
        root.addView(banner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(0, dp(76), dp(28), 0)
        })
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            setMargins(0, 0, 0, dp(14))
        })
        root.addView(recenter, FrameLayout.LayoutParams(dp(52), dp(52)).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            setMargins(dp(14), 0, 0, dp(84))
        })
        trafficBar.visibility = View.GONE
        recenter.postDelayed(poll, 700L)
        bottom.postDelayed(placeTick, 3000L)
        // 기존 버튼·팝업 카드처럼 끌어서 옮길 수 있고, 놓은 자리는 저장돼 다음에도 그대로 뜬다(가로/세로 따로).
        // 설정의 "팝업 위치 잠금"이 켜져 있으면 안 움직인다. 화면에 붙인 뒤에 걸어야 저장된 위치가 복원된다.
        com.tmap.nda.PopupCard.attachDrag(activity, banner, root, "naverGuideBanner")
        com.tmap.nda.PopupCard.attachDrag(activity, bottom, root, "naverGuideBottom")
    }

    private var wanted = false
    private var obscured = false
    private fun apply() {
        val v = if (wanted && !obscured) View.VISIBLE else View.GONE
        banner.visibility = v; bottom.visibility = v; trafficBar.visibility = v
        recenter.visibility = if (wanted && !obscured && recenterWanted) View.VISIBLE else View.GONE
    }
    fun show() { wanted = true; apply() }
    fun hide() { wanted = false; apply(); originTried = false; originText = ""; geoText = ""; placeStep = 0 }

    /** 팝업 카드가 떠 있는 동안은 안내 박스를 잠시 숨겨서 겹쳐 보이지 않게 한다. */
    fun setObscured(b: Boolean) { obscured = b; apply() }

    /** 새 경로가 정해졌을 때(시작·재탐색) 정체 막대를 다시 만든다. */
    fun setRoute(route: NaverRoute) {
        trafficBar.setRoute(route); this.route = route; junctionKey = null
        // 안내를 시작한 자리(경로의 첫 점)의 주소를 출발지로 기억한다(재탐색 때는 바꾸지 않음)
        if (!originTried && route.path.isNotEmpty()) {
            originTried = true
            val p0 = route.path.first()
            reverse(p0.lat, p0.lon) { t -> if (t.isNotBlank()) { originText = t; renderPlaceLine() } }
        }
    }
    private var route: NaverRoute? = null
    private var junctionKey: String? = null

    fun update(s: GuidanceState, goalName: String) {
        show()
        val g = s.nextGuide
        val type = g?.type ?: 0
        val code = NaverTurnMap.toOpenpilotCode(type)
        arrowImg.setImageBitmap(NaverMapIcons.turnArrow(if (code == 201 || code == NaverTurnMap.NONE) 11 else code, dp(56), Color.WHITE))
        dist.text = distText(s.nextGuideDistMeters)
        instr.text = NaverTurnMap.label(type).ifBlank { g?.instructions.orEmpty() }.ifBlank { s.roadName }
        val sec = s.secondGuide
        if (sec != null) {
            val c2 = NaverTurnMap.toOpenpilotCode(sec.type)
            secondImg.setImageBitmap(NaverMapIcons.turnArrow(if (c2 == 201 || c2 == NaverTurnMap.NONE) 11 else c2, dp(30), Color.WHITE))
            secondDist.text = distText(s.secondGuideDistMeters)
            secondRow.visibility = View.VISIBLE
        } else {
            secondRow.visibility = View.GONE
        }
        val at = Date(System.currentTimeMillis() + s.remainTimeSec * 1000L)
        val arrive = SimpleDateFormat("h:mm", Locale.US).format(at)
        val ampm = SimpleDateFormat("a", Locale.US).format(at)
        val mins = (s.remainTimeSec / 60.0).roundToInt()
        val timeText = if (mins >= 60) "${mins / 60}시간 ${mins % 60}분" else "${mins}분"
        val km = if (s.remainMeters >= 1000) String.format(Locale.US, "%.0fkm", s.remainMeters / 1000.0) else "${s.remainMeters}m"
        val sb = android.text.SpannableStringBuilder()
        fun add(t: String, sp: Float, bold: Boolean) {
            val st = sb.length; sb.append(t)
            sb.setSpan(android.text.style.AbsoluteSizeSpan((sp * density).toInt()), st, sb.length, 0)
            if (bold) sb.setSpan(android.text.style.StyleSpan(Typeface.BOLD), st, sb.length, 0)
        }
        add(arrive, 24f, true); add(ampm, 15f, false); add("   ", 14f, false)
        add(timeText, 22f, true); add("   ", 14f, false); add(km, 22f, true)
        bottomEta.text = sb
        goalNameNow = goalName
        curPlace = listOf(s.roadName, geoText).filter { it.isNotBlank() }.distinct().joinToString(" · ")
        renderPlaceLine()
        trafficBar.setProgress(s.progressMeters)
    }

    private fun distText(m: Int) =
        if (m >= 1000) String.format(Locale.US, "%.1fkm", m / 1000.0) else "${m}m"

    private fun text(size: Float, color: Int, bold: Boolean) = TextView(activity).apply {
        textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }
}

/** 경로 남은 구간의 막히는 정도를 초록·주황·빨강 세로 막대로 그린다. 위=도착지, 아래=내 위치. */
class TrafficBar(activity: Activity) : View(activity) {
    private var segs: List<Pair<Double, Int>> = emptyList()   // (구간 길이 m, 혼잡도)
    private var total = 1.0
    private var progress = 0.0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = activity.resources.displayMetrics.density

    fun setRoute(route: NaverRoute) {
        segs = route.sections.map { it.distance.toDouble() to it.congestion }
        total = route.distanceMeters.toDouble().coerceAtLeast(1.0)
        progress = 0.0
        invalidate()
    }

    fun setProgress(m: Double) { progress = m; invalidate() }

    private fun colorOf(c: Int) = when (c) {
        2 -> 0xFFFFA000.toInt()
        3 -> 0xFFE53935.toInt()
        else -> 0xFF1DB954.toInt()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val bw = 6f * d
        val left = (w - bw) / 2f
        val remain = (total - progress).coerceAtLeast(1.0)
        val carR = 7f * d
        val top = 0f; val bottom = h - carR * 2f
        paint.style = Paint.Style.FILL
        paint.color = 0x66FFFFFF
        canvas.drawRoundRect(RectF(left, top, left + bw, bottom), bw / 2f, bw / 2f, paint)
        if (segs.isEmpty()) {
            paint.color = colorOf(1)
            canvas.drawRoundRect(RectF(left, top, left + bw, bottom), bw / 2f, bw / 2f, paint)
        }
        var cursor = 0.0   // 경로 시작부터 이 구간 앞까지 거리
        for ((len, cong) in segs) {
            val s0 = cursor; val s1 = cursor + len
            cursor = s1
            if (s1 <= progress) continue
            val a = (maxOf(s0, progress) - progress) / remain   // 0=내 위치, 1=도착지
            val b = ((s1 - progress) / remain).coerceAtMost(1.0)
            paint.color = colorOf(cong)
            canvas.drawRect(left, bottom - (b * (bottom - top)).toFloat(), left + bw, bottom - (a * (bottom - top)).toFloat(), paint)
        }
        // 내 위치 표시(아래 끝)
        val cx = w / 2f; val cy = h - carR
        paint.color = Color.WHITE; canvas.drawCircle(cx, cy, carR, paint)
        paint.color = NaverMapIcons.BLUE
        canvas.drawPath(android.graphics.Path().apply {
            moveTo(cx, cy - carR * 0.6f); lineTo(cx + carR * 0.5f, cy + carR * 0.5f); lineTo(cx, cy + carR * 0.2f); lineTo(cx - carR * 0.5f, cy + carR * 0.5f); close()
        }, paint)
    }
}
