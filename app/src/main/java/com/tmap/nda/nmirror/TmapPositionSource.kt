package com.tmap.nda.nmirror

/**
 * 티맵 내비 엔진이 계속 갱신하고 있는 안내 데이터(RGData)에서 **현재 차 위치**만 꺼내온다.
 *
 * v: 재억 제보(2026-09-04) - 나브디에 회전 화살표·거리·도로명은 뜨는데 가운데 지도(경로선)만
 * 안 그려지는 문제. 경로 좌표(180개)는 정상적으로 나가는 것을 로그로 확인했는데도 안 그려졌다.
 * 남은 차이는 **차가 그 경로 어디에 있는지를 한 번도 안 알려준 것** - 길 모양만 있고 내 위치가
 * 없으면 어디를 중심으로 어느 방향으로 그릴지 정할 수가 없다. nMirror가 순정 티맵에서 빼가는
 * RGData에는 이 값들이 들어 있다(vpPosPointLat/Lon, nPosAngle, nPosSpeed, remainedLengthToEnd).
 *
 * 우리 앱은 티맵 UI SDK를 품고 있어 같은 엔진이 이미 돌고 있으므로, UdpSenderService가
 * 제한속도를 읽을 때 쓰는 것과 동일한 통로(SDKManager.getRecentRGData())로 그대로 꺼내 쓴다.
 * 값을 못 읽으면 null을 돌려주고, 그 경우 JSON에 위치 칸을 아예 넣지 않는다(예전과 동일). #문제시 원복
 */
object TmapPositionSource {

    data class Position(
        val latitude: Double,
        val longitude: Double,
        val angle: Int,
        val speed: Int,
        val remainedLengthToEnd: Int,
        // 티맵 엔진이 쓰는 원본 형식 그대로(속도*10+20, 예: 1020=100km/h). 받는 쪽도 같은
        // 형식을 기대하므로 km/h로 바꾸지 않고 그대로 넘긴다. 0 = 못 읽음.
        val roadLimitSpeedRaw: Int
    )

    private var companion: Any? = null
    private var getInstance: java.lang.reflect.Method? = null
    private var getRecentRGData: java.lang.reflect.Method? = null
    @Volatile private var unavailable = false

    fun current(): Position? {
        if (unavailable) return null
        return try {
            if (companion == null) {
                val cls = Class.forName("com.skt.tmap.engine.navigation.SDKManager")
                companion = cls.getField("Companion").get(null)
                getInstance = companion?.javaClass?.getMethod("getInstance")
            }
            val manager = getInstance?.invoke(companion) ?: return null
            if (getRecentRGData == null) {
                getRecentRGData = manager.javaClass.getMethod("getRecentRGData")
            }
            val rgData = getRecentRGData?.invoke(manager) ?: return null

            val lat = doubleField(rgData, "vpPosPointLat")
            val lon = doubleField(rgData, "vpPosPointLon")
            // 위경도가 0이면 아직 위치를 못 잡은 것 - 그대로 보내면 아프리카 앞바다를 가리킨다.
            if (lat == null || lon == null || lat == 0.0 || lon == 0.0) return null

            Position(
                latitude = lat,
                longitude = lon,
                angle = intField(rgData, "nPosAngle") ?: 0,
                speed = intField(rgData, "nPosSpeed") ?: 0,
                remainedLengthToEnd = intField(rgData, "remainedLengthToEnd") ?: 0,
                roadLimitSpeedRaw = intField(rgData, "nRoadLimitSpeed") ?: 0
            )
        } catch (e: Exception) {
            unavailable = true
            com.tmap.nda.NavLogger.e("[nMirror] 티맵 엔진에서 현재 위치를 못 읽음(${e.javaClass.simpleName}) - 위치 없이 보냄")
            null
        }
    }

    private fun doubleField(target: Any, name: String): Double? = try {
        target.javaClass.getField(name).get(target) as? Double
    } catch (e: Exception) {
        null
    }

    private fun intField(target: Any, name: String): Int? = try {
        target.javaClass.getField(name).get(target) as? Int
    } catch (e: Exception) {
        null
    }
}
