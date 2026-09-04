package com.tmap.nda.nmirror

import android.service.notification.StatusBarNotification
import com.tmap.nda.NavLogger

/**
 * 순정 티맵 앱이 지금 길안내 중인지를 알림으로 감지한다.
 *
 * v: 재억 결정(2026-09-04) - 계기판 안내의 우선순위는 **1순위 순정 티맵, 2순위 카카오(이 앱)**.
 * 둘이 동시에 안내하면 nMirror에는 우선순위 개념이 없어서 서로 덮어쓰고, 계기판이 1초 단위로
 * 번갈아 바뀐다. 그래서 순정 티맵이 안내 중인 동안에는 [NMirrorSender]가 전송을 쉬고 자리를
 * 비켜준다. 티맵 안내가 끝나면 nMirror도 5초 뒤 "안내 종료"로 보므로 자연스럽게 우리가 이어받는다.
 *
 * 감지는 티맵이 안내 중에 계속 띄워두는 상주 알림(ongoing)의 등장/사라짐으로 한다. 시간 제한을
 * 두지 않는 이유는, 긴 직진 구간에서는 알림 내용이 한동안 안 바뀔 수 있어 "몇 초 안에 갱신"
 * 기준을 쓰면 안내 중인데도 끝난 것으로 오판하기 때문. #문제시 원복: 이 파일과 아래 두
 * 호출부(MiniPlayerNotificationListener, NMirrorSender.send의 양보 분기)만 지우면 됨
 */
object TmapGuidanceWatch {

    // nMirror가 후킹 대상으로 삼는 것과 동일한 두 패키지(일반 티맵 / 티맵 for 기아).
    private val TMAP_PACKAGES = setOf("com.skt.skaf.l001mtm091", "com.skt.tmap.ku")

    @Volatile private var guiding = false

    fun isGuiding(): Boolean = guiding

    /** 리스너가 붙는 순간 이미 떠 있는 알림들로 초기 상태를 맞춘다(앱이 도중에 재시작된 경우). */
    fun syncFrom(active: Array<StatusBarNotification>?) {
        val found = active?.any { it.packageName in TMAP_PACKAGES && it.isOngoing } == true
        setGuiding(found, "기존 알림 확인")
    }

    fun onPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in TMAP_PACKAGES) return
        if (sbn.isOngoing) setGuiding(true, "티맵 안내 알림 뜸")
    }

    fun onRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName !in TMAP_PACKAGES) return
        setGuiding(false, "티맵 안내 알림 사라짐")
    }

    private fun setGuiding(value: Boolean, reason: String) {
        if (guiding == value) return
        guiding = value
        NavLogger.d(
            if (value) "[우선순위] 순정 티맵이 안내 중($reason) - 계기판을 양보하고 카카오 안내 전송을 쉼"
            else "[우선순위] 순정 티맵 안내 끝($reason) - 카카오 안내를 계기판으로 다시 보냄"
        )
    }

    fun statusForLog(): String = if (guiding) "티맵안내중(양보)" else "티맵안내없음"
}
