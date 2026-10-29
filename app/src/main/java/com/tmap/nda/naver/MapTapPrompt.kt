package com.tmap.nda.naver

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.tmap.nda.HistoryEntry
import com.tmap.nda.NavLogger
import com.tmap.nda.R

/**
 * 지도를 한 번 터치하면 그 자리를 목적지로 "OO으로 갈까요?" 하고 물어보고, "길 안내"를 누르면 [onGo]로 넘긴다.
 * 안내 중이라 [onAddWaypoint]가 있으면 "경유지 추가 / 새 목적지 / 취소" 셋 중에서 고르게 한다.
 * 이름은 폰 안 행안부 주소에서 가장 가까운 건물로 붙인다(없으면 "지도에서 고른 곳").
 */
object MapTapPrompt {
    private var showing = false

    fun ask(
        activity: Activity, lat: Double, lon: Double,
        onAddWaypoint: ((HistoryEntry) -> Unit)? = null,
        onRemoveWaypoint: (() -> Unit)? = null,
        onGo: (HistoryEntry) -> Unit
    ) {
        if (showing || activity.isFinishing || activity.isDestroyed) return
        showing = true
        Thread {
            val near = try { OfflinePlaceSearch.nearestAddress(activity, lat, lon) } catch (e: Throwable) { null }
            Handler(Looper.getMainLooper()).post {
                if (activity.isFinishing || activity.isDestroyed) { showing = false; return@post }
                val name = near?.name ?: "지도에서 고른 곳"
                val addr = near?.addr.orEmpty()
                val entry = HistoryEntry(name, addr, lat, lon)
                NavLogger.d(activity, "[지도터치] ${"%.5f".format(lat)},${"%.5f".format(lon)} → $name $addr")
                val label = if (addr.isBlank()) name else "$name\n$addr"
                val b = android.app.AlertDialog.Builder(activity, R.style.RoundedDialogTheme)
                    .setMessage(label)
                    .setOnDismissListener { showing = false }
                if (onAddWaypoint != null) {
                    // 화면 왼쪽부터 "경유지 추가 / 새 목적지 / 취소(또는 경유지 삭제)" 순서로 보이게 한다.
                    // 안드로이드 팝업은 중립(왼쪽) - 부정(가운데) - 긍정(오른쪽) 순으로 놓으므로 그에 맞춰 배정한다.
                    // 버튼은 셋까지라 삭제가 있을 땐 "취소"는 팝업 바깥을 누르는 걸로 대신한다.
                    b.setTitle("경유지를 추가할까요?")
                        .setNeutralButton("경유지 추가") { _, _ -> onAddWaypoint(entry) }
                        .setNegativeButton("새 목적지") { _, _ -> onGo(entry) }
                    if (onRemoveWaypoint != null) {
                        b.setPositiveButton("경유지 삭제") { _, _ -> onRemoveWaypoint() }
                    } else {
                        b.setPositiveButton("취소", null)
                    }
                } else {
                    b.setNegativeButton("취소", null)
                    b.setTitle("여기로 갈까요?")
                        .setPositiveButton("길 안내") { _, _ -> onGo(entry) }
                }
                b.show()
            }
        }.start()
    }
}
