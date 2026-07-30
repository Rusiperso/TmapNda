package com.tmap.nda

object SdiDataRepository {
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
