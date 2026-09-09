package com.tmap.nda

import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

// v: 재억 요청(2026-09-03) - 가로챌 때마다 파일 로그를 남겨서 실기기 로그에 145줄이
// 쌓여 있었음. 내용은 매번 완전히 같고("가로채서 무시함"), 이 훅이 살아있다는 것만
// 확인되면 되는 로그라 종류별로 첫 1회만 파일에 남김(logcat에는 계속 다 나옴). #문제시 원복
object AudioFocusHacker {
    @JvmStatic
    fun requestAudioFocus(
        am: AudioManager,
        l: AudioManager.OnAudioFocusChangeListener,
        streamType: Int,
        durationHint: Int
    ): Int {
        Log.e("TmapVolume", "[AudioFocusHacker] requestAudioFocus(old) intercepted! NOP!")
        NavLogger.dIfChanged("focus_req_old", "[AudioFocusHacker] requestAudioFocus(old) intercepted! NOP!")
        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @JvmStatic
    fun requestAudioFocus(
        am: AudioManager,
        request: AudioFocusRequest
    ): Int {
        Log.e("TmapVolume", "[AudioFocusHacker] requestAudioFocus(new) intercepted! NOP!")
        NavLogger.dIfChanged("focus_req_new", "[AudioFocusHacker] requestAudioFocus(new) intercepted! NOP!")
        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @JvmStatic
    fun abandonAudioFocus(
        am: AudioManager,
        l: AudioManager.OnAudioFocusChangeListener
    ): Int {
        Log.e("TmapVolume", "[AudioFocusHacker] abandonAudioFocus(old) intercepted! NOP!")
        NavLogger.dIfChanged("focus_abandon_old", "[AudioFocusHacker] abandonAudioFocus(old) intercepted! NOP!")
        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @JvmStatic
    fun abandonAudioFocusRequest(
        am: AudioManager,
        request: AudioFocusRequest
    ): Int {
        Log.e("TmapVolume", "[AudioFocusHacker] abandonAudioFocusRequest(new) intercepted! NOP!")
        NavLogger.dIfChanged("focus_abandon_new", "[AudioFocusHacker] abandonAudioFocusRequest(new) intercepted! NOP!")
        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    // v: 재억 요청(2026-09-09) - playSoundEffect(int)는 오디오 포커스 요청 없이 바로
    // 소리를 내는 경로라서 위 requestAudioFocus 계열 차단으로는 안 막혔음. 실제 발생
    // 지점은 카카오내비 SDK 쪽(KakaoNaviActivity가 화면 위에 떠 있는 동안 로그에 찍힘,
    // 카카오 음소거 시 소리가 사라지는 것과 일치) - 티맵이 아니었음. 같은 방식으로
    // 호출 자체를 가로채서 아무 동작도 안 하게 함. #문제시 원복
    @JvmStatic
    fun playSoundEffect(target: Any?, effectType: Int) {
        Log.e("TmapVolume", "[AudioFocusHacker] playSoundEffect($effectType) intercepted! NOP!")
        NavLogger.dIfChanged("play_sound_effect", "[AudioFocusHacker] playSoundEffect intercepted! NOP!")
    }
}

object AudioStreamDiagnostics {
    // v: 재억 제보(2026-08-22) - "안내음성 볼륨을 20%/30%로 낮춰도 경고음이랑 따로
    // 논다"는 원인을 잡기 위해, KakaoGuidanceDelegate 안에 있던 진단 함수를 공용으로
    // 빼서 경고음 재생 시점(MapActivity/KakaoNaviActivity)에서도 같은 형식으로 찍히게
    // 함 - 같은 로그 태그로 시간순 비교가 가능해짐. #문제시 원복
    fun log(context: android.content.Context, tag: String) {
        try {
            val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager ?: return
            val streams = mapOf(
                "MUSIC" to AudioManager.STREAM_MUSIC,
                "SYSTEM" to AudioManager.STREAM_SYSTEM,
                "NOTIFICATION" to AudioManager.STREAM_NOTIFICATION,
                "RING" to AudioManager.STREAM_RING,
                "ALARM" to AudioManager.STREAM_ALARM,
                "VOICE_CALL" to AudioManager.STREAM_VOICE_CALL,
                "DTMF" to AudioManager.STREAM_DTMF
            )
            val volDump = streams.entries.joinToString(", ") { (name, stream) ->
                "$name=${am.getStreamVolume(stream)}/${am.getStreamMaxVolume(stream)}"
            }
            NavLogger.trace("voice", "[볼륨진단][$tag] 스트림볼륨: $volDump")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val configs = am.activePlaybackConfigurations
                for (cfg in configs) {
                    val attrs = cfg.audioAttributes
                    NavLogger.trace("voice", "[볼륨진단][$tag] 활성재생: usage=${attrs.usage} contentType=${attrs.contentType}")
                }
            }
        } catch (e: Exception) {
            NavLogger.e(context, "[볼륨진단][$tag] 예외: ${e.message}")
        }
    }
}
