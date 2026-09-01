package com.tmap.nda.navdy

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import com.tmap.nda.NavLogger
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Navdy 애프터마켓 HUD/클러스터로 경로 안내 정보를 블루투스로 전송.
 *
 * 프로토콜은 Navdy Inc.가 만들고, 이후 alelec(Andrew Leech)이
 * MIT 라이선스로 공개 유지보수 중인 오픈소스 프로젝트
 * (gitlab.com/alelec/navdy) 문서를 참고해 독자적으로 재구현함.
 *
 * 통신 형식: [4바이트 길이 헤더][protobuf(Wire) 직렬화된 NavdyEvent 바이트]
 * NavdyEvent { tag2=type(enum), tag108(extension)=NavigationManeuverEvent }
 */
object NavdySender {

    // Navdy 서비스 UUID (Navdy 공식 규격)
    private val NAVDY_SERVICE_UUID: UUID =
        UUID.fromString("1992B7D7-C9B9-4F4F-AA7F-9FE33D95851E")

    // NavdyEvent.MessageType.NavigationManeuverEvent
    private const val MSG_TYPE_NAVIGATION_MANEUVER_EVENT = 8

    // NavdyEvent 필드 태그
    private const val NAVDY_EVENT_TAG_TYPE = 2
    private const val NAVDY_EVENT_EXT_TAG_NAVIGATION_MANEUVER = 108

    // NavigationManeuverEvent 필드 태그
    private const val FIELD_CURRENT_ROAD = 1
    private const val FIELD_PENDING_TURN = 2
    private const val FIELD_DISTANCE_TO_PENDING_TURN = 3
    private const val FIELD_PENDING_STREET = 4
    private const val FIELD_ETA = 5
    private const val FIELD_SPEED = 6
    private const val FIELD_NAVIGATION_STATE = 7
    private const val FIELD_ETA_UTC_TIME = 8

    // NavigationSessionState - Navdy 실제 안드로이드 앱을 디컴파일한 오픈소스(gitlab.com/alelec/navdy/
    // alelec_navdy_client, NavigationSessionState.java)로 원본 enum 값을 직접 확인함. 안내 중에는
    // 항상 NAV_SESSION_STARTED(4)를 보내면 됨. #문제시 원복
    private const val NAV_SESSION_STARTED = 4

    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var outputStream: OutputStream? = null
    @Volatile private var connecting = false


    /**
     * 지정한 블루투스 기기(Navdy 또는 Navdy 프로토콜 호환 기기)로 연결 시도.
     * 이미 페어링되어 있는 BluetoothDevice를 넘겨줘야 함.
     */
    fun connect(context: Context, device: BluetoothDevice) {
        if (connecting || socket?.isConnected == true) return
        connecting = true
        executor.execute {
            try {
                // v: 재억 제보(2026-08-27, 실기기 로그로 확인) - 안드로이드 12+에서
                // cancelDiscovery()는 BLUETOOTH_SCAN 권한이 없으면 SecurityException을 던짐
                // ("Need android.permission.BLUETOOTH_SCAN ... AdapterService cancelDiscovery").
                // 이 예외가 바깥 catch까지 튀면서 매번 연결 자체가 조용히 실패하고 있었음 -
                // 지금까지 "연결 시도" 로그만 반복되던 진짜 원인. BLUETOOTH_SCAN 권한을
                // 요청하도록 고쳤지만(MainActivity), 혹시 거부된 기기에서도 최소한 연결
                // 시도 자체는 막히지 않게 여기서 따로 막아줌(discovery 취소는 없어도 대부분
                // 연결에 지장 없음). #문제시 원복
                try {
                    BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
                } catch (e: SecurityException) {
                    NavLogger.d("[Navdy] cancelDiscovery 권한 없음 - 건너뛰고 연결 계속 시도")
                }
                // v: 재억 제보(2026-08-31, 로그 재분석) - 정식 연결(보안/비보안) 시도가
                // 매번 "read failed, socket might closed or timeout"으로 즉시(0.1초 내) 실패하고,
                // 그 다음 채널 우회 연결은 항상 2번 채널에서 "성공"으로 뜨지만 처음 데이터를
                // 보내는 순간(빠르면 17ms, 늦어도 1초 내) 100% Broken pipe로 끊기는 걸 확인함.
                // 연결 유지 시간과 상관없이 "첫 전송"에서 무조건 끊긴다는 건, 채널 우회 연결이
                // 진짜 연결이 아니라 안드로이드가 SDP(기기가 어느 채널을 쓰는지 조회하는 절차)를
                // 건너뛰고 억지로 연 껍데기일 가능성이 높다는 뜻. 이 "read failed" 에러 자체는
                // 삼성 최신 안드로이드에서 흔히 보고되는 문제로, SDP 캐시가 새로 갱신되기 전에
                // 연결을 시도해서 생기는 경우가 많다고 알려져 있음. 그래서 정식 연결을 시도하기
                // 전에 fetchUuidsWithSdp()로 강제로 새로 조회하고, 그 결과(ACTION_UUID 브로드캐스트)를
                // 최대 3초까지 기다렸다가 연결하도록 함. #문제시 원복
                refreshSdpAndWait(context, device)
                // v: 재억 제보(2026-08-28, 실기기 로그로 확인) - "IOException read failed, socket
                // might closed or timeout, read ret: -1" 실패가 계속 남음. 표준 방식
                // (createRfcommSocketToServiceRecord)은 연결 전에 SDP로 UUID 서비스 조회를
                // 하는데, 나브디처럼 흔치 않은 블루투스 기기 + 일부 삼성폰 조합에서 이 조회
                // 단계가 자주 실패하는 걸로 알려짐. 표준 방식이 실패하면 SDP 조회를 건너뛰고
                // 고정 채널(1번)로 바로 붙는 우회 방식(리플렉션, 다른 블루투스 시리얼 기기
                // 연동에서 흔히 쓰는 방법)으로 한 번 더 시도. #문제시 원복
                // v: 재억 제보(2026-08-28, 실기기 로그 분석) - 25분 넘게 계속 반복되는
                // "read failed, socket might closed or timeout" 실패 패턴을 확인함. 원인은
                // 표준 방식(s)이나 우회 방식(fallback)의 connect()가 실패해도 그 소켓 객체를
                // 닫지 않고 그냥 버리고 있었던 것 - closeQuietly()는 아직 socket/outputStream
                // 클래스 변수에 할당되기 전이라 이 실패한 로컬 소켓들은 정리 대상이 아니었음.
                // 15초 재연결 루프가 실패를 반복할 때마다 안 닫힌 소켓이 계속 쌓여서, 결국
                // 폰의 블루투스 RFCOMM 자원이 고갈돼 계속 실패하는 상태로 악화됐을 가능성이
                // 높음. 실패한 소켓은 그 즉시 닫아서 누적되지 않게 함. #문제시 원복
                var s: BluetoothSocket? = null
                val sock = try {
                    s = device.createRfcommSocketToServiceRecord(NAVDY_SERVICE_UUID)
                    s.connect()
                    s
                } catch (e: IOException) {
                    try { s?.close() } catch (_: Exception) {}
                    NavLogger.d("[Navdy] 표준(보안) 연결 실패(${e.message}), 비보안 연결 시도")
                    // v: 재억 요청(2026-08-31, 실기기 로그 분석) - "채널 찍기 전에 표준 방식으로
                    // 더 못 해보냐"는 지적으로 나브디 공식 안드로이드 앱(디컴파일 오픈소스,
                    // gitlab.com/alelec/navdy/alelec_navdy_client, BTSocketFactory.java)을 직접
                    // 확인함. 공식 앱도 같은 UUID로 createRfcommSocketToServiceRecord(보안)를
                    // 기본으로 쓰지만, 같은 클래스에 createInsecureRfcommSocketToServiceRecord
                    // (비보안) 경로도 존재함. 비보안 방식은 채널 번호를 추측하는 게 아니라
                    // SDP로 정확한 채널을 정식으로 조회하면서, 실패 원인으로 의심되는 페어링/
                    // 암호화 협상 단계만 건너뜀 - "채널 1~5번 찍기"보다 훨씬 표준에 가까운
                    // 방법이라 이걸 먼저 시도하고, 그래도 안 되면 기존 채널 찍기로 넘어감. #문제시 원복
                    var insecure: BluetoothSocket? = null
                    val insecureSock = try {
                        insecure = device.createInsecureRfcommSocketToServiceRecord(NAVDY_SERVICE_UUID)
                        insecure.connect()
                        NavLogger.d("[Navdy] 비보안 연결 성공")
                        insecure
                    } catch (e3: IOException) {
                        try { insecure?.close() } catch (_: Exception) {}
                        NavLogger.d("[Navdy] 비보안 연결도 실패(${e3.message}), 채널 우회 연결 시도")
                        null
                    }
                    insecureSock ?: run {
                        // v: 재억 재제보(2026-08-28) - "나브디 쓰는 다른 사람도 안 된다"는 제보로
                        // 다시 파봄. 표준 방식이 98% 이상 실패하는 건 확인했었지만, 그동안 우회
                        // 방식도 "무조건 1번 채널"만 시도하고 있었음 - 근데 SPP 채널 번호는
                        // 기기/펌웨어마다 다를 수 있어서, 1번이 애초에 이 나브디 기기의 실제
                        // 채널이 아닐 가능성이 있음. 흔히 쓰이는 범위(1~5번)를 순서대로 시도해서
                        // 맞는 채널을 찾도록 확장. #문제시 원복
                        var fallback: BluetoothSocket? = null
                        var lastFallbackError: Exception? = null
                        for (channel in 1..5) {
                            try {
                                fallback = device.javaClass
                                    .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                                    .invoke(device, channel) as BluetoothSocket
                                fallback.connect()
                                NavLogger.d("[Navdy] 채널 $channel 로 연결 성공")
                                lastFallbackError = null
                                break
                            } catch (e2: Exception) {
                                try { fallback?.close() } catch (_: Exception) {}
                                fallback = null
                                lastFallbackError = e2
                            }
                        }
                        fallback ?: throw (lastFallbackError ?: e)
                    }
                }
                socket = sock
                outputStream = sock.outputStream
                NavLogger.d("[Navdy] 연결 성공: ${device.name}")
            } catch (e: Exception) {
                // v: 재억 제보(2026-08-27) - 로그 파일에는 "연결 시도"만 계속 남고 성공/실패 여부가
                // 안 남아서 원인 파악이 안 됐음. 기존엔 android.util.Log만 써서 logcat에만 남고
                // 앱이 공유하는 로그 파일(NavLogger)에는 기록이 안 됐던 게 원인. #문제시 원복
                NavLogger.e("[Navdy] 연결 실패: ${e.javaClass.simpleName} ${e.message}")
                closeQuietly()
            } finally {
                connecting = false
            }
        }
    }

    // v: 재억 제보(2026-08-31, 로그 재분석) - fetchUuidsWithSdp()는 비동기라 호출만 하고 바로
    // connect()하면 아무 의미가 없음(기존 캐시 그대로 씀). ACTION_UUID 브로드캐스트가 올 때까지
    // 최대 3초 기다림 - SDP 조회가 실패하거나 너무 오래 걸리면 그냥 넘어가서 기존 방식대로
    // 진행(최소한 지금보다 나빠지진 않음). #문제시 원복
    private fun refreshSdpAndWait(context: Context, device: BluetoothDevice) {
        val latch = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == BluetoothDevice.ACTION_UUID) {
                    val uuids = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
                    NavLogger.d("[Navdy] SDP 새로고침 결과: ${uuids?.joinToString { (it as? ParcelUuid)?.uuid.toString() } ?: "없음"}")
                    latch.countDown()
                }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_UUID), Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_UUID))
            }
            if (device.fetchUuidsWithSdp()) {
                latch.await(3, TimeUnit.SECONDS)
            }
        } catch (e: Exception) {
            NavLogger.d("[Navdy] SDP 새로고침 실패(${e.message}) - 기존 방식으로 계속 진행")
        } finally {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }

    fun isConnected(): Boolean = socket?.isConnected == true

    fun disconnect() {
        executor.execute { closeQuietly() }
    }

    private fun closeQuietly() {
        try { outputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        outputStream = null
        socket = null
    }

    /**
     * 경로 안내 정보를 Navdy 프로토콜 형식으로 전송.
     *
     * @param currentRoad 현재 도로명
     * @param turn NavdyTurn enum 값 (아래 매핑 함수 참고)
     * @param distanceToTurn 다음 턴까지 거리 텍스트 (예: "300m")
     * @param pendingStreet 다음 진입 도로명
     * @param eta 도착 예상 시간 텍스트 (예: "14:32")
     * @param speed 현재 속도 텍스트 (예: "62km/h")
     */
    // v: 재억 제보(2026-08-28, 실기기 로그로 확인) - 길안내 시작 자체는 몇 분씩 안정적으로
    // 유지되는데, 주행 중 목적지를 변경하는 순간(경로 재계산되면서 위치/경로 갱신 콜백이
    // 짧은 시간에 몰려 들어옴)에만 Broken pipe로 끊기는 패턴을 확인함. KakaoHudBridge의
    // 500ms 스로틀은 "새 데이터 계산" 빈도만 제한하고, 실제 블루투스 전송 자체의 최소
    // 간격은 보장 안 하고 있었음(서로 다른 콜백 두 개가 거의 동시에 통과하면 둘 다
    // 큐에 쌓여 짧은 간격으로 연달아 전송됨). 여기 전송 직전에 한 번 더 최소 간격을
    // 강제해서, 목적지 변경 같은 순간에 블루투스로 데이터가 몰아쳐 나가지 않게 함. #문제시 원복
    @Volatile private var lastWriteTime = 0L
    private const val MIN_WRITE_INTERVAL_MS = 300L

    fun sendManeuver(
        currentRoad: String,
        turn: NavdyTurn,
        distanceToTurn: String,
        pendingStreet: String,
        eta: String,
        speed: String
    ) {
        // v: 재억 요청(2026-08-28) - "연결 안 됨" 상태를 매번/주기적으로 로그 남기지 않음.
        // 그 상태의 "왜"는 재연결 루프(NavdyAutoConnect)가 연결을 시도할 때마다 이미
        // 성공/실패 이유를 로그로 남기고 있으므로 여기서 또 남기면 중복 스팸이 됨. #문제시 원복
        val stream = outputStream ?: return
        executor.execute {
            try {
                // v: 재억 제보(2026-08-28) - 짧은 간격으로 연달아 전송 요청이 몰릴 때(목적지
                // 변경 등) 마지막 전송 이후 최소 시간이 안 지났으면 이번 건은 건너뜀 -
                // 어차피 곧 다음 갱신이 또 오므로 하나쯤 건너뛰어도 정보 손실 체감은 없고,
                // 블루투스로 데이터가 몰아쳐 나가는 것만 막으면 됨. #문제시 원복
                val now = System.currentTimeMillis()
                if (now - lastWriteTime < MIN_WRITE_INTERVAL_MS) return@execute
                lastWriteTime = now
                val maneuverBytes = buildNavigationManeuverEvent(
                    currentRoad, turn.protoValue, distanceToTurn, pendingStreet, eta, speed
                )
                val eventBytes = buildNavdyEvent(maneuverBytes)
                writeFramed(stream, eventBytes)
            } catch (e: Exception) {
                NavLogger.e("[Navdy] 전송 실패로 연결 끊김 처리: ${e.javaClass.simpleName} ${e.message}")
                // v: 재억 요청(2026-08-28) - 전송이 실패해도 socket/outputStream을 그대로 두면,
                // 안드로이드 BluetoothSocket.isConnected()가 물리적 연결 끊김을 실시간으로
                // 반영 안 해줘서(close() 호출 전까진 계속 true로 보고할 수 있음) 재연결 루프가
                // "이미 연결됨"으로 착각하고 영영 재시도를 안 할 수 있음. 전송 실패 시점에
                // 직접 정리해서 다음 재연결 루프 틱에 새로 붙게 함. #문제시 원복
                closeQuietly()
            }
        }
    }

    // ---- protobuf(Wire) 수동 인코딩 ----
    // proto2 wire format: (tag << 3 | wireType) varint, 뒤에 값

    private fun buildNavigationManeuverEvent(
        currentRoad: String,
        turnValue: Int,
        distanceToTurn: String,
        pendingStreet: String,
        eta: String,
        speed: String
    ): ByteArray {
        val out = ByteArrayOutputStream()
        writeString(out, FIELD_CURRENT_ROAD, currentRoad)
        writeVarintField(out, FIELD_PENDING_TURN, turnValue.toLong())
        writeString(out, FIELD_DISTANCE_TO_PENDING_TURN, distanceToTurn)
        writeString(out, FIELD_PENDING_STREET, pendingStreet)
        writeString(out, FIELD_ETA, eta)
        writeString(out, FIELD_SPEED, speed)
        // v: 재억 제보(2026-08-27) - 여태 이 필드를 아예 안 보내고 있었음. 안 보내면 받는 쪽
        // (Navdy 기기)이 기본값 NAV_SESSION_ENGINE_NOT_READY(=1, "내비 엔진이 아직 준비 안 됨")로
        // 해석해서, 블루투스 연결과 데이터 전송 자체는 멀쩡히 성공해도 기기 화면에는 아무것도
        // 안 그려주고 있었을 가능성이 매우 높음(디컴파일한 실제 안드로이드 앱 코드로 default
        // 값 확인함). "안내 중"임을 명시적으로 알려줌. #문제시 원복
        writeVarintField(out, FIELD_NAVIGATION_STATE, NAV_SESSION_STARTED.toLong())
        return out.toByteArray()
    }

    private fun buildNavdyEvent(maneuverEventBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        // tag2 required: type = NavigationManeuverEvent(8)
        writeVarintField(out, NAVDY_EVENT_TAG_TYPE, MSG_TYPE_NAVIGATION_MANEUVER_EVENT.toLong())
        // tag108 extension: embedded message
        writeEmbeddedMessage(out, NAVDY_EVENT_EXT_TAG_NAVIGATION_MANEUVER, maneuverEventBytes)
        return out.toByteArray()
    }

    private fun writeFramed(stream: OutputStream, payload: ByteArray) {
        // Frame { tag1 required FIXED32 size } — Wire의 FIXED32는 리틀엔디안 4바이트
        val frame = ByteArrayOutputStream()
        frame.write((0x1.shl(3)) or 5) // tag1, wiretype5(=FIXED32)
        frame.write(payload.size and 0xFF)
        frame.write((payload.size shr 8) and 0xFF)
        frame.write((payload.size shr 16) and 0xFF)
        frame.write((payload.size shr 24) and 0xFF)
        stream.write(frame.toByteArray())
        stream.write(payload)
        stream.flush()
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            if ((v and 0x7F.toLong().inv()) == 0L) {
                out.write(v.toInt())
                return
            } else {
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
        }
    }

    private fun writeVarintField(out: ByteArrayOutputStream, tag: Int, value: Long) {
        writeVarint(out, ((tag.toLong() shl 3) or 0)) // wiretype 0 = varint
        writeVarint(out, value)
    }

    private fun writeString(out: ByteArrayOutputStream, tag: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeVarint(out, ((tag.toLong() shl 3) or 2)) // wiretype 2 = length-delimited
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun writeEmbeddedMessage(out: ByteArrayOutputStream, tag: Int, bytes: ByteArray) {
        writeVarint(out, ((tag.toLong() shl 3) or 2))
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }
}

/**
 * NavdyEvent.NavigationTurn enum 매핑.
 * 카카오내비 SDK의 방향 코드를 이 값으로 변환해서 사용.
 */
enum class NavdyTurn(val protoValue: Int) {
    START(1),
    EASY_LEFT(2),
    EASY_RIGHT(3),
    END(4),
    KEEP_LEFT(5),
    KEEP_RIGHT(6),
    LEFT(7),
    OUT_OF_ROUTE(8),
    RIGHT(9),
    SHARP_LEFT(10),
    SHARP_RIGHT(11),
    STRAIGHT(12),
    UTURN_LEFT(13),
    UTURN_RIGHT(14),
    ROUNDABOUT_SE(15),
    ROUNDABOUT_E(16),
    ROUNDABOUT_NE(17),
    ROUNDABOUT_N(18),
    ROUNDABOUT_NW(19),
    ROUNDABOUT_W(20),
    ROUNDABOUT_SW(21),
    ROUNDABOUT_S(22),
    FERRY(23),
    STATE_BOUNDARY(24),
    FOLLOW_ROUTE(25),
    EXIT_RIGHT(26),
    EXIT_LEFT(27),
    MOTORWAY(28),
    EXIT_ROUNDABOUT(29),
    UNKNOWN(30),
    MERGE_LEFT(31),
    MERGE_RIGHT(32)
}
