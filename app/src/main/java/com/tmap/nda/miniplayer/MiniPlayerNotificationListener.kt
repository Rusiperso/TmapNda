package com.tmap.nda.miniplayer

import android.service.notification.NotificationListenerService

/**
 * 알림 내용은 다루지 않고, 안드로이드 "알림 접근" 권한을 등록하기 위한 최소 서비스.
 * 이 서비스가 매니페스트에 등록되고 사용자가 시스템 설정에서 권한을 허용해야만,
 * MediaSessionManager.getActiveSessions()로 "지금 재생 중인 음악" 정보를 읽을 수 있음
 * (미니 플레이어 기능의 전제조건). #문제시 원복
 */
class MiniPlayerNotificationListener : NotificationListenerService()
