package com.tmap.nda

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings

// v: 사용자 요청(재억, 2026-08-12) - 클러스터 세션 기능 조사용, open_cover apk 분석 참고.
// Android Auto(gearhead) 앱이 지금 화면에 떠 있는지(포그라운드)를 판별하는 헬퍼.
// UsageStatsManager는 런타임 권한이 아니라 설정 > 앱 > 특별한 접근 > "사용정보 접근"에서
// 사용자가 수동으로 허용해야 동작함. 권한이 없으면 isAndroidAutoForeground()는 항상 false.
// #문제시 원복
object AutoWatchHelper {

    const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"

    /** 사용정보 접근 권한이 허용돼있는지 확인 */
    fun hasUsageAccessPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** 사용정보 접근 설정 화면으로 이동시키는 Intent */
    fun usageAccessSettingsIntent(): Intent {
        return Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    }

    /**
     * 최근 [windowMs] 동안의 포그라운드 이벤트를 훑어서 가장 마지막으로 MOVE_TO_FOREGROUND된
     * 패키지가 Android Auto(gearhead)인지 확인. 권한이 없거나 이벤트가 없으면 false.
     */
    fun isAndroidAutoForeground(context: Context, windowMs: Long = 10_000L): Boolean {
        if (!hasUsageAccessPermission(context)) return false
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return false
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - windowMs, now)
            val event = UsageEvents.Event()
            var lastForegroundPackage: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
                ) {
                    lastForegroundPackage = event.packageName
                }
            }
            lastForegroundPackage == ANDROID_AUTO_PACKAGE
        } catch (e: Exception) {
            NavLogger.e(context, "[AutoWatchHelper] AA 포그라운드 감지 예외: ${e.message}")
            false
        }
    }
}
