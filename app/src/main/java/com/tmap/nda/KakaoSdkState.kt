package com.tmap.nda

/**
 * KNSDK.install()/initializeWithAppKey()는 프로세스 전체에서 한 번만 하면 되는데,
 * MapActivity(앱 시작 시점 선행 초기화)와 KakaoNaviActivity(카카오 안내 전용 액티비티)
 * 양쪽에서 이 상태를 참조해야 해서 별도 싱글턴으로 뺌.
 * MapActivity가 앱 켜질 때 이미 초기화해두면, KakaoNaviActivity는 재초기화 없이
 * 바로 sharedGuidance()를 쓸 수 있음.
 */
object KakaoSdkState {
    @Volatile
    var initialized: Boolean = false

    // TmapUISDK.setVolume()은 조회(getter)가 없어서, 우리가 마지막으로 호출한 값을
    // 직접 기록해뒀다가 로그에서 "카카오 음성 재생 시점에 티맵이 진짜 음소거 상태였는지"를
    // 확인할 수 있게 함. #문제시 원복
    @Volatile
    var lastAppliedTmapVolume: Int? = null
}
