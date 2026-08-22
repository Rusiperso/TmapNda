package com.tmap.nda

object SdiDataRepository {
    // v4.13: MapActivity/KakaoNaviActivity가 각자 독립적으로 lastOverSpeedWarningTime을
    // 들고 있어서, 둘 다 살아있는 동안 같은 GPS 속도값에 대해 경고음이 두 번(각 화면당
    // 8초 쿨다운) 겹쳐 울릴 수 있었음. 화면과 무관한 공용 쿨다운으로 통합. #문제시 원복
    @Volatile var lastOverSpeedWarningTime: Long = 0L

    // v: 재억 제보(2026-08-22) - 카메라/방지턱에 접근하는 동안 300~500m 지점에서 한 번 울리고,
    // 계속 속도를 안 줄이면 8초 쿨다운마다 계속 울리다가 100m 이내에서 또 울림(사용자 입장에선
    // "또 운다"). 카메라에 접근하는 동안은 "이 카메라 하나당 딱 한 번"만 울리게 하고, 카메라를
    // 지나가서 이벤트가 사라지면 다음 카메라를 위해 다시 무장(false)하도록 분리. 카메라가 없는
    // 상태에서 그냥 계속 과속 중일 때 울리는 일반 경고는 기존 8초 반복 그대로 유지. #문제시 원복
    @Volatile var cameraApproachWarned: Boolean = false

    /** 지금 카메라/방지턱 등 안전이벤트에 근접 중인지(500m 이내) */
    fun isNearCameraEvent(): Boolean = sdiType > 0 && sdiDistance in 1..500

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
