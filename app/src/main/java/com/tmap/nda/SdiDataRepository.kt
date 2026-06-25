package com.tmap.nda

object SdiDataRepository {
    var roadLimitSpeed: Int = 80
    var sdiType: Int = 1
    var sdiSpeedLimit: Int = 80
    var sdiDistance: Int = 300
    var sdiBlockType: Int = 0
    var sdiBlockSpeed: Int = 0
    var sdiBlockDist: Int = 0

    fun updateCurrentSdiState(
        limitSpeed: Int, type: Int, speedLimit: Int, distance: Int,
        blockType: Int, blockSpeed: Int, blockDist: Int
    ) {
        roadLimitSpeed = limitSpeed
        sdiType = type
        sdiSpeedLimit = speedLimit
        sdiDistance = distance
        sdiBlockType = blockType
        sdiBlockSpeed = blockSpeed
        sdiBlockDist = blockDist
    }
}
