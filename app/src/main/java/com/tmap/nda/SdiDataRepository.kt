package com.tmap.nda

object SdiDataRepository {
    // v4.13: MapActivity/KakaoNaviActivity가 각자 독립적으로 lastOverSpeedWarningTime을
    // 들고 있어서, 둘 다 살아있는 동안 같은 GPS 속도값에 대해 경고음이 두 번(각 화면당
    // 8초 쿨다운) 겹쳐 울릴 수 있었음. 화면과 무관한 공용 쿨다운으로 통합. #문제시 원복
    @Volatile var lastOverSpeedWarningTime: Long = 0L

    var roadLimitSpeed: Int = 80

    // v: 버그수정(재억 제보 - "10% 이하로 달렸는데도 경고음 남") - 카카오 화면은
    // roadLimitSpeed(위 값, Tmap 전용)를 채워주는 코드가 없어서 항상 Tmap 화면의
    // 마지막 값(또는 기본값 80)이 그대로 남아있었음. 카카오 화면은 이 값을 별도로 써서
    // "Tmap이 안 채워준 값을 잘못 갖다쓰는" 문제를 원천 차단함. #문제시 원복
    @Volatile var kakaoRoadLimitSpeed: Int = 0
    @Volatile var kakaoRoadLimitSpeedUpdatedAt: Long = 0L

    /** 카카오 화면 과속경고음이 이 값을 신뢰해도 되는지(실제로 한 번이라도 채워졌고, 너무 오래되지 않았는지) */
    fun isKakaoRoadLimitFresh(maxAgeMs: Long = 8000L): Boolean {
        return kakaoRoadLimitSpeed >= 30 &&
            (System.currentTimeMillis() - kakaoRoadLimitSpeedUpdatedAt) < maxAgeMs
    }
    var sdiType: Int = 0
    var sdiSpeedLimit: Int = 0
    var sdiDistance: Int = 0
    var sdiBlockType: Int = 0
    var sdiBlockSpeed: Int = 0
    var sdiBlockDist: Int = 0
    var sdiBlockTime: Int = 0
    var isBlockSection: Boolean = false

    fun updateCurrentSdiState(
        limitSpeed: Int, type: Int, speedLimit: Int, distance: Int,
        blockType: Int, blockSpeed: Int, blockDist: Int, blockTime: Int = 0, blockSection: Boolean = false
    ) {
        roadLimitSpeed = limitSpeed
        sdiType = type
        sdiSpeedLimit = speedLimit
        sdiDistance = distance
        sdiBlockType = blockType
        sdiBlockSpeed = blockSpeed
        sdiBlockDist = blockDist
        sdiBlockTime = blockTime
        isBlockSection = blockSection
    }
}
