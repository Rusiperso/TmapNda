package com.tmap.nda

object SdiDataRepository {
    // v4.13: MapActivity/KakaoNaviActivity가 각자 독립적으로 lastOverSpeedWarningTime을
    // 들고 있어서, 둘 다 살아있는 동안 같은 GPS 속도값에 대해 경고음이 두 번(각 화면당
    // 8초 쿨다운) 겹쳐 울릴 수 있었음. 화면과 무관한 공용 쿨다운으로 통합. #문제시 원복
    @Volatile var lastOverSpeedWarningTime: Long = 0L

    var roadLimitSpeed: Int = 80
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
