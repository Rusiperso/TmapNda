package com.tmap.nda.naver

/** 네이버 길찾기(Directions 15) 응답을 앱 안에서 쓰는 모양. 좌표는 경도(lon), 위도(lat). */
data class LonLat(val lon: Double, val lat: Double)

/** 안내 지점 1개. pointIndex = path 안의 위치, distance = 직전 안내 지점부터의 거리(m), duration = 같은 구간 시간(ms). */
data class NaverGuide(
    val pointIndex: Int,
    val type: Int,
    val instructions: String,
    val distance: Int,
    val durationMs: Long
)

/** 큰 도로 구간. 도로 이름과 혼잡도 표시용. */
data class NaverSection(
    val pointIndex: Int,
    val pointCount: Int,
    val distance: Int,
    val name: String,
    val congestion: Int,
    val speed: Int
)

data class NaverRoute(
    val option: String,
    val path: List<LonLat>,
    val guides: List<NaverGuide>,
    val sections: List<NaverSection>,
    val distanceMeters: Int,
    val durationMs: Long,
    val tollFare: Int,
    val taxiFare: Int,
    val fuelPrice: Int
)

/** 길찾기 한 번의 결과. 성공이면 routes가 채워지고, 실패면 code/message로 이유를 알 수 있다. */
data class NaverDirectionsResult(
    val code: Int,
    val message: String,
    val routes: List<NaverRoute>
) {
    val ok: Boolean get() = code == 0 && routes.isNotEmpty()
}
