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

    // 권한 미허용 상태 로그 스로틀용(5분). #문제시 원복
    @Volatile private var lastPermissionLogTime = 0L

    // 기기 통신 창구 목록을 앱 실행당 한 번만 기록하기 위한 플래그. #문제시 원복
    @Volatile private var navdyServiceDumpDone = false

    // v: 재억 요청(2026-08-27) - 원래 Activity에서 1회만 호출되던 걸, UdpSenderService의
    // 백그라운드 재연결 루프에서도 주기적으로 호출할 수 있도록 Context로 일반화. #문제시 원복
    fun tryConnect(context: Context) {
        try {
            // v: 재억 제보(2026-09-04) - 초기 화면의 "Navdy 연결" 체크박스를 꺼도 실제로는
            // 꺼지지 않고 있었음. 이 함수를 부르는 곳 세 군데(앱 시작 / 안내 시작 /
            // UdpSenderService의 15초 루프)가 전부 그 설정을 안 보고 무조건 불렀기 때문.
            // nMirror에도 나브디로 보내는 기능이 있어서, 둘 다 켜지면 같은 기기에 블루투스
            // 연결을 두 개 여는 셈이라 서로 끊는다(같은 기기에 두 번째 연결을 여는 시도
            // 자체가 기존 연결을 끊는 것이 v19.2.94 로그로 확인됨). 그래서 이 스위치가
            // 실제로 꺼져야 한다. #문제시 원복
            val enabled = context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                .getBoolean("REQ_NAVDY", false)
            if (!enabled) return
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
                    // v: 재억 제보(2026-09-02) - 이 줄이 15초마다 계속 찍혀서 로그 파일을
                    // 가득 채우고 있었음(권한이 안 켜지는 한 영원히 반복되는 상태 로그라
                    // 매번 남길 이유가 없음). 5분에 한 번만 남김. #문제시 원복
                    val now = System.currentTimeMillis()
                    if (now - lastPermissionLogTime > 5 * 60 * 1000L) {
                        lastPermissionLogTime = now
                        NavLogger.d(context, "[Navdy] 권한 없음(${missing.joinToString()}) - 자동연결 건너뜀 (설정 > 앱 > TmapNda > 권한 > 주변 기기에서 허용 필요)")
                    }
                    return
                }
            }
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null || !adapter.isEnabled) {
                NavLogger.d(context, "[Navdy] 블루투스 꺼져있음 - 자동연결 건너뜀")
                return
            }
            // 실제 연결은 NavdySender가 자기 스레드에서 10초 간격으로 담당한다. 여기서는
            // 시작만 시키면 되고, 이미 시작돼 있으면 내부에서 무시되므로 계속 불려도 안전함. #문제시 원복
            val bonded = adapter.bondedDevices
            val navdyDevice = bonded?.firstOrNull { device ->
                val name = device.name ?: ""
                name.contains("Navdy", ignoreCase = true)
            }
            if (navdyDevice == null) {
                NavLogger.d(context, "[Navdy] 페어링된 Navdy 기기 없음 (블루투스 설정에서 먼저 페어링 필요)")
            } else if (!navdyServiceDumpDone) {
                // 기기가 어떤 통신 창구를 열어뒀는지 앱 실행당 한 번만 기록한다. 우리가 거는
                // 503b9411-… 이 목록에 없으면 그 기기는 다른 규격을 쓴다는 뜻이라, 안 붙을 때
                // 여기부터 보면 된다(재억 기기는 이 목록에 503b9411이 있는 것을 확인함). #문제시 원복
                navdyServiceDumpDone = true
                try {
                    val uuids = navdyDevice.uuids
                    if (uuids.isNullOrEmpty()) {
                        NavLogger.e(context, "[Navdy][기기창구조사] 기기가 알려주는 통신 창구 목록이 비어있음(폰이 아직 조회를 못 했거나 기기가 안 알려줌)")
                    } else {
                        NavLogger.e(context, "[Navdy][기기창구조사] ${navdyDevice.name} 이 열어둔 창구 ${uuids.size}개:")
                        uuids.forEach { NavLogger.e(context, "[Navdy][기기창구조사]   ${it.uuid}") }
                    }
                } catch (e: Exception) {
                    NavLogger.e(context, "[Navdy][기기창구조사] 실패: ${e.message}")
                }
            }
            NavdySender.start(context)
        } catch (e: Exception) {
            NavLogger.e(context, "[Navdy] 자동연결 시도 실패: ${e.message}")
        }
    }
}
