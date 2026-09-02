package com.tmap.nda

/**
 * v: 재억 제보(2026-09-02) - "안내 중에 즐겨찾기를 누르면 경유지로 추가할지 물어봐야 하는데
 * 바로 새 안내를 시작한다". 카카오 화면(KakaoNaviActivity)은 자기 안에서 바로 경유지를 추가할
 * 수 있지만, 티맵 화면(MapActivity)에는 경로를 다시 짜는 코드(KNSDK/naviView)가 없음.
 *
 * 그래서 티맵 화면에서 "경유지 추가"를 고르면 여기에 담아두고 화면을 닫고, 뒤에 살아있는
 * 카카오 안내 화면이 다시 올라오는 순간(onResume) 그걸 집어서 실제 경유지 추가를 수행함.
 * 새 액티비티를 띄우는 방식(startActivity)은 KakaoNaviActivity가 standard 런치모드라 안내
 * 중인 화면이 아닌 새 인스턴스가 만들어져서 못 씀. #문제시 원복
 *
 * 30초가 지난 요청은 무시함(화면 전환이 실패했거나 사용자가 딴 걸 하다 돌아온 경우, 뒤늦게
 * 엉뚱한 경유지가 끼어드는 걸 막기 위함).
 */
object PendingWaypointRequest {
    private const val EXPIRE_MS = 30_000L

    @Volatile private var entry: HistoryEntry? = null
    @Volatile private var requestedAt: Long = 0L

    fun put(target: HistoryEntry) {
        entry = target
        requestedAt = System.currentTimeMillis()
    }

    /** 유효한 요청이 있으면 꺼내고 비움. 없거나 만료됐으면 null. */
    fun take(): HistoryEntry? {
        val target = entry ?: return null
        entry = null
        if (System.currentTimeMillis() - requestedAt > EXPIRE_MS) return null
        return target
    }

    fun clear() {
        entry = null
    }
}
