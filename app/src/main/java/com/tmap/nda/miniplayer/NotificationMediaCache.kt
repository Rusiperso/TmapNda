package com.tmap.nda.miniplayer

/**
 * v: 재억 제보(2026-08-30) - MiniPlayerNotificationListener가 알림에서 읽은
 * "제목/부제"를 패키지명별로 저장해두는 공용 캐시. MediaSession 메타데이터가
 * 앱/시스템 쪽 지연으로 늦게 갱신될 때, 이 캐시(알림창에 실제로 뜬 텍스트 기반이라
 * 더 즉각적)를 우선 사용하기 위함. #문제시 원복
 */
object NotificationMediaCache {
    data class Entry(val title: String, val text: String?, val updatedAt: Long)

    @Volatile
    private var byPackage: Map<String, Entry> = emptyMap()

    fun update(packageName: String, title: String, text: String?) {
        val current = byPackage[packageName]
        // 완전히 동일한 내용이면 시간만 갱신하지 않음(불필요한 쓰기 방지) - 그래도
        // 최초 저장이거나 내용이 바뀌었으면 갱신
        if (current != null && current.title == title && current.text == text) return
        byPackage = byPackage + (packageName to Entry(title, text, System.currentTimeMillis()))
    }

    fun get(packageName: String): Entry? = byPackage[packageName]
}
