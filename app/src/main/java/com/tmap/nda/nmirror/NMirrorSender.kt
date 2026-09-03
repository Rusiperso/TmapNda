package com.tmap.nda.nmirror

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.aa.nmirror.service.ITbtService
import com.tmap.nda.NavLogger
import org.json.JSONObject

/**
 * 카카오 길안내 정보를 nMirror에 넘겨서 차량 순정 계기판/HUD에 띄운다.
 *
 * v: 재억 제보(2026-09-03) - 순정 티맵으로 안내하면 차량 순정 HUD에 뜨는데 이 앱의 카카오
 * 안내는 안 뜬다는 문제. nMirror 4.8.0을 뜯어 확인한 경로:
 *   TbtService.sendTbt(json) -> NotiCenter(f5252n) -> service/aa/a.notiTbtInfo
 *   -> androidx.car.app Trip 생성 -> 안드로이드 오토 -> 차량 계기판/HUD
 * 즉 nMirror가 순정 티맵에서 빼낸 정보가 지나가는 바로 그 길에 우리가 직접 넣는다.
 *
 * 알아둘 것 두 가지(둘 다 nMirror 코드에서 확인):
 *  - nMirror는 **마지막 안내를 받은 지 5초 안쪽이면 "안내 중"** 으로 판단한다
 *    (TbtService.i(): now - 마지막수신 < 5000). 그래서 안내 중에는 최소 몇 초에 한 번씩
 *    계속 보내야 하고, 안내를 끝내면 그냥 안 보내면 5초 뒤 자동으로 안내 종료가 된다.
 *  - nMirror 설정의 **"차량 계기판에 길안내 표시"(navi_instruction_to_car_cluster)** 가
 *    꺼져 있으면 받아도 무시한다. 사용자가 켜야 한다.
 *
 * #문제시 원복: KakaoHudBridge의 NMirrorSender.send(...) 호출만 지우면 됨
 */
object NMirrorSender {

    private const val NMIRROR_PACKAGE = "com.aa.nmirror"
    private const val NMIRROR_SERVICE = "com.aa.nmirror.service.TbtService"

    // nMirror가 이 간격 안에 안내를 못 받으면 "안내 종료"로 판단하므로 그보다 짧게 보낸다.
    private const val SEND_INTERVAL_MS = 1000L

    @Volatile private var service: ITbtService? = null
    @Volatile private var binding = false
    private var lastSentAtMs = 0L
    private var sentCount = 0
    private var loggedFirstSend = false
    private var loggedUnavailable = false

    // v: 재억 요청(2026-09-03) - 순정 티맵과 동시에 안내하면 nMirror 쪽에서 서로 덮어써
    // 화면이 왔다갔다 할 수 있어 기본은 꺼두고 초기 화면 체크박스로 켠다. #문제시 원복
    private const val PREFS = "TmapNdaPrefs"
    private const val KEY_ENABLED = "nmirror_cluster_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) unbind(context)
    }

    private fun unbind(context: Context) {
        val bound = service != null || binding
        service = null
        binding = false
        if (bound) {
            try { context.applicationContext.unbindService(connection) } catch (_: Exception) {}
            NavLogger.d("[nMirror] 계기판 전송 꺼짐 - 연결 정리")
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = ITbtService.Stub.asInterface(binder)
            binding = false
            NavLogger.d("[nMirror] 연결됨 - 이제 길안내를 차량 계기판/HUD로 보낼 수 있음")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
            NavLogger.d("[nMirror] 연결 끊김 - 다음 안내 때 다시 붙는다")
        }
    }

    fun send(
        context: Context,
        turnDistanceMeters: Int,
        turnMainText: String,
        roadName: String,
        rgCodeName: String,
        directionAngle: Int,
        remainDistanceMeters: Int,
        remainTimeSeconds: Int,
        destinationName: String,
        hasNext: Boolean,
        nextTurnDistanceMeters: Int,
        nextTurnMainText: String,
        nextRgCodeName: String,
        nextDirectionAngle: Int
    ) {
        if (!isEnabled(context)) return

        val now = System.currentTimeMillis()
        if (now - lastSentAtMs < SEND_INTERVAL_MS) return

        val target = service
        if (target == null) {
            ensureBound(context)
            return
        }

        try {
            val guidePoint = JSONObject()
                .put("nTBTDist", turnDistanceMeters)
                .put("nTBTTime", 0)
                .put("nTBTTurnType", KakaoToTmapTurn.from(rgCodeName, directionAngle))
                .put("szTBTMainText", turnMainText)
                .put("szCrossName", roadName)
                .put("szRoadName", roadName)

            val rgData = JSONObject()
                .put("nTotalDist", remainDistanceMeters)
                .put("nTotalTime", remainTimeSeconds)
                .put("szGoPosName", destinationName)
                .put("stGuidePoint", guidePoint)

            if (hasNext) {
                rgData.put(
                    "stGuidePointNext",
                    JSONObject()
                        .put("nTBTDist", nextTurnDistanceMeters)
                        .put("nTBTTime", 0)
                        .put("nTBTTurnType", KakaoToTmapTurn.from(nextRgCodeName, nextDirectionAngle))
                        .put("szTBTMainText", nextTurnMainText)
                        .put("szCrossName", nextTurnMainText)
                )
            }

            target.sendTbt(rgData.toString(), null)
            lastSentAtMs = now
            sentCount++
            if (!loggedFirstSend) {
                loggedFirstSend = true
                NavLogger.d("[nMirror] 첫 전송 성공: 도로=$roadName 회전=$rgCodeName 거리=${turnDistanceMeters}m (이후 전송은 메모리에만 기록)")
            }
        } catch (e: Exception) {
            NavLogger.e("[nMirror] 전송 실패: ${e.javaClass.simpleName} ${e.message}")
            service = null
        }
    }

    private fun ensureBound(context: Context) {
        if (binding) return
        binding = true
        try {
            val intent = Intent().setComponent(ComponentName(NMIRROR_PACKAGE, NMIRROR_SERVICE))
            val ok = context.applicationContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!ok) {
                binding = false
                if (!loggedUnavailable) {
                    loggedUnavailable = true
                    NavLogger.d("[nMirror] 앱이 없거나 창구가 닫혀 있음 - 계기판 전송은 건너뜀")
                }
            }
        } catch (e: Exception) {
            binding = false
            if (!loggedUnavailable) {
                loggedUnavailable = true
                NavLogger.e("[nMirror] 연결 시도 실패: ${e.javaClass.simpleName} ${e.message}")
            }
        }
    }

    fun statusForLog(): String =
        if (service != null) "nMirror=연결됨 전송=${sentCount}회" else "nMirror=끊김"
}
