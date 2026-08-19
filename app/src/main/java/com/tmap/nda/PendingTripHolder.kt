package com.tmap.nda

/**
 * v13.7: 재억 요청 - "추천/고속도로/무료도로" 고르는 화면(MapActivity)에서 이미 시간
 * 비교용으로 계산해둔 경로(trip)를, 안내가 실제로 시작되는 화면(KakaoNaviActivity)에서
 * 다시 처음부터 계산하지 않고 재사용해서 안내 시작 딜레이를 줄이려고 만든 임시 보관함.
 *
 * 같은 프로세스 안에서 화면끼리 객체를 그대로 넘기는 용도라 Intent에 안 담고 여기 담음.
 * 재사용 가능한지 100% 확신은 못 해서(SDK 문서 없음), 쓰는 쪽에서 실패하면 예전 방식
 * (처음부터 새로 계산)으로 안전하게 돌아가도록 try-catch로 감싸서 씀. #문제시 원복
 */
object PendingTripHolder {
    private const val MAX_AGE_MS = 15_000L

    @Volatile private var trip: Any? = null
    @Volatile private var destLat: Double = 0.0
    @Volatile private var destLon: Double = 0.0
    @Volatile private var savedAtMs: Long = 0L

    fun set(trip: Any?, destLat: Double, destLon: Double) {
        this.trip = trip
        this.destLat = destLat
        this.destLon = destLon
        this.savedAtMs = System.currentTimeMillis()
    }

    /** 목적지 좌표가 일치하고, 너무 오래되지 않았을 때만(15초 이내) 돌려줌. 그 외엔 null. */
    fun consumeIfMatches(destLat: Double, destLon: Double): Any? {
        val t = trip
        trip = null
        if (t == null) return null
        val ageMs = System.currentTimeMillis() - savedAtMs
        if (ageMs > MAX_AGE_MS) return null
        val latMatches = Math.abs(this.destLat - destLat) < 0.0005
        val lonMatches = Math.abs(this.destLon - destLon) < 0.0005
        return if (latMatches && lonMatches) t else null
    }

    fun clear() {
        trip = null
    }
}
