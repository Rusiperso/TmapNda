package com.tmap.nda

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build

/**
 * 즐겨찾기(QuickSlot)를 홈화면 바로가기로 고정(pin)해서, 아이콘 한 번 눌러 바로 그
 * 목적지로 안내 시작할 수 있게 하는 헬퍼.
 *
 * - "홈화면에 추가" 요청 시 requestPinShortcut() 호출
 * - 즐겨찾기 내용이 앱 안에서 수정되면 updateShortcuts()로 이미 깔린 바로가기도 같이 갱신
 * - 삭제되면 disableShortcuts()로 눌렀을 때 안내 메시지 뜨게 처리
 * - 바로가기를 눌렀을 때(MainActivity.onNewIntent 등에서 처리), 이미 다른 목적지로
 *   안내 중이면 그냥 덮어쓰지 않고 "OO(으)로 갈까요?" 확인 팝업을 먼저 띄움
 */
object QuickSlotShortcutHelper {
    const val EXTRA_SHORTCUT_SLOT = "nda_shortcut_slot"
    private const val SHORTCUT_ID_PREFIX = "quickslot_"

    private fun shortcutId(slot: String) = "$SHORTCUT_ID_PREFIX$slot"

    /** 홈화면에 이 즐겨찾기를 고정 요청. 사용자가 확인 팝업에서 승인해야 실제로 깔림(OS 표준 동작). */
    fun requestPin(context: Context, slot: String, entry: HistoryEntry) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val shortcutManager = context.getSystemService(ShortcutManager::class.java) ?: return
            if (!shortcutManager.isRequestPinShortcutSupported) {
                NavLogger.d(context, "[바로가기] 이 런처는 홈화면 고정을 지원 안 함")
                return
            }
            val info = buildShortcutInfo(context, slot, entry)
            shortcutManager.requestPinShortcut(info, null)
            NavLogger.d(context, "[바로가기] 홈화면 고정 요청: slot=$slot name=${entry.name}")
        } catch (e: Exception) {
            NavLogger.e(context, "[바로가기] 고정 요청 실패: ${e.message}")
        }
    }

    /** 즐겨찾기 내용이 앱 안에서 바뀌었을 때 호출 - 이미 깔린 바로가기가 있으면 자동으로 내용 갱신. */
    fun syncAfterUpdate(context: Context, slot: String, entry: HistoryEntry?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        try {
            val shortcutManager = context.getSystemService(ShortcutManager::class.java) ?: return
            val id = shortcutId(slot)
            val alreadyPinned = shortcutManager.pinnedShortcuts.any { it.id == id }
            if (!alreadyPinned) return
            if (entry == null) {
                // v: 재억 요청 - 즐겨찾기를 삭제해도 바로가기 아이콘 자체는 안드로이드 표준
                // 동작상 자동으로 안 없어짐. 대신 눌렀을 때 "더 이상 유효하지 않음" 안내가
                // 뜨도록 비활성화 처리. #문제시 원복
                shortcutManager.disableShortcuts(
                    listOf(id),
                    context.getString(R.string.app_name) + ": 삭제된 즐겨찾기입니다"
                )
                NavLogger.d(context, "[바로가기] 즐겨찾기 삭제됨 - 바로가기 비활성화: slot=$slot")
            } else {
                val info = buildShortcutInfo(context, slot, entry)
                shortcutManager.updateShortcuts(listOf(info))
                NavLogger.d(context, "[바로가기] 즐겨찾기 수정됨 - 바로가기 자동 동기화: slot=$slot name=${entry.name}")
            }
        } catch (e: Exception) {
            NavLogger.e(context, "[바로가기] 동기화 실패: ${e.message}")
        }
    }

    private fun buildShortcutInfo(context: Context, slot: String, entry: HistoryEntry): ShortcutInfo {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(EXTRA_SHORTCUT_SLOT, slot)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return ShortcutInfo.Builder(context, shortcutId(slot))
            .setShortLabel(entry.name.take(10).ifBlank { "즐겨찾기" })
            .setLongLabel("${entry.name} 안내 시작")
            .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(intent)
            .build()
    }

    /**
     * 바로가기로 앱이 열렸을 때 호출. 이미 다른 목적지로 안내 중이면 팝업으로 확인부터 받고,
     * 아니면 바로 안내 시작.
     */
    fun handleShortcutIntent(
        activity: android.app.Activity,
        intent: Intent?,
        isCurrentlyGuiding: () -> Boolean,
        startGuideTo: (HistoryEntry) -> Unit
    ) {
        val slot = intent?.getStringExtra(EXTRA_SHORTCUT_SLOT) ?: return
        val entry = QuickSlotStore.get(activity, slot) ?: run {
            NavLogger.e(activity, "[바로가기] 실행됐지만 즐겨찾기 데이터 없음: slot=$slot")
            android.widget.Toast.makeText(activity, "삭제된 즐겨찾기입니다", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (isCurrentlyGuiding()) {
            android.app.AlertDialog.Builder(activity)
                .setMessage("${entry.name}(으)로 갈까요?")
                .setPositiveButton("예") { _, _ -> startGuideTo(entry) }
                .setNegativeButton("아니오", null)
                .show()
        } else {
            startGuideTo(entry)
        }
    }
}
