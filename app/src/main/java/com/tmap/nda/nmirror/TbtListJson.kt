package com.tmap.nda.nmirror

import com.tmap.nda.NavLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * 앞으로 지날 고속도로 시설(톨게이트/휴게소/IC/JC) 목록을 만든다.
 *
 * v: 재억 제보(2026-09-04, 나브디 사진 비교) - 순정 티맵으로 보낼 때는 나브디 오른쪽에
 * "청도새마을휴게소 52km / 다음 32km"가 뜨는데 우리 앱으로 보낼 때만 그 자리가 비었다.
 * 원인은 길안내와 같이 보내는 두 번째 자리(`tbtListInfos`)를 우리가 계속 null로 두고
 * 있었던 것. 그 자리에 뭐가 들어가야 하는지는 티맵 내비 SDK 원본 소스(TBTListInfo.java)로
 * 확정했다 - 추측이 아니라 정의 그대로다:
 *
 *   nTBTType         1=톨게이트, 2=휴게소, 3=IC, 4=JC, 5=회전지점
 *   szTBTMainText    이름
 *   remainDistance   현재 위치에서 남은 거리(m)
 *   distanceFromPreviousTBT  바로 앞 시설에서부터의 거리(m)  ← 화면의 "다음 32km"
 *   bHighway         고속도로 여부
 *
 * 카카오도 같은 정보를 정식으로 준다(KNGuide_Route.hwInfo → 휴게소 목록/시설 목록).
 * 다만 이 저장소에서 카카오 타입을 코드에 직접 쓰면 빌드가 깨진 전례가 여러 번 있어
 * (KNLane/KNDriveLaneView) 여기서도 리플렉션으로 꺼낸다. #문제시 원복: 이 파일을 지우고
 * 보내는 쪽에서 null을 넘기면 예전 동작으로 돌아간다.
 */
object TbtListJson {

    private const val TYPE_TG = 1
    private const val TYPE_SA = 2
    private const val TYPE_IC = 3
    private const val TYPE_JC = 4

    /** 화면에 쓰는 건 앞의 몇 개뿐이라 목록이 길어져도 앞부분만 보낸다. */
    private const val MAX_ITEMS = 10

    fun build(routeGuide: Any?, currentLocation: Any?): String? {
        if (routeGuide == null || currentLocation == null) return null
        return try {
            val hwInfo = invoke(routeGuide, "getHwInfo") ?: return null
            val facilities = (asList(invoke(hwInfo, "getNearRgs")) + asList(invoke(hwInfo, "getNearSas")))
                .filterNotNull()
            if (facilities.isEmpty()) return null

            val items = facilities
                .mapNotNull { toItem(it, currentLocation) }
                .distinctBy { it.type to it.name }
                .sortedBy { it.remainDistance }
                .take(MAX_ITEMS)
            if (items.isEmpty()) return null

            val array = JSONArray()
            var previousDistance = 0
            for (item in items) {
                array.put(
                    JSONObject()
                        .put("nTBTType", item.type)
                        .put("szTBTMainText", item.name)
                        .put("remainDistance", item.remainDistance)
                        .put("distanceFromPreviousTBT", item.remainDistance - previousDistance)
                        .put("bHighway", true)
                )
                previousDistance = item.remainDistance
            }

            NavLogger.dIfChanged(
                "고속도로시설",
                "[고속도로 시설] 나브디로 보냄 ${items.size}개: " +
                    items.joinToString(", ") { "${typeName(it.type)} ${it.name} ${it.remainDistance}m" }
            )
            array.toString()
        } catch (e: Exception) {
            NavLogger.dIfChanged(
                "고속도로시설오류",
                "[고속도로 시설] 목록을 못 만듦(${e.javaClass.simpleName} ${e.message}) - 이번엔 안 보냄"
            )
            null
        }
    }

    private data class Item(val type: Int, val name: String, val remainDistance: Int)

    private fun toItem(facility: Any, currentLocation: Any): Item? {
        val type = typeOf(facility) ?: return null
        val name = (invoke(facility, "getNodeName") as? String).orEmpty()
        if (name.isBlank()) return null
        val location = invoke(facility, "getLocation") ?: return null
        val distance = distanceTo(currentLocation, location) ?: return null
        if (distance <= 0) return null
        return Item(type, name, distance)
    }

    /** KNHighwayRGType_TG / _SA / _IC / _JC 를 티맵 nTBTType 번호로. RA(회차로)는 대응이 없어 뺀다. */
    private fun typeOf(facility: Any): Int? {
        val typeName = invoke(facility, "highwayRGType")?.toString().orEmpty()
        return when {
            typeName.endsWith("_TG") -> TYPE_TG
            typeName.endsWith("_SA") -> TYPE_SA
            typeName.endsWith("_IC") -> TYPE_IC
            typeName.endsWith("_JC") -> TYPE_JC
            else -> null
        }
    }

    private fun distanceTo(currentLocation: Any, target: Any): Int? {
        val method = currentLocation.javaClass.methods.firstOrNull {
            it.name == "distToLocation" && it.parameterTypes.size == 1
        } ?: return null
        return (method.invoke(currentLocation, target) as? Number)?.toInt()
    }

    private fun typeName(type: Int): String = when (type) {
        TYPE_TG -> "톨게이트"
        TYPE_SA -> "휴게소"
        TYPE_IC -> "IC"
        else -> "JC"
    }

    private fun invoke(target: Any?, name: String): Any? {
        if (target == null) return null
        val method = target.javaClass.methods.firstOrNull {
            it.name == name && it.parameterTypes.isEmpty()
        } ?: return null
        return method.invoke(target)
    }

    private fun asList(value: Any?): List<Any?> = (value as? List<*>) ?: emptyList()
}
