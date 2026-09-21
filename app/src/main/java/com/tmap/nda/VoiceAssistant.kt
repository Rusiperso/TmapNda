package com.tmap.nda

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * 음성 명령 처리(카카오·티맵 두 화면 공용). 말한 글자를 보고 주변 검색 / 즐겨찾기·목적지 안내 /
 * 설정 켜고 끄기 / 날씨 / 시간 명령을 처리하고, 화면마다 다른 동작은 Host로 넘김. #문제시 원복
 */
class VoiceAssistant(private val activity: Activity, private val host: Host) {

    interface Host {
        fun currentLatLon(): Pair<Double?, Double?>
        /** 목적지·키워드 검색(각 화면의 기존 검색 흐름) */
        fun search(query: String)
        /** 즐겨찾기로 안내. priorityIndex: null=기존 즐겨찾기 탭 동작, 0=추천, 1=고속도로, 2=무료도로 */
        fun goFavorite(entry: HistoryEntry, priorityIndex: Int?, replace: Boolean = false)
        fun applyDayNight()
        fun applySettingSideEffects()
        /** 즐겨찾기 항목을 지금 안내의 경유지로 추가 */
        fun addWaypoint(entry: HistoryEntry)
        /** 검색한 곳을 경유지로 추가하는 흐름을 시작. 지원 안 하는 화면은 false. */
        fun addWaypointBySearch(query: String): Boolean = false
        /** 지금 안내 음성이 음소거인지. 모르면 null. */
        fun isMuted(): Boolean? = null
        /** 음성인식을 바로 다시 켬(되묻기 대답 받기용) */
        fun launchRecognizer() {}
        /** 지금 진행 방향(도, 0~360). 모르면 null. */
        fun currentBearing(): Float? = null
        /** 즐겨찾기 칸에 등록할 장소 검색을 시작. 지원 안 하는 화면은 false. */
        fun registerFavorite(slot: String, query: String): Boolean = false
        /** 카카오/티맵 화면 전환. 화면에 보여줄 결과 문장을 돌려줌. */
        fun switchScreen(toKakao: Boolean): String = "이 화면에서는 전환할 수 없어요"
    }

    private val searchHttpClient by lazy { OkHttpClient() }

    // ---- 음성 대답(폰 기본 음성으로 읽어줌) ----
    private var voiceTts: android.speech.tts.TextToSpeech? = null
    private var voiceTtsReady = false
    private var voiceTtsPending: String? = null

    // 말이 끝난 뒤 실행할 일(되묻기 뒤에 음성인식을 다시 켤 때 씀)
    private var voiceTtsOnDone: (() -> Unit)? = null

    private fun speakReply(text: String, onDone: (() -> Unit)? = null) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
        NavLogger.d(activity, "[음성대답] $text")
        voiceTtsOnDone = onDone
        if (onDone != null) {
            // 음성 엔진이 못 읽는 경우를 대비한 안전장치: 12초 뒤에도 안 끝났으면 그냥 이어감
            val guard = onDone
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (voiceTtsOnDone === guard) { voiceTtsOnDone = null; guard() }
            }, 12000)
        }
        try {
            val engine = voiceTts
            if (engine == null) {
                voiceTtsPending = text
                voiceTts = android.speech.tts.TextToSpeech(activity) { status ->
                    if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                        voiceTts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {}
                            override fun onError(utteranceId: String?) { finishSpeech() }
                            override fun onDone(utteranceId: String?) { finishSpeech() }
                        })
                        voiceTts?.language = java.util.Locale.KOREAN
                        voiceTts?.setAudioAttributes(
                            android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        voiceTtsReady = true
                        voiceTtsPending?.let { voiceTts?.speak(it, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "voiceReply") }
                        voiceTtsPending = null
                    } else {
                        NavLogger.e(activity, "[음성대답] 음성 엔진 준비 실패: $status")
                    }
                }
            } else if (voiceTtsReady) {
                engine.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "voiceReply")
            } else {
                voiceTtsPending = text
            }
        } catch (e: Exception) {
            NavLogger.e(activity, "[음성대답] 예외: ${e.message}")
        }
    }

    private fun finishSpeech() {
        val cb = voiceTtsOnDone ?: return
        voiceTtsOnDone = null
        activity.runOnUiThread { cb() }
    }

    // ---- 되묻기: "운락이 집 말씀이신가요?" -> 말로 "응/아니"를 받음 ----
    private var pendingAnswer: ((List<String>) -> Unit)? = null

    /** 음성인식 결과가 되묻기의 대답이면 여기서 처리하고 true를 돌려줌. */
    fun consumeAnswer(list: List<String>): Boolean {
        val cb = pendingAnswer ?: return false
        pendingAnswer = null
        cb(list)
        return true
    }

    /** 대답 없이 음성인식이 닫혔을 때 되묻기를 정리 */
    fun cancelAnswer() {
        if (pendingAnswer != null) {
            pendingAnswer = null
            NavLogger.d(activity, "[음성명령] 되묻기 대답 없음 - 취소")
        }
    }

    private fun askYesNo(question: String, onYes: () -> Unit, onNo: () -> Unit) {
        speakReply(question) {
            pendingAnswer = { answers ->
                val yes = listOf("네", "예", "응", "어", "맞아", "맞습니다", "그래", "그렇", "그거", "오케이", "좋아", "ㅇㅇ")
                val no = listOf("아니", "아냐", "틀려", "노", "싫어", "아닌")
                val joined = answers.joinToString(" ").replace(" ", "")
                NavLogger.d(activity, "[음성명령] 되묻기 대답: $answers")
                when {
                    no.any { joined.contains(it) } -> onNo()
                    yes.any { joined.contains(it) } -> onYes()
                    else -> speakReply("잘 못 알아들었어요. 다시 말씀해 주세요")
                }
            }
            host.launchRecognizer()
        }
    }

    // "지금 몇 시야" -> 현재 시각을 말해줌
    private fun handleTimeCommand(t: String): Boolean {
        if (!(t.contains("몇시") || t.contains("지금시간") || t.contains("현재시간"))) return false
        val cal = java.util.Calendar.getInstance()
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val m = cal.get(java.util.Calendar.MINUTE)
        val ampm = if (h < 12) "오전" else "오후"
        val h12 = if (h % 12 == 0) 12 else h % 12
        speakReply("지금은 $ampm ${h12}시 ${m}분이에요")
        return true
    }

    // "오늘 날씨 어때" -> 현재 위치 날씨(Open-Meteo, 키 필요 없음)를 읽어줌
    private fun handleWeatherCommand(t: String): Boolean {
        if (!(t.contains("날씨") || t.contains("기온"))) return false
        val (lat, lon) = host.currentLatLon()
        if (lat == null || lon == null) {
            speakReply("현재 위치를 아직 못 찾았어요")
            return true
        }
        Toast.makeText(activity, "날씨 확인 중", Toast.LENGTH_SHORT).show()
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,precipitation,weather_code" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=1"
        searchHttpClient.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                NavLogger.e(activity, "[날씨] 조회 실패: ${e.message}")
                activity.runOnUiThread { speakReply("날씨를 가져오지 못했어요") }
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    try {
                        val json = JSONObject(it.body?.string() ?: "{}")
                        val cur = json.getJSONObject("current")
                        val daily = json.optJSONObject("daily")
                        val temp = Math.round(cur.getDouble("temperature_2m"))
                        val feels = Math.round(cur.getDouble("apparent_temperature"))
                        val code = cur.getInt("weather_code")
                        val rain = cur.optDouble("precipitation", 0.0)
                        val tMax = daily?.optJSONArray("temperature_2m_max")?.optDouble(0)?.takeIf { d -> !d.isNaN() }?.let { d -> Math.round(d) }
                        val tMin = daily?.optJSONArray("temperature_2m_min")?.optDouble(0)?.takeIf { d -> !d.isNaN() }?.let { d -> Math.round(d) }
                        val pop = daily?.optJSONArray("precipitation_probability_max")?.optInt(0, -1) ?: -1
                        val desc = when (code) {
                            0 -> "맑고"
                            1 -> "대체로 맑고"
                            2 -> "구름이 조금 있고"
                            3 -> "흐리고"
                            45, 48 -> "안개가 끼고"
                            51, 53, 55, 56, 57 -> "이슬비가 내리고"
                            61, 63, 65, 66, 67 -> "비가 오고"
                            71, 73, 75, 77 -> "눈이 오고"
                            80, 81, 82 -> "소나기가 오고"
                            85, 86 -> "눈이 오고"
                            95, 96, 99 -> "천둥번개가 치고"
                            else -> "날씨가 변하고"
                        }
                        val sb = StringBuilder()
                        sb.append("현재 $desc 기온은 ${temp}도, 체감은 ${feels}도예요.")
                        if (tMax != null && tMin != null) sb.append(" 오늘 최고 ${tMax}도, 최저 ${tMin}도.")
                        if (pop >= 0) sb.append(" 비 올 확률은 ${pop}퍼센트예요.")
                        else if (rain > 0) sb.append(" 지금 비가 오고 있어요.")
                        val body = sb.toString()

                        // 지역 이름(카카오 좌표->행정동)은 붙일 수 있을 때만 붙임
                        val restKey = activity.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                            .getString("kakao_rest_api_key", "") ?: ""
                        if (restKey.isBlank()) {
                            activity.runOnUiThread { speakReply(body) }
                            return
                        }
                        val regionReq = Request.Builder()
                            .url("https://dapi.kakao.com/v2/local/geo/coord2regioncode.json?x=$lon&y=$lat")
                            .header("Authorization", "KakaoAK $restKey").build()
                        searchHttpClient.newCall(regionReq).enqueue(object : Callback {
                            override fun onFailure(call: Call, e: IOException) {
                                activity.runOnUiThread { speakReply(body) }
                            }

                            override fun onResponse(call: Call, response: okhttp3.Response) {
                                var prefix = ""
                                response.use { r ->
                                    try {
                                        val docs = JSONObject(r.body?.string() ?: "{}").optJSONArray("documents")
                                        if (docs != null && docs.length() > 0) {
                                            var d = docs.getJSONObject(0)
                                            for (i in 0 until docs.length()) {
                                                if (docs.getJSONObject(i).optString("region_type") == "H") { d = docs.getJSONObject(i); break }
                                            }
                                            prefix = (d.optString("region_2depth_name") + " " + d.optString("region_3depth_name")).trim() + " "
                                        }
                                    } catch (e: Exception) { }
                                }
                                activity.runOnUiThread { speakReply(prefix + body) }
                            }
                        })
                    } catch (e: Exception) {
                        NavLogger.e(activity, "[날씨] 해석 실패: ${e.message}")
                        activity.runOnUiThread { speakReply("날씨를 해석하지 못했어요") }
                    }
                }
            }
        })
        return true
    }

    // 설정 켜고 끄기 음성 명령 - "위성지도 켜줘", "교통정보 꺼줘", "밤 모드 켜줘" 등.
    // 안전과 관계있는 설정은 음성으로 끌 수 없게 막음(켜기만 가능).
    private class VoiceSetting(val aliases: List<String>, val key: String, val label: String,
                               val inverted: Boolean = false, val safety: Boolean = false)

    private val voiceSettings by lazy {
        listOf(
            VoiceSetting(listOf("위성지도", "위성"), "tmap_satellite_view_enabled", "위성지도"),
            VoiceSetting(listOf("교통정보", "교통상황", "정체표시"), "tmap_traffic_info_enabled", "교통정보"),
            VoiceSetting(listOf("미니플레이어", "음악위젯"), com.tmap.nda.miniplayer.MiniPlayerManager.PREF_KEY_ENABLED, "미니 플레이어"),
            VoiceSetting(listOf("과속경고음", "과속경고", "경고음"), "over_speed_warning_enabled", "과속 경고음", safety = true),
            VoiceSetting(listOf("이동식카메라감속", "이동식카메라", "이동식감속"), "mobile_cam_slowdown_disabled", "이동식카메라 감속", inverted = true, safety = true),
            VoiceSetting(listOf("상단바이벤트", "이벤트표시"), "topbar_event_enabled", "상단바 이벤트 표시"),
            VoiceSetting(listOf("사고알림", "공사알림", "사고공사알림"), "accident_alert_enabled", "사고·공사구간 알림", safety = true),
            VoiceSetting(listOf("긴급차량알림", "긴급차량"), "emergency_alert_enabled", "긴급차량 알림", safety = true),
            VoiceSetting(listOf("스쿨존회피", "스쿨존"), "kakao_avoid_school_zone", "스쿨존 회피"),
            VoiceSetting(listOf("도착알림", "목적지도착알림"), "arrival_radius_alert_enabled", "도착 알림"),
            VoiceSetting(listOf("화면블랙", "폰화면블랙", "블랙처리"), "black_screen_on_usb_connect", "차량 연결시 폰 화면 블랙"),
            VoiceSetting(listOf("경유지버튼"), "show_waypoint_button", "경유지 버튼"),
            VoiceSetting(listOf("카테고리버튼", "주변버튼"), "show_category_button", "카테고리 버튼"),
            VoiceSetting(listOf("경로선"), "route_line_display_enabled", "경로선 콤마 화면 표시"),
            VoiceSetting(listOf("차선안내", "차선오버레이"), "lane_overlay_tmap_enabled", "차선 안내 오버레이"),
            VoiceSetting(listOf("상단바버튼"), "show_toggle_top_panel_button", "상단바 표시/숨김 버튼")
        )
    }

    private fun handleSettingCommand(t: String): Boolean {
        val wantOn = t.contains("켜") || t.contains("켤")
        val wantOff = t.contains("꺼") || t.contains("끄") || t.contains("끔") || t.contains("해제") || t.contains("없애")
        val prefs = activity.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)

        // 지도 밝기: "밤 모드 켜줘", "낮 모드 켜줘", "주야 모드 낮으로 바꿔줘", "자동 모드"
        val modeTalk = t.contains("모드") || t.contains("주야") || t.contains("지도밝기") || t.contains("바꿔")
        val dayNight = when {
            !modeTalk -> null
            t.contains("자동") -> DayNightHelper.MODE_AUTO
            t.contains("밤") || t.contains("야간") -> if (wantOff) DayNightHelper.MODE_DAY else DayNightHelper.MODE_NIGHT
            t.contains("낮") || t.contains("주간") -> if (wantOff) DayNightHelper.MODE_NIGHT else DayNightHelper.MODE_DAY
            else -> null
        }
        if (dayNight == null && !wantOn && !wantOff) return false
        if (dayNight != null) {
            prefs.edit().putString(DayNightHelper.KEY_MODE, dayNight).apply()
            host.applyDayNight()
            speakReply(when (dayNight) {
                DayNightHelper.MODE_NIGHT -> "지도를 밤 모드로 바꿨어요"
                DayNightHelper.MODE_DAY -> "지도를 낮 모드로 바꿨어요"
                else -> "지도 밝기를 자동으로 바꿨어요"
            })
            return true
        }

        val hit = voiceSettings
            .flatMap { s -> s.aliases.map { a -> a to s } }
            .sortedByDescending { it.first.length }
            .firstOrNull { t.contains(it.first) }
            ?.second ?: return false

        if (wantOff && !wantOn && hit.safety) {
            speakReply("${hit.label}은 안전과 관계있는 설정이라 음성으로는 끌 수 없어요. 설정에서 직접 꺼주세요")
            return true
        }
        val turnOn = wantOn
        prefs.edit().putBoolean(hit.key, if (hit.inverted) !turnOn else turnOn).apply()
        NavLogger.d(activity, "[음성명령] 설정 변경: ${hit.label} -> ${if (turnOn) "켬" else "끔"}")
        host.applySettingSideEffects()
        speakReply("${hit.label} ${if (turnOn) "켰어요" else "껐어요"}")
        return true
    }


    // ---- 화면의 기존 버튼을 대신 눌러주는 명령(안내 종료, 로그 전송, 업데이트 확인 등) ----
    private fun clickById(name: String): Boolean {
        val id = activity.resources.getIdentifier(name, "id", activity.packageName)
        if (id == 0) return false
        val v = activity.findViewById<android.view.View>(id) ?: return false
        return v.performClick()
    }

    // Nda 앱 자체 기능: 안내 종료, 로그 전송, 업데이트 확인, 내 차 위치, 남은 시간/거리
    private fun handleNdaCommand(t: String): Boolean {
        // --- 화면의 기존 버튼/기능을 말로 실행 ---
        if (t.contains("경유지취소") || t.contains("경유지삭제") || t.contains("경유지빼") || t.contains("경유지지워")) {
            val ok = clickById("btnCancelWaypoint")
            speakReply(if (ok) "경유지를 취소할게요" else "취소할 경유지가 없어요")
            return true
        }
        if (t.contains("주변카테고리") || t.contains("카테고리열") || t.contains("주변탐색") || t.contains("주변검색열")) {
            val ok = clickById("btnNearbyCategory")
            speakReply(if (ok) "주변 카테고리를 열게요" else "이 화면에서는 열 수 없어요")
            return true
        }
        if (t.contains("즐겨찾기열") || t.contains("즐겨찾기목록") || t.contains("즐겨찾기보여")) {
            val ok = clickById("btnFavorites")
            speakReply(if (ok) "즐겨찾기를 열게요" else "이 화면에서는 열 수 없어요")
            return true
        }
        if (t.contains("도움말") || t.contains("사용법")) {
            val ok = clickById("btnHelp") || clickById("btnGuideHelp")
            speakReply(if (ok) "도움말을 열게요" else "도움말을 못 찾았어요")
            return true
        }
        if (t.contains("상단바숨겨") || t.contains("상단바보여") || t.contains("상단바꺼") || t.contains("상단바켜") || t.contains("상단바접어")) {
            val ok = clickById("btnToggleTopPanel")
            speakReply(if (ok) "상단바를 바꿨어요" else "상단바 버튼을 못 찾았어요")
            return true
        }
        if (t.contains("앱종료") || t.contains("프로그램종료") || t.contains("앱꺼줘")) {
            askYesNo("앱을 종료할까요?",
                onYes = { clickById("btnExitApp") },
                onNo = { speakReply("종료하지 않을게요") })
            return true
        }
        // 즐겨찾기 번호로 안내: "즐겨찾기 3번으로 가자", "2번으로 가자"
        if (t.contains("가자") || t.contains("가줘") || t.contains("안내") || t.contains("출발") || t.contains("바꿔")) {
            val m = Regex("(?:즐겨찾기)?(\\d+)번").find(t)
            val ordinals = listOf("첫" to 1, "두" to 2, "세" to 3, "네" to 4, "다섯" to 5, "여섯" to 6, "일곱" to 7, "여덟" to 8, "아홉" to 9, "열" to 10)
            val ord = ordinals.firstOrNull { t.contains("${it.first}번째") || t.contains("${it.first}번") }?.second
            val n = m?.groupValues?.get(1)?.toIntOrNull() ?: ord
            if (n != null && (t.contains("즐겨찾기") || m != null)) {
                val entry = QuickSlotStore.get(activity, "fav$n")
                if (entry == null) {
                    speakReply("즐겨찾기 ${n}번이 비어 있어요")
                } else {
                    var prio: Int? = null
                    if (t.contains("무료")) prio = 2 else if (t.contains("고속")) prio = 1 else if (t.contains("추천")) prio = 0
                    Toast.makeText(activity, "${entry.name}(으)로 안내", Toast.LENGTH_SHORT).show()
                    host.goFavorite(entry, prio, t.contains("바꿔"))
                }
                return true
            }
        }
        if ((t.contains("음소거") || t.contains("무음")) && (t.contains("켜") || t.contains("꺼") || t.contains("해제") || t.contains("풀어") || t.contains("끄") || t.contains("설정"))) {
            val wantMute = (t.contains("켜") || t.contains("설정")) && !t.contains("해제") && !t.contains("풀어")
            val now = host.isMuted()
            if (now == wantMute) {
                speakReply(if (wantMute) "이미 음소거예요" else "이미 소리가 켜져 있어요")
            } else {
                val ok = clickById("btnKakaoMuteToggle") || clickById("btnMuteToggle")
                speakReply(if (!ok) "음소거 버튼을 못 찾았어요" else if (wantMute) "안내 음성을 음소거했어요" else "안내 음성을 켰어요")
            }
            return true
        }
        if (t.contains("안내종료") || t.contains("안내끝") || t.contains("길안내종료") || t.contains("경로취소") || t.contains("안내멈춰") || t.contains("안내그만")) {
            val ok = clickById("btnStopKakaoGuidance") || clickById("btnStopGuidance")
            speakReply(if (ok) "안내를 종료할게요" else "지금 진행 중인 안내가 없어요")
            return true
        }
        if (t.contains("로그전송") || t.contains("로그보내") || t.contains("로그공유")) {
            val ok = clickById("btnShareLogTopBar")
            speakReply(if (ok) "로그 전송 화면을 열었어요" else "로그 전송 버튼을 못 찾았어요")
            return true
        }
        if (t.contains("업데이트확인") || t.contains("업데이트해") || t.contains("업데이트있")) {
            val ok = clickById("btnCheckUpdate")
            speakReply(if (ok) "업데이트를 확인할게요" else "이 화면에서는 업데이트 확인을 못 해요")
            return true
        }
        if (t.contains("내차위치") || t.contains("주차위치") || t.contains("차어디") || t.contains("주차한곳")) {
            try {
                val ok = clickById("btnParkedLocation")
                if (!ok) speakReply("이 화면에서는 내 차 위치를 열 수 없어요")
            } catch (e: Exception) {
                NavLogger.e(activity, "[음성명령] 내 차 위치 실패: ${e.message}")
            }
            return true
        }
        if ((t.contains("남았") || t.contains("도착까지") || t.contains("언제도착") || t.contains("도착시간") || t.contains("남은거리") || t.contains("남은시간")) && !t.contains("가자")) {
            if (!KakaoRouteDataRepository.isFresh() || KakaoRouteDataRepository.remainDist <= 0) {
                speakReply("지금 안내 중인 경로가 없어요")
                return true
            }
            val distM = KakaoRouteDataRepository.remainDist
            val sec = KakaoRouteDataRepository.remainTime
            val min = (sec + 30) / 60
            val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.SECOND, sec) }
            val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
            val distText = if (distM >= 1000) String.format("%.1f킬로미터", distM / 1000.0) else "${distM}미터"
            val timeText = if (min >= 60) "${min / 60}시간 ${min % 60}분" else "${min}분"
            val ampm = if (h < 12) "오전" else "오후"
            val h12 = if (h % 12 == 0) 12 else h % 12
            speakReply("목적지까지 $distText, 약 $timeText 남았어요. ${ampm} ${h12}시 ${cal.get(java.util.Calendar.MINUTE)}분쯤 도착해요")
            return true
        }
        return false
    }

    // ---- 미디어: 재생/멈춤/다음·이전 곡/볼륨. 지금 재생 중인 음악 앱에 키를 보냄 ----
    private fun sendMediaKey(code: Int) {
        val am = activity.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
    }

    private fun handleMediaCommand(t: String): Boolean {
        val am = activity.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        when {
            t.contains("다음곡") || t.contains("다음노래") || t.contains("다음거") -> {
                sendMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT); speakReply("다음 곡이에요"); return true
            }
            t.contains("이전곡") || t.contains("이전노래") || t.contains("앞곡") -> {
                sendMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS); speakReply("이전 곡이에요"); return true
            }
            t == "멈춰" || t.contains("일시정지") || ((t.contains("음악") || t.contains("노래") || t.contains("재생")) && (t.contains("멈춰") || t.contains("꺼줘") || t.contains("정지") || t.contains("그만"))) -> {
                sendMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE); speakReply("음악을 멈췄어요"); return true
            }
            t.contains("볼륨올려") || t.contains("소리키워") || t.contains("소리크게") || t.contains("음량올려") || t.contains("볼륨높여") -> {
                repeat(2) { am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_RAISE, 0) }
                speakReply("음악 볼륨 ${volumePercent(am)}퍼센트"); return true
            }
            t.contains("볼륨내려") || t.contains("소리줄여") || t.contains("소리작게") || t.contains("음량내려") || t.contains("볼륨낮춰") -> {
                repeat(2) { am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_LOWER, 0) }
                speakReply("음악 볼륨 ${volumePercent(am)}퍼센트"); return true
            }
            t.contains("볼륨최대") -> {
                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC), 0)
                speakReply("음악 볼륨을 최대로 올렸어요"); return true
            }
        }
        // "애플뮤직에서 음악 틀어줘", "음악 틀어줘", "노래 재생해줘"
        val wantsPlay = (t.contains("음악") || t.contains("노래") || t.contains("재생")) &&
            (t.contains("틀어") || t.contains("재생") || t.contains("켜줘") || t.contains("들려"))
        if (wantsPlay && !t.contains("검색")) {
            val appName = t.substringBefore("에서", "").takeIf { it.isNotEmpty() && t.contains("에서") }
            val launched = if (appName != null) launchAppByName(appName) else null
            if (launched != null) {
                speakReply("${launched}에서 음악을 재생할게요")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    sendMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
                }, 2500)
            } else {
                sendMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
                speakReply("음악을 재생할게요")
            }
            return true
        }
        return false
    }

    private fun volumePercent(am: android.media.AudioManager): Int {
        val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) * 100 / max
    }

    // ---- 알람·타이머(시계 앱에 부탁) ----
    private fun handleAlarmCommand(t: String): Boolean {
        val timer = Regex("(\\d+)(시간|분|초)(뒤|후|타이머)").find(t) ?: Regex("(\\d+)(시간|분|초)간?타이머").find(t)
        if (timer != null && (t.contains("타이머") || t.contains("알려줘") || t.contains("깨워"))) {
            val n = timer.groupValues[1].toInt()
            val secs = when (timer.groupValues[2]) { "시간" -> n * 3600; "분" -> n * 60; else -> n }
            return try {
                activity.startActivity(Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(android.provider.AlarmClock.EXTRA_LENGTH, secs)
                    putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                })
                speakReply("${timer.groupValues[1]}${timer.groupValues[2]} 타이머를 맞췄어요"); true
            } catch (e: Exception) {
                NavLogger.e(activity, "[음성명령] 타이머 실패: ${e.message}"); speakReply("타이머를 못 맞췄어요"); true
            }
        }
        val alarm = Regex("(오전|오후)?(\\d+)시(?:(\\d+)분)?(?:에)?(?:알람|깨워)").find(t)
        if (alarm != null) {
            var h = alarm.groupValues[2].toInt()
            val m = alarm.groupValues[3].ifEmpty { "0" }.toInt()
            if (alarm.groupValues[1] == "오후" && h < 12) h += 12
            if (alarm.groupValues[1] == "오전" && h == 12) h = 0
            return try {
                activity.startActivity(Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(android.provider.AlarmClock.EXTRA_HOUR, h)
                    putExtra(android.provider.AlarmClock.EXTRA_MINUTES, m)
                    putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                })
                speakReply("${h}시 ${m}분에 알람을 맞췄어요"); true
            } catch (e: Exception) {
                NavLogger.e(activity, "[음성명령] 알람 실패: ${e.message}"); speakReply("알람을 못 맞췄어요"); true
            }
        }
        return false
    }

    // ---- 메모: 폰 안에 저장(최근 것부터 읽어줌) ----
    private fun handleMemoCommand(t: String): Boolean {
        if (!t.contains("메모")) return false
        val prefs = activity.getSharedPreferences("TmapNdaVoiceMemo", Context.MODE_PRIVATE)
        val arr = org.json.JSONArray(prefs.getString("memos", "[]") ?: "[]")
        fun save() = prefs.edit().putString("memos", arr.toString()).apply()

        if (t.contains("지워") || t.contains("삭제")) {
            if (t.contains("전부") || t.contains("모두")) {
                prefs.edit().putString("memos", "[]").apply()
                speakReply("메모를 모두 지웠어요")
            } else if (arr.length() > 0) {
                arr.remove(arr.length() - 1); save()
                speakReply("가장 최근 메모를 지웠어요")
            } else speakReply("지울 메모가 없어요")
            return true
        }
        if (t.contains("읽어") || t.contains("목록") || t.contains("뭐있") || t.contains("보여") || t.contains("뭐라고")) {
            if (arr.length() == 0) { speakReply("저장된 메모가 없어요"); return true }
            val sb = StringBuilder("최근 메모예요. ")
            for (i in (arr.length() - 1) downTo maxOf(0, arr.length() - 3)) {
                sb.append(arr.getJSONObject(i).optString("text")).append(". ")
            }
            speakReply(sb.toString()); return true
        }
        var body = t
        listOf("메모해줘", "메모해", "메모할게", "메모", "적어줘", "기록해줘", "기억해줘").forEach { body = body.replace(it, "") }
        body = body.trim()
        if (body.isEmpty()) { speakReply("메모할 내용을 같이 말해주세요. 예를 들면 '메모해줘 우유 사기'"); return true }
        arr.put(JSONObject().put("t", System.currentTimeMillis()).put("text", body))
        save()
        speakReply("메모했어요: $body")
        return true
    }

    // ---- 인터넷 검색 / 유튜브 검색(브라우저·앱에 부탁) ----
    private fun handleWebCommand(t: String): Boolean {
        val viaYoutube = t.contains("유튜브") && (t.contains("검색") || t.contains("찾아") || t.contains("틀어"))
        val viaWeb = (t.contains("인터넷") || t.contains("구글") || t.contains("네이버") || t.contains("웹")) &&
            (t.contains("검색") || t.contains("찾아") || t.contains("알아봐"))
        if (!viaYoutube && !viaWeb) return false
        var q = t
        listOf("인터넷에서", "인터넷", "구글에서", "구글", "네이버에서", "네이버", "유튜브에서", "유튜브", "웹에서", "웹",
            "검색해줘", "검색해", "검색", "찾아줘", "찾아", "알아봐줘", "알아봐", "틀어줘", "틀어", "좀", "해줘").forEach { q = q.replace(it, "") }
        q = q.trim()
        if (q.isEmpty()) { speakReply("무엇을 검색할지 같이 말해주세요"); return true }
        val enc = java.net.URLEncoder.encode(q, "UTF-8")
        val url = when {
            viaYoutube -> "https://www.youtube.com/results?search_query=$enc"
            t.contains("네이버") -> "https://search.naver.com/search.naver?query=$enc"
            else -> "https://www.google.com/search?q=$enc"
        }
        return try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            speakReply("$q 검색할게요"); true
        } catch (e: Exception) {
            NavLogger.e(activity, "[음성명령] 웹 검색 실패: ${e.message}"); speakReply("검색을 열지 못했어요"); true
        }
    }

    // ---- 다른 앱 실행(설치된 앱 이름으로 찾음) ----
    private val appAliases = mapOf(
        "유튜브" to listOf("com.google.android.youtube"),
        "크롬" to listOf("com.android.chrome"),
        "카톡" to listOf("com.kakao.talk"), "카카오톡" to listOf("com.kakao.talk"),
        "애플뮤직" to listOf("com.apple.android.music"),
        "스포티파이" to listOf("com.spotify.music"),
        "넷플릭스" to listOf("com.netflix.mediaclient"),
        "멜론" to listOf("com.iloen.melon"),
        "지니" to listOf("com.ktmusic.geniemusic"),
        "네이버" to listOf("com.nhn.android.search"),
        "인스타" to listOf("com.instagram.android"), "인스타그램" to listOf("com.instagram.android"),
        "구글맵" to listOf("com.google.android.apps.maps"),
        "카메라" to listOf("com.sec.android.app.camera", "com.android.camera", "com.google.android.GoogleCamera"),
        "갤러리" to listOf("com.sec.android.gallery3d", "com.google.android.apps.photos"),
        "계산기" to listOf("com.sec.android.app.popupcalculator", "com.google.android.calculator"),
        "전화" to listOf("com.samsung.android.dialer", "com.google.android.dialer", "com.android.dialer"),
        "문자" to listOf("com.samsung.android.messaging", "com.google.android.apps.messaging")
    )

    /** 이름으로 앱을 찾아 실행. 성공하면 앱 이름, 실패하면 null. */
    private fun launchAppByName(name: String): String? {
        val pm = activity.packageManager
        val key = name.replace(" ", "").lowercase()
        appAliases.entries.firstOrNull { key.contains(it.key.lowercase()) }?.value?.forEach { pkg ->
            pm.getLaunchIntentForPackage(pkg)?.let { li ->
                activity.startActivity(li); return name
            }
        }
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = pm.queryIntentActivities(launcher, 0).mapNotNull { ri ->
            val label = ri.loadLabel(pm).toString().replace(" ", "").lowercase()
            if (key.length >= 2 && (label == key || label.contains(key) || key.contains(label)) && label.length >= 2) ri to label else null
        }.sortedBy { kotlin.math.abs(it.second.length - key.length) }
        val best = matches.firstOrNull() ?: return null
        val li = pm.getLaunchIntentForPackage(best.first.activityInfo.packageName) ?: return null
        activity.startActivity(li)
        return best.first.loadLabel(pm).toString()
    }

    private fun handleAppLaunchCommand(t: String): Boolean {
        val verbs = listOf("실행해줘", "실행해", "실행", "열어줘", "열어", "띄워줘", "띄워", "켜줘", "켜", "들어가줘", "앱")
        if (verbs.none { t.contains(it) }) return false
        var name = t
        verbs.sortedByDescending { it.length }.forEach { name = name.replace(it, "") }
        listOf("좀", "를", "을").forEach { name = name.replace(it, "") }
        name = name.trim()
        if (name.length < 2) return false
        return try {
            val label = launchAppByName(name)
            if (label != null) { speakReply("${label} 열게요"); true } else false
        } catch (e: Exception) {
            NavLogger.e(activity, "[음성명령] 앱 실행 실패: ${e.message}")
            false
        }
    }

    fun shutdown() {
        try { voiceTts?.shutdown() } catch (e: Exception) { }
        voiceTts = null
        voiceTtsReady = false
    }


    /** 음성인식 실행용 인텐트: 후보를 여러 개 받고, 즐겨찾기 이름·자주 쓰는 명령어를 힌트로 줌(안드로이드 13+). */
    fun recognizerIntent(): Intent {
        return Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "목적지나 명령을 말씀하세요")
            putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                val hints = ArrayList<String>()
                favoriteEntries().forEach { (_, e) -> hints.add(e.name) }
                hints.addAll(listOf("무료도로", "고속도로", "경유지", "위성지도", "교통정보", "미니 플레이어", "주야 모드",
                    "안내 종료", "로그 전송", "내 차 위치", "근처", "맛집", "주유소", "충전소"))
                putStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_BIASING_STRINGS, hints)
            }
        }
    }

    // ---- 잘못 알아들은 말 보정 ----
    // 소리가 비슷해서 자주 틀리는 말을 원래 말로 되돌림(로그에서 실제로 틀린 것들 위주).
    private fun fixMisheard(t: String): String {
        var s = t
        // 사용자가 설정에서 직접 적어둔 보정을 먼저 적용
        VoiceCorrections.pairs(activity).forEach { (a, b) -> s = s.replace(a, b) }
        listOf(
            "경비실" to "경유지", "경유치" to "경유지", "경우지" to "경유지",
            "무료돈" to "무료도로", "무룡도로" to "무료도로", "무료돌" to "무료도로",
            "주야모두" to "주야모드", "모두로바꿔" to "모드바꿔",
            "나주로" to "낮으로", "난주로" to "낮으로"
        ).forEach { (a, b) -> s = s.replace(a, b) }
        return s
    }

    // 한글을 자음·모음으로 풀어서 소리 비슷한 정도를 비교(예: 란이 ≈ 라니). 첫소리 'ㅇ'은 소리가 없어 뺌.
    private fun jamo(s: String): String {
        val sb = StringBuilder()
        val cho = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
        val jung = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
        val jong = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"
        for (c in s) {
            if (c in '가'..'힣') {
                val code = c - '가'
                val ci = code / (21 * 28)
                val ji = (code % (21 * 28)) / 28
                val fi = code % 28
                if (cho[ci] != 'ㅇ') sb.append(cho[ci])
                sb.append(jung[ji])
                if (fi != 0) sb.append(jong[fi])
            } else sb.append(c)
        }
        return sb.toString()
    }

    private fun editDistance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    /** name 안에서 target과 소리가 가장 비슷한 부분의 유사도(0~1). */
    private fun soundSimilarity(name: String, target: String): Double {
        val a = jamo(name); val b = jamo(target)
        val short = if (a.length <= b.length) a else b
        val long = if (a.length <= b.length) b else a
        if (short.length < 3) return 0.0
        var best = 0.0
        for (len in maxOf(1, short.length - 1)..minOf(long.length, short.length + 1)) {
            for (start in 0..(long.length - len)) {
                val d = editDistance(long.substring(start, start + len), short)
                best = maxOf(best, 1.0 - d.toDouble() / maxOf(short.length, len))
            }
        }
        return best
    }

    private fun favoriteEntries(): List<Pair<String, HistoryEntry>> {
        val slots = listOf(QuickSlotStore.SLOT_HOME, QuickSlotStore.SLOT_WORK) +
            QuickSlotStore.favoriteSlots(QuickSlotStore.favoriteCount(activity))
        return slots.mapNotNull { s -> QuickSlotStore.get(activity, s)?.let { s to it } }
    }

    /** 말한 이름과 맞는 즐겨찾기를 찾음: 정확히 같음 > 포함 > 소리 비슷함(0.75 이상) 순. */
    private fun matchFavorite(target: String): HistoryEntry? {
        val list = favoriteEntries()
        list.firstOrNull { (slot, e) ->
            val n = e.name.replace(" ", "")
            n == target || (slot == QuickSlotStore.SLOT_HOME && target == "집") || (slot == QuickSlotStore.SLOT_WORK && target == "회사")
        }?.let { return it.second }
        list.firstOrNull { (_, e) ->
            val n = e.name.replace(" ", "")
            target.length >= 2 && (n.contains(target) || target.contains(n))
        }?.let { return it.second }
        return list.map { (_, e) -> e to soundSimilarity(e.name.replace(" ", ""), target) }
            .filter { it.second >= 0.75 }.maxByOrNull { it.second }?.first
    }

    // 음성인식이 후보 여러 개를 주면, 앞에서부터 "알아들을 수 있는 명령"인 것을 골라 실행.
    // 어느 것도 명령이 아니면 첫 번째 후보를 목적지 검색으로 처리.
    fun handleAlternatives(list: List<String>) {
        val cands = list.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cands.isEmpty()) return
        // 되묻기("~말씀이신가요?")에 대한 대답이면 그쪽으로 처리
        if (consumeAnswer(cands)) return
        NavLogger.d(activity, "[음성명령] 후보=${cands.joinToString(" | ")}")
        for (c in cands) {
            if (handle(c, strict = true)) {
                if (c != cands[0]) NavLogger.d(activity, "[음성명령] 후보 중 '$c' 로 실행")
                return
            }
        }
        // 정확히 맞는 것이 없으면, 후보들 중 즐겨찾기 이름과 소리가 가장 비슷한 것을 골라 되물음
        var best: Triple<GoParse, HistoryEntry, Double>? = null
        for (c in cands) {
            val gp = parseGo(c) ?: continue
            for ((_, e) in favoriteEntries()) {
                val s = soundSimilarity(e.name.replace(" ", ""), gp.target)
                if (best == null || s > best!!.third) best = Triple(gp, e, s)
            }
        }
        val b = best
        if (b != null && b.third >= 0.55 && b.first.explicit) {
            val (gp, entry, sim) = b
            NavLogger.d(activity, "[음성명령] 되묻기: '${entry.name}' (말=${gp.target}, 유사도=${"%.2f".format(sim)})")
            askYesNo("${entry.name} 말씀이신가요?",
                onYes = { host.goFavorite(entry, gp.priorityIndex, gp.replace) },
                onNo = { speakReply("알겠어요. 다시 말씀해 주세요") })
            return
        }
        handle(cands[0], strict = false)
    }

    class GoParse(val target: String, val priorityIndex: Int?, val replace: Boolean, val explicit: Boolean)

    /** "OO으로 (무료도로로) 가자/바꿔줘" 같은 말에서 이름·경로방식·바꿈 여부를 뽑음. 안내 명령이 아니면 null. */
    private fun parseGo(spoken: String): GoParse? {
        var t = fixMisheard(spoken.replace(" ", ""))
        val priorityWords = listOf(
            "무료도로로" to 2, "무료도로" to 2, "무료로" to 2, "무료" to 2,
            "고속도로로" to 1, "고속도로" to 1, "고속으로" to 1, "고속" to 1,
            "추천경로로" to 0, "추천경로" to 0, "추천으로" to 0, "최적경로" to 0
        )
        var priorityIndex: Int? = null
        for ((word, idx) in priorityWords) {
            if (t.contains(word)) { if (priorityIndex == null) priorityIndex = idx; t = t.replace(word, "") }
        }
        val goVerbs = listOf("가자", "가줘", "가고싶어", "안내해줘", "안내시작", "안내", "출발", "갈래", "가요", "바꿔줘", "바꿔서", "바꿔", "변경해줘", "변경")
        if (goVerbs.none { t.contains(it) }) return null
        val replace = t.contains("바꿔") || t.contains("변경")
        var target = t
        goVerbs.sortedByDescending { it.length }.forEach { target = target.replace(it, "") }
        listOf("목적지를", "목적지로", "목적지").forEach { target = target.replace(it, "") }
        target = target.trim()
        listOf("으로", "한테", "로", "에게", "에", "를", "을")
            .firstOrNull { target.endsWith(it) && target.length > it.length + 1 }
            ?.let { target = target.removeSuffix(it) }
        if (target.isEmpty()) return null
        val explicit = listOf("가자", "가줘", "가고싶어", "안내", "출발", "갈래", "가요").any { t.contains(it) } || t.contains("목적지")
        return GoParse(target, priorityIndex, replace, explicit)
    }

    // 음성 명령: 말한 글자를 보고 (1) "근처 맛집 찾아봐" 같은 주변 검색, (2) "라니집으로 무료도로로 가자"
    // 같은 즐겨찾기 안내(+경로 방식), (3) "양구군청으로 가자" 같은 목적지 안내(기존 추천/고속/무료 선택
    // 카드), 그 외에는 지금처럼 목적지 검색으로 처리. strict=true면 확실한 명령일 때만 실행하고 false를 돌려줌. #문제시 원복
    fun handle(spoken: String, strict: Boolean = false): Boolean {
        val orig = fixMisheard(spoken.replace(" ", ""))
        if (handleTimeCommand(orig)) return true
        if (handleWeatherCommand(orig)) return true
        if (handleNdaCommand(orig)) return true
        if (handleMediaCommand(orig)) return true
        if (handleAlarmCommand(orig)) return true
        if (handleMemoCommand(orig)) return true
        if (handleWebCommand(orig)) return true
        if (handleSettingCommand(orig)) return true
        if (handleNearestBestCommand(orig)) return true
        if (handleCarSettingCommand(orig)) return true
        if (handleFavoriteManageCommand(orig)) return true
        if (handleMiscNdaCommand(orig)) return true
        if (handleWaypointCommand(orig)) return true
        if (handleAppLaunchCommand(orig)) return true
        var t = orig

        // 경로 방식 말 찾기(없으면 null)
        val priorityWords = listOf(
            "무료도로로" to 2, "무료도로" to 2, "무료로" to 2, "무료" to 2,
            "고속도로로" to 1, "고속도로" to 1, "고속으로" to 1, "고속" to 1,
            "추천경로로" to 0, "추천경로" to 0, "추천으로" to 0, "최적경로" to 0
        )
        var priorityIndex: Int? = null
        for ((word, idx) in priorityWords) {
            if (t.contains(word)) {
                if (priorityIndex == null) priorityIndex = idx
                t = t.replace(word, "")
            }
        }

        val goVerbs = listOf("가자", "가줘", "가고싶어", "안내해줘", "안내시작", "안내", "출발", "갈래", "가요", "바꿔줘", "바꿔서", "바꿔", "변경해줘", "변경")
        val isGo = goVerbs.any { t.contains(it) }
        val isReplace = t.contains("바꿔") || t.contains("변경")
        val nearbyWords = listOf("근처", "주변", "가까운", "근방")
        val isNearby = nearbyWords.any { t.contains(it) }
        val fillers = listOf("찾아봐줘", "찾아봐", "찾아줘", "찾아", "검색해줘", "검색해", "검색", "알려줘", "어디있어", "어디야", "어디", "있어", "있나", "좀", "해줘")

        if (isNearby && !isGo) {
            var kw = t
            nearbyWords.forEach { kw = kw.replace(it, "") }
            fillers.forEach { kw = kw.replace(it, "") }
            kw = kw.trim().trimEnd('에', '서', '의', '를', '을')
            if (kw.isNotEmpty()) {
                NavLogger.d(activity, "[음성명령] 주변검색: '$kw' (원문=$spoken)")
                Toast.makeText(activity, "근처 $kw 검색", Toast.LENGTH_SHORT).show()
                host.search(kw)
                return true
            }
        }

        if (isGo) {
            var target = t
            goVerbs.sortedByDescending { it.length }.forEach { target = target.replace(it, "") }
            listOf("목적지를", "목적지로", "목적지") .forEach { target = target.replace(it, "") }
            target = target.trim()
            listOf("으로", "한테", "로", "에게", "에", "를", "을")
                .firstOrNull { target.endsWith(it) && target.length > it.length + 1 }
                ?.let { target = target.removeSuffix(it) }
            if (target.isNotEmpty()) {
                val matched = matchFavorite(target)
                if (matched != null) {
                    NavLogger.d(activity, "[음성명령] 즐겨찾기 안내: '${matched.name}' 방식=$priorityIndex (원문=$spoken)")
                    Toast.makeText(activity, "${matched.name}(으)로 안내", Toast.LENGTH_SHORT).show()
                    host.goFavorite(matched, priorityIndex, isReplace)
                    return true
                }
                if (strict) return false
                NavLogger.d(activity, "[음성명령] 목적지 검색: '$target' (원문=$spoken)")
                host.search(target)
                return true
            }
        }

        if (strict) return false
        host.search(spoken)
        return true
    }


    // ---- "제일 저렴한 주유소 찾아줘", "편의점 제일 가까운 곳 찾아줘" ----
    // 진행방향(안내 중이면 경로 앞쪽, 아니면 GPS 방향)에 있는 곳만 먼저 보고, 없으면 되물어서 뒤쪽까지 봄.
    private class Place(val name: String, val lat: Double, val lon: Double, val distM: Double, val price: Int?, val label: String)

    private fun metersBetween(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val dLat = Math.toRadians(la2 - la1)
        val dLon = Math.toRadians(lo2 - lo1)
        val h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 6371000.0 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h))
    }

    /** 경로선(경도,위도 목록)에 점을 투영해 (경로를 따라간 거리, 경로에서 떨어진 거리)를 돌려줌. */
    private fun projectOnRoute(lat: Double, lon: Double, route: List<Pair<Double, Double>>, oLat: Double, oLon: Double): Pair<Double, Double>? {
        if (route.size < 2) return null
        val kx = 111320.0 * Math.cos(Math.toRadians(oLat))
        val ky = 110540.0
        fun xy(la: Double, lo: Double) = Pair((lo - oLon) * kx, (la - oLat) * ky)
        val p = xy(lat, lon)
        var cum = 0.0
        var bestPerp = Double.MAX_VALUE
        var bestAlong = 0.0
        var prev = xy(route[0].second, route[0].first)
        for (i in 1 until route.size) {
            val cur = xy(route[i].second, route[i].first)
            val sx = cur.first - prev.first
            val sy = cur.second - prev.second
            val len = Math.hypot(sx, sy)
            if (len > 0) {
                val t = (((p.first - prev.first) * sx + (p.second - prev.second) * sy) / (len * len)).coerceIn(0.0, 1.0)
                val perp = Math.hypot(p.first - (prev.first + t * sx), p.second - (prev.second + t * sy))
                if (perp < bestPerp) { bestPerp = perp; bestAlong = cum + t * len }
                cum += len
            }
            prev = cur
        }
        return if (bestPerp == Double.MAX_VALUE) null else Pair(bestAlong, bestPerp)
    }

    /** 진행방향 앞쪽인지: true/false, 판단할 근거(경로·GPS방향)가 없으면 null. */
    private fun isAhead(lat: Double, lon: Double, curLat: Double, curLon: Double): Boolean? {
        if (KakaoRouteDataRepository.isFresh()) {
            val route = KakaoRouteDataRepository.routeCoordinates
            val me = projectOnRoute(curLat, curLon, route, curLat, curLon)
            val it = projectOnRoute(lat, lon, route, curLat, curLon)
            if (me != null && it != null && it.second <= 400.0) return it.first > me.first
        }
        val bearing = host.currentBearing() ?: return null
        val dLon = Math.toRadians(lon - curLon)
        val lat1 = Math.toRadians(curLat)
        val lat2 = Math.toRadians(lat)
        val y = Math.sin(dLon) * Math.cos(lat2)
        val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon)
        val target = (Math.toDegrees(Math.atan2(y, x)) + 360) % 360
        var diff = Math.abs(target - bearing)
        if (diff > 180) diff = 360 - diff
        return diff <= 90
    }

    private fun distText(m: Double) = if (m >= 1000) String.format("%.1f킬로미터", m / 1000.0) else "${m.toInt()}미터"

    /** 후보들에서 진행방향 앞쪽 우선으로 1등을 골라 말로 안내. cheapest=true면 가격 기준, 아니면 거리 기준. */
    private fun pickAndAnnounce(places: List<Place>, kind: String, cheapest: Boolean, curLat: Double, curLon: Double) {
        if (places.isEmpty()) { speakReply("근처에서 $kind 을(를) 못 찾았어요"); return }
        fun best(list: List<Place>): Place? =
            if (cheapest) {
                val priced = list.filter { it.price != null }
                (if (priced.isNotEmpty()) priced else list).sortedWith(compareBy<Place>({ it.price ?: Int.MAX_VALUE }, { it.distM })).firstOrNull()
            } else list.minByOrNull { it.distM }
        val flagged = places.map { it to isAhead(it.lat, it.lon, curLat, curLon) }
        val known = flagged.any { it.second != null }
        val ahead = flagged.filter { it.second == true }.map { it.first }
        fun announce(p: Place, note: String) {
            val priceText = p.price?.let { "${p.label} ${it}원, " } ?: ""
            val entry = HistoryEntry(p.name, "", p.lat, p.lon, p.distM)
            NavLogger.d(activity, "[음성명령] $kind 1등: ${p.name} ${priceText}${distText(p.distM)} ($note)")
            askYesNo("$note${p.name}, ${priceText}${distText(p.distM)}이에요. 안내할까요?",
                onYes = { host.goFavorite(entry, null, false) },
                onNo = { speakReply("알겠어요") })
        }
        if (!known) { best(places)?.let { announce(it, "") }; return }
        val a = best(ahead)
        if (a != null) { announce(a, "진행방향에서 "); return }
        askYesNo("진행방향 앞쪽에는 $kind 이(가) 없어요. 뒤쪽까지 찾아볼까요?",
            onYes = { best(places)?.let { announce(it, "뒤쪽까지 포함하면 ") } },
            onNo = { speakReply("알겠어요") })
    }

    private fun handleNearestBestCommand(t: String): Boolean {
        val cheapest = t.contains("저렴") || t.contains("싼") || t.contains("최저가") || t.contains("싸")
        val nearest = t.contains("제일가까") || t.contains("가장가까") || t.contains("가까운")
        if (!cheapest && !nearest) return false
        if (!(t.contains("찾아") || t.contains("알려") || t.contains("어디") || t.contains("검색"))) return false
        val kind = SearchRanking.CATEGORY_KEYWORDS.keys.filter { it != "충전소" }.firstOrNull { t.contains(it) } ?: return false
        if (cheapest && kind != "주유소") return false   // 가격 비교는 주유소만 가능
        val (lat, lon) = host.currentLatLon()
        if (lat == null || lon == null) { speakReply("현재 위치를 아직 못 찾았어요"); return true }
        val prefs = activity.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
        val restKey = prefs.getString("kakao_rest_api_key", "") ?: ""
        if (restKey.isBlank()) { speakReply("카카오 키가 없어서 검색을 못 해요"); return true }
        Toast.makeText(activity, "$kind 찾는 중", Toast.LENGTH_SHORT).show()

        val opinetKey = prefs.getString("opinet_api_key", null)
        if (cheapest && !opinetKey.isNullOrBlank()) {
            val prodcd = OpinetHelper.savedFuelType(activity) ?: OpinetHelper.FUEL_TYPES[0].second
            val fuelLabel = OpinetHelper.FUEL_TYPES.firstOrNull { it.second == prodcd }?.first ?: "가격"
            OpinetHelper.fetchNearby(activity, searchHttpClient, opinetKey, lat, lon, null, prodcd) { stations ->
                val brands = OpinetHelper.savedBrandFilter(activity)
                val filtered = if (brands.isNullOrEmpty()) stations else stations.filter { it.brandCode in brands }
                val places = filtered.map { Place("${it.brandName} ${it.name}".trim(), it.lat, it.lon, it.distanceMeters, it.gasolinePrice, fuelLabel) }
                activity.runOnUiThread { pickAndAnnounce(places, kind, true, lat, lon) }
            }
        } else {
            if (cheapest) speakReply("오피넷 키가 없어서 가격 비교는 못 하고 가까운 곳으로 찾을게요")
            val code = SearchRanking.CATEGORY_KEYWORDS[kind] ?: return true
            NearbyCategoryPopup.performCategorySearchShared(activity, searchHttpClient, restKey, code, lat, lon) { hits ->
                val places = hits.map { Place(it.name, it.lat, it.lon, it.distanceMeters ?: metersBetween(lat, lon, it.lat, it.lon), null, "") }
                activity.runOnUiThread { pickAndAnnounce(places, kind, false, lat, lon) }
            }
        }
        return true
    }

    // ---- 차종·연료·하이패스, 오피넷 유종·브랜드 ----
    private fun handleCarSettingCommand(t: String): Boolean {
        val wantOn = t.contains("켜") || t.contains("켤")
        val wantOff = t.contains("꺼") || t.contains("끄") || t.contains("해제") || t.contains("없애")
        // 하이패스 장착
        if (t.contains("하이패스") && (wantOn || wantOff || t.contains("장착"))) {
            val on = if (wantOff) false else true
            CarFuelSettings.save(activity, CarFuelSettings.getCarType(activity), CarFuelSettings.getCarFuel(activity), on)
            speakReply(if (on) "하이패스 장착으로 바꿨어요. 통행료를 하이패스 요금으로 계산해요" else "하이패스 장착을 껐어요")
            return true
        }
        // 오피넷(주유소 검색) 유종
        if (t.contains("유종")) {
            val hit = OpinetHelper.FUEL_TYPES.firstOrNull { t.contains(it.first.replace(" ", "")) }
            if (hit != null) {
                OpinetHelper.saveFuelType(activity, hit.second)
                speakReply("주유소 검색 유종을 ${hit.first}로 바꿨어요")
                return true
            }
        }
        // 오피넷 브랜드
        if (t.contains("브랜드") && (t.contains("주유소") || t.contains("바꿔") || t.contains("설정") || t.contains("전체"))) {
            if (t.contains("전체") || t.contains("모두") || t.contains("상관없")) {
                OpinetHelper.saveBrandFilter(activity, emptySet())
                speakReply("주유소 브랜드를 전체로 바꿨어요")
                return true
            }
            val aliases = mapOf(
                "SKE" to listOf("sk", "에스케이"), "GSC" to listOf("gs", "지에스"), "HDO" to listOf("현대오일", "오일뱅크"),
                "SOL" to listOf("s-oil", "soil", "에스오일", "에쓰오일", "s오일"), "RTX" to listOf("알뜰"), "NHO" to listOf("자가상표", "농협")
            )
            val picked = aliases.filter { (_, ws) -> ws.any { t.lowercase().contains(it) } }.keys
            if (picked.isNotEmpty()) {
                OpinetHelper.saveBrandFilter(activity, picked.toSet())
                val names = OpinetHelper.BRANDS.filter { it.second in picked }.joinToString(", ") { it.first }
                speakReply("주유소 브랜드를 $names(으)로 바꿨어요")
                return true
            }
        }
        // 카카오 경로 계산용 연료·차종
        if (t.contains("연료")) {
            val hit = CarFuelSettings.CAR_FUEL_LABELS.entries.sortedByDescending { it.value.length }
                .firstOrNull { t.contains(it.value.replace(" ", "")) }
            if (hit != null) {
                CarFuelSettings.save(activity, CarFuelSettings.getCarType(activity), hit.key, CarFuelSettings.getUseHipass(activity))
                speakReply("연료를 ${hit.value}(으)로 바꿨어요")
                return true
            }
        }
        if (t.contains("차종")) {
            val hit = CarFuelSettings.CAR_TYPE_LABELS.entries.sortedByDescending { it.value.length }
                .firstOrNull { t.contains(it.value.replace(" ", "")) }
            if (hit != null) {
                CarFuelSettings.save(activity, hit.key, CarFuelSettings.getCarFuel(activity), CarFuelSettings.getUseHipass(activity))
                speakReply("차종을 ${hit.value}(으)로 바꿨어요")
                return true
            }
        }
        return false
    }

    // ---- 즐겨찾기 관리: 삭제 / 이름 변경 / 이동방식 저장·삭제 / 등록 ----
    private fun slotOf(t: String): String? {
        Regex("(\\d+)번").find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { if (it in 1..QuickSlotStore.MAX_FAVORITE_SLOTS) return "fav$it" }
        if (Regex("(^|[^가-힣])집(을|은|이|의|에|으로|$)").containsMatchIn(t) || t.startsWith("집")) return QuickSlotStore.SLOT_HOME
        if (t.contains("회사")) return QuickSlotStore.SLOT_WORK
        // 이름으로 찾기
        favoriteEntries().forEach { (slot, e) ->
            val n = e.name.replace(" ", "")
            if (n.length >= 2 && t.contains(n)) return slot
        }
        return null
    }

    private fun slotLabel(slot: String): String = when (slot) {
        QuickSlotStore.SLOT_HOME -> "집"
        QuickSlotStore.SLOT_WORK -> "회사"
        else -> "즐겨찾기 ${slot.removePrefix("fav")}번"
    }

    private fun handleFavoriteManageCommand(t: String): Boolean {
        if (!(t.contains("즐겨찾기") || t.contains("집") || t.contains("회사") || Regex("\\d+번").containsMatchIn(t))) return false
        // 삭제
        if ((t.contains("삭제") || t.contains("지워")) && (t.contains("즐겨찾기") || t.contains("등록"))) {
            val slot = slotOf(t) ?: return false
            val e = QuickSlotStore.get(activity, slot)
            if (e == null) { speakReply("${slotLabel(slot)}은 비어 있어요"); return true }
            askYesNo("${slotLabel(slot)} ${e.name}을 삭제할까요?",
                onYes = { QuickSlotStore.delete(activity, slot); speakReply("삭제했어요") },
                onNo = { speakReply("삭제하지 않을게요") })
            return true
        }
        // 이름 변경: "즐겨찾기 3번 이름을 처가로 변경"
        val ren = Regex("이름(?:을|은)?(.+?)(?:으로|로)(?:변경|바꿔|수정|해줘)").find(t)
        if (ren != null) {
            val slot = slotOf(t.substringBefore("이름")) ?: return false
            val e = QuickSlotStore.get(activity, slot)
            val newName = ren.groupValues[1].trim()
            if (e == null) { speakReply("${slotLabel(slot)}은 비어 있어요"); return true }
            if (newName.isEmpty()) return false
            QuickSlotStore.save(activity, slot, e.copy(name = newName))
            speakReply("${slotLabel(slot)} 이름을 ${newName}(으)로 바꿨어요")
            return true
        }
        // 이동방식 저장/삭제: "즐겨찾기 3번 이동방식 무료도로로 저장", "이동방식 삭제"
        if (t.contains("이동방식") || t.contains("경로방식")) {
            val slot = slotOf(t) ?: return false
            val e = QuickSlotStore.get(activity, slot)
            if (e == null) { speakReply("${slotLabel(slot)}은 비어 있어요"); return true }
            if (t.contains("삭제") || t.contains("지워") || t.contains("초기화")) {
                QuickSlotStore.clearRoutePreference(activity, slot)
                speakReply("${slotLabel(slot)}의 저장된 이동방식을 지웠어요")
                return true
            }
            val (name, avoid, label) = when {
                t.contains("무료") -> Triple("KNRoutePriority_Recommand", com.kakaomobility.knsdk.KNRouteAvoidOption.KNRouteAvoidOption_Fare.value, "무료도로 우선")
                t.contains("고속") -> Triple("KNRoutePriority_HighWay", 0, "고속도로 우선")
                t.contains("추천") -> Triple("KNRoutePriority_Recommand", 0, "추천 경로")
                else -> return false
            }
            QuickSlotStore.updateRoutePreference(activity, slot, name, avoid)
            speakReply("${slotLabel(slot)}의 이동방식을 $label(으)로 저장했어요")
            return true
        }
        // 등록: "즐겨찾기 3번에 강남역 등록해줘", "집을 서울역으로 등록해줘"
        if (t.contains("등록") || t.contains("저장해")) {
            val slot = slotOf(t.substringBefore("등록").substringBefore("저장")) ?: return false
            var place = t
            listOf("즐겨찾기", "등록해줘", "등록해", "등록", "저장해줘", "저장해", "해줘", "으로", "에", "을", "를", "로").forEach { place = place.replace(it, "") }
            place = place.replace(Regex("\\d+번"), "").replace("집", "").replace("회사", "").trim()
            if (place.length < 2) { speakReply("어디를 등록할지 같이 말해주세요. 예를 들면 '즐겨찾기 3번에 강남역 등록해줘'"); return true }
            if (host.registerFavorite(slot, place)) speakReply("${slotLabel(slot)}에 등록할 ${place}을 검색할게요. 결과에서 골라 주세요")
            else speakReply("이 화면에서는 등록을 못 해요. 카카오 화면에서 말씀해 주세요")
            return true
        }
        return false
    }

    // ---- 화면 전환 / 볼륨 / 백업 / 로그 삭제 / 연결 스위치 ----
    private fun handleMiscNdaCommand(t: String): Boolean {
        if ((t.contains("카카오화면") || t.contains("티맵화면")) && (t.contains("바꿔") || t.contains("전환") || t.contains("열어") || t.contains("가줘") || t.contains("보여"))) {
            val toKakao = t.contains("카카오화면")
            val msg = host.switchScreen(toKakao)
            speakReply(msg)
            return true
        }
        if (t.contains("안내음량") || t.contains("길안내음량") || t.contains("안내소리")) {
            val cur = VolumeHelper.guideVolumePercent(activity)
            val pct = Regex("(\\d+)(?:퍼센트|%)").find(t)?.groupValues?.get(1)?.toIntOrNull()
            val next = when {
                pct != null -> pct
                t.contains("올려") || t.contains("키워") || t.contains("크게") -> cur + 10
                t.contains("내려") || t.contains("줄여") || t.contains("작게") -> cur - 10
                else -> return false
            }.coerceIn(0, 100)
            VolumeHelper.setGuideVolumePercent(activity, next)
            speakReply("안내 음량을 ${next}퍼센트로 맞췄어요")
            return true
        }
        if (t.contains("백업") && !t.contains("복원")) {
            val ok = try { SettingsBackup.exportToLocalDownloads(activity) } catch (e: Exception) { false }
            speakReply(if (ok) "설정을 다운로드 폴더에 백업했어요" else "백업에 실패했어요")
            return true
        }
        if (t.contains("로그삭제") || t.contains("로그지워") || t.contains("로그전체삭제")) {
            askYesNo("로그를 모두 삭제할까요?",
                onYes = { try { NavLogger.deleteAllLogFiles(activity) } catch (e: Exception) { }; speakReply("로그를 삭제했어요") },
                onNo = { speakReply("삭제하지 않을게요") })
            return true
        }
        val wantOn = t.contains("켜") || t.contains("켤")
        val wantOff = t.contains("꺼") || t.contains("끄") || t.contains("해제")
        if ((wantOn || wantOff) && (t.lowercase().contains("nmirror") || t.contains("엔미러"))) {
            com.tmap.nda.nmirror.NMirrorSender.setEnabled(activity, wantOn && !wantOff)
            speakReply("nMirror 안내 전달을 ${if (wantOn && !wantOff) "켰어요" else "껐어요"}")
            return true
        }
        if ((wantOn || wantOff) && t.contains("오류보고")) {
            DiscordReporter.setEnabled(activity, wantOn && !wantOff)
            speakReply("자동 오류 보고를 ${if (wantOn && !wantOff) "켰어요" else "껐어요"}")
            return true
        }
        if ((wantOn || wantOff) && (t.contains("다른앱위") || t.contains("백그라운드안내") || t.contains("미니안내"))) {
            val on = wantOn && !wantOff
            activity.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).edit().putBoolean("background_overlay_enabled", on).apply()
            if (on && !NavOverlayManager.hasPermission(activity)) {
                NavOverlayManager.requestPermission(activity)
                speakReply("다른 앱 위에 표시를 켰어요. 권한 화면에서 TmapNda를 허용해 주세요")
            } else speakReply("다른 앱 위에 표시를 ${if (on) "켰어요" else "껐어요"}")
            return true
        }
        return false
    }
    // "미용실 경유지로 추가해줘" - 안내 중에 즐겨찾기(또는 검색한 곳)를 경유지로 넣음
    private fun handleWaypointCommand(t: String): Boolean {
        if (!(t.contains("경유지") && t.contains("추가"))) return false
        var target = t
        listOf("경유지로", "경유지", "즐겨찾기에서", "즐겨찾기", "추가해줘", "추가해", "추가", "해줘", "넣어줘", "좀").forEach { target = target.replace(it, "") }
        listOf("으로", "로", "를", "을", "에")
            .firstOrNull { target.endsWith(it) && target.length > it.length + 1 }
            ?.let { target = target.removeSuffix(it) }
        if (target.isEmpty()) { speakReply("어디를 경유지로 넣을지 같이 말해주세요"); return true }
        val fav = matchFavorite(target)
        if (fav != null) {
            NavLogger.d(activity, "[음성명령] 경유지 추가(즐겨찾기): '${fav.name}'")
            host.addWaypoint(fav)
            return true
        }
        NavLogger.d(activity, "[음성명령] 경유지 추가(검색): '$target'")
        if (!host.addWaypointBySearch(target)) speakReply("이 화면에서는 즐겨찾기에 있는 곳만 경유지로 넣을 수 있어요")
        return true
    }
}
