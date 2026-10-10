package com.tmap.nda.naver

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * 앱의 이동방식 6칸(추천·고속도로·무료도로·큰길 우선·최단거리·선호경로)을 네이버 길찾기 옵션에 대응시키고,
 * 경로 선택 카드에 채울 "옵션별 소요시간·통행료·경로"를 한꺼번에 구해준다.
 *
 * 네이버는 한 번 호출에 옵션을 3개까지만 받으므로 호출 2번(추천·빠른길·편한길 / 무료도로)으로 4칸을 채운다.
 * 최단거리·선호경로는 네이버에 같은 옵션이 없어서 [UNSUPPORTED]로 표시한다.
 */
object NaverRouteOptions {

    /** 카드 칸 번호 → 네이버 옵션. RouteChoiceOptions의 순서(추천, 고속도로, 무료도로, 큰길 우선, 최단거리, 선호경로)와 같다. */
    val OPTION_BY_SLOT: List<String?> = listOf("traoptimal", "trafast", "traavoidtoll", "tracomfort", null, null)

    /** minutesArr에 이 값이 들어 있으면 "이 방식은 네이버에 없음"이라는 뜻. */
    const val UNSUPPORTED = -2

    /**
     * 출발 → 목적지의 4가지 방식 경로를 구해 결과가 도착할 때마다 [onResult]로 알려준다(메인 스레드).
     * [onResult]의 slot은 카드 칸 번호이고, route가 null이면 그 칸은 실패/미지원이다.
     */
    fun compute(
        context: Context,
        from: LonLat,
        to: LonLat,
        vias: List<LonLat> = emptyList(),
        onResult: (slot: Int, route: NaverRoute?, supported: Boolean) -> Unit
    ) {
        val ui = Handler(Looper.getMainLooper())
        // 미지원 칸은 바로 알려준다
        OPTION_BY_SLOT.forEachIndexed { slot, opt -> if (opt == null) ui.post { onResult(slot, null, false) } }
        Thread {
            val all = ArrayList<NaverRoute>()
            val first = NaverDirectionsClient.requestRoute(context, from, to, vias, "traoptimal:trafast:tracomfort")
            deliver(ui, first.routes, onResult)
            all.addAll(first.routes)
            // 앞 호출이 통째로 실패한 경우(키/한도 문제)엔 무료도로 호출을 아끼고 실패로 알린다
            if (first.ok) {
                val free = NaverDirectionsClient.requestRoute(context, from, to, vias, "traavoidtoll")
                deliver(ui, free.routes, onResult)
                all.addAll(free.routes)
            }
            // 끝내 경로를 못 받은 칸은 실패로 알린다
            ui.post {
                OPTION_BY_SLOT.forEachIndexed { slot, opt ->
                    if (opt != null && all.none { it.option == opt }) onResult(slot, null, true)
                }
            }
        }.start()
    }

    private fun deliver(ui: Handler, routes: List<NaverRoute>, onResult: (Int, NaverRoute?, Boolean) -> Unit) {
        routes.forEach { r ->
            val slot = OPTION_BY_SLOT.indexOf(r.option)
            if (slot >= 0) ui.post { onResult(slot, r, true) }
        }
    }
}
