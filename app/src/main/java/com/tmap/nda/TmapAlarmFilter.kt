package com.tmap.nda

/**
 * 2026-11-12 재억 요청 - 티맵 SDK가 안내 문장 앞에 붙이는 알림음("띵"/"띵띵"/"띠리링") 중
 * 카메라·과속 관련만 "제한속도의 110%를 넘을 때만" 나오게 함(카카오·네이버 화면과 같은 기준).
 * 안내 문장(방지턱 등)은 그대로 둠. 빌드 플러그인(AudioFocusInterceptorPlugin)이 티맵 UI SDK의
 * audio.e.a(List, 콜백) 맨 앞에서 [filter]를 호출하게 끼워 넣음. 스위치 [PREF_KEY]를 끄면 원래대로. #문제시 원복
 *
 * 알림음 번호(SDK 내부 표, 코드로 확인): 25=클릭 26=시계 27=경로이탈 28=안내종료 29=고정카메라300m 30=고정카메라600m
 * 33=이동식카메라300m 34=과속 39=카메라통과 41=일반 안내 알림("띵", 턴 안내 앞에도 붙음)
 * 41은 같은 목록에 단속·사고다발 같은 안전정보 문장이 있을 때만 걸러냄(턴 안내 소리는 살림).
 */
object TmapAlarmFilter {
    const val PREF_KEY = "tmap_camera_chime_gate"
    private val GATED = setOf("29", "30", "33", "34", "39")
    private val TAG_REGEX = Regex("<[^>]*>")
    private val SAFETY_WORDS = listOf("카메라", "단속", "사고", "과속", "구간", "안전운전", "스쿨존", "보호구역")

    /** 알림음만 있던 목록이 통째로 비면 이 객체를 돌려줌(플러그인이 재생 자체를 건너뜀). */
    @JvmField
    val DROP: List<String> = ArrayList()

    @JvmStatic
    fun filter(list: List<String>): List<String> {
        try {
            if (list.isEmpty() || !enabled()) return list
            var out: ArrayList<String>? = null
            val safetyText = list.any { !it.contains("<alarm>") && TAG_REGEX.replace(it, "").let { t -> SAFETY_WORDS.any { w -> t.contains(w) } } }
            for ((i, item) in list.withIndex()) {
                if (!item.contains("<alarm>")) { out?.add(item); continue }
                val code = TAG_REGEX.replace(item, "").trim()
                // 2026-11-15 진단 - 필터가 걸리지 않던 원인 확인용. 알림음이 들어올 때마다 번호와 전체 목록을 남김.
                if (code !in GATED && !(code == "41" && safetyText)) NavLogger.d("[티맵알림음] 걸러내지 않는 번호=$code 목록=$list")
                if ((code in GATED || (code == "41" && safetyText)) && !isOver110(code)) {
                    if (out == null) { out = ArrayList(list.subList(0, i)) }
                    continue
                }
                out?.add(item)
            }
            return when {
                out == null -> list
                out.isEmpty() -> DROP
                else -> out
            }
        } catch (e: Exception) {
            NavLogger.e("[티맵알림음] 필터 예외: ${e.message}")
            return list
        }
    }

    private fun enabled(): Boolean {
        val ctx = NavLogger.appContext ?: return true
        return ctx.getSharedPreferences("TmapNdaPrefs", android.content.Context.MODE_PRIVATE)
            .getBoolean(PREF_KEY, true)
    }

    /** 속도가 제한의 110%를 넘으면 true(소리 허용). 속도/제한을 모르면 true(안전 쪽으로 소리는 살림). */
    private fun isOver110(code: String): Boolean {
        val now = System.currentTimeMillis()
        val speed = SdiDataRepository.lastSpeedKph
        val fresh = now - SdiDataRepository.lastSpeedAtMs <= 5000L
        val camera = SdiDataRepository.sdiSpeedLimit
        val limit = if (SdiDataRepository.isNearCameraEvent() && camera >= 30) camera else SdiDataRepository.roadLimitSpeed
        val allow = when {
            !fresh || limit < 30 -> true
            else -> speed > limit * 1.1
        }
        NavLogger.d("[티맵알림음] 번호=$code 속도=$speed 제한=$limit(×1.1=${limit * 1.1}) 결과=${if (allow) "소리 허용" else "소리 제거"}")
        return allow
    }
}
