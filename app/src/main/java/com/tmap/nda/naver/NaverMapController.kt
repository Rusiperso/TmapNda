package com.tmap.nda.naver

import android.app.Activity
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.naver.maps.geometry.LatLng
import com.naver.maps.geometry.LatLngBounds
import com.naver.maps.map.CameraAnimation
import com.naver.maps.map.CameraPosition
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.MapView
import com.naver.maps.map.NaverMap
import com.naver.maps.map.NaverMapSdk
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.MultipartPathOverlay
import com.naver.maps.map.overlay.OverlayImage
import com.naver.maps.map.overlay.PathOverlay

/**
 * 안내 화면 뒤에 깔리는 네이버 지도. 카카오 SDK의 지도(KNNaviView)가 하던 일 - 경로선, 목적지 핀,
 * 내 위치 따라가기, 줌 유지, 낮밤 - 을 대신한다. 화면 위의 UI(상단바·버튼·카드)는 이 클래스와 무관하다.
 */
class NaverMapController(private val activity: Activity, private val host: FrameLayout) {

    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    val mapView: MapView = MapView(activity)
    var map: NaverMap? = null
        private set
    private var onReadyCallbacks = ArrayList<(NaverMap) -> Unit>()

    private val previewPaths = ArrayList<PathOverlay>()
    private var activePath: PathOverlay? = null
    private var pin: Marker? = null
    private var carMarker: Marker? = null

    /** 사용자가 손으로 지도를 움직이면 이 시각까지 자동 따라가기를 쉰다. */
    @Volatile var followPausedUntil = 0L
    @Volatile private var entryUntil = 0L
    /** 핀치 줌 직후 이 시각까지는 자동 줌을 하지 않고 사용자가 맞춘 줌을 유지한다. */
    @Volatile var zoomHoldUntil = 0L
    /** 상단 바가 가리는 만큼 위로 비워두는 여백(px)과, 차가 놓일 위치를 아래로 내리는 비율(0~0.5). */
    var topInsetPx = 0
    var driverAnchorBottomFraction = 0.0
    /** 안내 중 차가 놓일 세로 위치 비율(0=맨 위, 1=맨 아래, 0.5=가운데). 클수록 차가 아래로 내려가 앞이 더 보임. */
    var driverAnchorYFraction = 0.79f
    /** 가로 화면에서 차를 놓을 가로 위치 비율(0.5=가운데, 작을수록 왼쪽). null이면 가운데. */
     var anchorXFraction: Float? = null

    fun init(client: String, savedState: Bundle?, onReady: (NaverMap) -> Unit) {
        NaverMapSdk.getInstance(activity).client = NaverMapSdk.NcpKeyClient(client)
        host.addView(mapView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        mapView.onCreate(savedState)
        onReadyCallbacks.add(onReady)
        mapView.getMapAsync { m ->
            map = m
            m.mapType = NaverMap.MapType.Basic
            m.uiSettings.apply {
                isCompassEnabled = false; isScaleBarEnabled = false
                isZoomControlEnabled = false; isLocationButtonEnabled = false
                isRotateGesturesEnabled = true; isTiltGesturesEnabled = true
            }
            m.locationOverlay.isVisible = true
            m.locationOverlay.iconAlpha = 1f
            m.locationOverlay.icon = OverlayImage.fromBitmap(NaverMapIcons.car(dp(56)))
            m.locationOverlay.iconWidth = dp(56); m.locationOverlay.iconHeight = dp(56)
            m.locationOverlay.circleRadius = 0
            m.locationOverlay.zIndex = 50
            m.addOnCameraChangeListener { reason, _ ->
                if (reason == CameraUpdate.REASON_GESTURE) {
                    val now = System.currentTimeMillis()
                    followPausedUntil = now + 10_000L
                    zoomHoldUntil = now + 10_000L
                }
            }
            onReadyCallbacks.forEach { it(m) }
            onReadyCallbacks.clear()
        }
    }

    /** 지도가 준비되면 실행(이미 준비됐으면 바로). */
    fun whenReady(block: (NaverMap) -> Unit) {
        val m = map
        if (m != null) block(m) else onReadyCallbacks.add(block)
    }

    // ===== 생명주기 =====
    fun onStart() = mapView.onStart()
    fun onResume() = mapView.onResume()
    fun onPause() = mapView.onPause()
    fun onStop() = mapView.onStop()
    fun onSaveInstanceState(out: Bundle) = mapView.onSaveInstanceState(out)
    fun onLowMemory() = mapView.onLowMemory()
    fun onDestroy() = mapView.onDestroy()

    // ===== 위성지도 =====
    /** 켜면 일반 위성 지도(Hybrid), 끄면 일반 지도(Basic). */
    fun setSatellite(on: Boolean) = whenReady {
        val t = if (on) NaverMap.MapType.Hybrid else NaverMap.MapType.Basic
        if (it.mapType != t) it.mapType = t   // 같은 값 재설정도 지도를 통째로 다시 그려 하얗게 비므로 바뀔 때만
    }

    // ===== 기울기 / 교통 / 위치 아이콘 =====
    /** 켜면 지도를 눕히지 않고 위에서 내려다보는 평면으로, 끄면 안내 중 40도 기울기. */
    @Volatile private var flat = false
    fun setFlat(on: Boolean) = whenReady { m ->
        val same = flat == on && m.maxTilt == (if (on) 0.0 else 60.0)
        flat = on
        if (same) return@whenReady
        m.maxTilt = if (on) 0.0 else 60.0
        val c = m.cameraPosition
        m.moveCamera(CameraUpdate.toCameraPosition(CameraPosition(c.target, c.zoom, if (on) 0.0 else 40.0, c.bearing)))
    }

    /** 네이버 실시간 교통 정보(도로 정체 색) 레이어. */
    fun setTraffic(on: Boolean) = whenReady { if (it.isLayerGroupEnabled(NaverMap.LAYER_GROUP_TRAFFIC) != on) it.setLayerGroupEnabled(NaverMap.LAYER_GROUP_TRAFFIC, on) }

    // ===== 낮/밤 =====
    fun setNight(night: Boolean) = whenReady { if (it.isNightModeEnabled != night) it.isNightModeEnabled = night }

    // ===== 경로선 =====
    private fun latLngs(path: List<LonLat>) = path.map { LatLng(it.lat, it.lon) }

    /** 후보 경로들을 색깔별로 그린다. [selected]가 있으면 그것만 진하게, 나머지는 흐리게. */
    fun showPreview(routes: List<NaverRoute>, colorOf: (NaverRoute) -> Int, selected: NaverRoute? = null) = whenReady { m ->
        clearPreview()
        activePath?.map = null; activePath = null
        routes.forEach { r ->
            val isSel = selected == null || r === selected
            previewPaths.add(PathOverlay().apply {
                coords = latLngs(r.path)
                width = dp(if (isSel) 10 else 7)
                color = if (isSel) colorOf(r) else (colorOf(r) and 0x00FFFFFF) or 0x66000000
                outlineWidth = dp(2); outlineColor = Color.WHITE
                zIndex = if (isSel) 1 else 0
                isHideCollidedSymbols = true; isHideCollidedMarkers = true; isHideCollidedCaptions = true
                this.map = m
            })
        }
    }

    fun clearPreview() {
        previewPaths.forEach { it.map = null }
        previewPaths.clear()
    }

    private var lastActiveRoute: NaverRoute? = null
    private var activeMulti: MultipartPathOverlay? = null

    fun showActiveRoute(route: NaverRoute) = whenReady { m ->
        lastActiveRoute = route
        clearPreview()
        activePath?.map = null; activePath = null
        activeMulti?.map = null; activeMulti = null
        val colored = activity.getSharedPreferences("TmapNdaPrefs", android.content.Context.MODE_PRIVATE)
            .getBoolean("naver_route_traffic_color", false)
        val blue = 0xFF2E7DFF.toInt(); val gray = 0xFFB0BEC5.toInt()
        if (colored && route.sections.isNotEmpty() && route.path.size >= 2) {
            // 구간별 막힘(원활=초록 · 서행=주황 · 정체=빨강)을 경로선에 입힌다. 구간 사이 빈 곳은 파란색.
            val coords = ArrayList<List<LatLng>>(); val colors = ArrayList<MultipartPathOverlay.ColorPart>()
            val last = route.path.size - 1
            fun add(from: Int, to: Int, color: Int) {
                if (to <= from) return
                coords.add(latLngs(route.path.subList(from, minOf(to, last) + 1)))
                colors.add(MultipartPathOverlay.ColorPart(color, Color.WHITE, gray, Color.WHITE))
            }
            var cursor = 0
            route.sections.sortedBy { it.pointIndex }.forEach { sec ->
                val from = sec.pointIndex.coerceIn(cursor, last)
                val to = (sec.pointIndex + maxOf(1, sec.pointCount)).coerceIn(from, last)
                add(cursor, from, blue)
                val color = when (sec.congestion) { 1 -> 0xFF1DB954.toInt(); 2 -> 0xFFFFA000.toInt(); 3 -> 0xFFE53935.toInt(); else -> blue }
                add(from, to, color)
                cursor = to
            }
            add(cursor, last, blue)
            activeMulti = MultipartPathOverlay(coords, colors).apply {
                width = dp(11); outlineWidth = dp(2)
                patternImage = OverlayImage.fromBitmap(NaverMapIcons.chevron(dp(14)))
                patternInterval = dp(34)
                isHideCollidedSymbols = true; isHideCollidedMarkers = true; isHideCollidedCaptions = true
                this.map = m
            }
            return@whenReady
        }
        activePath = PathOverlay().apply {
            coords = latLngs(route.path)
            width = dp(11); color = blue; outlineWidth = dp(2); outlineColor = Color.WHITE
            passedColor = gray; passedOutlineColor = Color.WHITE
            patternImage = OverlayImage.fromBitmap(NaverMapIcons.chevron(dp(14)))
            patternInterval = dp(34)
            isHideCollidedSymbols = true; isHideCollidedMarkers = true; isHideCollidedCaptions = true
            this.map = m
        }
    }

    /** 경로선 표시 옵션(교통 색 등)이 바뀌었을 때 지금 경로를 다시 그린다. */
    fun redrawActiveRoute() { lastActiveRoute?.let { showActiveRoute(it) } }

    fun setProgress(fraction: Double) {
        activePath?.progress = fraction.coerceIn(0.0, 1.0)
        activeMulti?.progress = fraction.coerceIn(0.0, 1.0)
    }

    fun clearAllRoutes() {
        clearPreview()
        activePath?.map = null; activePath = null
        activeMulti?.map = null; activeMulti = null
        clearDestination()
        setSafetyPoints(emptyList())
    }

    /** 안내 중 도착지 표시(안내 시작 때 지워지던 핀 대신 끝까지 유지). */
    private var destMarker: Marker? = null
    fun showDestination(lat: Double, lon: Double, caption: String) = whenReady { m ->
        destMarker?.map = null
        destMarker = Marker().apply {
            position = LatLng(lat, lon)
            icon = OverlayImage.fromBitmap(NaverMapIcons.arrival(dp(46), dp(58)))
            width = dp(46); height = dp(58)
            isForceShowIcon = true
            zIndex = 100
            this.map = m
        }
    }

    /** 다음 회전 지점에 표지(흰 네모 + 화살표)를 놓는다. 같은 지점·종류면 다시 만들지 않는다. */
    private var turnSign: Marker? = null
    private var turnSignKey: String? = null
    fun showTurnSign(lat: Double, lon: Double, code: Int) = whenReady { m ->
        val key = "$lat,$lon,$code"
        if (key == turnSignKey) return@whenReady
        turnSignKey = key
        turnSign?.map = null
        turnSign = Marker().apply {
            position = LatLng(lat, lon)
            icon = OverlayImage.fromBitmap(NaverMapIcons.turnSign(code, dp(44)))
            width = dp(44); height = dp(44)
            isForceShowIcon = true
            zIndex = 90
            this.map = m
        }
    }

    /** 앞쪽 안전정보(카메라·방지턱·구간단속 등)를 경로 위에 찍는다. 목록에서 빠지면(지나가면) 지도에서도 없어진다. */
    private val safetyMarkers = HashMap<Long, Marker>()
    fun setSafetyPoints(points: List<SafetyPoint>) = whenReady { m ->
        val keep = HashSet<Long>()
        for (p in points) keep.add(p.id)
        val gone = safetyMarkers.keys.filter { it !in keep }
        for (id in gone) { safetyMarkers.remove(id)?.map = null }
        for (p in points) {
            if (p.lat.isNaN() || p.lon.isNaN()) continue
            val mk = safetyMarkers.getOrPut(p.id) {
                Marker().apply {
                    position = LatLng(p.lat, p.lon)
                    icon = OverlayImage.fromBitmap(NaverMapIcons.safety(p.type, p.speedLimit, dp(40)))
                    width = dp(40); height = dp(40)
                    isForceShowIcon = true
                    zIndex = 80
                    captionTextSize = 12f
                    captionColor = Color.BLACK
                    captionHaloColor = Color.WHITE
                    this.map = m
                }
            }
            mk.position = LatLng(p.lat, p.lon)
            mk.captionText = if (p.aheadMeters >= 1000) String.format("%.1fkm", p.aheadMeters / 1000.0) else "${p.aheadMeters / 10 * 10}m"
        }
    }

    fun clearTurnSign() { turnSign?.map = null; turnSign = null; turnSignKey = null }

    fun clearDestination() { clearTurnSign(); destMarker?.map = null; destMarker = null }

    // ===== 핀 / 마커 =====
    fun showPin(lat: Double, lon: Double, caption: String) = whenReady { m ->
        pin?.map = null
        pin = Marker().apply {
            position = LatLng(lat, lon)
            icon = OverlayImage.fromBitmap(NaverMapIcons.arrival(dp(46), dp(58)))
            width = dp(46); height = dp(58)
            captionText = caption
            isForceShowIcon = true
            zIndex = 100
            this.map = m
        }
    }

    fun clearPin() { pin?.map = null; pin = null }

    /** "내 차 위치"(주차한 곳) 표시. */
    fun showParkedCar(lat: Double, lon: Double, caption: String) = whenReady { m ->
        carMarker?.map = null
        carMarker = Marker().apply { position = LatLng(lat, lon); captionText = caption; this.map = m }
    }

    fun clearParkedCar() { carMarker?.map = null; carMarker = null }

    // ===== 카메라 =====
    fun moveTo(lat: Double, lon: Double, zoom: Double, animated: Boolean = true) = whenReady { m ->
        val upd = CameraUpdate.toCameraPosition(CameraPosition(LatLng(lat, lon), zoom, 0.0, 0.0))
        m.moveCamera(if (animated) upd.animate(CameraAnimation.Easing, 600) else upd)
    }

    /** 점들이 모두 보이게 맞춘다(상단 바와 아래 여백만큼 피해서). */
    fun fitTo(points: List<LatLng>, paddingPx: Int = dp(90)) = whenReady { m ->
        if (points.size < 2) { points.firstOrNull()?.let { moveTo(it.latitude, it.longitude, 15.0) }; return@whenReady }
        val b = LatLngBounds.Builder()
        points.forEach { b.include(it) }
        try { m.moveCamera(CameraUpdate.fitBounds(b.build(), paddingPx, paddingPx + topInsetPx, paddingPx, paddingPx)) } catch (_: Exception) {}
    }

    fun fitToRoutes(routes: List<NaverRoute>, extra: List<LatLng> = emptyList()) {
        val pts = ArrayList<LatLng>(extra)
        routes.forEach { r -> pts.addAll(r.path.map { LatLng(it.lat, it.lon) }) }
        fitTo(pts)
    }

    fun setLocation(loc: Location) = whenReady { m ->
        m.locationOverlay.position = LatLng(loc.latitude, loc.longitude)
        if (loc.hasBearing()) m.locationOverlay.bearing = loc.bearing
    }

    /** 안내 중 내 위치를 따라간다. 사용자가 손으로 움직인 뒤 10초간은 쉬고, 핀치로 맞춘 줌은 그동안 유지한다. */
    fun follow(loc: Location, animated: Boolean = true, entryMs: Long = 0L) = whenReady { m ->
        val now = System.currentTimeMillis()
        if (now < followPausedUntil) return@whenReady
        if (entryMs <= 0 && now < entryUntil) return@whenReady   // 진입 애니메이션 중엔 끼어들지 않음
        if (entryMs > 0) entryUntil = now + entryMs
        val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6 else 0.0
        val moving = loc.hasSpeed() && loc.speed > 1.5f
        val zoom = if (now < zoomHoldUntil) m.cameraPosition.zoom else if (nearTurn) 17.4 else when {
            speedKmh > 90 -> 14.8; speedKmh > 50 -> 15.6; speedKmh > 15 -> 16.4; else -> 17.0
        }
        val bearing = if (moving && loc.hasBearing()) loc.bearing.toDouble() else m.cameraPosition.bearing
        // 차를 화면 아래쪽으로 내려서 앞이 더 보이게(여백으로 구현)
        val h = host.height
        val bottomPad = (h * driverAnchorBottomFraction).toInt()
        val ax = anchorXFraction
        // 운전자 쪽 치우침은 오른쪽 여백(엔진이 몇 초에 걸쳐 천천히 반영해 차가 미끄러져 보임) 대신, 카메라 이동 때 차가 놓일 자리(pivot)로 한 번에 맞춤
        m.setContentPadding(0, topInsetPx, 0, bottomPad)
        val upd = CameraUpdate.toCameraPosition(CameraPosition(LatLng(loc.latitude, loc.longitude), zoom, if (flat || nearTurn) 0.0 else 40.0, bearing))
        // 재억 요청(2026-10-13): 차가 세로 가운데에 있어 앞 경로선이 잘림 -> 세로도 아래쪽(driverAnchorYFraction)에 놓음. 가로는 ax(없으면 가운데). #문제시 원복
        upd.pivot(android.graphics.PointF(ax ?: 0.5f, driverAnchorYFraction))
        // entryMs>0: 안내 시작 진입 - 위치·확대·기울기를 한 번에 부드럽게(툭툭 끊김 방지)
        // 멀리(전체 경로 화면 등)서 날아오면 타일이 흐릿하게 늦게 떠서 오히려 끊겨 보임 -> 멀거나 배율 차가 크면 한 번에 맞추고, 가까울 때만 부드럽게
        val cp = m.cameraPosition
        val near = entryMs > 0 && cp.target.distanceTo(LatLng(loc.latitude, loc.longitude)) < 1500.0 && Math.abs(cp.zoom - zoom) < 2.5
        if (entryMs > 0 && !near) entryUntil = 0L
        m.moveCamera(if (near) upd.animate(CameraAnimation.Easing, entryMs) else if (entryMs > 0) upd else if (animated) upd.animate(CameraAnimation.Linear, 900) else upd)
    }

    /** 회전이 200m 안으로 다가오면 네이버 앱처럼 지도를 평면으로 확대한다. */
    @Volatile var nearTurn = false

    fun resumeFollow() { followPausedUntil = 0L; zoomHoldUntil = 0L }

    /** 사용자가 맞춘 줌을 [ms] 동안 유지(자동 줌이 덮어쓰지 않게). */
    fun holdZoom(ms: Long = 10_000L) { zoomHoldUntil = System.currentTimeMillis() + ms }

    fun setZoom(zoom: Double) = whenReady { it.moveCamera(CameraUpdate.zoomTo(zoom)) }

    /** 지도를 진북 고정 2D로(경로 미리보기 등). */
    fun northUp2D() = whenReady { m ->
        val c = m.cameraPosition
        m.moveCamera(CameraUpdate.toCameraPosition(CameraPosition(c.target, c.zoom, 0.0, 0.0)))
    }

    val cameraZoom: Double get() = map?.cameraPosition?.zoom ?: 15.0

    fun view(): View = mapView
}
