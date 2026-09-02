package com.tmap.nda

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
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
 *
 * v: 재억 제보(2026-09-03) - "앱 종료를 눌러 화면이 다 닫힌 뒤"에도 이 워치독만 계속
 * 돌면서 9분 내내 멈춤(최대 25초)을 보고하고 있었음. 화면이 아예 없는데 화면 멈춤을
 * 보고한 것. 원인은 앱을 끄면 안드로이드가 그 프로세스를 캐시 상태로 내려 CPU를 거의
 * 안 주는데, 워치독은 그 지연을 "메인 스레드가 멈췄다"로 오해했기 때문. 화면이 하나도
 * 안 떠 있는 동안은 감지 자체를 건너뛴다(화면이 없으면 멈출 화면도 없음). #문제시 원복
 */
object MainThreadWatchdog {
    private const val DISCORD_REPORT_THRESHOLD_MS = 5000L

    @Volatile private var started = false
    @Volatile private var visibleActivityCount = 0
    private val handler = Handler(Looper.getMainLooper())
    private var lastTickAt = 0L

    fun ensureStarted(context: Context) {
        if (started) return
        started = true
        trackForeground(context)
        lastTickAt = System.currentTimeMillis()
        val runnable = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                val sinceLast = now - lastTickAt
                // 정상이면 300ms 근처여야 함. 훨씬 크면(예: 1초 이상) 그 사이 메인
                // 스레드가 멈춰있었다는 뜻 - 그 경우만 눈에 띄게 남기고, 정상 틱은
                // 로그 용량 아끼려고 5초에 한 번만 남김.
                if (sinceLast > 1000L && visibleActivityCount > 0) {
                    NavLogger.e(context, "[전역워치독] 메인스레드 멈춤 감지! 공백=${sinceLast}ms")
                    // 1~2초짜리 짧은 멈춤은 GC 등으로 흔하고 체감도 거의 없어서,
                    // 디코 알림은 실제로 "멈췄다"고 느낄 5초 이상만 보냄(로그에는 다 남음).
                    if (sinceLast >= DISCORD_REPORT_THRESHOLD_MS) {
                        DiscordReporter.reportMainThreadFreeze(context, sinceLast)
                    }
                } else if (sinceLast <= 1000L && now % 5000L < 300L) {
                    NavLogger.dIfChanged(context, "watchdog", "[전역워치독] 정상")
                }
                lastTickAt = now
                handler.postDelayed(this, 300L)
            }
        }
        handler.post(runnable)
    }

    private fun trackForeground(context: Context) {
        val app = context.applicationContext as? Application ?: return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                visibleActivityCount++
            }

            override fun onActivityStopped(activity: Activity) {
                if (visibleActivityCount > 0) visibleActivityCount--
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
