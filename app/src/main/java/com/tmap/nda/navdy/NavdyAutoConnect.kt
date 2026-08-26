package com.tmap.nda.navdy

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
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

    fun tryConnect(activity: Activity) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val granted = ContextCompat.checkSelfPermission(
                    activity, Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    NavLogger.d(activity, "[Navdy] BLUETOOTH_CONNECT 권한 없음 - 자동연결 건너뜀")
                    return
                }
            }
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null || !adapter.isEnabled) {
                NavLogger.d(activity, "[Navdy] 블루투스 꺼져있음 - 자동연결 건너뜀")
                return
            }
            val bonded = adapter.bondedDevices
            val navdyDevice = bonded?.firstOrNull { device ->
                val name = device.name ?: ""
                name.contains("Navdy", ignoreCase = true)
            }
            if (navdyDevice != null) {
                NavLogger.d(activity, "[Navdy] 페어링된 기기 발견: ${navdyDevice.name} - 연결 시도")
                NavdySender.connect(navdyDevice)
            } else {
                NavLogger.d(activity, "[Navdy] 페어링된 Navdy 기기 없음 (블루투스 설정에서 먼저 페어링 필요)")
            }
        } catch (e: Exception) {
            NavLogger.e(activity, "[Navdy] 자동연결 시도 실패: ${e.message}")
        }
    }
}
