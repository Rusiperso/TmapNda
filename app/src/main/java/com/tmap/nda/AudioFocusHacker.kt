package com.tmap.nda

import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

object AudioFocusHacker {
    @JvmStatic
    fun requestAudioFocus(
        am: AudioManager,
        l: AudioManager.OnAudioFocusChangeListener,
        streamType: Int,
        durationHint: Int
    ): Int {
        Log.e("TmapVolume", "[AudioFocusHacker] requestAudioFocus(old) intercepted! NOP!")
        NavLogger.e("[AudioFocusHacker] requestAudioFocus(old) intercepted! NOP!")
        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @JvmStatic
    fun requestAudioFocus(
        am: AudioManager,
        request: AudioFocusRequest
    ): Int {
        Log.e("TmapVolume", "[AudioFocusHacker] requestAudioFocus(new) intercepted! NOP!")
        NavLogger.e("[AudioFocusHacker] requestAudioFocus(new) intercepted! NOP!")
        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @JvmStatic
    fun abandonAudioFocus(
        am: AudioManager,
        l: AudioManager.OnAudioFocusChangeListener
    ): Int {
        Log.e("TmapVolume", "[AudioFocusHacker] abandonAudioFocus(old) intercepted! NOP!")
        NavLogger.e("[AudioFocusHacker] abandonAudioFocus(old) intercepted! NOP!")
        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @JvmStatic
    fun abandonAudioFocusRequest(
        am: AudioManager,
        request: AudioFocusRequest
    ): Int {
        Log.e("TmapVolume", "[AudioFocusHacker] abandonAudioFocusRequest(new) intercepted! NOP!")
        NavLogger.e("[AudioFocusHacker] abandonAudioFocusRequest(new) intercepted! NOP!")
        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED
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
