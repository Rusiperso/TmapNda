package com.tmap.nda

import com.kakaomobility.knsdk.KNRouteAvoidOption
import com.kakaomobility.knsdk.KNRoutePriority

// 경로 선택 카드/목록에 나오는 6가지 이동방식. 세 화면(티맵 검색, 카카오 화면, 경유지 추가)이
// 같은 순서·같은 이름을 쓰도록 한 곳에 모음. 순서는 카드의 2줄 x 3칸 배치 그대로
// (위: 추천 경로 / 고속도로 / 무료도로, 아래: 큰길 우선 / 최단거리 / 선호경로).
// 무료도로는 경로 종류가 아니라 "요금 피하기" 옵션이라 추천 + Fare 회피로 표현함.
object RouteChoiceOptions {
    val labels = listOf("추천 경로", "고속도로", "무료도로", "큰길 우선", "최단거리", "선호경로")
    val priorities = listOf(
        KNRoutePriority.KNRoutePriority_Recommand,
        KNRoutePriority.KNRoutePriority_HighWay,
        KNRoutePriority.KNRoutePriority_Recommand,
        KNRoutePriority.KNRoutePriority_WideWay,
        KNRoutePriority.KNRoutePriority_Distance,
        KNRoutePriority.KNRoutePriority_Preferred
    )
    val avoidOptions = listOf(
        0,
        0,
        KNRouteAvoidOption.KNRouteAvoidOption_Fare.value,
        0,
        0,
        0
    )
    val count: Int get() = labels.size

    // 카드 칸 순서 저장(개인 취향). 값은 "0,1,2,3,4,5"처럼 방식 번호를 자리 순서대로 나열한 것.
    // 저장값이 깨졌거나 개수가 안 맞으면 기본 순서로 되돌림.
    private const val PREFS = "TmapNdaPrefs"
    private const val KEY_ORDER = "route_choice_order"

    fun loadOrder(context: android.content.Context): List<Int> {
        val default = List(count) { it }
        return try {
            val raw = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .getString(KEY_ORDER, null) ?: return default
            val parsed = raw.split(",").map { it.trim().toInt() }
            if (parsed.size == count && parsed.toSet() == default.toSet()) parsed else default
        } catch (e: Exception) {
            default
        }
    }

    fun saveOrder(context: android.content.Context, order: List<Int>) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString(KEY_ORDER, order.joinToString(",")).apply()
    }
}
