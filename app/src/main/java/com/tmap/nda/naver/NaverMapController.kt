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
    /** 핀치 줌 직후 이 시각까지는 자동 줌을 하지 않고 사용자가 맞춘 줌을 유지한다. */
    @Volatile var zoomHoldUntil = 0L
    /** 상단 바가 가리는 만큼 위로 비워두는 여백(px)과, 차가 놓일 위치를 아래로 내리는 비율(0~0.5). */
    var topInsetPx = 0
    var driverAnchorBottomFraction = 0.0
    /** 가로 화면에서 차를 놓을 가로 위치 비율(0.5=가운데, 작을수록 왼쪽). null이면 가운데. */
     var anchorXFraction: Float? = null

    fun init(client: String, savedState: Bundle?, onReady: (NaverMap) -> Unit) {
        NaverMapSdk.getInstance(activity).client = NaverMapSdk.NcpKeyClient(client)
        host.addView(mapView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        mapView.onCreate(savedState)
        onReadyCallbacks.add(onReady)
        mapView.getMapAsync { m ->
            map = m
            m.mapType = NaverMap.MapType.Navi
            m.uiSettings.apply {
                isCompassEnabled = false; isScaleBarEnabled = false
                isZoomControlEnabled = false; isLocationButtonEnabled = false
                isRotateGesturesEnabled = true; isTiltGesturesEnabled = true
            }
            m.locationOverlay.isVisible = true
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
    /** 켜면 내비용 위성 지도(NaviHybrid), 끄면 내비용 일반 지도(Navi). */
    fun setSatellite(on: Boolean) = whenReady {
        it.mapType = if (on) NaverMap.MapType.NaviHybrid else NaverMap.MapType.Navi
    }

    // ===== 낮/밤 =====
    fun setNight(night: Boolean) = whenReady { it.isNightModeEnabled = night }

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
                this.map = m
            })
        }
    }

    fun clearPreview() {
        previewPaths.forEach { it.map = null }
        previewPaths.clear()
    }

    fun showActiveRoute(route: NaverRoute) = whenReady { m ->
        clearPreview()
        activePath?.map = null
        activePath = PathOverlay().apply {
            coords = latLngs(route.path)
            width = dp(11); color = 0xFF2E7DFF.toInt(); outlineWidth = dp(2); outlineColor = Color.WHITE
            passedColor = 0xFFB0BEC5.toInt(); passedOutlineColor = Color.WHITE
            this.map = m
        }
    }

    fun setProgress(fraction: Double) {
        activePath?.progress = fraction.coerceIn(0.0, 1.0)
    }

    fun clearAllRoutes() {
        clearPreview()
        activePath?.map = null; activePath = null
    }

    // ===== 핀 / 마커 =====
    fun showPin(lat: Double, lon: Double, caption: String) = whenReady { m ->
        pin?.map = null
        pin = Marker().apply { position = LatLng(lat, lon); captionText = caption; this.map = m }
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
    fun follow(loc: Location, animated: Boolean = true) = whenReady { m ->
        val now = System.currentTimeMillis()
        if (now < followPausedUntil) return@whenReady
        val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6 else 0.0
        val moving = loc.hasSpeed() && loc.speed > 1.5f
        val zoom = if (now < zoomHoldUntil) m.cameraPosition.zoom else when {
            speedKmh > 90 -> 14.8; speedKmh > 50 -> 15.6; speedKmh > 15 -> 16.4; else -> 17.0
        }
        val bearing = if (moving && loc.hasBearing()) loc.bearing.toDouble() else m.cameraPosition.bearing
        // 차를 화면 아래쪽으로 내려서 앞이 더 보이게(여백으로 구현)
        val h = host.height
        val bottomPad = (h * driverAnchorBottomFraction).toInt()
        val ax = anchorXFraction
        val rightPad = if (ax != null) ((1f - 2f * ax) * host.width).toInt().coerceAtLeast(0) else 0
        m.setContentPadding(0, topInsetPx, rightPad, bottomPad)
        val upd = CameraUpdate.toCameraPosition(CameraPosition(LatLng(loc.latitude, loc.longitude), zoom, 40.0, bearing))
        m.moveCamera(if (animated) upd.animate(CameraAnimation.Linear, 900) else upd)
    }

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
