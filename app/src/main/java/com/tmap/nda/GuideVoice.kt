package com.tmap.nda

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * 네이버 길안내 음성(폰 TTS)의 목소리 선택.
 * 폰에 실제로 설치된 한국어 목소리를 목록으로 보여주고, 누르면 샘플을 들려준다.
 * 고른 목소리 이름은 TmapNdaPrefs의 "guide_tts_voice"에 저장되고 NaverNavigator가 읽어서 쓴다.
 */
object GuideVoice {
    private const val PREF_KEY = "guide_tts_voice"
    // 저장한 목소리가 없을 때 쓰는 기본 목소리(폰에 없으면 폰의 기본 목소리 그대로 쓴다).
    private const val DEFAULT_VOICE = "ko-kr-x-ism-network"
    private const val SAMPLE = "삼백 미터 앞에서 우회전 하세요"

    fun saved(context: Context): String =
        context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).getString(PREF_KEY, "").orEmpty()
            .ifEmpty { DEFAULT_VOICE }

    /** 저장된 목소리가 있고 폰에 아직 있으면 입힌다. 없으면 기본 목소리 그대로 둔다. */
    fun applySaved(context: Context, tts: TextToSpeech) {
        val name = saved(context)
        if (name.isEmpty()) return
        try {
            tts.voices?.firstOrNull { it.name == name }?.let { tts.voice = it }
        } catch (_: Exception) { }
    }

    fun buildItems(context: Context): List<View> {
        val title = TextView(context).apply {
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            text = "안내 목소리 (네이버 길안내)"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(40, 10, 40, 2)
        }
        val hint = TextView(context).apply {
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            text = "폰에 설치된 한국어 목소리를 눌러서 들어보고 고를 수 있어요"
            setTextColor(Color.parseColor("#999999"))
            textSize = 12f
            setPadding(40, 0, 40, 4)
        }
        val value = TextView(context).apply {
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            setTextColor(Color.WHITE)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        fun refresh() { value.text = shortName(saved(context)) }
        refresh()
        val button = Button(context).apply {
            text = "선택"
            setOnClickListener { showPicker(context) { refresh() } }
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(40, 4, 40, 8)
            addView(value)
            addView(button)
        }
        return listOf(title, hint, row)
    }

    private fun shortName(name: String) = name.removePrefix("ko-kr-x-").removePrefix("ko-KR-").take(24)

    private fun showPicker(context: Context, onSaved: () -> Unit) {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context) { status ->
            val engine = tts
            if (status != TextToSpeech.SUCCESS || engine == null) {
                Toast.makeText(context, "음성 엔진을 열 수 없어요.", Toast.LENGTH_SHORT).show()
                engine?.shutdown()
                return@TextToSpeech
            }
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            val voices: List<Voice> = try {
                engine.voices.orEmpty().filter { it.locale.language == Locale.KOREAN.language }
                    .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.name }))
            } catch (_: Exception) { emptyList() }
            if (voices.isEmpty()) {
                Toast.makeText(context, "설치된 한국어 목소리가 없어요. 폰 설정의 텍스트 음성 변환에서 한국어 음성 데이터를 설치해주세요.", Toast.LENGTH_LONG).show()
                engine.shutdown()
                return@TextToSpeech
            }
            val labels = voices.mapIndexed { i, v ->
                "목소리 ${i + 1} · ${if (v.isNetworkConnectionRequired) "온라인" else "오프라인"}\n${v.name}"
            }.toTypedArray()
            var checked = voices.indexOfFirst { it.name == saved(context) }
            var pending = if (checked >= 0) voices[checked].name else ""
            val dialog = AlertDialog.Builder(context, R.style.RoundedDialogTheme)
                .setTitle("안내 목소리 고르기 (눌러서 들어보세요)")
                .setSingleChoiceItems(labels, checked) { _, which ->
                    pending = voices[which].name
                    engine.voice = voices[which]
                    val p = Bundle().apply {
                        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f)
                    }
                    engine.speak(SAMPLE, TextToSpeech.QUEUE_FLUSH, p, "sample")
                }
                .setNegativeButton("취소", null)
                .setPositiveButton("이 목소리로 저장", null)
                .create()
            dialog.setOnDismissListener { engine.stop(); engine.shutdown() }
            dialog.show()
            dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_212121_rounded)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (pending.isEmpty()) {
                    Toast.makeText(context, "목소리를 먼저 눌러서 골라주세요.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                    .edit().putString(PREF_KEY, pending).apply()
                onSaved()
                Toast.makeText(context, "저장했어요. 다음 안내부터 이 목소리로 말해요.", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
    }
}
