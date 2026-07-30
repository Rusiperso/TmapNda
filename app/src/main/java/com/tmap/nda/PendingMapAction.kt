package com.tmap.nda

/**
 * KakaoNaviActivity는 MapActivity 위에 별도 Activity로 떠있다가 finish()하면
 * 원래 있던(백스택에 남아있던) MapActivity 인스턴스가 그대로 onResume됨.
 * "검색 버튼"/"최근 목적지" 탭을 눌렀을 때 새 MapActivity를 또 띄우지 않고
 * 이 기존 인스턴스가 재개되는 시점에 원하는 동작(검색창 열기/특정 검색어로
 * 바로 재검색)을 하도록 신호만 넘겨주는 용도. #문제시 원복
 */
object PendingMapAction {
    @Volatile
    var openSearchPanel: Boolean = false

    @Volatile
    var autoSearchQuery: String? = null

    @Volatile
    var openFullHistoryDialog: Boolean = false
}
