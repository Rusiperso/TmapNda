package com.tmap.nda

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import kotlin.math.PI
import kotlin.math.sin

/**
 * 2026-11-11 재억 시험 - 아이폰 블루투스 음악을 틀면 엔미러(안드로이드오토)에서 Nda 소리가 안 나는 문제.
 * ToneGenerator(STREAM_MUSIC)는 "음악 길"로만 나가서 차가 음악 소리끼리 다투는 걸로 볼 수 있음.
 * 경고음을 "길안내 길"(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)로 보냄. 스위치(guide_sound_as_navigation)를
 * 끄면 예전 방식(ToneGenerator)으로 돌아감. #문제시 원복
 */
object GuideBeep {
    const val PREF_KEY = "guide_sound_as_navigation"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).getBoolean(PREF_KEY, true)

    /** 짧은 삑 소리 [count]번. 실패하면 예전 ToneGenerator 방식으로 대신 울림. */
    fun play(context: Context, volumePercent: Int, count: Int = 2) {
        if (!enabled(context)) { legacy(volumePercent); return }
        try {
            val rate = 22050
            val beepMs = 110
            val gapMs = 70
            val beepSamples = rate * beepMs / 1000
            val gapSamples = rate * gapMs / 1000
            val total = (beepSamples + gapSamples) * count
            val pcm = ShortArray(total)
            var pos = 0
            repeat(count) {
                for (i in 0 until beepSamples) {
                    val fade = minOf(1f, minOf(i, beepSamples - i) / 200f)
                    pcm[pos++] = (sin(2 * PI * 1480 * i / rate) * 0.8 * fade * Short.MAX_VALUE).toInt().toShort()
                }
                pos += gapSamples
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm, 0, pcm.size)
            track.setVolume(volumePercent.coerceIn(1, 100) / 100f)
            track.play()
            val ms = (total * 1000L / rate) + 300L
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try { track.release() } catch (_: Exception) {}
            }, ms)
            NavLogger.d(context, "[길안내소리] 경고음을 길안내 길로 재생 vol=$volumePercent%")
        } catch (e: Exception) {
            NavLogger.d(context, "[길안내소리] 길안내 길 재생 실패, 예전 방식으로: ${e.message}")
            legacy(volumePercent)
        }
    }

    /**
     * 2026-11-19 재억 요청 - 네이버 화면 카메라 경고음을 우리 삐삐 대신 티맵 SDK의 "띵띵"(고정카메라 300m 알림음 idw_fix_cam_300.ogg,
     * 앱에 이미 들어 있음)으로 냄. 길안내 길·길안내 음량으로 재생. 파일을 못 찾거나 실패하면 아무 소리도 안 냄. #문제시 원복
     */
    fun playTmapChime(context: Context, volumePercent: Int) {
        try {
            val id = context.resources.getIdentifier("idw_fix_cam_300", "raw", context.packageName)
            if (id == 0) { NavLogger.d(context, "[길안내소리] 티맵 알림음 파일 없음"); return }
            val mp = android.media.MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(if (enabled(context)) AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE else AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            context.resources.openRawResourceFd(id).use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            val v = volumePercent.coerceIn(1, 100) / 100f
            mp.setVolume(v, v)
            mp.setOnCompletionListener { try { it.release() } catch (_: Exception) {} }
            mp.setOnErrorListener { p, _, _ -> try { p.release() } catch (_: Exception) {}; true }
            mp.prepare()
            mp.start()
            NavLogger.d(context, "[길안내소리] 티맵 알림음 재생 vol=$volumePercent%")
        } catch (e: Exception) {
            NavLogger.d(context, "[길안내소리] 티맵 알림음 재생 실패: ${e.message}")
        }
    }

    private fun legacy(volumePercent: Int) {
        val tone = ToneGenerator(AudioManager.STREAM_MUSIC, volumePercent)
        tone.startTone(ToneGenerator.TONE_CDMA_PIP, 400)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tone.release() }, 500)
    }

    /** 카카오 SDK 안내 음성의 소리 길을 "길안내"로 바꿈(SDK 기본은 미디어/음악). 스위치가 꺼져 있으면 기본값으로 되돌림. */
    fun applyKakaoVoiceUsage(context: Context) {
        try {
            val on = enabled(context)
            val usage = if (on) AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE else AudioAttributes.USAGE_MEDIA
            val content = if (on) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MUSIC
            val cls = Class.forName("com.kakaomobility.knsdk.guidance.knguidance.voiceguide.KNGuide_Voice")
            val comp = cls.getField("Companion").get(null)!!
            comp.javaClass.getMethod("setUsage", Int::class.javaPrimitiveType).invoke(comp, usage)
            comp.javaClass.getMethod("setContentType", Int::class.javaPrimitiveType).invoke(comp, content)
            NavLogger.d(context, "[길안내소리] 카카오 음성 소리 길=${if (on) "길안내" else "미디어(기본)"}")
        } catch (e: Exception) {
            NavLogger.d(context, "[길안내소리] 카카오 음성 소리 길 변경 실패: ${e.message}")
        }
    }
}
