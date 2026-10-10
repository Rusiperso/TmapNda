package com.tmap.nda

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.tmap.nda.naver.NaverDirectionsClient

/**
 * 메뉴 → 설정 → "길안내 선택" 탭의 내용.
 * 카카오로 길 안내 / 네이버로 길 안내 중 하나를 고르면 그 엔진의 키를 넣는 팝업이 뜨고,
 * 키를 다 넣고 저장하면 앱이 다시 시작되면서 고른 엔진이 적용된다.
 */
object GuideEngineSettings {

    fun buildItems(context: Context): List<View> {
        val dp = { v: Int -> (v * context.resources.displayMetrics.density).toInt() }

        val status = TextView(context).apply {
            setTextColor(Color.parseColor("#BBBBBB"))
            textSize = 13f
            setPadding(40, 4, 40, 16)
        }
        val kakaoBtn = choiceButton(context, "카카오로 길 안내")
        val naverBtn = choiceButton(context, "네이버로 길 안내")

        fun refresh() {
            val naver = GuideEngine.isNaver(context)
            style(kakaoBtn, !naver, AppAccent.KAKAO_YELLOW)
            style(naverBtn, naver, AppAccent.NAVER_GREEN)
            val keysOk = if (naver) NaverDirectionsClient.hasKeys(context) else hasKakaoNativeKey(context)
            status.text = "현재: ${if (naver) "네이버" else "카카오"} 길안내 · 키 ${if (keysOk) "입력됨" else "입력 필요"}\n" +
                "바꾸거나 키를 고치려면 아래 버튼을 눌러주세요. 저장하면 앱이 다시 시작돼요."
        }

        kakaoBtn.setOnClickListener { showKeyDialog(context, naver = false) }
        naverBtn.setOnClickListener { showKeyDialog(context, naver = true) }
        refresh()

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(40, 8, 40, 8)
            addView(kakaoBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(naverBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
        }
        return listOf(row, status)
    }

    private fun hasKakaoNativeKey(context: Context) =
        !context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).getString("kakao_native_app_key", "").isNullOrBlank()

    private fun choiceButton(context: Context, label: String) = TextView(context).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setPadding(0, 26, 0, 26)
        isClickable = true
    }

    private fun style(v: TextView, selected: Boolean, accent: String) {
        v.background = GradientDrawable().apply {
            cornerRadius = 36f
            setColor(Color.parseColor(if (selected) accent else "#1AFFFFFF"))
        }
        v.setTextColor(if (selected) Color.parseColor("#212121") else Color.parseColor("#DDDDDD"))
        v.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    /** 고른 엔진의 키를 넣는 팝업. 저장하면 엔진을 바꾸고 앱을 다시 시작한다. */
    private fun showKeyDialog(context: Context, naver: Boolean) {
        val prefs = context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
        val dp = { v: Int -> (v * context.resources.displayMetrics.density).toInt() }

        fun field(hint: String, value: String): EditText = EditText(context).apply {
            this.hint = hint
            setText(value)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#888888"))
        }
        fun note(text: String) = TextView(context).apply {
            this.text = text
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 12f
            setPadding(0, dp(2), 0, dp(10))
        }

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }

        val kakaoNative = field("카카오 네이티브 앱 키 (길안내용)", prefs.getString("kakao_native_app_key", "").orEmpty())
        val naverId = field("네이버 Key ID (Client ID)", NaverDirectionsClient.keyId(context))
        val naverSecret = field("네이버 Key Secret", NaverDirectionsClient.keySecret(context))

        if (naver) {
            box.addView(naverId)
            box.addView(note("네이버 클라우드 플랫폼 Application에서 Directions와 Dynamic Map을 체크하고, Android 패키지 이름에 이 앱의 패키지 이름(${context.packageName})을 등록하세요."))
            box.addView(naverSecret)
            box.addView(note("장소 검색은 티맵 검색을 씁니다(카카오 키 불필요)."))
        } else {
            box.addView(kakaoNative)
            box.addView(note("카카오 콘솔 [플랫폼 키]의 '네이티브 앱 키'만 넣으세요."))
            box.addView(note("장소 검색은 티맵 검색을 씁니다(카카오 REST 키는 필요 없어요)."))
        }

        val dialog = AlertDialog.Builder(context, R.style.RoundedDialogTheme)
            .setTitle(if (naver) "네이버로 길 안내" else "카카오로 길 안내")
            .setView(box)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장하고 재시작", null)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_212121_rounded)
        // 키가 비었을 때 팝업이 닫히지 않도록 직접 처리한다.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (naver) {
                val id = naverId.text.toString().trim()
                val secret = naverSecret.text.toString().trim()
                if (id.isEmpty() || secret.isEmpty()) {
                    Toast.makeText(context, "Key ID와 Key Secret을 모두 입력해주세요.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                NaverDirectionsClient.saveKeys(context, id, secret)
            } else {
                val nativeKey = kakaoNative.text.toString().trim()
                if (nativeKey.isEmpty()) {
                    Toast.makeText(context, "카카오 네이티브 앱 키를 입력해주세요.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs.edit()
                    .putString("kakao_native_app_key", nativeKey)
                    .apply()
            }
            GuideEngine.set(context, if (naver) GuideEngine.NAVER else GuideEngine.KAKAO)
            dialog.dismiss()
            Toast.makeText(context, "저장했어요. 앱을 다시 시작합니다.", Toast.LENGTH_SHORT).show()
            AppRestart.restart(context)
        }
    }
}
