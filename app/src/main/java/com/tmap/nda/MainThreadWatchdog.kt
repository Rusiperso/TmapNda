package com.tmap.nda

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * v: 재억 재제보(2026-08-30, "앱 켤 때 / 카카오 길안내 종료할 때 등 여러 상황에서 멈춘다.
 * 15초짜리 워치독으로는 못 잡는다") - 기존 워치독은 MapActivity.onCreate 직후 15초만
 * 돌고 꺼졌음. 특정 화면(Activity)에 종속되지 않고, 앱 프로세스가 살아있는 내내(15초
 * 제한 없이) 계속 도는 전역 워치독으로 교체. 티맵 화면이든 카카오 길안내 화면이든
 * 같은 프로세스의 같은 메인 스레드를 공유하므로, 어느 화면에서 멈추든 이걸로 다
 * 잡힘. 재억님이 멈춘 직후 서둘러 로그를 뽑으실 필요 없이, 편하실 때 아무 때나
 * 로그를 뽑아도 이미 그 순간이 기록돼 있음. #문제시 원복
 */
object MainThreadWatchdog {
    @Volatile private var started = false
    private val handler = Handler(Looper.getMainLooper())
    private var lastTickAt = 0L

    fun ensureStarted(context: Context) {
        if (started) return
        started = true
        lastTickAt = System.currentTimeMillis()
        val runnable = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                val sinceLast = now - lastTickAt
                // 정상이면 300ms 근처여야 함. 훨씬 크면(예: 1초 이상) 그 사이 메인
                // 스레드가 멈춰있었다는 뜻 - 그 경우만 눈에 띄게 남기고, 정상 틱은
                // 로그 용량 아끼려고 5초에 한 번만 남김.
                if (sinceLast > 1000L) {
                    NavLogger.e(context, "[전역워치독] 메인스레드 멈춤 감지! 공백=${sinceLast}ms")
                    DiscordReporter.reportMainThreadFreeze(context, sinceLast)
                } else if (now % 5000L < 300L) {
                    NavLogger.dIfChanged(context, "watchdog", "[전역워치독] 정상")
                }
                lastTickAt = now
                handler.postDelayed(this, 300L)
            }
        }
        handler.post(runnable)
    }
}
