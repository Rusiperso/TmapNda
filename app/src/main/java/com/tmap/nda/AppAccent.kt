package com.tmap.nda

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * 화면에 따라 달라지는 "포인트 색"(선택된 칸·시작 버튼 같은 박스의 배경색). 글씨 색은 바꾸지 않는다.
 *  - 티맵 화면: 파란색
 *  - 카카오 안내 화면: 노란색(원래 색)
 *  - 네이버 안내 화면: 네이버 녹색
 */
object AppAccent {
    const val NAVER_GREEN = "#03C75A"
    const val KAKAO_YELLOW = "#FFD54F"
    /** 티맵 하단 "주행종료" 버튼과 같은 진한 파랑. */
    const val TMAP_BLUE = "#3D83FF"

    /** [hex]와 같은 색을 Int로(글씨 색에 쓰기 편하게). */
    fun color(context: Context): Int = android.graphics.Color.parseColor(hex(context))

    fun hex(context: Context): String {
        var c: Context = context
        while (c is ContextWrapper && c !is Activity) c = c.baseContext
        return when (c) {
            is NaverNaviActivity -> NAVER_GREEN
            is KakaoNaviActivity -> KAKAO_YELLOW
            else -> TMAP_BLUE
        }
    }
}

/** 설정을 바꾼 뒤 앱을 처음부터 다시 켠다(엔진 키를 새로 반영하려고). */
object AppRestart {
    fun restart(context: Context) {
        val app = context.applicationContext
        val target = Intent(app, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        // 별도 프로세스의 중간 화면이 본 프로세스가 끝난 뒤에 앱을 다시 켠다.
        app.startActivity(Intent(app, RestartTrampolineActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("target", target)
        })
        Handler(Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 700L)
    }
}
