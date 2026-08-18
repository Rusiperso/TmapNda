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

    val ALL_SLOTS = listOf(SLOT_HOME, SLOT_WORK, SLOT_FAV1, SLOT_FAV2, SLOT_FAV3)

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
                obj.optDouble("lon")
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
        prefs(context).edit().putString(slot, obj.toString()).apply()
    }

    // v12.3: 재억 요청 - 등록 해제(삭제) 기능. #문제시 원복
    fun delete(context: Context, slot: String) {
        prefs(context).edit().remove(slot).apply()
    }
}
