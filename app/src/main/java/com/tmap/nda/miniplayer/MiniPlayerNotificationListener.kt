package com.tmap.nda.miniplayer

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 알림 내용은 다루지 않고, 안드로이드 "알림 접근" 권한을 등록하기 위한 최소 서비스.
 * 이 서비스가 매니페스트에 등록되고 사용자가 시스템 설정에서 권한을 허용해야만,
 * MediaSessionManager.getActiveSessions()로 "지금 재생 중인 음악" 정보를 읽을 수 있음
 * (미니 플레이어 기능의 전제조건). #문제시 원복
 *
 * v: 재억 제보(2026-08-30, 실기기로 확인 - "카카오 화면은 Festival로 정확한데 티맵
 * 화면은 Poison에서 안 바뀜") - MediaController.metadata(공식 세션 API)를 직접 다시
 * 읽어봐도 18초 넘게 옛 곡 정보가 그대로였음(우리 캐싱 문제가 아니라 앱/시스템 쪽
 * 지연). 반면 카카오는 정확했던 걸로 봐서, 화면(알림창)에 실제로 뜨는 알림 텍스트를
 * 직접 읽는 방식을 쓰는 걸로 추정됨 - 그게 훨씬 즉각적으로 갱신되기 때문. 여기서도
 * 알림 내용(제목/부제)을 같이 읽어서 NotificationMediaCache에 저장해두고,
 * MiniPlayerManager가 MediaSession 메타데이터보다 이걸 우선 사용하도록 함. #문제시 원복
 */
class MiniPlayerNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val extras = sbn.notification?.extras ?: return
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            if (!title.isNullOrBlank()) {
                NotificationMediaCache.update(sbn.packageName, title, text)
            }
        } catch (e: Exception) {
            // 알림 파싱 실패는 조용히 무시 - MediaSession 메타데이터로 폴백됨
        }
    }
}
