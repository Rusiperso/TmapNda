package com.tmap.nda

import android.content.Context
import com.kakaomobility.knsdk.KNRouteAvoidOption

/**
 * 재억 요청(2026-09-15) - "스쿨존(어린이보호구역) 회피"를 앱 전역 설정 하나로 켜두면,
 * 추천/고속도로/무료도로 중 뭘 고르든 항상 같이 적용되게. 경로 방식마다 따로 물어보게
 * 만들면 복잡해지니, 실제로 경로를 요청/재적용하는 지점에서 avoidOption 값에 SZone
 * 비트만 더해주는 방식으로 처리. TmapNdaPrefs에 저장돼 설정 백업에도 자동 포함됨. #문제시 원복
 */
object RouteAvoidSettings {
    private const val PREFS_NAME = "TmapNdaPrefs"
    private const val KEY_AVOID_SCHOOL_ZONE = "kakao_avoid_school_zone"

    fun getAvoidSchoolZone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AVOID_SCHOOL_ZONE, false)

    fun setAvoidSchoolZone(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AVOID_SCHOOL_ZONE, enabled).apply()
    }

    /** 기존 avoidOption 값에, 스쿨존 회피가 켜져 있으면 그 비트를 더해서 돌려줌. */
    fun applySchoolZoneAvoid(context: Context, baseAvoidOption: Int): Int {
        return if (getAvoidSchoolZone(context)) {
            baseAvoidOption or KNRouteAvoidOption.KNRouteAvoidOption_SZone.value
        } else {
            baseAvoidOption
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
