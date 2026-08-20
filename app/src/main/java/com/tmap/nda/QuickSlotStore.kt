package com.tmap.nda

import android.content.Context
import org.json.JSONObject

/**
 * v11.3: 최근 검색 이력 팝업에 집/회사/즐겨찾기1/2/3 다섯 칸 빠른등록 버튼 추가(재억 요청).
 * 짧게 누르면: 등록 안 됐으면 검색해서 등록+바로 안내, 등록 됐으면 검색 없이 바로 안내.
 * 길게 누르면: 이미 등록돼 있어도 무시하고 다시 검색해서 덮어씀.
 * SearchHistoryStore와 같은 SharedPreferences 저장 방식을 씀. #문제시 원복
 */
object QuickSlotStore {
    const val SLOT_HOME = "home"
    const val SLOT_WORK = "work"
    const val SLOT_FAV1 = "fav1"
    const val SLOT_FAV2 = "fav2"
    const val SLOT_FAV3 = "fav3"
    const val SLOT_FAV4 = "fav4"
    const val SLOT_FAV5 = "fav5"

    val ALL_SLOTS = listOf(SLOT_HOME, SLOT_WORK, SLOT_FAV1, SLOT_FAV2, SLOT_FAV3, SLOT_FAV4, SLOT_FAV5)

    private fun prefs(context: Context) =
        context.getSharedPreferences("TmapNdaQuickSlots", Context.MODE_PRIVATE)

    fun get(context: Context, slot: String): HistoryEntry? {
        val raw = prefs(context).getString(slot, null) ?: return null
        return try {
            val obj = JSONObject(raw)
            HistoryEntry(
                obj.optString("name"),
                obj.optString("addr"),
                obj.optDouble("lat"),
                obj.optDouble("lon"),
                routePriorityName = if (obj.has("routePriorityName") && !obj.isNull("routePriorityName")) obj.optString("routePriorityName") else null,
                routeAvoidOption = obj.optInt("routeAvoidOption", 0)
            )
        } catch (e: Exception) {
            null
        }
    }

    fun save(context: Context, slot: String, entry: HistoryEntry) {
        val obj = JSONObject()
        obj.put("name", entry.name)
        obj.put("addr", entry.addr)
        obj.put("lat", entry.lat)
        obj.put("lon", entry.lon)
        if (entry.routePriorityName != null) {
            obj.put("routePriorityName", entry.routePriorityName)
        }
        obj.put("routeAvoidOption", entry.routeAvoidOption)
        prefs(context).edit().putString(slot, obj.toString()).apply()
    }

    // v13.2-3: 재억 요청 - 저장된 이동 방식(추천/고속도로/무료도로)만 따로 바꿀 때
    // 주소/이름은 그대로 두고 이 두 값만 갱신. #문제시 원복
    fun updateRoutePreference(context: Context, slot: String, routePriorityName: String, routeAvoidOption: Int) {
        val existing = get(context, slot) ?: return
        save(context, slot, existing.copy(routePriorityName = routePriorityName, routeAvoidOption = routeAvoidOption))
    }

    // v14.9: 재억 요청 - "안내 방법 변경" 메뉴에서 저장해둔 이동방식만 지우는 기능.
    // 주소/이름/집·회사 등록 자체는 그대로 두고 routePriorityName만 null로 되돌려서,
    // 다음부터는 다시 "어떻게 갈까요?" 팝업이 매번 뜨는 상태로 돌아가게 함. #문제시 원복
    fun clearRoutePreference(context: Context, slot: String) {
        val existing = get(context, slot) ?: return
        save(context, slot, existing.copy(routePriorityName = null, routeAvoidOption = 0))
    }

    // v12.3: 재억 요청 - 등록 해제(삭제) 기능. #문제시 원복
    fun delete(context: Context, slot: String) {
        prefs(context).edit().remove(slot).apply()
    }
}
