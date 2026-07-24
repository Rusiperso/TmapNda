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
