package com.tmap.nda.navdy

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors

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

    private const val TAG = "NavdySender"

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

    // NavigationSessionState (일부만 사용 — 안내 중 상태)
    private const val NAV_SESSION_ROUTE_ACTIVE = 6 // 원본 enum 값은 재확인 필요, 아래 참고

    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var outputStream: OutputStream? = null
    @Volatile private var connecting = false

    /**
     * 지정한 블루투스 기기(Navdy 또는 Navdy 프로토콜 호환 기기)로 연결 시도.
     * 이미 페어링되어 있는 BluetoothDevice를 넘겨줘야 함.
     */
    fun connect(device: BluetoothDevice) {
        if (connecting || socket?.isConnected == true) return
        connecting = true
        executor.execute {
            try {
                BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
                val sock = device.createRfcommSocketToServiceRecord(NAVDY_SERVICE_UUID)
                sock.connect()
                socket = sock
                outputStream = sock.outputStream
                Log.i(TAG, "Navdy 기기 연결 성공: ${device.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Navdy 연결 실패", e)
                closeQuietly()
            } finally {
                connecting = false
            }
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
    fun sendManeuver(
        currentRoad: String,
        turn: NavdyTurn,
        distanceToTurn: String,
        pendingStreet: String,
        eta: String,
        speed: String
    ) {
        val stream = outputStream ?: return
        executor.execute {
            try {
                val maneuverBytes = buildNavigationManeuverEvent(
                    currentRoad, turn.protoValue, distanceToTurn, pendingStreet, eta, speed
                )
                val eventBytes = buildNavdyEvent(maneuverBytes)
                writeFramed(stream, eventBytes)
            } catch (e: Exception) {
                Log.e(TAG, "전송 실패", e)
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
