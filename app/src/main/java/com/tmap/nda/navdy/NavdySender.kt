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
import java.io.InputStream
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

    // 정품 앱이 대기 소켓을 열 때 쓰는 서비스 이름과 동일해야 함(BTSocketAcceptor("Navdy", ...)).
    // 나브디가 폰의 이 이름/UUID를 보고 찾아 붙는 것으로 보임. #문제시 원복
    private const val NAVDY_SDP_NAME = "Navdy"

    // v: 재억 제보(2026-09-02, "나브디 아직 안됨") - v19.2.77로 방향은 고쳤고 실기기 로그에서
    // "대기 소켓 열림 - 나브디가 연결해오길 기다리는 중"까지 확인됐지만(18:12:47), 그 뒤
    // 1분 40초 동안 나브디가 걸어오지 않았음. 정품 앱 소스를 다시 보니 폰이 대기 소켓을
    // 하나가 아니라 **두 개** 열고 있었음:
    //   ClientConnectionService.getConnectionListeners() -> BTSocketAcceptor("Navdy", 1992B7D7-...)
    //   ClientConnectionService.createProxyService()     -> BTSocketAcceptor("Navdy-Proxy-Tunnel", D72BC85F-...)
    // 나브디가 폰을 "정품 앱이 돌고 있는 폰"으로 인정하는 조건에 이 두 번째 창구의 존재가
    // 포함될 가능성이 있어서(둘 다 보고 판단하거나, 프록시 쪽으로 먼저 접속을 시도할 수 있음)
    // 정품과 동일하게 두 개를 다 열어둠. 프록시 쪽으로 들어온 연결은 아직 우리가 쓸 일이
    // 없으므로 기록만 남기고 정리함 - 다음 로그에서 나브디가 어느 쪽으로 접속을 시도하는지
    // 확인하는 용도로도 씀. #문제시 원복
    private val NAVDY_PROXY_TUNNEL_UUID: UUID =
        UUID.fromString("D72BC85F-F015-4F24-A72F-35924E10888F")
    private const val NAVDY_PROXY_SDP_NAME = "Navdy-Proxy-Tunnel"

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
    @Volatile private var readerThread: Thread? = null
    // v: 재억 요청(2026-09-02) - "보내다가 끊겼는지 보내보지도 못하고 끊겼는지도 알아야지"라는
    // 지적. 이 연결에서 지금까지 몇 번 전송에 성공했는지/언제 연결됐는지를 기억해뒀다가,
    // 끊길 때 로그에 같이 남겨서 "이번 연결은 한 번도 성공 못 하고 끊겼다" vs "N번 잘 보내다
    // 끊겼다"를 바로 구분할 수 있게 함. #문제시 원복
    @Volatile private var connectedAtMs = 0L
    @Volatile private var sentCountSinceConnect = 0
    // v: 재억 요청(2026-09-02) - "왜 실패하는지도 로그에 남게 했지?" 확인 중 빠진 걸 찾음.
    // 다음에 또 27초쯤에 끊기면 "살아있음 신호를 보냈는데도 끊긴 것"인지 "신호 자체가 안
    // 나간 것"인지 구분돼야 원인을 좁힐 수 있음. 이 연결에서 핑을 몇 번 보냈는지 세서
    // 끊길 때 로그에 같이 남김. #문제시 원복
    @Volatile private var pingCountSinceConnect = 0
    @Volatile private var receivedCountSinceConnect = 0

    // v: 재억 요청(2026-09-02) - "로그를 계속 쌓지 말고 그때 왜 끊겼는지 정도만 남게 해.
    // 계속 쌓으면 용량만 늘어난다". 지금까지 나브디 로그는 전송할 때마다(초당 1회),
    // 받을 때마다(4초마다), 연결 실패할 때마다(20초마다 3줄씩) 계속 쌓이고 있었음.
    // 상태가 "바뀌는 순간"만 남기고 같은 상태 반복은 생략함. 끊긴 이유와 그때까지의
    // 누적 횟수는 끊길 때 한 줄에 다 들어가므로 정보 손실은 없음. #문제시 원복
    private val lastLoggedByKey = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun logIfChanged(key: String, message: String, isError: Boolean = false) {
        if (lastLoggedByKey.put(key, message) == message) return
        if (isError) NavLogger.e(message) else NavLogger.d(message)
    }
    private fun resetLogState(vararg keys: String) {
        keys.forEach { lastLoggedByKey.remove(it) }
    }


    /**
     * v: 재억 제보(2026-09-02, 실기기 로그로 원인 확정) - 나브디가 계속 안 붙던 진짜 이유는
     * "연결 방향이 반대"였음.
     *
     * 실기기 로그에서 매번 똑같이 반복되던 패턴:
     *   정식(보안) 연결 실패 -> 비보안 연결도 실패 -> 채널 2번으로 억지 연결 "성공" ->
     *   2~50ms 만에 상대가 끊음 -> 첫 전송에서 Broken pipe -> 15초 뒤 처음부터 반복
     *   (전송 성공 0회, 328줄 내내 동일)
     *
     * 나브디 정품 안드로이드 앱 소스(gitlab.com/alelec/navdy/alelec_navdy_client를 직접
     * 받아서 확인, ClientConnectionService.getConnectionListeners())를 보니 폰 쪽은
     *   new AcceptorListener(context, new BTSocketAcceptor("Navdy", NAVDY_PROTO_SERVICE_UUID), ...)
     * 즉 BTSocketAcceptor = "연결을 받는 쪽(서버)"이었음. 나브디 기기가 폰한테 걸어오는
     * 구조인데, 이 앱은 지금까지 반대로 폰이 나브디한테 걸고 있었음. 그래서 정식 통로는
     * 애초에 열려있지도 않았고(그래서 100% 실패), 채널을 억지로 찍어 붙은 건 진짜 연결이
     * 아니라 나브디가 모르는 손님으로 보고 즉시 끊은 것. 재페어링이 소용없던 이유도 이것.
     *
     * UUID(1992B7D7-...)는 정품 앱과 동일해서 맞았고, 서비스 이름은 정확히 "Navdy",
     * 기본은 보안(secure) 방식인 것도 같은 소스에서 확인함.
     *
     * 이제 정품 앱과 동일하게 폰이 기다리는 쪽이 됨. 채널 1~5 억지 찍기는 "가짜 성공"만
     * 만들어내던 코드라 같이 제거함.
     * #문제시 원복: 아래 startListening()을 지우고 이 파일 히스토리의 connect() 복원
     */
    @Volatile private var serverSocket: android.bluetooth.BluetoothServerSocket? = null
    @Volatile private var listenerThread: Thread? = null
    @Volatile private var listening = false

    fun startListening(context: Context) {
        if (listening) return
        listening = true
        val thread = Thread {
            NavLogger.d("[Navdy] 연결 대기 시작 (정품 앱과 동일하게 폰이 받는 쪽)")
            while (listening) {
                var accepted: BluetoothSocket? = null
                try {
                    val adapter = BluetoothAdapter.getDefaultAdapter()
                    if (adapter == null || !adapter.isEnabled) {
                        NavLogger.d("[Navdy] 블루투스 꺼져있음 - 10초 뒤 다시 대기 시도")
                        Thread.sleep(10_000)
                        continue
                    }
                    // 정품 앱과 동일: 서비스 이름 "Navdy", 같은 UUID, 보안 방식이 기본.
                    // 보안 방식으로 대기 소켓을 못 열면 비보안으로 한 번 더 시도. #문제시 원복
                    val server = try {
                        adapter.listenUsingRfcommWithServiceRecord(NAVDY_SDP_NAME, NAVDY_SERVICE_UUID)
                    } catch (e: IOException) {
                        NavLogger.d("[Navdy] 보안 대기 소켓 열기 실패(${e.message}) - 비보안으로 재시도")
                        adapter.listenUsingInsecureRfcommWithServiceRecord(NAVDY_SDP_NAME, NAVDY_SERVICE_UUID)
                    }
                    serverSocket = server
                    logIfChanged("listen", "[Navdy] 대기 소켓 열림 - 나브디가 연결해오길 기다리는 중")

                    // 나브디가 붙을 때까지 여기서 멈춰 있음(연결되면 반환됨)
                    accepted = server.accept()
                    try { server.close() } catch (_: Exception) {}
                    serverSocket = null

                    socket = accepted
                    outputStream = accepted.outputStream
                    connectedAtMs = System.currentTimeMillis()
                    sentCountSinceConnect = 0
                    pingCountSinceConnect = 0
                    receivedCountSinceConnect = 0
                    val remoteName = try { accepted.remoteDevice?.name } catch (e: SecurityException) { null }
                    NavLogger.d("[Navdy] 연결됨(나브디가 걸어옴): ${remoteName ?: "이름 확인 불가"}")

                    // 정품 앱처럼 받는 쪽도 같이 열어둠. 끊길 때까지 여기서 대기했다가,
                    // 끊기면 다시 위로 올라가 새 대기 소켓을 염. #문제시 원복
                    startReaderThread(accepted)
                    startPingThread()   // 4초마다 살아있음 신호(정품 앱과 동일) #문제시 원복
                    readerThread?.join()
                    NavLogger.d("[Navdy] 연결 종료됨 - 다시 대기 상태로 돌아감")
                    closeQuietly()
                } catch (e: SecurityException) {
                    NavLogger.e("[Navdy] 블루투스 권한 없음(주변 기기) - 대기 불가: ${e.message}")
                    listening = false
                } catch (e: Exception) {
                    logIfChanged("listenerr", "[Navdy] 대기 중 오류: ${e.javaClass.simpleName} ${e.message} - 10초 뒤 재시도", isError = true)
                    try { accepted?.close() } catch (_: Exception) {}
                    try { serverSocket?.close() } catch (_: Exception) {}
                    serverSocket = null
                    closeQuietly()
                    try { Thread.sleep(10_000) } catch (_: InterruptedException) { break }
                }
            }
            NavLogger.d("[Navdy] 연결 대기 종료")
        }
        thread.isDaemon = true
        thread.name = "NavdyAcceptor"
        listenerThread = thread
        thread.start()
        startProxyTunnelListening()
        startDialing(context)
    }

    /**
     * 정품 앱처럼 "거는 쪽"도 같이 돌림(위 connectLegacy 주석의 근거 참고).
     * 20초마다 한 번, 아직 연결이 없을 때만 시도. 이름에 navdy 또는 hud가 들어간 기기를
     * 대상으로 하는 것도 정품 앱과 동일(BTDeviceBroadcaster.isDisplay의 정규식 "navdy|hud").
     * #문제시 원복: 이 함수 호출만 빼면 대기만 하는 v19.2.79 동작으로 돌아감
     */
    private fun startDialing(context: Context) {
        val thread = Thread {
            while (listening) {
                try {
                    if (socket?.isConnected != true && !connecting) {
                        val adapter = BluetoothAdapter.getDefaultAdapter()
                        val target = adapter?.bondedDevices?.firstOrNull { d ->
                            val n = (try { d.name } catch (e: SecurityException) { null } ?: "").lowercase()
                            n.contains("navdy") || n.contains("hud")
                        }
                        if (target != null) {
                            connectLegacy(context, target)
                        }
                    }
                    Thread.sleep(20_000)
                } catch (e: InterruptedException) {
                    return@Thread
                } catch (e: Exception) {
                    try { Thread.sleep(20_000) } catch (_: InterruptedException) { return@Thread }
                }
            }
        }
        thread.isDaemon = true
        thread.name = "NavdyDialer"
        thread.start()
    }

    /**
     * 정품 앱과 동일하게 "Navdy-Proxy-Tunnel" 대기 창구도 같이 열어둠(위 상수 주석 참고).
     * 들어온 연결은 기록만 남기고 닫음 - 나브디가 어느 쪽으로 접속하는지 확인하는 용도. #문제시 원복
     */
    @Volatile private var proxyServerSocket: android.bluetooth.BluetoothServerSocket? = null
    private fun startProxyTunnelListening() {
        val thread = Thread {
            while (listening) {
                try {
                    val adapter = BluetoothAdapter.getDefaultAdapter()
                    if (adapter == null || !adapter.isEnabled) {
                        Thread.sleep(10_000)
                        continue
                    }
                    val server = adapter.listenUsingRfcommWithServiceRecord(
                        NAVDY_PROXY_SDP_NAME, NAVDY_PROXY_TUNNEL_UUID
                    )
                    proxyServerSocket = server
                    logIfChanged("proxy", "[Navdy] 프록시 대기 창구도 열림(정품 앱과 동일하게 2개 운영)")
                    val sock = server.accept()
                    NavLogger.d("[Navdy] 프록시 창구로 연결이 들어옴 - 나브디가 이쪽을 쓰는 것으로 보임(기록만 하고 닫음)")
                    try { sock.close() } catch (_: Exception) {}
                    try { server.close() } catch (_: Exception) {}
                    proxyServerSocket = null
                } catch (e: SecurityException) {
                    NavLogger.e("[Navdy] 프록시 대기 권한 없음: ${e.message}")
                    return@Thread
                } catch (e: Exception) {
                    try { proxyServerSocket?.close() } catch (_: Exception) {}
                    proxyServerSocket = null
                    try { Thread.sleep(10_000) } catch (_: InterruptedException) { return@Thread }
                }
            }
        }
        thread.isDaemon = true
        thread.name = "NavdyProxyAcceptor"
        thread.start()
    }

    fun stopListening() {
        listening = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        try { proxyServerSocket?.close() } catch (_: Exception) {}
        proxyServerSocket = null
        listenerThread?.interrupt()
        listenerThread = null
        closeQuietly()
    }

    /**
     * v: 재억 제보(2026-09-02, "나브디 화면 반응 없음") - 정품 앱 소스를 더 깊이 파본 결과
     * v19.2.77의 결론("폰은 받기만 한다")이 반쪽이었음을 확인함. 정품 앱은 **양쪽을 다 함**:
     *
     *   1) 받는 쪽 - ClientConnectionService.getConnectionListeners()
     *        BTSocketAcceptor("Navdy", 1992B7D7-...)
     *   2) 거는 쪽 - ConnectionService.PendingConnectHandler / reconnectRunnable
     *        -> mRemoteDevice.connect() -> Connection.build() -> BTSocketFactory(address)
     *        -> device.createRfcommSocketToServiceRecord(serviceUUID)
     *      이때 쓰는 주소/UUID는 BTRemoteDeviceScanner가 만들어 둔 것으로,
     *        new ServiceAddress(address, NAVDY_PROTO_SERVICE_UUID)  <- 우리가 쓰던 그 UUID
     *      즉 **나브디 기기 쪽에도 같은 UUID의 받는 창구가 있고, 폰이 거기로 건다.**
     *
     *   그리고 기기를 찾는 방법도 확인됨 - BTRemoteDeviceScanner가 블루투스 "검색"으로
     *   찾은 기기 중 이름에 navdy 또는 hud가 들어간 것(BTDeviceBroadcaster.isDisplay,
     *   정규식 "navdy|hud")을 나브디로 판단함. 페어링 목록이 아니라 검색 결과를 씀 -
     *   검색을 돌리면 안드로이드가 그 기기의 서비스 목록(SDP)도 새로 받아오기 때문에,
     *   지금까지 SDP 조회 결과가 로그에 한 줄도 없던 것과 직접 관련이 있을 수 있음.
     *
     * 그래서 이제 대기(받기)와 걸기를 동시에 함. 걸기는 20초마다 한 번씩만 시도해서
     * 예전처럼 로그가 도배되지 않게 하고, 채널 1~5 억지 찍기는 계속 제외(가짜 성공만 만듦).
     * #문제시 원복: startDialing() 호출을 지우면 v19.2.79처럼 대기만 함
     */
    private fun connectLegacy(context: Context, device: BluetoothDevice) {
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
                    logIfChanged("dial1", "[Navdy] 표준(보안) 연결 실패(${e.message}), 비보안 연결 시도")
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
                        // v: 재억 제보(2026-09-02) - 채널 우회는 v19.2.80에서 제거했는데
                        // 이 문구만 예전 그대로 남아 있어서 로그가 헷갈렸음. #문제시 원복
                        logIfChanged("dial2", "[Navdy] 비보안 연결도 실패(${e3.message}) - 이번 시도는 여기서 끝(20초 뒤 재시도)")
                        null
                    }
                    // v: 재억 요청(2026-09-02) - 채널 1~5 억지 찍기는 제거함. 실기기 로그에서
                    // 매번 "채널 2 로 연결 성공" 뒤 2~50ms 만에 상대가 끊는 게 확인됐고
                    // (전송 성공 0회), 이건 나브디 서비스가 아닌 다른 창구에 잘못 붙은
                    // 가짜 성공이었음. 정품 앱도 이런 방식은 쓰지 않음. #문제시 원복
                    insecureSock ?: throw e
                }
                socket = sock
                outputStream = sock.outputStream
                // v: 재억 제보(2026-09-02) - 순정 티맵은 같은 나브디 기기에 문제없이 붙는데
                // 이 앱만 연결 직후 첫 전송에서 100% Broken pipe로 끊기는 걸 로그로 재확인함.
                // 나브디 정품 앱 소스(alelec 오픈소스, ProtobufLink.java)를 직접 대조해보니,
                // 정품은 연결되자마자 반드시 받는 쪽(InputStream)도 같이 읽는 스레드를 띄움 -
                // 이 앱은 지금까지 보내는 쪽(OutputStream)만 쓰고 받는 쪽은 아예 열지도 않았음.
                // 받는 쪽을 안 열어두면 기기/블루투스 스택이 "응답 없는 이상한 연결"로 보고
                // 바로 끊어버리는 것으로 추정됨. 정품처럼 받는 스레드를 같이 띄워서 양방향을
                // 살려둠(받은 내용 자체는 아직 안 쓰므로 그냥 읽어서 버림). #문제시 원복
                startReaderThread(sock)
                startPingThread()   // 4초마다 살아있음 신호(정품 앱과 동일) #문제시 원복
                connectedAtMs = System.currentTimeMillis()
                sentCountSinceConnect = 0
                pingCountSinceConnect = 0
                receivedCountSinceConnect = 0
                NavLogger.d("[Navdy] 연결 성공: ${device.name}")
            } catch (e: Exception) {
                // v: 재억 제보(2026-08-27) - 로그 파일에는 "연결 시도"만 계속 남고 성공/실패 여부가
                // 안 남아서 원인 파악이 안 됐음. 기존엔 android.util.Log만 써서 logcat에만 남고
                // 앱이 공유하는 로그 파일(NavLogger)에는 기록이 안 됐던 게 원인. #문제시 원복
                logIfChanged("dial3", "[Navdy] 연결 실패: ${e.javaClass.simpleName} ${e.message}", isError = true)
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

    private fun connectionAgeMs(): Long =
        if (connectedAtMs == 0L) 0L else System.currentTimeMillis() - connectedAtMs

    fun disconnect() {
        executor.execute { closeQuietly() }
    }

    // v: 재억 제보(2026-09-02) - 나브디 정품 앱처럼 연결 유지 중엔 항상 받는 쪽을 읽어줌.
    // 별도 스레드로 돌리는 이유: executor는 전송(쓰기) 전용 단일 스레드라, 여기서 블로킹
    // read()를 돌리면 전송 루프까지 같이 막힘. 받은 내용 자체는 지금은 안 쓰고 버리기만
    // 하지만, 읽는 동작 자체가 연결을 살아있게 유지하는 데 필요함(위 connect() 주석 참고).
    // 소켓이 끊기면 read()가 예외를 던지며 자연히 스레드가 종료됨. #문제시 원복
    private fun startReaderThread(sock: BluetoothSocket) {
        val thread = Thread {
            NavLogger.d("[Navdy] 받는 스레드 시작")
            try {
                val input: InputStream = sock.inputStream
                val buffer = ByteArray(1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n == -1) {
                        NavLogger.d("[Navdy] 받는 쪽 스트림이 상대방에 의해 정상 종료됨(EOF), ${connectionAgeMs()}ms만에, 그동안 보낸 전송 성공 ${sentCountSinceConnect}회, 살아있음 신호 ${pingCountSinceConnect}회, 수신 ${receivedCountSinceConnect}회")
                        break
                    }
                    // v: 재억 요청(2026-09-02) - 나브디가 4초마다 보내는 신호를 매번 남기면
                    // 로그가 계속 쌓임. 처음 받은 것만 남기고, 총 몇 번 받았는지는 끊길 때
                    // 한 줄로 남김. #문제시 원복
                    receivedCountSinceConnect++
                    if (receivedCountSinceConnect == 1) {
                        NavLogger.d("[Navdy] 나브디에서 데이터 받기 시작(${n}바이트) - 이후 수신은 로그 생략")
                    }
                }
            } catch (e: Exception) {
                // v: 재억 제보(2026-09-02) - "받는 쪽도 로그가 남아야 왜 끊기는지 알지"라는
                // 지적. 여기서 나는 예외가 곧 "받다가 끊긴 이유"이므로 조용히 삼키지 않고
                // 남김 - 전송 쪽 Broken pipe 로그와 대조해서 어느 쪽이 먼저 끊었는지
                // 판단하는 데 씀. 연결 유지 시간/그동안 보낸 성공 횟수도 같이 남겨서
                // "받아보지도 못하고 끊겼는지 vs 한동안 잘 받다 끊겼는지" 구분되게 함. #문제시 원복
                NavLogger.d("[Navdy] 받는 스레드 종료: ${e.javaClass.simpleName} ${e.message}, ${connectionAgeMs()}ms만에, 그동안 보낸 전송 성공 ${sentCountSinceConnect}회, 살아있음 신호 ${pingCountSinceConnect}회, 수신 ${receivedCountSinceConnect}회")
            }
        }
        thread.isDaemon = true
        thread.name = "NavdyReader"
        readerThread = thread
        thread.start()
    }

    private fun closeQuietly() {
        try { outputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        outputStream = null
        socket = null
        readerThread = null
        connectedAtMs = 0L
        sentCountSinceConnect = 0
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
                // v: 재억 요청(2026-09-02) - 예전엔 전송할 때마다(초당 1회) "시도"와 "성공"
                // 두 줄을 남겨서 로그가 계속 쌓였음. 이 연결에서 처음 성공한 한 번만 남기고
                // 나머지는 생략함 - 몇 번 보냈는지는 끊길 때 누적 횟수로 다 나오고,
                // 실패하면 실패 로그가 이유와 함께 따로 남으므로 정보 손실은 없음. #문제시 원복
                writeFramed(stream, eventBytes)
                sentCountSinceConnect++
                lastSentAtMs = System.currentTimeMillis()
                if (sentCountSinceConnect == 1) {
                    NavLogger.d("[Navdy] 첫 전송 성공: 도로=$currentRoad 턴=${turn.name} 거리=$distanceToTurn 크기=${eventBytes.size}B (이후 전송은 로그 생략)")
                }
            } catch (e: Exception) {
                // v: 재억 요청(2026-09-02) - "보내다 끊겼는지 보내보지도 못하고 끊겼는지"까지
                // 바로 알 수 있게, 이 연결에서 지금까지 성공한 전송 횟수와 연결 유지 시간을
                // 같이 남김. sentCountSinceConnect==0 이면 이 연결은 단 한 번도 전송에
                // 성공하지 못하고 끊긴 것. e.message에는 writeFramed()가 붙인 단계 정보
                // ([길이헤더 전송 중]/[본문 전송 중]/[flush 중])가 포함되어 있어 정확히
                // 어느 단계에서 끊겼는지도 같이 남음. #문제시 원복
                NavLogger.e("[Navdy] 전송 실패로 연결 끊김 처리: ${e.javaClass.simpleName} ${e.message}, 이 연결에서 성공 ${sentCountSinceConnect}회 후 끊김, 살아있음 신호 ${pingCountSinceConnect}회, 연결 유지 ${connectionAgeMs()}ms")
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

    // v: 재억 제보(2026-09-02, 로그로 원인 확정) - v19.2.80에서 처음으로 연결과 전송이
    // 실제로 성공했음(19:29:52 연결 -> 전송 성공 7회 -> 나브디가 보낸 데이터 12회 수신).
    // 그런데 26.9초 만에 나브디가 연결을 끊었음.
    //
    // 로그의 수신 패턴이 답이었음: 처음 194바이트(첫 인사로 보임) 뒤로 4초마다 5~12바이트짜리
    // 작은 데이터가 계속 들어왔는데, 이 앱은 그걸 읽고 버리기만 하고 아무 답도 안 보냈음.
    // 정품 앱 소스를 확인하니 ConnectionService.sendPingIfNeeded()가
    //   "마지막으로 뭔가 보낸 지 4초가 넘었으면 Ping 이벤트를 보낸다"
    // 를 계속 반복하고 있었음. 즉 서로 4초마다 살아있다는 신호를 주고받는 구조인데,
    // 우리가 답을 안 해서 나브디가 죽은 연결로 보고 끊은 것.
    //
    // Ping 이벤트 형식도 같은 소스에서 확인함:
    //   NavdyEvent.MessageType.Ping = 40
    //   Ext_NavdyEvent.ping = 확장 태그 140, 내용은 빈 메시지
    // #문제시 원복: startPingThread() 호출을 빼면 됨
    private const val MSG_TYPE_PING = 40
    private const val NAVDY_EVENT_EXT_TAG_PING = 140
    private const val PING_INTERVAL_MS = 4000L

    @Volatile private var pingThread: Thread? = null
    @Volatile private var lastSentAtMs = 0L

    private fun buildPingEvent(): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarintField(out, NAVDY_EVENT_TAG_TYPE, MSG_TYPE_PING.toLong())
        // 내용이 없는 빈 메시지를 확장 태그 140에 담음
        writeEmbeddedMessage(out, NAVDY_EVENT_EXT_TAG_PING, ByteArray(0))
        return out.toByteArray()
    }

    private fun startPingThread() {
        pingThread?.interrupt()
        val thread = Thread {
            while (socket?.isConnected == true) {
                try {
                    Thread.sleep(1000)
                    val stream = outputStream ?: break
                    if (System.currentTimeMillis() - lastSentAtMs < PING_INTERVAL_MS) continue
                    synchronized(this) {
                        writeFramed(stream, buildPingEvent())
                        stream.flush()
                        lastSentAtMs = System.currentTimeMillis()
                        pingCountSinceConnect++
                    }
                    // 첫 핑만 로그로 남김(4초마다 찍으면 로그 도배됨). 나머지는 끊길 때
                    // 누적 횟수로 확인 가능. #문제시 원복
                    if (pingCountSinceConnect == 1) {
                        NavLogger.d("[Navdy] 살아있음 신호(Ping) 전송 시작 - 이후 4초마다 자동 전송")
                    }
                } catch (e: InterruptedException) {
                    return@Thread
                } catch (e: Exception) {
                    NavLogger.d("[Navdy] 살아있음 신호(Ping) 전송 실패 - 연결 끊긴 것으로 보고 정리: ${e.message}")
                    closeQuietly()
                    return@Thread
                }
            }
        }
        thread.isDaemon = true
        thread.name = "NavdyPing"
        pingThread = thread
        thread.start()
    }

    private fun buildNavdyEvent(maneuverEventBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        // tag2 required: type = NavigationManeuverEvent(8)
        writeVarintField(out, NAVDY_EVENT_TAG_TYPE, MSG_TYPE_NAVIGATION_MANEUVER_EVENT.toLong())
        // tag108 extension: embedded message
        writeEmbeddedMessage(out, NAVDY_EVENT_EXT_TAG_NAVIGATION_MANEUVER, maneuverEventBytes)
        return out.toByteArray()
    }

    // v: 재억 요청(2026-09-02) - "보내다 끊어졌는지 아니면 보내보지도 못하고 끊어졌는지"까지
    // 구분해달라는 지적. 헤더/본문/flush 세 단계를 각각 try로 감싸서, 실패하면 어느 단계에서
    // 끊겼는지를 예외 메시지 앞에 붙여서 던짐 - sendManeuver()의 catch 로그에 그대로 찍힘. #문제시 원복
    private fun writeFramed(stream: OutputStream, payload: ByteArray) {
        // Frame { tag1 required FIXED32 size } — Wire의 FIXED32는 리틀엔디안 4바이트
        val frame = ByteArrayOutputStream()
        frame.write((0x1.shl(3)) or 5) // tag1, wiretype5(=FIXED32)
        frame.write(payload.size and 0xFF)
        frame.write((payload.size shr 8) and 0xFF)
        frame.write((payload.size shr 16) and 0xFF)
        frame.write((payload.size shr 24) and 0xFF)
        val frameBytes = frame.toByteArray()
        try {
            stream.write(frameBytes)
        } catch (e: IOException) {
            throw IOException("[길이헤더(${frameBytes.size}B) 전송 중 끊김] ${e.message}", e)
        }
        try {
            stream.write(payload)
        } catch (e: IOException) {
            throw IOException("[본문(${payload.size}B) 전송 중 끊김 - 헤더는 보냈음] ${e.message}", e)
        }
        try {
            stream.flush()
        } catch (e: IOException) {
            throw IOException("[flush 중 끊김 - 헤더+본문은 다 썼음] ${e.message}", e)
        }
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
