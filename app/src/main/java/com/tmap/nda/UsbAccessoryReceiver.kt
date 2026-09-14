package com.tmap.nda

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// v: 재억 요청(2026-09-15) - 차량(AA)에 USB로 연결/해제되는 걸 감지해서
// BlackScreenOverlayService를 켜고 끔. USB_STATE는 안드로이드 시스템만 보낼 수 있는
// protected broadcast라 매니페스트 등록은 8+에서 막힐 수 있어서(암시적 브로드캐스트 제한),
// 항상 떠있는 UdpSenderService에서 registerReceiver로 직접 등록해서 씀(엔미러도 동일 방식).
// extra "accessory"가 true일 때만 AA(액세서리 모드) 연결로 판단 - 그냥 PC/충전기 연결과
// 구분하기 위함(그럴 땐 화면을 가릴 필요 없음).
class UsbAccessoryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.hardware.usb.action.USB_STATE") return
        // 기본 꺼짐 - 이 기능을 원하는 사람만 켜서 쓰게(opt-in). 안 켠 사람은 아무 영향 없음.
        val enabled = context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
            .getBoolean("black_screen_on_usb_connect", false)
        if (!enabled) return
        val isAccessory = intent.getBooleanExtra("accessory", false)
        val serviceIntent = Intent(context, BlackScreenOverlayService::class.java)
        serviceIntent.action = if (isAccessory) {
            BlackScreenOverlayService.ACTION_SHOW
        } else {
            BlackScreenOverlayService.ACTION_HIDE
        }
        context.startService(serviceIntent)
    }
}
