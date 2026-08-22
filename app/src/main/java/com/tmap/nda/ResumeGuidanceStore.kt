package com.tmap.nda

import android.content.Context
import org.json.JSONObject

/**
 * v12.9: 재억 요청 - 앱을 업데이트하거나 껐다 켜도 안내를 이어갈 수 있도록, 지금
 * 안내 중인 목적지를 저장해두고, 다음에 앱을 열 때 "이어서 안내할까요?" 물어봄.
 * 정상적으로 목적지에 도착해서 안내가 끝나면(안내종료 버튼으로 중간에 끈 게 아니라)
 * 자동으로 지워짐. QuickSlotStore와 같은 SharedPreferences 저장 방식을 씀. #문제시 원복
 */
object ResumeGuidanceStore {
    private const val KEY = "resume_guidance"

    private fun prefs(context: Context) =
        context.getSharedPreferences("TmapNdaQuickSlots", Context.MODE_PRIVATE)

    fun get(context: Context): HistoryEntry? {
        val raw = prefs(context).getString(KEY, null) ?: return null
        return try {
            val obj = JSONObject(raw)
            HistoryEntry(
                obj.optString("name"),
                obj.optString("addr"),
                obj.optDouble("lat"),
                obj.optDouble("lon"),
                routePriorityName = obj.optString("routePriorityName", null).takeIf { !it.isNullOrBlank() },
                routeAvoidOption = obj.optInt("routeAvoidOption", 0)
            )
        } catch (e: Exception) {
            null
        }
    }

    fun save(context: Context, entry: HistoryEntry) {
        val obj = JSONObject()
        obj.put("name", entry.name)
        obj.put("addr", entry.addr)
        obj.put("lat", entry.lat)
        obj.put("lon", entry.lon)
        // v: 재억 지적(2026-08-22) - "무료도로로 가다가 끊기고 이어서 안내하면 매번
        // 추천 경로로 다시 시작된다"는 문제. 원인은 이 저장소가 목적지 좌표만 저장하고
        // 그때 골랐던 경로 방식(추천/고속도로/무료도로)은 저장하지 않아서, 이어할 때
        // 항상 기본값(추천)으로 재시작됐던 것. 이제 같이 저장해서 그대로 이어감. #문제시 원복
        if (entry.routePriorityName != null) {
            obj.put("routePriorityName", entry.routePriorityName)
            obj.put("routeAvoidOption", entry.routeAvoidOption)
        }
        prefs(context).edit().putString(KEY, obj.toString()).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }
}
