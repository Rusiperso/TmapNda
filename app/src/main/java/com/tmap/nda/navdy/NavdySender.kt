package com.tmap.nda.navdy

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.tmap.nda.NavLogger
import com.tmap.nda.nmirror.GuidanceSnapshot
import com.tmap.nda.nmirror.RgDataJson
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 나브디 HUD로 길안내를 블루투스로 직접 보낸다.
 *
 * ★ v19.2.98에서 통신 방식을 통째로 갈아엎었다(2026-09-04).
 *
 * 그 전까지는 정품 나브디 앱 규격(protobuf, UUID 1992B7D7-…)으로 구현했는데, 며칠에 걸친
 * 실기기 로그에서 **우리가 보낸 어떤 메시지에도 기기가 한 번도 응답하지 않았다**(경로 만들기
 * 요청 수십 회 → 응답 0, 안내상태 물어보기 → 응답 0). 원인은 규격이 아니라 주소였다:
 *
 *   `[Navdy][기기창구조사]` 로그에 찍힌, 기기가 실제로 열어둔 창구 4개
 *     00000000-deca-fade-deca-deafdecacaff
 *     00001101-0000-1000-8000-00805f9b34fb  (표준 SPP)
 *     503b9411-7894-43fa-bc9d-ec62fe9c9d32  ← 이것
 *     dfce890e-6631-4e4a-800d-9415d98ccff7
 *   여기에 **정품 앱이 쓰는 1992B7D7-… 은 아예 없다.** 없는 문을 계속 두드리고 있었던 것.
 *
 * 반면 nMirror 4.8.0(`com.aa.nmirror.navdy.NavdyBluetoothService`)은 위 목록에 있는
 * **503b9411-…** 로 붙어서 나브디에 길안내를 보내고 있었다. 그 방식을 그대로 구현한다.
 * (nMirror APK 디컴파일 + 문자열 복호화로 확인. 문자열은 `o1.a.a(long)`으로 난독화돼 있음)
 *
 * 규격은 정품보다 훨씬 단순하다 — 경로 만들기 요청도, 인터넷 프록시도 없다:
 *
 *   프레임: [타입 2바이트][길이 4바이트][내용]   (둘 다 big-endian)
 *
 *   폰 → 나브디
 *     14  안내 중인지 여부 (1바이트: 1=안내중)
 *     15  제어 JSON — {"type":"set_time","time_zone":…,"time_stamp":…}
 *     16  길안내 — {"rgData":{…}, "tbtListInfos":null,"oilType":0}     ← 핵심
 *     20  경로 좌표(float 위도,경도 반복 / 없으면 int 0)
 *     100 "PING" (10초마다)
 *   나브디 → 폰
 *     12 meta 문자열 / 14 안내중 / 15 제어 JSON(기기 버튼 조작) / 17 기기 GPS
 *
 * #문제시 원복: 이 파일을 v19.2.97 버전으로 되돌리면 예전 protobuf 방식으로 돌아간다.
 */
object NavdySender {

    // nMirror가 쓰는 것과 동일. 보안 연결은 503b9411, 비보안 대체 경로는 e06251a8.
    private val UUID_SECURE: UUID = UUID.fromString("503b9411-7894-43fa-bc9d-ec62fe9c9d32")
    private val UUID_INSECURE: UUID = UUID.fromString("e06251a8-ebfd-427f-a9f7-a21aae3d5127")

    private const val TYPE_NAVI_PLAYING = 14
    private const val TYPE_CONTROL_JSON = 15
    private const val TYPE_GUIDANCE = 16
    private const val TYPE_ROUTE_COORDS = 20
    private const val TYPE_PING = 100

    private const val PING_INTERVAL_MS = 10_000L
    private const val MIN_SEND_INTERVAL_MS = 1_000L
    private const val MAX_FRAME_SIZE = 10 * 1024 * 1024

    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var output: DataOutputStream? = null
    @Volatile private var running = false
    @Volatile private var connecting = false
    @Volatile private var appContext: Context? = null

    @Volatile private var navigating = false
    @Volatile private var destinationName = ""

    @Volatile private var connectedAtMs = 0L
    @Volatile private var sentCount = 0
    @Volatile private var pingCount = 0
    @Volatile private var receivedCount = 0
    @Volatile private var lastSentAtMs = 0L
    private val receivedTypes = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    // 같은 문구가 반복해서 로그를 채우지 않게 함(연결 실패는 10초마다 반복되므로). #문제시 원복
    private val lastLogged = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun logIfChanged(key: String, message: String, isError: Boolean = false) {
        if (lastLogged.put(key, message) == message) return
        if (isError) NavLogger.e(message) else NavLogger.d(message)
    }

    fun isConnected(): Boolean = socket?.isConnected == true

    fun start(context: Context) {
        appContext = context.applicationContext
        if (running) return
        running = true
        Thread({
            NavLogger.d("[Navdy] 연결 담당 시작 (nMirror와 같은 방식으로 폰이 거는 쪽)")
            while (running) {
                try {
                    if (!isConnected()) connectOnce()
                    else sendPing()
                    Thread.sleep(PING_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    return@Thread
                } catch (e: Exception) {
                    logIfChanged("loop", "[Navdy] 연결 담당 오류: ${e.javaClass.simpleName} ${e.message}", isError = true)
                    try { Thread.sleep(PING_INTERVAL_MS) } catch (_: InterruptedException) { return@Thread }
                }
            }
        }, "NavdyLoop").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        closeQuietly()
        NavLogger.d("[Navdy] 연결 끔 - 더 이상 붙지 않음")
    }

    /** 카카오 안내 시작/종료. 기기는 이 값으로 화면을 켜고 끈다. */
    fun setNavigating(on: Boolean, destination: String = "") {
        navigating = on
        if (on) destinationName = destination
        sendNaviPlaying()
    }

    fun sendGuidance(snapshot: GuidanceSnapshot) {
        val stream = output ?: return
        val now = System.currentTimeMillis()
        if (now - lastSentAtMs < MIN_SEND_INTERVAL_MS) return
        lastSentAtMs = now
        executor.execute {
            try {
                // nMirror가 만들어 보내는 것과 같은 형태. tbtListInfos(차선/경로 목록)는
                // 우리가 가진 정보가 없어 null로 둔다 - 받는 쪽이 null을 허용한다. #문제시 원복
                val body = "{\"rgData\":${RgDataJson.build(snapshot)}, " +
                    "\"tbtListInfos\":null,\"oilType\":0}"
                writeFrame(stream, TYPE_GUIDANCE, body.toByteArray(Charsets.UTF_8))
                sentCount++
                if (sentCount == 1) {
                    NavLogger.d("[Navdy] 첫 길안내 전송 성공: 도로=${snapshot.roadName} 거리=${snapshot.turnDistanceMeters}m (이후는 메모리에만 기록)")
                } else {
                    NavLogger.trace("navdy", "전송 ${snapshot.roadName} ${snapshot.turnDistanceMeters}m")
                }
            } catch (e: Exception) {
                val reason = "[Navdy] 전송 실패로 연결 끊김: ${e.javaClass.simpleName} ${e.message}, " +
                    "이 연결에서 성공 ${sentCount}회, 유지 ${connectionAgeMs()}ms"
                NavLogger.flushTrace("navdy", reason)
                com.tmap.nda.DiscordReporter.reportNavdyDisconnect(reason)
                closeQuietly()
            }
        }
    }

    fun statusForLog(): String =
        if (isConnected()) {
            "connected=true 유지=${connectionAgeMs()}ms 전송=${sentCount}회 신호=${pingCount}회 " +
                "수신=${receivedCount}회 안내중=$navigating(목적지=${destinationName.ifBlank { "없음" }}) " +
                "받은종류[${receivedSummary()}]"
        } else {
            "connected=false 동작중=$running"
        }

    private fun connectionAgeMs(): Long =
        if (connectedAtMs == 0L) 0L else System.currentTimeMillis() - connectedAtMs

    private fun receivedSummary(): String =
        receivedTypes.entries.sortedByDescending { it.value }
            .joinToString(", ") { "${typeName(it.key)}=${it.value}회" }
            .ifEmpty { "없음" }

    private fun typeName(type: Int): String = when (type) {
        12 -> "기기정보(meta)"
        14 -> "안내중여부"
        15 -> "제어JSON"
        17 -> "기기GPS"
        else -> "알수없음(번호=$type)"
    }

    private fun connectOnce() {
        if (connecting) return
        connecting = true
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null || !adapter.isEnabled) {
                logIfChanged("bt", "[Navdy] 블루투스 꺼져있음 - 10초 뒤 다시 시도")
                return
            }
            val device = adapter.bondedDevices?.firstOrNull { d ->
                val n = (try { d.name } catch (e: SecurityException) { null } ?: "").lowercase()
                n.contains("navdy") || n.contains("hud")
            }
            if (device == null) {
                logIfChanged("pair", "[Navdy] 페어링된 나브디 기기 없음 - 블루투스 설정에서 먼저 페어링 필요")
                return
            }
            try { adapter.cancelDiscovery() } catch (_: SecurityException) {}

            val sock = try {
                device.createRfcommSocketToServiceRecord(UUID_SECURE).also { it.connect() }
            } catch (e: IOException) {
                logIfChanged("dial", "[Navdy] 보안 연결 실패(${e.message}) - 비보안으로 재시도")
                try {
                    device.createInsecureRfcommSocketToServiceRecord(UUID_INSECURE).also { it.connect() }
                } catch (e2: IOException) {
                    logIfChanged("dial2", "[Navdy] 비보안 연결도 실패(${e2.message}) - 10초 뒤 재시도")
                    null
                }
            } ?: return

            socket = sock
            output = DataOutputStream(sock.outputStream)
            connectedAtMs = System.currentTimeMillis()
            sentCount = 0
            pingCount = 0
            receivedCount = 0
            receivedTypes.clear()
            lastLogged.remove("dial")
            lastLogged.remove("dial2")
            NavLogger.d("[Navdy] 연결됨: ${try { device.name } catch (e: SecurityException) { "이름 확인 불가" }}")

            startReader(sock)
            greet()
        } catch (e: SecurityException) {
            logIfChanged("perm", "[Navdy] 블루투스 권한 없음(주변 기기) - 연결 불가: ${e.message}", isError = true)
        } catch (e: Exception) {
            logIfChanged("conn", "[Navdy] 연결 실패: ${e.javaClass.simpleName} ${e.message}", isError = true)
            if (!isConnected()) closeQuietly()
        } finally {
            connecting = false
        }
    }

    /** 붙자마자 하는 인사. nMirror가 연결 직후 보내는 순서 그대로. */
    private fun greet() {
        val stream = output ?: return
        executor.execute {
            try {
                val time = JSONObject()
                    .put("type", "set_time")
                    .put("time_zone", TimeZone.getDefault().id)
                    .put("time_stamp", System.currentTimeMillis())
                writeFrame(stream, TYPE_CONTROL_JSON, time.toString().toByteArray(Charsets.UTF_8))

                writeFrame(stream, TYPE_NAVI_PLAYING, byteArrayOf(if (navigating) 1 else 0))

                // 경로 좌표는 아직 안 보낸다(기기가 없어도 동작함). nMirror도 비어있을 땐
                // 4바이트짜리 0을 보내므로 형식만 맞춰준다. #문제시 원복
                writeFrame(stream, TYPE_ROUTE_COORDS, byteArrayOf(0, 0, 0, 0))
                NavLogger.d("[Navdy] 인사 보냄(시각 동기화 + 안내상태) - 이제 길안내를 보낼 수 있음")
            } catch (e: Exception) {
                NavLogger.e("[Navdy] 인사 실패: ${e.javaClass.simpleName} ${e.message}")
                closeQuietly()
            }
        }
    }

    private fun sendNaviPlaying() {
        val stream = output ?: return
        executor.execute {
            try {
                writeFrame(stream, TYPE_NAVI_PLAYING, byteArrayOf(if (navigating) 1 else 0))
                NavLogger.d("[Navdy] 안내 상태 알림: ${if (navigating) "안내 시작(목적지=$destinationName)" else "안내 종료"}")
            } catch (e: Exception) {
                NavLogger.e("[Navdy] 안내 상태 전송 실패: ${e.message}")
                closeQuietly()
            }
        }
    }

    private fun sendPing() {
        val stream = output ?: return
        executor.execute {
            try {
                writeFrame(stream, TYPE_PING, "PING".toByteArray(Charsets.UTF_8))
                pingCount++
                if (pingCount == 1) NavLogger.d("[Navdy] 살아있음 신호(PING) 시작 - 이후 10초마다")
                else NavLogger.trace("navdy", "PING ${pingCount}번째")
            } catch (e: Exception) {
                NavLogger.d("[Navdy] PING 실패 - 연결 끊긴 것으로 보고 정리: ${e.message}")
                closeQuietly()
            }
        }
    }

    @Synchronized
    private fun writeFrame(stream: DataOutputStream, type: Int, payload: ByteArray) {
        stream.writeShort(type)
        stream.writeInt(payload.size)
        stream.write(payload)
        stream.flush()
    }

    private fun startReader(sock: BluetoothSocket) {
        Thread({
            try {
                val input = DataInputStream(java.io.BufferedInputStream(sock.inputStream))
                while (socket === sock) {
                    val type = input.readShort().toInt() and 0xFFFF
                    val size = input.readInt()
                    if (size < 0 || size > MAX_FRAME_SIZE) throw IOException("프레임 크기 이상: $size")
                    val payload = ByteArray(size)
                    input.readFully(payload)
                    receivedCount++
                    val count = (receivedTypes[type] ?: 0) + 1
                    receivedTypes[type] = count
                    if (count == 1) {
                        NavLogger.d("[Navdy] 기기가 보낸 메시지 처음 받음: ${typeName(type)} (${size}바이트)")
                    } else {
                        NavLogger.trace("navdy", "수신 ${typeName(type)} ${size}B")
                    }
                }
            } catch (e: Exception) {
                val reason = "[Navdy] 받는 스레드 종료: ${e.javaClass.simpleName} ${e.message}, " +
                    "${connectionAgeMs()}ms만에, 전송 ${sentCount}회, 신호 ${pingCount}회, 수신 ${receivedCount}회"
                NavLogger.flushTrace("navdy", reason)
                com.tmap.nda.DiscordReporter.reportNavdyDisconnect(reason)
            } finally {
                if (socket === sock) closeQuietly()
            }
        }, "NavdyReader").apply { isDaemon = true }.start()
    }

    private fun closeQuietly() {
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        output = null
        socket = null
        connectedAtMs = 0L
    }
}
