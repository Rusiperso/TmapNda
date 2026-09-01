package com.tmap.nda.navdy

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.tmap.nda.NavLogger

/**
 * 페어링되어 있는 블루투스 기기 목록에서 이름에 "Navdy"가 들어간 기기를 찾아
 * NavdySender로 자동 연결.
 *
 * 사전 조건: 해당 기기가 안드로이드 블루투스 설정에서 이미 페어링되어 있어야 함
 * (최초 1회는 사용자가 직접 안드로이드 블루투스 설정에서 페어링해야 함 - 이건
 * 표준 안드로이드 페어링 UI라 앱에서 대신 못 함).
 */
object NavdyAutoConnect {

    // v: 재억 요청(2026-08-27) - 원래 Activity에서 1회만 호출되던 걸, UdpSenderService의
    // 백그라운드 재연결 루프에서도 주기적으로 호출할 수 있도록 Context로 일반화. #문제시 원복
    fun tryConnect(context: Context) {
        try {
            // v: 재억 요청(2026-08-28) - "성공했으면 그걸로 끝, 끊겼을 때 왜만 남기면 된다"는
            // 지적. 연결 성공 로그는 NavdySender.connect()가 성공 시점에 이미 1번 남기므로,
            // 여기선 계속 조용히 넘어가기만 하면 됨(연결 유지 중이라고 계속 남기지 않음). #문제시 원복
            if (NavdySender.isConnected()) return
            // v: 재억 제보(2026-08-27, 실기기 로그로 확인) - BLUETOOTH_CONNECT만 확인했었는데,
            // 연결 전 cancelDiscovery() 호출에 BLUETOOTH_SCAN도 별도로 필요함(SecurityException으로
            // 확인됨: "Need android.permission.BLUETOOTH_SCAN ... cancelDiscovery"). 둘 다 확인. #문제시 원복
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val missing = listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
                    .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isNotEmpty()) {
                    NavLogger.d(context, "[Navdy] 권한 없음(${missing.joinToString()}) - 자동연결 건너뜀")
                    return
                }
            }
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null || !adapter.isEnabled) {
                NavLogger.d(context, "[Navdy] 블루투스 꺼져있음 - 자동연결 건너뜀")
                return
            }
            val bonded = adapter.bondedDevices
            val navdyDevice = bonded?.firstOrNull { device ->
                val name = device.name ?: ""
                name.contains("Navdy", ignoreCase = true)
            }
            if (navdyDevice != null) {
                NavLogger.d(context, "[Navdy] 페어링된 기기 발견: ${navdyDevice.name} - 연결 시도")
                NavdySender.connect(context, navdyDevice)
            } else {
                NavLogger.d(context, "[Navdy] 페어링된 Navdy 기기 없음 (블루투스 설정에서 먼저 페어링 필요)")
            }
        } catch (e: Exception) {
            NavLogger.e(context, "[Navdy] 자동연결 시도 실패: ${e.message}")
        }
    }
}
