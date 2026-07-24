package com.tmap.nda

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

data class OpenpilotState(
    val carrot2: String = "",
    val ip: String = "",
    val trafficState: Int = 0,
    val xState: Int = 0,
    val active: Boolean = false
)

/** NDA(EON:ROAD_LIMIT_SERVICE:v1) 브릿지를 통해 openpilot으로부터 역수신한 GPS 위치. */
data class NdaGpsState(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0,
    val speed: Double = 0.0,
    val bearing: Double = 0.0,
    val accuracy: Double = 0.0,
    val timestampMs: Long = 0L,
    val hasFix: Boolean = false
)

object OpenpilotStateRepository {
    private val _state = MutableLiveData(OpenpilotState())
    val state: LiveData<OpenpilotState> get() = _state

    private val _ndaGpsState = MutableLiveData(NdaGpsState())
    val ndaGpsState: LiveData<NdaGpsState> get() = _ndaGpsState

    fun updateState(
        carrot2: String,
        ip: String,
        trafficState: Int,
        xState: Int,
        active: Boolean
    ) {
        _state.postValue(OpenpilotState(carrot2, ip, trafficState, xState, active))
    }

    fun updateNdaGps(
        latitude: Double,
        longitude: Double,
        altitude: Double,
        speed: Double,
        bearing: Double,
        accuracy: Double,
        timestampMs: Long
    ) {
        _ndaGpsState.postValue(
            NdaGpsState(latitude, longitude, altitude, speed, bearing, accuracy, timestampMs, hasFix = true)
        )
    }
}
