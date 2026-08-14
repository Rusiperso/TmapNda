package com.tmap.nda

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import com.google.gson.Gson
import com.tmapmobility.tmap.tmapsdk.ui.util.TmapUISDK
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import android.net.wifi.WifiManager
import android.os.PowerManager
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class UdpSenderService : Service() {

    private val CHANNEL_ID = "TmapNdaChannel"
    private val UDP_PORT = 7706
    private var targetIp = "255.255.255.255"

    // v9.3: 정지 시 남은시간 0 되는 문제 우회용 - 직전 유효 남은시간(초)과 그 시각. #문제시 원복
    private var lastValidGoPosTime = 0
    private var lastValidGoPosTimeAt = 0L

    // v: 사용자 요청 - "내 폰이 남의 핫스팟에 붙는 경우"랑 "내 폰이 직접 핫스팟을 켜고
    // 콤마가 거기 붙는 경우" 둘 다 항상 되게 해달라고 함. 이 둘은 안드로이드 입장에서
    // 완전히 다르게 인식됨:
    //  1) 클라이언트 모드(핫스팟에 붙는 쪽) - ConnectivityManager가 TRANSPORT_WIFI
    //     네트워크로 잡아줌 -> Network.bindSocket()으로 명시적으로 그 쪽에 소켓을 묶음.
    //  2) 호스트 모드(직접 핫스팟 켜는 쪽) - 자기 자신의 핫스팟은 ConnectivityManager
    //     기준으로는 "연결된 네트워크"가 아니라서(그냥 내 인터페이스일 뿐) 1번 방식으로는
    //     못 찾음. 이 경우 NetworkInterface를 직접 뒤져서 모바일 데이터(rmnet 계열)가
    //     아닌 로컬 인터페이스(보통 wlan0/ap0/swlan0)를 찾아서 그 IP로 소켓을 직접 bind.
    // (사용자 제보: 헤드유닛에 Wi-Fi+모바일데이터 동시 활성 시 UDP가 모바일 쪽으로 새서
    // openpilot 비콘을 몇 분간 못 받던 문제의 원인으로 추정) #문제시 원복
    //
    // 수신(listen) 소켓 전용 - 이미 고정 포트로 0.0.0.0에 bind()하는 소켓들이라 여기서
    // 추가로 로컬 인터페이스에 bind()하면 "이미 바인딩됨" 예외가 남. ConnectivityManager로
    // Wi-Fi 네트워크를 찾을 수 있을 때만(클라이언트 모드) 묶어주고, 못 찾으면(호스트 모드
    // 등) 그냥 넘어감 - 어차피 0.0.0.0으로 듣는 소켓은 모든 인터페이스에서 수신 가능해서
    // 호스트 모드에서도 수신 자체는 원래 문제없음.
    private fun bindToWifiIfAvailable(socket: DatagramSocket, label: String) {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val wifiNetwork = cm.allNetworks.firstOrNull { net ->
                val caps = cm.getNetworkCapabilities(net)
                caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }
            if (wifiNetwork != null) {
                wifiNetwork.bindSocket(socket)
                NavLogger.d(this, "[네트워크] $label 소켓을 Wi-Fi 네트워크(클라이언트 모드)에 바인딩함")
            } else {
                NavLogger.d(this, "[네트워크] $label - Wi-Fi 클라이언트 네트워크 없음(핫스팟 호스트 모드일 수 있음), 0.0.0.0 수신이라 문제없음")
            }
        } catch (e: Exception) {
            NavLogger.e(this, "[네트워크] $label Wi-Fi 바인딩 실패: ${e.message}")
        }
    }

    // 송신 소켓 전용 - 클라이언트/호스트 모드 둘 다 커버(위 주석 참고). 호출 전에 소켓이
    // 아직 아무 데도 안 묶인 상태(DatagramSocket(null))여야 함.
    // v: 재억 제보(2026-08-12) - v5.0에선 연결 정상, v7.0에서 연결 안 됨. 원인 확정:
    // Network.bindSocket()은 "어느 네트워크로 내보낼지"만 지정하고 실제 로컬 포트를
    // 할당하지 않아서, 호출 후에도 socket.localPort가 여전히 -1(안 묶인 것처럼) 나올 수
    // 있음. 그러면 호출부의 "if (socket.localPort <= 0)" 체크가 "아직 안 묶였다"고
    // 오판해서, 이미 Network.bindSocket()으로 묶인 소켓에 또 bind()를 시도 - 이게
    // 기기/안드로이드 버전에 따라 SocketException("already bound")을 던져서 송신
    // 소켓 자체가 깨진 채로 남을 수 있었음. localPort로 성공 여부를 추측하지 않고
    // 함수가 직접 Boolean으로 성공 여부를 반환하도록 변경. #문제시 원복
    private fun bindSendSocketToLocalNetwork(socket: DatagramSocket, label: String): Boolean {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val wifiNetwork = cm?.allNetworks?.firstOrNull { net ->
                val caps = cm.getNetworkCapabilities(net)
                caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }
            if (wifiNetwork != null) {
                val linkProps = cm.getLinkProperties(wifiNetwork)
                val ifaceName = linkProps?.interfaceName ?: "알수없음"
                // v: 재억 제보(2026-08-13, SM-S711N "iPhone" 핫스팟 사례) - 폰이 자기
                // 핫스팟을 직접 켠 "호스트 모드"인데도, 이 기기(삼성 커스텀 롬 일부 기종)는
                // ConnectivityManager가 그 자기 핫스팟을 마치 일반 Wi-Fi 클라이언트 연결인 것
                // 처럼 TRANSPORT_WIFI 네트워크로 노출함. 그래서 Network.bindSocket()이
                // EPERM 없이 "성공"까지 하지만, 실제로는 그 네트워크 객체에 브로드캐스트
                // (255.255.255.255) 라우트가 없어서 매번 ENETUNREACH로 이어짐 - 50분간
                // 574회 반복 확인. 호스트 모드 인터페이스 이름(swlan0/ap0/softap류)이면
                // 아예 Network.bindSocket()을 쓰지 말고, 원래 EPERM일 때만 타던 아래
                // "로컬 인터페이스 직접 bind" 폴백으로 바로 넘어가도록 변경. 그쪽은 실제
                // 인터페이스의 커널 라우팅 테이블을 그대로 쓰기 때문에 브로드캐스트가
                // 정상적으로 나감. #문제시 원복
                val looksLikeHostMode = ifaceName.lowercase().let {
                    it.contains("swlan") || it.contains("softap") || it.startsWith("ap")
                }
                if (looksLikeHostMode) {
                    NavLogger.d(this, "[네트워크] $label: Wi-Fi 네트워크가 호스트모드 인터페이스($ifaceName)로 보여 Network.bindSocket() 건너뛰고 로컬 인터페이스 직접 바인딩으로 폴백")
                } else {
                    wifiNetwork.bindSocket(socket)
                    // v: 재억 제보(2026-08-13) - ENETUNREACH 자가치유(소켓 재생성)를 계속 반복해도
                    // 안 풀리는 사례 확인(50분간 574회 실패, 재생성 580회 다 무의미). "죽은 네트워크
                    // 객체에 한 번 묶인 뒤 안 풀리는" 기존 케이스라면 재생성 한 번으로 회복됐어야
                    // 하는데 안 됐다는 건, 매번 다시 잡히는 Wi-Fi 네트워크 자체가 콤마로 가는
                    // 경로를 못 찾고 있다는 뜻(AP 격리, 잘못된 서브넷, 이 기기 특유의 멀티
                    // 네트워크 스택 등 후보). 재생성 성공 로그에 "성공했다"는 것만 남고 정작
                    // 어느 네트워크(인터페이스/링크주소/netId)에 묶였는지가 없어서 원인 후보를
                    // 못 좁혔음 - LinkProperties와 netId를 실제로 찍어서 다음 로그에서 매번
                    // 같은 죽은 네트워크에 묶이는지 vs 다른 Wi-Fi를 찾아도 다 막히는지 구분
                    // 가능하게 함. #문제시 원복
                    val linkAddrs = linkProps?.linkAddresses?.joinToString(", ") { it.address.hostAddress ?: "?" } ?: "없음"
                    val netId = try {
                        val f = wifiNetwork.javaClass.getDeclaredField("netId")
                        f.isAccessible = true
                        f.getInt(wifiNetwork)
                    } catch (e: Exception) { -1 }
                    NavLogger.d(this, "[네트워크] $label: Wi-Fi 네트워크(클라이언트 모드)에 바인딩함 (netId=$netId, iface=$ifaceName, 주소=$linkAddrs)")
                    return true
                }
            }
        } catch (e: Exception) {
            NavLogger.e(this, "[네트워크] $label Wi-Fi 네트워크 바인딩 시도 실패: ${e.message}")
        }

        // 클라이언트로 잡히는 Wi-Fi 네트워크가 없으면(=내 폰이 핫스팟을 켜고 있는 호스트
        // 모드일 가능성) 모바일 데이터가 아닌 로컬 인터페이스를 직접 찾아서 그쪽으로 바인딩.
        try {
            val candidates = mutableListOf<Pair<java.net.NetworkInterface, java.net.Inet4Address>>()
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                // v: 재억 제보(2026-08-13, 다른 차량 "NAV 안 뜸") - Wi-Fi 네트워크에는
                // 있는데(netId 207) Network.bindSocket()이 EPERM으로 거부됨(안드로이드가
                // 앱이 자기 폰의 핫스팟 호스트 네트워크에 직접 바인딩하는 걸 막는 정책 -
                // 코드로 우회 불가, 정상적인 OS 동작). 그래서 이 폴백(로컬 인터페이스 직접
                // 탐색)으로 넘어오는데, 여기서 모바일 인터페이스를 걸러내려던
                // startsWith("rmnet") 체크가 "v4-rmnet_data0" 같은 이름은 못 걸러냄 -
                // IPv6-only 통신사 회선에서 커널이 CLAT(464xlat) 변환용 가상 인터페이스에
                // "v4-" 접두어를 붙이기 때문. 그 결과 모바일데이터가 그대로 선택돼서 콤마
                // (Wi-Fi 대역)로 영원히 도달 못 하는 ENETUNREACH가 반복됐음. startsWith
                // 대신 contains로 바꿔서 "v4-" 접두어가 붙어도 걸러지게 함. 같은 로그에서
                // VPN(tun0)도 같이 떠있는 게 확인돼서, 순서상 tun0이 먼저 잡히면 똑같은
                // 문제가 재발할 수 있어 VPN류도 제외. #문제시 원복
                if (name.contains("rmnet") || name.contains("ccmni") || name.contains("wwan") ||
                    name.contains("tun") || name.contains("ppp") || name.contains("clat")
                ) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is java.net.Inet4Address) {
                        candidates.add(iface to addr)
                    }
                }
            }
            // Wi-Fi/핫스팟으로 보이는 이름(wlan/swlan/ap0 등)을 우선 선택하고, 없으면
            // 후보 중 첫 번째로 폴백.
            val wifiLike = candidates.firstOrNull { (iface, _) ->
                val n = iface.name.lowercase()
                n.contains("wlan") || n.contains("softap") || n.startsWith("ap")
            }
            val chosen = wifiLike ?: candidates.firstOrNull()
            if (chosen != null) {
                val (iface, addr) = chosen
                socket.bind(java.net.InetSocketAddress(addr, 0))
                NavLogger.d(this, "[네트워크] $label: 로컬 인터페이스(${iface.name}=${addr.hostAddress})에 직접 바인딩함 (핫스팟 호스트 모드 추정, 후보 ${candidates.size}개 중 선택)")
                return true
            }
            NavLogger.d(this, "[네트워크] $label: 바인딩 가능한 Wi-Fi/핫스팟 인터페이스를 못 찾음 - 기본 라우팅 사용")
        } catch (e: Exception) {
            NavLogger.e(this, "[네트워크] $label 로컬 인터페이스 바인딩 실패: ${e.message}")
        }
        return false
    }

    // ===== NDA(EON:ROAD_LIMIT_SERVICE:v1) 호환 브릿지 관련 상수 =====
    // road_speed_limiter.py (carrot 계열이 아닌 다수의 HKG 커뮤니티 fork에도 포함된 표준 UDP 브릿지) 프로토콜을
    // 그대로 흉내내어, nMirror 없이도 이 프로토콜을 쓰는 openpilot과 직접 연동한다.
    //  - openpilot -> phone : "EON:ROAD_LIMIT_SERVICE:v1" 비콘(5초 주기, broadcast) + GPS 위치 JSON, 전부 UDP 2899
    //  - phone -> openpilot : road_limit / active / request_gps JSON, UDP 843 (실패시 2843)
    private val NDA_BEACON = "EON:ROAD_LIMIT_SERVICE:v1"
    private val NDA_LISTEN_PORT = 2899
    private val NDA_SEND_PORT_PRIMARY = 843
    private val NDA_SEND_PORT_FALLBACK = 2843
    private val NDA_SEND_PORT_EXTRA = 3843
    private val NDA_SEND_PORTS = intArrayOf(NDA_SEND_PORT_PRIMARY, NDA_SEND_PORT_FALLBACK, NDA_SEND_PORT_EXTRA)
    private val NDA_ACTIVE_TIMEOUT_MS = 8000L

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var udpSocket: DatagramSocket? = null

    // isRunning: 서비스 전체 생존 여부가 아니라, "루프들이 이미 시작되었는가"를 나타내는 가드로 사용.
    // onStartCommand가 여러 번 호출되어도(START_STICKY 재시작, 인텐트 재전달 등) 소켓/코루틴이
    // 중복 생성되지 않도록 막는다. 이게 없으면 startReceivingLoop() 등이 두 번 실행되면서
    // 같은 DatagramSocket 필드에 여러 코루틴이 동시에 receive()를 거는 경쟁 상태가 생기고,
    // 한쪽에서 close()가 불리는 순간 다른 쪽에서 EBADF(Bad file descriptor)가 터진다.
    private val isRunning = AtomicBoolean(false)
    private val gson = Gson()

    private var packetIndex = 0
    private var wakeLock: PowerManager.WakeLock? = null

    // NDA 브릿지 상태
    private var ndaListenSocket: DatagramSocket? = null
    private var ndaSendSocket: DatagramSocket? = null
    @Volatile private var ndaRemoteAddr: InetAddress? = null
    @Volatile private var ndaRemotePort = NDA_SEND_PORT_PRIMARY
    @Volatile private var ndaLastBeaconTime = 0L

    // Latest Safe Drive Info
    // v4.13: roadLimitSpeed는 openpilot에 보내는 nRoadLimitSpeed용으로, 카메라 개별
    // 제한속도(sdiSpeedLimit)가 들어오면 그 값으로도 덮어써짐(구간단속 카메라 감속 등을
    // openpilot에 알려주기 위한 의도적 설계). 문제는 이 변수를 그대로
    // SdiDataRepository.roadLimitSpeed(=과속경고음이 읽는 "도로 기본 제한속도")에도
    // 흘려보내고 있어서, 카메라 옆을 지날 때 카메라의 개별 제한속도가 도로 기본
    // 제한속도인 것처럼 오인식돼 "10% 안 넘었는데 경고음" 오탐이 발생했음(사용자 지적).
    // 카메라 영향을 받는 이 값과 별개로, 순수 "도로 기본 제한속도"만 추적하는
    // generalRoadLimitSpeed를 새로 둬서 SdiDataRepository/알람 쪽엔 이걸로만 전달. #문제시 원복
    private var roadLimitSpeed = 0
    private var generalRoadLimitSpeed = 0
    // v4.2: roadLimitSpeed가 한 번 값이 들어오면(30 이상) 절대 안 낮아지는 버그 수정용 -
    // 마지막으로 유효한 값을 받은 시각. #문제시 원복
    private var lastRoadLimitUpdateTime = 0L
    private var lastGeneralRoadLimitUpdateTime = 0L
    private var sdiType = 0
    private var sdiSpeedLimit = 0
    private var sdiDistance = 0
    private var sdiBlockType = 0
    private var sdiBlockSpeed = 0
    private var sdiBlockDist = 0

    @Volatile
    private var currentGpsStatusText = "탐색 중"

    // v: 재억 실사용 로그로 확인됨(2026-08-12) - v7.0에서 소켓을 특정 Wi-Fi 네트워크에
    // 명시적으로 바인딩(Network.bindSocket())하기 시작했는데, 그 네트워크 연결이 중간에
    // 바뀌거나 무효화되면 소켓이 죽은 네트워크에 영원히 묶인 채로 남아서 그 뒤로
    // ENETUNREACH만 계속 반복됨(로그 확인: 1시간 15분 동안 26,619번 연속 실패, 단 한
    // 번도 자연 복구 안 됨). 이 함수를 별도로 빼서, sendSdiData()에서 ENETUNREACH
    // 감지되면 소켓을 통째로 버리고 새로 만들어 재바인딩하도록 함(자가치유). #문제시 원복
    private fun createUdpSendSocket() {
        try {
            udpSocket?.close()
        } catch (e: Exception) {
            // 이미 죽은 소켓 닫다가 나는 예외는 무시
        }
        try {
            val socket = DatagramSocket(null)
            val bindSucceeded = bindSendSocketToLocalNetwork(socket, "openpilot 전송용")
            if (!bindSucceeded) {
                socket.bind(java.net.InetSocketAddress(0))
            }
            socket.broadcast = true
            udpSocket = socket
            NavLogger.d(this, "openpilot 전송용 UDP 소켓 생성 성공 (port=$UDP_PORT, 실제바인딩=${socket.localAddress?.hostAddress}:${socket.localPort})")
        } catch (e: Exception) {
            NavLogger.e(this, "openpilot 전송용 UDP 소켓 생성 실패: ${e.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        NavLogger.d(this, "===== UdpSenderService.onCreate() 호출됨 - 서비스 생성됨 =====")
        createNotificationChannel()
        // v4.18: "앱이 실행이 안되고 튕겨" - 영상으로 확인함. startForeground()를
        // FOREGROUND_SERVICE_TYPE_LOCATION으로 부르는데 ACCESS_BACKGROUND_LOCATION 권한
        // 체크가 전혀 없었음 - 이 권한이 없으면 안드로이드 10+에서 SecurityException으로
        // 그 자리에서 바로 죽음. 그리고 MainActivity의 "항상 허용" 요청 자체가 이 기기/버전
        // 에서는 다이얼로그도 안 뜨고 즉시 거부돼서(안드로이드 11+부터 흔한 동작 - 설정
        // 앱을 통해서만 허용 가능), 매번 여기서 크래시 -> 재시작 -> 다시 크래시 무한루프에
        // 빠졌던 것. 권한 있으면 기존대로 location 타입, 없으면 일반 포그라운드 서비스로
        // 안전하게 낮춰서 최소한 앱이 안 죽게 함. #문제시 원복
        val hasBackgroundLocation = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasBackgroundLocation) {
                startForeground(1, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(1, createNotification())
            }
        } catch (e: Exception) {
            // 그래도 혹시 다른 이유(제조사별 정책 등)로 예외가 나도 앱 자체는 안 죽게 최종 방어
            NavLogger.e(this, "startForeground 실패 - 백그라운드 위치 권한 없이 계속 진행: ${e.message}")
            try { startForeground(1, createNotification()) } catch (e2: Exception) {
                NavLogger.e(this, "startForeground 재시도도 실패: ${e2.message}")
            }
        }

        createUdpSendSocket()

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TmapNda::UdpSenderWakelock")
            wakeLock?.acquire()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // GPS 상태 모니터링 등록
        try {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            locationManager.registerGnssStatusCallback(object : android.location.GnssStatus.Callback() {
                override fun onStarted() {
                    currentGpsStatusText = "탐색 중"
                }
                override fun onStopped() {
                    currentGpsStatusText = "NO_SIGNAL"
                }
                override fun onFirstFix(ttffMillis: Int) {
                    currentGpsStatusText = "수신 양호"
                }
                override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
                    var usedInFix = 0
                    for (i in 0 until status.satelliteCount) {
                        if (status.usedInFix(i)) usedInFix++
                    }
                    if (usedInFix >= 4) {
                        currentGpsStatusText = "GOOD($usedInFix)"
                    } else {
                        currentGpsStatusText = "BAD($usedInFix)"
                    }
                }
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        } catch (e: SecurityException) {
            currentGpsStatusText = "권한 없음"
        }

        // v4.24: "맨날 뭐만하면 로그 가져오게 코드 넣는다고 하지 말고, 가져올 수 있는 건
        // 다 가져오게 해라"(사용자) - 매번 새 버그마다 로그 한 줄씩 추가하는 대신, 앱의 거의
        // 모든 핵심 상태를 15초마다 한 줄로 통째로 남기는 "전체상태 스냅샷"을 상시 가동.
        // 앞으로 어떤 문제가 나와도 이 로그 하나로 그 시점 전후 상황을 다 알 수 있게 함.
        // 차량 HUD(TmapNdaCarAppService) 연결 여부도 포함. #문제시 원복
        serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    logFullStateSnapshot()
                } catch (e: Exception) {
                    NavLogger.e(this@UdpSenderService, "[전체상태] 스냅샷 예외: ${e.message}")
                }
                delay(15000)
            }
        }
    }

    private fun logFullStateSnapshot() {
        val ctx = this
        val opState = OpenpilotStateRepository.state.value
        val ndaGps = OpenpilotStateRepository.ndaGpsState.value
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) { "?" }
        val volumePercent = try { VolumeHelper.savedVolumePercent(ctx) } catch (e: Exception) { -1 }
        val am = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        val musicVol = am?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val musicMax = am?.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        val memInfo = android.app.ActivityManager.MemoryInfo()
        (getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)?.getMemoryInfo(memInfo)
        val batteryPct = try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) { null }

        NavLogger.d(
            ctx,
            "[전체상태] v=$versionName " +
                "연결(ip=${opState?.ip ?: "-"}, active=${opState?.active}, displayActive=${opState?.displayActive}, " +
                "flickering=${opState?.isFlickering}, 마지막수신=${opState?.lastUpdateTime?.let { System.currentTimeMillis() - it }}ms전) " +
                "도로제한(camera=$roadLimitSpeed, general=$generalRoadLimitSpeed) " +
                "SDI(type=$sdiType, limit=$sdiSpeedLimit, dist=$sdiDistance) " +
                "차선(source=${LaneSignalRepository.source}, 개수=${LaneSignalRepository.lanes.size}, fresh=${LaneSignalRepository.isFresh()}) " +
                "볼륨(저장%=$volumePercent, STREAM_MUSIC=$musicVol/$musicMax) " +
                "HUD(everConnected=${com.tmap.nda.hud.TmapNdaCarAppService.everConnected} - AndroidAuto Cluster용, 이 구조에선 항상 false가 정상) " +
                "AA연결방식(${OpenpilotStateRepository.carConnectionTypeLabel}) " +
                // v: 사용자 요청(재억, 2026-08-12) - AutoWatchHelper 검증용. 사용정보 접근
                // 권한 없으면 항상 false로 찍힘(정상) - 설정에서 허용 후 재확인 필요. #문제시 원복
                "AA포그라운드(권한=${AutoWatchHelper.hasUsageAccessPermission(ctx)}, foreground=${AutoWatchHelper.isAndroidAutoForeground(ctx)}) " +
                "구형NDA비콘(addr=${ndaRemoteAddr}, GPS=${ndaGps?.hasFix} - openpilot 포크에 따라 지원 여부가 다름, 재억 본인 차량은 미지원이 정상) " +
                "폰IP=${getLocalIpAddressesSummary()} " +
                "배터리=${batteryPct}% 메모리여유=${memInfo.availMem / 1024 / 1024}MB/${memInfo.totalMem / 1024 / 1024}MB lowMemory=${memInfo.lowMemory} " +
                "기기=${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL} SDK=${android.os.Build.VERSION.SDK_INT}"
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NavLogger.d(this, "===== UdpSenderService.onStartCommand() 호출됨 (intent=${if (intent == null) "null(시스템이 재시작시킴)" else "있음"}, isRunning=${isRunning.get()}) =====")
        val sharedPref = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
        targetIp = sharedPref.getString("TARGET_IP", "255.255.255.255") ?: "255.255.255.255"

        // 핵심 수정: onStartCommand가 여러 번 호출되어도 루프/소켓은 딱 한 번만 시작.
        // 이미 돌고 있으면(compareAndSet 실패) 아무것도 하지 않고 targetIp 갱신만 반영.
        if (isRunning.compareAndSet(false, true)) {
            startObservingEDC()
            startSendingLoop()
            startReceivingLoop()
            startNdaListenLoop()
            startNdaSendLoop()
            startCommaHttpNaviLoop()
            startMainConnectionWatchdog()
        }

        return START_STICKY
    }

    private var lastSdiJsonStr: String? = null
    private var lastSdiUpdateTime = 0L
    private var lastSdiPlusJsonStr: String? = null
    private var lastSdiPlusUpdateTime = 0L

    private var fullBundleDumped = false
    private var lastSendErrorLogTime = 0L
    // v: 재억 실사용 로그로 확인됨(2026-08-12) - 콤마를 물리적으로 뗀 뒤 3분 넘게 데이터가
    // 안 왔는데도 "5초간 UDP 수신 없음" 로그가 단 한 번도 안 찍힘 = socket.soTimeout(5000)이
    // 실제로는 전혀 작동을 안 하고 있었음(이 기기/커스텀 ROM에서 Network.bindSocket() 이후
    // soTimeout이 무시되는 걸로 추정). 소켓 타임아웃에 의존하지 않고, 마지막으로 실제 패킷을
    // 받은 시각을 직접 기록해뒀다가 별도의 감시 타이머(startMainConnectionWatchdog)가
    // 독립적으로 체크하도록 변경 - socket.receive()가 블로킹된 채로 안 풀려도 이 감시는
    // 정상 작동함. #문제시 원복
    @Volatile private var lastPacketReceivedTime = 0L
    private var lastSocketRecreateTime = 0L

    private val edcObserver = Observer<Bundle> { bundle ->
        if (bundle != null && isRunning.get()) {
            // 이전엔 10초마다 영구 반복 덤프해서 장시간 주행 시 로그가 계속 쌓였음.
            // 진단 목적(안 쓰는 필드 확인)이면 세션당 1번이면 충분해서 1회성으로 변경. #문제시 원복
            if (!fullBundleDumped) {
                fullBundleDumped = true
                try {
                    NavLogger.e(this, "===== EDCData bundle 전체 키 덤프 =====")
                    for (key in bundle.keySet()) {
                        val value = bundle.get(key)
                        NavLogger.e(this, "EDCData[$key] = $value (${value?.javaClass?.simpleName})")
                    }
                } catch (e: Exception) {
                    NavLogger.e(this, "EDCData 전체 덤프 실패: ${e.message}")
                }
            }

            serviceScope.launch {
                try {
                    val json = JSONObject()
                    json.put("carrotIndex", packetIndex++)
                    json.put("navitype", "tmap")

                    // 1. limitSpeed 처리
                    val limitSpeedStr = bundle.getString("limitSpeed", bundle.getInt("limitSpeed", 0).toString())
                    val currentLimitSpeed = limitSpeedStr.toIntOrNull() ?: 0
                    // v8.8: 사용자 재제보(2026-08-13) - "10% 초과 옵션 켜도 여전히 계속 울림".
                    // v4.13에서 realRoadLimit(엔진 리플렉션) 경로는 카메라 근처면 도로 기본값으로
                    // 안 덮어쓰게 고쳤는데, 이 EDCData bundle의 "limitSpeed" 필드도 똑같이 카메라
                    // 근처에서 카메라 개별 제한속도를 리턴하는 경우가 있다는 걸 놓쳤음(같은 종류의
                    // 버그가 다른 통로로 남아있었음). 이번 프레임에 카메라 제한속도(firstSDIInfo의
                    // nSdiSpeedLimit)가 있으면 여기서도 generalRoadLimitSpeed는 안 건드림
                    // (roadLimitSpeed는 openpilot 카메라 감속용이라 그대로 반영해도 무방). #문제시 원복
                    val cameraLimitPeekThisFrame = try {
                        val sdiObjPeek = bundle.get("firstSDIInfo")
                        val sdiJsonPeek = when {
                            sdiObjPeek is String -> JSONObject(sdiObjPeek)
                            sdiObjPeek != null -> JSONObject(gson.toJson(sdiObjPeek))
                            else -> null
                        }
                        (sdiJsonPeek?.optInt("nSdiSpeedLimit", 0) ?: 0) >= 30
                    } catch (e: Exception) { false }
                    if (currentLimitSpeed >= 30) {
                        roadLimitSpeed = currentLimitSpeed
                        lastRoadLimitUpdateTime = System.currentTimeMillis()
                        if (!cameraLimitPeekThisFrame) {
                            generalRoadLimitSpeed = currentLimitSpeed
                            lastGeneralRoadLimitUpdateTime = System.currentTimeMillis()
                        }
                    }

                    // 2. firstSDIInfo (GRT47과 동일하게 모든 key를 최상위로 복사)
                    val sdiObj = bundle.get("firstSDIInfo")
                    if (sdiObj != null) {
                        lastSdiJsonStr = if (sdiObj is String) sdiObj else gson.toJson(sdiObj)
                        lastSdiUpdateTime = System.currentTimeMillis()
                    } else if (System.currentTimeMillis() - lastSdiUpdateTime > 2000) {
                        lastSdiJsonStr = null
                    }

                    if (lastSdiJsonStr != null) {
                        val sdiJson = JSONObject(lastSdiJsonStr)

                        val keys = sdiJson.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            json.put(k, sdiJson.get(k))
                        }

                        // Fallback logic for point camera (사용자 피드백 반영)
                        val sdiType = json.optInt("nSdiType", 0)
                        val sdiSpeedLimit = json.optInt("nSdiSpeedLimit", 0)
                        var sdiDist = json.optInt("nSdiDist", 0)
                        val bSdiBlockSection = json.optBoolean("bSdiBlockSection", false)

                        var nSdiBlockType = 0
                        if (bSdiBlockSection) {
                            nSdiBlockType = if (sdiType == 3) 3 else 2
                        } else if (sdiType == 2) {
                            nSdiBlockType = 1
                        }
                        if (nSdiBlockType > 0) {
                            json.put("nSdiBlockType", nSdiBlockType)
                            json.put("nSdiSection", 1) // 사용자 요청에 따라 강제로 1 고정
                        }

                        if (sdiType == 22) {
                            if (sdiDist <= 0) {
                                sdiDist = 150
                                json.put("nSdiDist", 150)
                            }
                            json.put("roadcate", 8)
                        }

                        if (sdiSpeedLimit >= 30) {
                            // v4.13: 이건 카메라(구간단속/스쿨존 등)의 개별 제한속도라
                            // generalRoadLimitSpeed는 절대 건드리지 않음 - roadLimitSpeed만
                            // (openpilot 카메라 감속용) 갱신. #문제시 원복
                            roadLimitSpeed = sdiSpeedLimit
                            lastRoadLimitUpdateTime = System.currentTimeMillis()
                        }

                        if (sdiType == 0 && sdiSpeedLimit > 0 && sdiDist > 0) {
                            json.put("nSdiType", 1) // 강제로 1로 세팅
                        }
                    }

                    // 1.5. Reflection을 통한 도로 기본 제한속도 추출 (TMAP 코어 엔진)
                    // v: 사용자 제보(재억, 2026-08-12) - "과속경고음이 10% 안 넘었는데도 울림".
                    // 원인: sdiSpeedLimit 경로는 generalRoadLimitSpeed를 안 건드리게
                    // 분리해뒀는데(v4.13), 바로 이 리플렉션 값(realRoadLimit)이 조건 없이
                    // generalRoadLimitSpeed까지 같이 덮어쓰고 있었음. Tmap 엔진이 카메라
                    // 근처에서 "현재 표시 제한속도"를 카메라 개별값으로 리턴하는 경우가 있어서,
                    // 그 값이 그대로 도로 기본 제한속도인 것처럼 오인식돼 과속경고음 임계값
                    // 계산이 틀어졌음. 이번 프레임에 카메라 이벤트(sdiSpeedLimit)가 있었으면
                    // realRoadLimit로 generalRoadLimitSpeed를 갱신하지 않음(roadLimitSpeed는
                    // 계속 갱신 - openpilot 카메라 감속용이라 문제 없음). #문제시 원복
                    val realRoadLimit = getRoadLimitSpeedFromEngine()
                    if (realRoadLimit >= 30) {
                        roadLimitSpeed = realRoadLimit
                        lastRoadLimitUpdateTime = System.currentTimeMillis()
                        val cameraEventThisFrame = lastSdiJsonStr != null &&
                            JSONObject(lastSdiJsonStr).optInt("nSdiSpeedLimit", 0) >= 30
                        if (!cameraEventThisFrame) {
                            generalRoadLimitSpeed = realRoadLimit
                            lastGeneralRoadLimitUpdateTime = System.currentTimeMillis()
                        }
                    }

                    // v4.2: roadLimitSpeed가 한 번 값이 들어오면(예: 고속도로 100) 그 뒤로
                    // 국도/제한속도 정보 없는 구간으로 빠져도 절대 안 낮아지고 계속 옛날
                    // 값을 openpilot에 보내던 버그. 8초 이상 새로 유효한 값(30 이상)이
                    // 안 들어오면 0으로 리셋 - 방지턱/카메라 감속(별개 로직, SdiDataRepository
                    // 기반)에는 영향 없음. #문제시 원복
                    if (roadLimitSpeed > 0 && System.currentTimeMillis() - lastRoadLimitUpdateTime > 8000) {
                        roadLimitSpeed = 0
                    }
                    if (generalRoadLimitSpeed > 0 && System.currentTimeMillis() - lastGeneralRoadLimitUpdateTime > 8000) {
                        generalRoadLimitSpeed = 0
                    }

                    // v: 사용자 제보 - "60도로인데 80으로 잡고 간다". 원인 추정: sdiSpeedLimit(카메라
                    // 개별 제한속도, 예: 구간단속 80)이 roadLimitSpeed(=openpilot에 nRoadLimitSpeed로
                    // 나가는 값)를 덮어쓰는데, 그 직후 Tmap 엔진 리플렉션(realRoadLimit)이 그 프레임에
                    // 유효값을 못 주면 카메라값(80)이 그대로 최대 8초간 남아있음 - 실제 도로 기본
                    // 제한속도(60)가 있는데도 openpilot한텐 80이 나감. 지금 이 프레임에 카메라
                    // 이벤트가 없으면(=lastSdiJsonStr가 null이거나 sdiSpeedLimit이 이번엔 안 들어왔으면)
                    // 8초 기다리지 말고 즉시 generalRoadLimitSpeed로 되돌림. #문제시 원복
                    if (lastSdiJsonStr == null && generalRoadLimitSpeed > 0 && roadLimitSpeed != generalRoadLimitSpeed) {
                        NavLogger.d(this@UdpSenderService, "[도로제한] 카메라 이벤트 없음 - roadLimitSpeed($roadLimitSpeed)를 generalRoadLimitSpeed($generalRoadLimitSpeed)로 즉시 복귀")
                        roadLimitSpeed = generalRoadLimitSpeed
                    }

                    json.put("nRoadLimitSpeed", roadLimitSpeed)

                    // 3. secondSDIInfo (GRT47과 동일하게 nSdiPlus... 접두어로 추가)
                    val sdiPlusObj = bundle.get("secondSDIInfo")
                    if (sdiPlusObj != null) {
                        lastSdiPlusJsonStr = if (sdiPlusObj is String) sdiPlusObj else gson.toJson(sdiPlusObj)
                        lastSdiPlusUpdateTime = System.currentTimeMillis()
                    } else if (System.currentTimeMillis() - lastSdiPlusUpdateTime > 2000) {
                        lastSdiPlusJsonStr = null
                    }

                    if (lastSdiPlusJsonStr != null) {
                        val plusJson = JSONObject(lastSdiPlusJsonStr)

                        val plusType = plusJson.optInt("nSdiType", 0)
                        var plusDist = plusJson.optInt("nSdiDist", 0)
                        if (plusType == 22 && plusDist <= 0) plusDist = 150

                        if (plusJson.has("nSdiType")) json.put("nSdiPlusType", plusJson.get("nSdiType"))
                        if (plusJson.has("nSdiSpeedLimit")) json.put("nSdiPlusSpeedLimit", plusJson.get("nSdiSpeedLimit"))
                        if (plusJson.has("nSdiDist") || plusDist > 0) json.put("nSdiPlusDist", plusDist)
                        if (plusJson.has("nSdiSection")) json.put("nSdiPlusBlockType", plusJson.get("nSdiSection"))
                        if (plusJson.has("nSdiBlockSpeed")) json.put("nSdiPlusBlockSpeed", plusJson.get("nSdiBlockSpeed"))
                        if (plusJson.has("nSdiBlockDist")) json.put("nSdiPlusBlockDist", plusJson.get("nSdiBlockDist"))
                    }

                    // 목적지까지 남은 거리(m)와 남은 시간(초)
                    // v9.3: 재억 지적 - 차가 멈추면(속도 0) 티맵이 "남은시간"을 0으로 줘버려서
                    // (거리÷속도 계산이라 정지시 0), 지금까지는 "시간이 0이면 거리도 0으로 지워서
                    // 보낸다"는 로직 때문에 콤마 화면의 목적지 정보(핀/거리)가 정지할 때마다
                    // 통째로 사라졌었음. 콤마 쪽(openpilot my 브랜치)은 "거리 AND 시간 둘 다 있어야
                    // 그린다"는 조건이 고정돼 있는데, 이건 openpilot 저장소를 고쳐야 해서 기기마다
                    // 따로 반영해야 하는 문제가 있음(사용자 3번: "Nda 쪽만 고쳐서 다른 사람도 다
                    // 적용되게"). 그래서 콤마 쪽은 그대로 두고, 대신 여기서 "시간이 순간적으로
                    // 0이 나오면 직전에 유효했던 남은시간 값을 대신 채워 보냄"으로 우회 - 거리는
                    // 항상 정확한 실시간 값 그대로 보내고, 시간만 약간 부정확(정지 직전 값 유지)할
                    // 뿐이라 화면이 사라지는 것보단 훨씬 나음. 다시 출발하면 티맵이 다시 정상적인
                    // 시간을 주므로 자동으로 갱신됨. #문제시 원복
                    val rawGoPosDist = bundle.getInt(
                        "nGoPosDist",
                        bundle.getInt("remainDistanceToGoPositionInMeter", 0)
                    )
                    val rawGoPosTime = bundle.getInt(
                        "nGoPosTime",
                        bundle.getInt("remainTimeToGoPositionInSec", 0)
                    )

                    val nGoPosDist: Int
                    val nGoPosTime: Int
                    if (rawGoPosDist > 0 && rawGoPosTime > 0) {
                        nGoPosDist = rawGoPosDist
                        nGoPosTime = rawGoPosTime
                        lastValidGoPosTime = rawGoPosTime
                        lastValidGoPosTimeAt = System.currentTimeMillis()
                    } else if (rawGoPosDist > 0 && lastValidGoPosTime > 0 &&
                        System.currentTimeMillis() - lastValidGoPosTimeAt < 5 * 60 * 1000L
                    ) {
                        // 경로는 살아있는데(거리>0) 정지 등으로 시간만 순간 0 - 5분 이내 직전 값 유지
                        nGoPosDist = rawGoPosDist
                        nGoPosTime = lastValidGoPosTime
                    } else {
                        // 경로 자체가 없거나(거리도 0), 5분 넘게 정지해서 직전 값을 더는 못 믿을 때
                        nGoPosDist = 0
                        nGoPosTime = 0
                    }

                    json.put("nGoPosDist", nGoPosDist)
                    json.put("nGoPosTime", nGoPosTime)

                    // 상시 안내 텍스트 표시를 위한 필수 TBT 더미 값 주입
                    var tbtDist = json.optInt("nSdiDist", 0)
                    var activeType = json.optInt("nSdiType", 0)

                    if (tbtDist <= 0) {
                        tbtDist = json.optInt("nSdiPlusDist", 0)
                        activeType = json.optInt("nSdiPlusType", 0)
                    }
                    if (tbtDist <= 0) {
                        tbtDist = json.optInt("nSdiBlockDist", 0)
                        activeType = 4 // 구간단속 중
                    }
                    if (tbtDist <= 0) {
                        tbtDist = 9999
                        activeType = 0
                    }

                    val prefix = when (activeType) {
                        1, 2, 3, 4, 7 -> "단속구간"
                        22 -> "방지턱"
                        33 -> "스쿨존"
                        else -> if (tbtDist < 9999) "주의구간" else "안심주행"
                    }

                    json.put("nTBTDist", tbtDist)      // 이벤트가 있으면 해당 거리 표출, 없으면 9999
                    json.put("nTBTTurnType", 51)    // Notification 타입 (직진/알림)
                    json.put("szTBTMainText", "$prefix | GPS: $currentGpsStatusText")

                    // v1.0.98: 이 edcObserver는 observeForever라 Activity 생명주기와 무관하게
                    // 항상 최신 데이터를 받음. MapActivity.extractAndDisplaySdiInfo()는
                    // observe(this@MapActivity, ...)라서 MapActivity가 onStop되면(카카오
                    // 화면이 위에 뜨면) 멈춰버림 - 그래서 여기서 직접 SdiDataRepository를
                    // 갱신해두면 KakaoNaviActivity 미니 HUD도 화면 전환과 무관하게 항상
                    // 최신값을 읽을 수 있음(사용자 요청: "카카오 화면에서도 티맵 정보 그대로").
                    // #문제시 원복
                    try {
                        val sdiType = json.optInt("nSdiType", 0)
                        val sdiSpeedLimit = json.optInt("nSdiSpeedLimit", 0)
                        val sdiDist = json.optInt("nSdiDist", 0)
                        val blockDist = json.optInt("nSdiBlockDist", 0)
                        val blockTime = json.optInt("nSdiBlockTime", 0)
                        val blockAvgSpeed = json.optInt("nSdiBlockAverageSpeed", 0)
                        val isBlockSection = sdiType == 2 || sdiType == 3 || sdiType == 4 || json.optBoolean("bSdiBlockSection", false)
                        // v9.4: 사용자 제보(재억, 2026-08-14) - "100 도로인데 101에서 경고음".
                        // 원인: SdiDataRepository.roadLimitSpeed를 여기(UdpSenderService의
                        // generalRoadLimitSpeed)랑 MapActivity(lastValidRoadLimit, 화면에 찍는
                        // 바로 그 숫자)가 각자 따로 튀는값 거르기를 거쳐서 동시에 같은 칸에
                        // 덮어쓰고 있었음. 두 필터링 타이밍이 어긋나는 순간엔 화면엔 100이
                        // 떠 있어도 실제 경고음 판정엔 다른(주로 더 낮은) 값이 쓰여서, 화면
                        // 숫자 기준 10% 안 넘었는데도 경고음이 울리는 것처럼 보였음.
                        // 여기서는 더 이상 이 칸을 안 건드리고 MapActivity가 화면에 찍는
                        // 값(lastValidRoadLimit)만 이 칸의 유일한 출처로 남김 - 경고음이
                        // 보는 숫자 = 화면에 뜨는 숫자가 항상 같아지도록. generalRoadLimitSpeed
                        // 자체는 openpilot 전송(nRoadLimitSpeed)엔 계속 그대로 쓰임, 영향 없음.
                        // #문제시 원복
                        SdiDataRepository.updateCurrentSdiState(
                            limitSpeed = SdiDataRepository.roadLimitSpeed,
                            type = sdiType,
                            speedLimit = sdiSpeedLimit,
                            distance = sdiDist,
                            blockType = if (isBlockSection) 1 else 0,
                            blockSpeed = blockAvgSpeed,
                            blockDist = blockDist,
                            blockTime = blockTime,
                            blockSection = isBlockSection && blockDist > 0
                        )
                    } catch (e: Exception) {
                        NavLogger.e(this@UdpSenderService, "SdiDataRepository 갱신 예외: ${e.message}")
                    }

                    // v1.1.00: 카카오 지도로 길안내 중이면(KakaoRouteDataRepository가 최근
                    // 5초 이내 갱신된 상태), 위에서 만든 티맵 기반 TBT 값들을 카카오의 실제
                    // 안내 데이터로 덮어씀. 어떤 openpilot fork가 이 UDP를 받든 값 자체는
                    // 항상 나가게 해두고, 실제로 화면에 그려지는지는 그 fork의 UI 코드에
                    // 달려있음(사용자 확인 예정). #문제시 원복
                    if (KakaoRouteDataRepository.isFresh()) {
                        val kr = KakaoRouteDataRepository
                        if (kr.remainDist > 0 && kr.remainTime > 0) {
                            json.put("nGoPosDist", kr.remainDist)
                            json.put("nGoPosTime", kr.remainTime)
                        }
                        val kakaoTbtDist = if (kr.tbtDist > 0) kr.tbtDist else 9999
                        json.put("nTBTDist", kakaoTbtDist)
                        json.put("nTBTTurnType", kr.tbtTurnType)
                        // v4.21: 예전 매핑(1,2,3,4,7→단속구간/22→방지턱/33→스쿨존)이 실제
                        // KNSafetyCode 값 체계랑 완전히 어긋나 있었음(사용자 지적으로 재검증) -
                        // 이제 kr.safetyType은 KakaoGuidanceDelegate에서 이미 Tmap/openpilot
                        // 고유 nSdiType 스킴(carrot_serv.py 기준: 1=고정식/2·3·4=구간단속/
                        // 7=이동식/22=방지턱/20=스쿨존 등)으로 번역해서 들어오므로, 라벨도
                        // 그 스킴 기준으로 맞춤. #문제시 원복
                        // v: 사용자 요청(2026-08-08) - 상단 라벨("신호단속 | GPS: ..")이 카메라
                        // 종류에 따라 계속 바뀌던 걸, 이제 최종 목적지명으로 고정. 카메라/방지턱
                        // 종류·거리는 openpilot HUD 쪽에 별도 아이콘으로 새로 추가하기로 해서
                        // (hud_renderer.py 쪽 작업), 상단 라벨은 더 이상 안전이벤트 종류를 안
                        // 보여줘도 됨 - 항상 목적지명 우선, 없으면 도로명, 그것도 없으면
                        // "카카오안내". #문제시 원복
                        val kakaoPrefix = if (kr.destinationName.isNotBlank() && kr.destinationName != "목적지") {
                            kr.destinationName
                        } else if (kr.roadName.isNotEmpty()) {
                            kr.roadName
                        } else {
                            "카카오안내"
                        }
                        json.put("szTBTMainText", "$kakaoPrefix | GPS: $currentGpsStatusText")
                        // v: 사용자 제안(2026-08-08) - "Tmap 우선순위 + 카카오 폴백"이라는
                        // 하이브리드 판단 자체가 문제였음. 실제 로그로 확인됨: safetyDistTrusted
                        // (카카오 SDK의 getRemainDist() 기반 검증)가 거의 항상 false로 나와서
                        // 카카오 안전정보가 사실상 거의 다 막히고 있었음(재억 로그 기준 검증됨=0,
                        // 미검증=417). 구조를 단순하게 바꿈: "카카오 길안내 중이면 카카오
                        // 안전정보만 사용, 길안내 안 하면 Tmap 안전정보만 사용"으로 명확히 분리.
                        // v: 사용자 요청(2026-08-08) - "카카오 길안내 중=카카오만" 구조를
                        // 하이브리드(Tmap 우선 + 카카오 trusted 폴백)로 롤백. 구조 자체가
                        // 문제였던 게 아니라 trusted 판정 로직이 버그였던 거라(DistFromS
                        // 뺄셈으로 이미 고침), 하이브리드로 되돌려도 이제는 정상 작동해야 함.
                        // 방지턱 speedLimit 예외는 그대로 유지. #문제시 원복
                        // v: 사용자 요청(2026-08-12) - 우선순위를 다시 반전: 카카오 길안내
                        // 중일 땐 카카오값이 검증됐으면(trusted) 카카오를 우선 채택하고, Tmap은
                        // 카카오가 못 잡았을 때(트러스트 안 됐거나 카카오 자체에 정보가 없을 때)만
                        // 백업으로 사용. 기존엔 반대로 Tmap이 항상 우선이고 카카오는 Tmap이
                        // 아무것도 못 잡았을 때만 백업이었음. Tmap 대기화면(길안내 안 할 때) 동작은
                        // 이 블록 자체가 kr.isFresh() 안에서만 도니까 안 건드림. #문제시 원복
                        val tmapHasSdi = json.optInt("nSdiType", 0) != 0 || json.optInt("nSdiDist", 0) > 0
                        val kakaoHasSdi = kr.safetyType >= 0 && kr.safetyDist > 0 && (kr.safetySpeedLimit > 0 || kr.safetyType == 22)
                        if (kakaoHasSdi && kr.safetyDistTrusted) {
                            json.put("nSdiType", kr.safetyType)
                            json.put("nSdiSpeedLimit", kr.safetySpeedLimit)
                            json.put("nSdiDist", kr.safetyDist)
                            // v: roadcate 버그 수정 - Tmap 자체 감지 분기(방지턱 sdiType==22일 때
                            // roadcate=8 채움)와 동일하게, 카카오 폴백으로 방지턱 정보가 채워질
                            // 때도 roadcate를 같이 채워야 함. carrot_serv.py 규격상 roadcate>=2가
                            // 없으면 방지턱 감속 자체가 자동 생략되는데, 지금까지 이 필드가
                            // 비어있었음. #문제시 원복
                            if (kr.safetyType == 22) {
                                json.put("roadcate", 8)
                            }
                            NavLogger.d(this@UdpSenderService, "[안전정보 우선순위] 검증된 카카오값 우선 채택: type=${kr.safetyType} speedLimit=${kr.safetySpeedLimit} dist=${kr.safetyDist}")
                        } else if (!tmapHasSdi && kakaoHasSdi) {
                            if (kr.safetyDistTrusted) {
                                json.put("nSdiType", kr.safetyType)
                                json.put("nSdiSpeedLimit", kr.safetySpeedLimit)
                                json.put("nSdiDist", kr.safetyDist)
                                if (kr.safetyType == 22) {
                                    json.put("roadcate", 8)
                                }
                                NavLogger.d(this@UdpSenderService, "[안전정보 우선순위] Tmap 없음 -> 검증된 카카오값으로 폴백: type=${kr.safetyType} speedLimit=${kr.safetySpeedLimit} dist=${kr.safetyDist}")
                            } else {
                                NavLogger.d(this@UdpSenderService, "[카카오 안전정보 검증용][실제전송안함 - 미검증 폴백] type=${kr.safetyType} speedLimit=${kr.safetySpeedLimit} dist=${kr.safetyDist}")
                            }
                        }
                        NavLogger.d(this@UdpSenderService, "[카카오->openpilot] UDP 페이로드 카카오 데이터로 덮어씀: nGoPosDist=${kr.remainDist} nTBTDist=$kakaoTbtDist turnType=${kr.tbtTurnType}")
                    }

                    // v: 2026-08-12 - carrot_serv.py의 update() 코드를 직접 확인해서 찾은 기능:
                    // "latitude"/"longitude"/"heading"/"accuracy"/"gps_speed" 필드를 보내면,
                    // openpilot 자체 내비 GPS가 3초 이상 안 들어올 때(터널/실내주차장 등) 자동으로
                    // 우리 폰 GPS로 대체해줌(GPS 백업 기능). 지금까지 이 필드들을 한 번도 안 보내고
                    // 있었어서 이 기능이 전혀 활용이 안 되고 있었음 - 추가함. #문제시 원복
                    try {
                        val locationManager = getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
                        val phoneLoc = locationManager?.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                            ?: locationManager?.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                        if (phoneLoc != null) {
                            json.put("latitude", phoneLoc.latitude)
                            json.put("longitude", phoneLoc.longitude)
                            json.put("heading", phoneLoc.bearing)
                            json.put("accuracy", phoneLoc.accuracy)
                            json.put("gps_speed", phoneLoc.speed)
                        }
                    } catch (e: Exception) {
                        NavLogger.e(this@UdpSenderService, "[GPS 백업] 폰 위치 조회 실패: ${e.message}")
                    }

                    latestPayload = json.toString()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    @Volatile
    private var latestPayload = "{}"

    private fun startObservingEDC() {
        CoroutineScope(Dispatchers.Main).launch {
            TmapUISDK.observableEDCData.observeForever(edcObserver)
        }
    }

    private fun startSendingLoop() {
        serviceScope.launch {
            while (isActive) {
                sendSdiData()
                delay(300) // 약 3.3Hz heartbeat
            }
        }
    }

    private var receiveSocket: DatagramSocket? = null

    // v4.16: "차량 HUD 전송이 심할 때 아예 안 되는 것 같다" - 로그로 확인해보니 이번 세션
    // 내내 7705(상태수신)/2899(NDA브릿지) 둘 다 openpilot으로부터 단 한 패킷도 못 받았음.
    // 두 개의 독립적인 소켓이 동시에 똑같이 침묵했다는 건 앱 코드 문제라기보다 네트워크
    // 자체(폰과 openpilot 기기가 같은 네트워크에 없음/AP 클라이언트 격리 등) 문제일 가능성이
    // 높음 - 폰이 지금 어느 IP 대역에 있는지를 로그로 남겨서 openpilot 기기의 IP 대역과
    // 실제로 같은 네트워크인지 다음 로그에서 바로 비교할 수 있게 함. #문제시 원복
    private fun getLocalIpAddressesSummary(): String {
        val ssidPart = try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ssid = wifiManager?.connectionInfo?.ssid?.trim('"')
            if (!ssid.isNullOrBlank() && ssid != "<unknown ssid>") "Wi-Fi SSID=$ssid, " else ""
        } catch (e: Exception) {
            ""
        }
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            val list = mutableListOf<String>()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is java.net.Inet4Address) {
                        list.add("${iface.name}=${addr.hostAddress}")
                    }
                }
            }
            ssidPart + (if (list.isEmpty()) "없음(네트워크 미연결?)" else list.joinToString(", "))
        } catch (e: Exception) {
            "${ssidPart}조회실패: ${e.message}"
        }
    }

    private fun startReceivingLoop() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket(null)
                socket.reuseAddress = true
                bindToWifiIfAvailable(socket, "openpilot 수신용(7705)")
                socket.bind(java.net.InetSocketAddress("0.0.0.0", 7705))
                socket.soTimeout = 5000
                receiveSocket = socket
                // v: "5초 타임아웃 로그조차 안 찍히고 완전히 조용해짐" 미스터리 - Wi-Fi
                // 바인딩(Network.bindSocket) 이후 soTimeout이 실제로 유지되는지 자체가
                // 의심스러워서, 설정 직후 실제 값을 읽어서 로그로 남김. 5000이 아닌
                // 다른 값(특히 0=무한대기)이 찍히면 이게 원인 확정. #문제시 원복
                NavLogger.d(this@UdpSenderService, "openpilot 수신 소켓 soTimeout 확인: ${socket.soTimeout}ms")
                NavLogger.d(this@UdpSenderService, "openpilot UDP 수신 소켓 bind 성공: 0.0.0.0:7705 / 폰 현재 IP: ${getLocalIpAddressesSummary()}")

                val buffer = ByteArray(4096)
                var lastActive: Boolean? = null
                var lastActivePeriodicLogTime = 0L
                while (isActive && isRunning.get()) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: java.net.SocketTimeoutException) {
                        // 상태가 바뀔 때만 로그 (매 5초 반복 스팸 방지). 계속 끊긴 상태면 조용히 재시도만.
                        if (lastActive != false) {
                            NavLogger.e(this@UdpSenderService, "openpilot으로부터 5초간 UDP 수신 없음 (연결 끊김 또는 openpilot 미실행 가능성) / 폰 현재 IP: ${getLocalIpAddressesSummary()}")
                            OpenpilotStateRepository.updateState("-", "-", 0, 0, false)
                            lastActive = false
                        }
                        continue
                    } catch (e: java.io.IOException) {
                        // 소켓이 외부(onDestroy 등)에서 close()된 경우 EBADF 등이 여기로 들어옴.
                        // 그 경우엔 죽은 소켓을 붙잡고 반복하지 말고 조용히 루프 종료.
                        if (!isActive || !isRunning.get()) {
                            break
                        }
                        // v: 오늘 실주행 로그로 확인됨 - 서비스 자체는 안 죽었는데(onDestroy
                        // 없음) 수신이 몇 분간 완전히 멈춘 사례가 있었음. 그동안은 여기서
                        // isRunning이 true여도 무조건 break로 루프를 영구 종료시키고 있어서,
                        // EBADF가 아닌 다른 일시적 IOException(네트워크 순간 끊김 등)에도
                        // 다시는 복구가 안 됐던 게 원인일 수 있음. 이제 영구 종료 대신
                        // 로그만 남기고 2초 쉬었다가 계속 재시도하도록 변경 - 소켓이 진짜
                        // 죽어서 반복 실패해도 스팸 로그만 나고 서비스 자체는 안 죽음. #문제시 원복
                        NavLogger.e(this@UdpSenderService, "openpilot UDP 수신 중 오류(재시도함): ${e.message}")
                        delay(2000)
                        continue
                    }
                    val data = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    try {
                        val json = JSONObject(data)
                        val carrot2 = json.optString("Carrot2", "-")
                        // v: 재억 제보(2026-08-12) - "Cruise On인데 콤마 연결 중이라니 말이
                        // 안 된다"는 정확한 지적. 원인: ip를 openpilot이 JSON 본문 안에 자기가
                        // 적어넣은 "ip" 필드값으로 판단하고 있었는데, 이 필드가 아직 안 채워져서
                        // "-"로 오는 경우가 있었음 - 그러면 active=true(Cruise On)는 정상 수신했는데
                        // ip="-"(연결 안 됨 판정)가 동시에 성립하는 모순이 생김. 패킷이 도착했다는
                        // 것 자체가 이미 "연결됨"의 증거이므로, 본문 필드 대신 실제 패킷 발신자
                        // 주소(packet.address)를 신뢰하도록 변경. #문제시 원복
                        val ip = packet.address?.hostAddress ?: json.optString("ip", "-")

                        // v: 재억 제보(2026-08-13, "불안정 또 뜸") - 판정 기준(4회/3초→6회/4초)을
                        // 아무리 완화해도 "불안정"이 계속 뜨는 근본 원인을 로그로 확인함. 콤마가
                        // 이 포트로 두 종류 패킷을 섞어 보냄: 1) {"ip":..,"navi_debug":1} 같은
                        // active 키 자체가 없는 ping성 패킷, 2) active 키가 있는 진짜 상태 패킷.
                        // json.optBoolean("active", false)가 키 없을 때 기본값 false를 리턴해서,
                        // ping 패킷이 올 때마다 "active가 true->false로 바뀜"으로 오인되고 바로
                        // 다음 진짜 패킷에서 다시 true로 바뀌는 가짜 전환이 패킷 사이클마다(초당
                        // 여러 번) 반복되고 있었음. 이게 실제 크루즈 흔들림과 무관하게 불안정
                        // 판정 문턱을 기계적으로 계속 넘기고 있었던 것. active 키가 아예 없는
                        // 패킷(=상태 정보가 없는 ping)은 상태 갱신 자체를 건너뛰도록 수정.
                        // 단 lastPacketReceivedTime(연결 끊김 감시용)은 패킷이 온 것 자체는
                        // 맞으므로 그대로 갱신함. #문제시 원복
                        if (!json.has("active")) {
                            lastPacketReceivedTime = System.currentTimeMillis()
                        } else {
                            val trafficState = json.optInt("trafficState", 0)
                            val xState = json.optInt("xState", 0)
                            val active = json.optBoolean("active", false)

                        if (lastActive != active) {
                            NavLogger.d(this@UdpSenderService, "openpilot 연결 상태 변경: active=$active, ip=${packet.address?.hostAddress}, carrot2=$carrot2")
                            // v1.6: '크루즈 작동 중인데 OP OFF로 뜬다'는 사용자 지적 진단용 - active
                            // 필드 외에 다른 키가 있는지 원본 JSON을 그대로 로그에 남김
                            // (상태 변화 시에만, 스팸 방지). #문제시 원복
                            NavLogger.d(this@UdpSenderService, "[OP상태? 원본] $data")
                            lastActive = active
                        }
                        // v4.5: 위 로그는 값이 "바뀔 때만" 찍혀서, active가 계속 false로
                        // 고정돼있으면(=바뀐 적이 없으면) 로그에 단 한 번만 남고 그 뒤로는
                        // 전혀 안 찍혀서 실제로 크루즈 켠 순간에 뭘 받고 있었는지 확인이
                        // 안 됐음(사용자 지적: 로그에 active= 기록이 거의 없다). 5초마다
                        // 지금 값을 무조건 한 번씩 찍어서, 다음 로그 캡처 때 그 순간의
                        // 실제 값을 확실히 볼 수 있게 함. #문제시 원복
                        if (System.currentTimeMillis() - lastActivePeriodicLogTime > 5000) {
                            lastActivePeriodicLogTime = System.currentTimeMillis()
                            NavLogger.d(this@UdpSenderService, "[OP상태 주기] active=$active, xState=$xState, trafficState=$trafficState, ip=$ip, carrot2=$carrot2")
                        }

                        OpenpilotStateRepository.updateState(carrot2, ip, trafficState, xState, active)
                        lastPacketReceivedTime = System.currentTimeMillis()
                        }
                    } catch (e: Exception) {
                        NavLogger.e(this@UdpSenderService, "openpilot UDP 패킷 파싱 실패: ${e.message}, raw=$data")
                    }
                }
            } catch (e: Exception) {
                NavLogger.e(this@UdpSenderService, "openpilot UDP 수신 소켓 bind/수신 실패: ${e.message}")
            }
        }
    }

    private fun sendSdiData() {
        if (latestPayload == "{}") return
        try {
            val buffer = latestPayload.toByteArray(Charsets.UTF_8)

            // GRT47의 UDP 전송 로직 유지 (127.0.0.1, 255.255.255.255 및 모든 인터페이스 브로드캐스트)
            val targetAddresses = mutableSetOf<InetAddress>()
            targetAddresses.add(InetAddress.getByName("127.0.0.1"))

            if (targetIp == "255.255.255.255") {
                targetAddresses.add(InetAddress.getByName("255.255.255.255"))
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val networkInterface = interfaces.nextElement()
                    if (networkInterface.isLoopback || !networkInterface.isUp) continue
                    for (interfaceAddress in networkInterface.interfaceAddresses) {
                        interfaceAddress.broadcast?.let { targetAddresses.add(it) }
                    }
                }
            } else {
                targetAddresses.add(InetAddress.getByName(targetIp))
            }

            for (address in targetAddresses) {
                val packet = DatagramPacket(buffer, buffer.size, address, UDP_PORT)
                udpSocket?.send(packet)
            }
        } catch (e: Exception) {
            val now = System.currentTimeMillis()
            if (now - lastSendErrorLogTime > 5000) {
                lastSendErrorLogTime = now
                NavLogger.e(this, "openpilot UDP 전송 실패: ${e.message}")
            }
            // v: ENETUNREACH가 뜨면 소켓이 죽은 네트워크에 묶여서 다시는 자연 복구가
            // 안 되는 걸로 확인됨(1시간 15분 26,619번 연속 실패 사례). 너무 자주 재생성하면
            // 오히려 부담이 되니 5초에 한 번씩만 시도. #문제시 원복
            if ((e.message?.contains("ENETUNREACH") == true || e.message?.contains("ENETDOWN") == true) &&
                now - lastSocketRecreateTime > 5000
            ) {
                lastSocketRecreateTime = now
                NavLogger.d(this, "[자가치유] ENETUNREACH 감지 - 전송 소켓 재생성 시도")
                createUdpSendSocket()
            }
        }
    }

    // ===== NDA(EON:ROAD_LIMIT_SERVICE:v1) 브릿지 =====

    /** openpilot이 5초마다 뿌리는 비콘("EON:ROAD_LIMIT_SERVICE:v1")과, request_gps=1 응답으로 오는
     *  GPS 위치 JSON({"location":[lat,lon,alt,speed,bearing,acc,time,vAcc,bAcc,sAcc]})을 같은 2899 포트에서 수신. */
    private fun startMainConnectionWatchdog() {
        serviceScope.launch(Dispatchers.IO) {
            while (isActive && isRunning.get()) {
                delay(1000)
                val elapsed = System.currentTimeMillis() - lastPacketReceivedTime
                // lastPacketReceivedTime이 0L이면 아직 한 번도 안 받은 초기 상태 - 그 경우는
                // 굳이 로그 안 남기고 조용히 대기(어차피 이미 "-" 상태).
                if (lastPacketReceivedTime > 0L && elapsed > 5000 &&
                    OpenpilotStateRepository.state.value?.ip?.let { it != "-" && it.isNotEmpty() } == true
                ) {
                    NavLogger.e(this@UdpSenderService, "[감시타이머] ${elapsed}ms 동안 openpilot 패킷 없음 - 소켓 타임아웃이 안 걸려서 직접 강제 리셋함")
                    OpenpilotStateRepository.updateState("-", "-", 0, 0, false)
                }
            }
        }
    }

    private fun startNdaListenLoop() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bindToWifiIfAvailable(this, "NDA 비콘 수신용($NDA_LISTEN_PORT)")
                    bind(java.net.InetSocketAddress("0.0.0.0", NDA_LISTEN_PORT))
                    soTimeout = 8000
                }
                ndaListenSocket = socket
                NavLogger.d(this@UdpSenderService, "[NDA] 비콘/GPS 수신 소켓 bind 성공: 0.0.0.0:$NDA_LISTEN_PORT / 폰 현재 IP: ${getLocalIpAddressesSummary()}")

                val buffer = ByteArray(2048)
                var lastNdaTimeoutLogTime = 0L
                while (isActive && isRunning.get()) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: java.net.SocketTimeoutException) {
                        // v4.16: 기존엔 타임아웃 시 완전히 조용해서 "NDA 브릿지가 죽었는지
                        // 살았는지"조차 로그로 알 수 없었음 - 30초 간격으로 생존 신호를 남김. #문제시 원복
                        val now = System.currentTimeMillis()
                        if (now - lastNdaTimeoutLogTime > 30000) {
                            lastNdaTimeoutLogTime = now
                            NavLogger.e(this@UdpSenderService, "[NDA] openpilot 비콘 수신 안 됨(계속 대기 중) / 폰 현재 IP: ${getLocalIpAddressesSummary()}")
                        }
                        continue
                    } catch (e: java.io.IOException) {
                        // 외부에서 close()된 경우(EBADF 등)만 루프 종료, 그 외엔 재시도.
                        // (위 openpilot 수신 루프와 동일한 이유로 변경) #문제시 원복
                        if (!isActive || !isRunning.get()) {
                            break
                        }
                        NavLogger.e(this@UdpSenderService, "[NDA] 수신 오류(재시도함): ${e.message}")
                        delay(2000)
                        continue
                    }

                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)

                    if (text.trim() == NDA_BEACON) {
                        val isNewHost = ndaRemoteAddr == null || ndaRemoteAddr != packet.address
                        ndaRemoteAddr = packet.address
                        ndaRemotePort = NDA_SEND_PORT_PRIMARY
                        ndaLastBeaconTime = System.currentTimeMillis()
                        OpenpilotStateRepository.updateNdaConnected(true)
                        if (isNewHost) {
                            NavLogger.d(this@UdpSenderService, "[NDA] openpilot 발견: ${packet.address?.hostAddress}")
                        }
                        continue
                    }

                    try {
                        val json = JSONObject(text)
                        if (json.has("location")) {
                            val arr = json.getJSONArray("location")
                            val lat = arr.getDouble(0)
                            val lon = arr.getDouble(1)
                            val alt = arr.getDouble(2)
                            val speed = arr.getDouble(3)
                            val bearing = arr.getDouble(4)
                            val accuracy = arr.getDouble(5)
                            val timeMs = arr.optLong(6, System.currentTimeMillis())
                            NavLogger.d(
                                this@UdpSenderService,
                                "[NDA] GPS 역수신: lat=$lat lon=$lon speed=$speed acc=$accuracy"
                            )
                            OpenpilotStateRepository.updateNdaGps(lat, lon, alt, speed, bearing, accuracy, timeMs)

                            // 사용자 진단용: openpilot road_speed_limiter.py에서 GPS 패킷에 얹어보내는
                            // 방지턱/크루즈 디버그 값. 4,5,6번 문제 확인용. (없는 구버전 openpilot이면
                            // json.optJSONObject가 null 반환하므로 조용히 무시됨) #문제시 원복
                            val debug = json.optJSONObject("debug")
                            if (debug != null) {
                                NavLogger.d(
                                    this@UdpSenderService,
                                    "[NDA][OP-DEBUG] cam_type=${debug.optInt("cam_type")} " +
                                        "raw_sdi_type=${debug.optInt("raw_sdi_type")} " +
                                        "cam_limit_speed=${debug.optInt("cam_limit_speed")} " +
                                        "cam_limit_speed_left_dist=${debug.optInt("cam_limit_speed_left_dist")} " +
                                        "road_limit_speed=${debug.optInt("road_limit_speed")}"
                                )
                            }
                        }
                    } catch (e: Exception) {
                        // 비콘도 GPS JSON도 아니면 무시 (알 수 없는 패킷)
                    }
                }
            } catch (e: Exception) {
                NavLogger.e(this@UdpSenderService, "[NDA] 리슨 소켓 bind/수신 실패: ${e.message}")
            }
        }
    }

    // v: 2026-08-12 - comma.ai 공식 코드(Naver Map 카풀비타/bin9208 openpilot 브랜치 리버스엔지니어링으로
    // 확인)에 이미 존재하는 HTTP 네비게이션 API를 활용. carrot_man.py의 carrot_navi_http_server(포트
    // 7713)가 POST /api/navi/{tmap_version}로 {"rgdata": <지금 UDP로 843/2843/3843에 보내는 것과
    // 동일한 페이로드>} 형태를 받으면 carrot_serv.update(rgdata)로 그대로 넘겨줌 - 즉 우리가 이미
    // 만들고 있는 latestPayload를 그대로 감싸서 보내기만 하면 됨. UDP 브로드캐스트는 패킷 유실
    // 가능성이 있는데, HTTP(TCP 기반)는 전송 성공/실패를 명확히 알 수 있고 재시도도 쉬워서, 기존
    // UDP 경로는 그대로 두고 이 HTTP 경로를 "보험"으로 병행 전송함(둘 중 하나만 도착해도 됨).
    // 콤마 IP는 이미 메인 채널(7705)에서 확보한 값을 그대로 재사용. #문제시 원복
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(800, TimeUnit.MILLISECONDS)
        .writeTimeout(800, TimeUnit.MILLISECONDS)
        .readTimeout(800, TimeUnit.MILLISECONDS)
        .build()
    private var httpNaviSuccessCount = 0
    private var httpNaviFailCount = 0
    private var lastHttpNaviLogTime = 0L

    private fun startCommaHttpNaviLoop() {
        serviceScope.launch(Dispatchers.IO) {
            while (isActive && isRunning.get()) {
                try {
                    val commaIp = OpenpilotStateRepository.state.value?.ip?.takeIf { it.isNotEmpty() && it != "-" }
                        ?: ndaRemoteAddr?.hostAddress
                    if (commaIp != null && latestPayload != "{}") {
                        val bodyJson = JSONObject().apply {
                            put("rgdata", JSONObject(latestPayload))
                            put("timestamp_ms", System.currentTimeMillis())
                        }
                        val body = bodyJson.toString().toRequestBody("application/json".toMediaTypeOrNull())
                        val request = Request.Builder()
                            .url("http://$commaIp:7713/api/navi/tmapnda")
                            .post(body)
                            .build()
                        try {
                            httpClient.newCall(request).execute().use { resp ->
                                if (resp.isSuccessful) httpNaviSuccessCount++ else httpNaviFailCount++
                            }
                        } catch (e: Exception) {
                            httpNaviFailCount++
                        }
                    }
                } catch (e: Exception) {
                    NavLogger.e(this@UdpSenderService, "[콤마 HTTP API] 전송 루프 예외: ${e.message}")
                }

                if (System.currentTimeMillis() - lastHttpNaviLogTime > 5000) {
                    NavLogger.d(this@UdpSenderService, "[콤마 HTTP API] 5초 요약: 성공=$httpNaviSuccessCount 실패=$httpNaviFailCount")
                    httpNaviSuccessCount = 0
                    httpNaviFailCount = 0
                    lastHttpNaviLogTime = System.currentTimeMillis()
                }
                delay(1000) // 1Hz - UDP(2Hz)보다 느리게, 보험 채널이라 부담 최소화
            }
        }
    }

    /**
     * 발견된 openpilot IP의 843/2843/3843 세 포트 모두에 매 사이클 동시 송신 (fork마다 리슨 포트가
     * 다를 수 있어 "가능성을 전부 열어놓는" 방식). 어느 포트가 실제로 살아있는지는 여기서 알 수 없고
     * (UDP send()는 상대가 없어도 거의 예외를 안 던짐), 5초 요약 로그로 "포트별 sendto 시도/실패 횟수"만
     * 확인 가능. 실제 도달 여부 확인은 openpilot 쪽 수신 로그가 있어야 함.
     */
    private fun createNdaSendSocket(): Boolean {
        try {
            ndaSendSocket?.close()
        } catch (e: Exception) {
            // 이미 죽은 소켓 닫다가 나는 예외는 무시
        }
        return try {
            val socket = DatagramSocket(null)
            val bindSucceeded = bindSendSocketToLocalNetwork(socket, "NDA 송신용")
            if (!bindSucceeded) {
                socket.bind(java.net.InetSocketAddress(0))
            }
            ndaSendSocket = socket
            socket.broadcast = true
            true
        } catch (e: Exception) {
            NavLogger.e(this, "[NDA] 송신 소켓 생성 실패: ${e.message}")
            false
        }
    }

    private fun startNdaSendLoop() {
        serviceScope.launch(Dispatchers.IO) {
            if (!createNdaSendSocket()) return@launch

            // 포트별 성공/실패 카운트 (순서: NDA_SEND_PORTS와 동일)
            val successCount = IntArray(NDA_SEND_PORTS.size)
            val failCount = IntArray(NDA_SEND_PORTS.size)
            var lastNdaSendLogTime = 0L
            var lastNdaSocketRecreateTime = 0L

            while (isActive && isRunning.get()) {
                // v: 사용자 지적(2026-08-08) - 지금까지 "비콘을 받아서 상대 주소를 알기 전엔
                // 아무것도 안 보냄" 구조였는데, 반대로 openpilot 쪽(road_speed_limiter.py류)은
                // "우리한테서 뭔가 받아야만 우리 주소를 알고 비콘을 보내주기 시작"하는 구조라,
                // 서로 상대가 먼저 말 걸어주길 기다리기만 하는 완벽한 닭과 달걀 문제였음 -
                // 그래서 지금까지 그 누구한테도(캘빈 포함) 이 기능이 작동할 수 없었음.
                // 상대 주소를 아직 모르면 브로드캐스트(255.255.255.255)로 먼저 찔러서
                // openpilot이 우리 주소를 등록할 기회를 줌. #문제시 원복
                val addr = ndaRemoteAddr ?: try {
                    InetAddress.getByName("255.255.255.255")
                } catch (e: Exception) {
                    null
                }
                if (addr != null) {
                    val json = buildNdaRoadLimitJson()
                    val bytes = json.toString().toByteArray(Charsets.UTF_8)

                    // request_gps가 포함된 패킷은 일부 carrot 수신부에서
                    // 일반 apilot 데이터 처리를 건너뛰므로 별도 패킷으로 분리
                    val gpsRequestBytes = JSONObject()
                        .put("request_gps", 1)
                        .toString()
                        .toByteArray(Charsets.UTF_8)

                    for ((idx, port) in NDA_SEND_PORTS.withIndex()) {
                        try {
                            // 1. 길안내·안전운전 데이터 전송
                            ndaSendSocket?.send(DatagramPacket(bytes, bytes.size, addr, port))

                            // 2. GPS 요청은 별도 패킷으로 전송
                            ndaSendSocket?.send(
                                DatagramPacket(
                                    gpsRequestBytes,
                                    gpsRequestBytes.size,
                                    addr,
                                    port
                                )
                            )

                            successCount[idx]++
                        } catch (e: Exception) {
                            failCount[idx]++
                            NavLogger.e(this@UdpSenderService, "[NDA] 송신 실패 target=$addr:$port: ${e.message}")
                            val nowRecreate = System.currentTimeMillis()
                            if ((e.message?.contains("ENETUNREACH") == true || e.message?.contains("ENETDOWN") == true) &&
                                nowRecreate - lastNdaSocketRecreateTime > 5000
                            ) {
                                lastNdaSocketRecreateTime = nowRecreate
                                NavLogger.d(this@UdpSenderService, "[자가치유] NDA 송신 소켓 ENETUNREACH 감지 - 재생성 시도")
                                createNdaSendSocket()
                            }
                        }
                    }

                    // 5초 간격으로 포트별 송신 시도/실패 카운트 요약 로그
                    val now = System.currentTimeMillis()
                    if (now - lastNdaSendLogTime > 5000) {
                        lastNdaSendLogTime = now
                        val summary = NDA_SEND_PORTS.indices.joinToString(", ") { i ->
                            "${NDA_SEND_PORTS[i]}(성공=${successCount[i]}/실패=${failCount[i]})"
                        }
                        NavLogger.d(this@UdpSenderService, "[NDA] 송신 요약(최근 5초): target=$addr $summary")
                        for (i in NDA_SEND_PORTS.indices) {
                            successCount[i] = 0
                            failCount[i] = 0
                        }
                    }

                    // 8초 넘게 비콘이 없으면 연결 끊긴 것으로 보고 초기화
                    if (System.currentTimeMillis() - ndaLastBeaconTime > NDA_ACTIVE_TIMEOUT_MS) {
                        NavLogger.d(this@UdpSenderService, "[NDA] 비콘 타임아웃, openpilot 연결 해제")
                        ndaRemoteAddr = null
                        OpenpilotStateRepository.updateNdaConnected(false)
                    }
                }
                delay(500) // 2Hz
            }
        }
    }

    /** 기존 latestPayload(carrot 프로토콜용 JSON)에서 값을 뽑아 road_speed_limiter.py 스키마로 재매핑. */
    private fun buildNdaRoadLimitJson(): JSONObject {
        val out = JSONObject()
        out.put("active", 1)

        try {
            val src = JSONObject(latestPayload)
            val roadLimit = JSONObject()

            val sdiType = src.optInt("nSdiType", 0)
            val sdiSpeedLimit = src.optInt("nSdiSpeedLimit", 0)
            val sdiDist = src.optInt("nSdiDist", 0)
            if (sdiType != 0) {
                // 방지턱 미감속 문제 진단용: Tmap SDK의 nSdiType 원본값이 openpilot이
                // 기대하는 cam_type=22(방지턱) 체계와 실제로 일치하는지 확인하기 위한 로그.
                // #문제시 원복
                NavLogger.d(this@UdpSenderService, "[NDA] nSdiType=$sdiType (openpilot cam_type으로 그대로 전달됨) speedLimit=$sdiSpeedLimit dist=$sdiDist")
            }

            var effectiveRoadLimit = src.optInt("nRoadLimitSpeed", 0)
            var effectiveSdiType = sdiType
            var effectiveSdiSpeedLimit = sdiSpeedLimit

            // v2.1: "이동식카메라는 감속을 안 했으면 좋겠다" - fork 종류(당근파일럿/그 외)와
            // 무관하게 이동식카메라 감속 자체를 원천 차단. cam_type을 0으로 보내면 어떤
            // openpilot fork든 이동식카메라로 인식을 못 해서 그쪽 네이티브 감속 로직이
            // 아예 안 걸림. #문제시 원복
            val mobileCamSlowdownDisabled = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                .getBoolean("mobile_cam_slowdown_disabled", false)
            if (mobileCamSlowdownDisabled && sdiType == 7) {
                effectiveSdiType = 0
                effectiveSdiSpeedLimit = 0
            }

            roadLimit.put("road_limit_speed", effectiveRoadLimit)
            roadLimit.put("is_highway", false)
            roadLimit.put("cam_type", effectiveSdiType)
            roadLimit.put("cam_limit_speed", effectiveSdiSpeedLimit)
            roadLimit.put("cam_limit_speed_left_dist", sdiDist)

            // 구간단속(secondSDIInfo/블록 정보)이 있으면 section_* 로 매핑
            val plusSpeedLimit = src.optInt("nSdiPlusSpeedLimit", 0)
            val plusDist = src.optInt("nSdiPlusDist", 0)
            if (plusSpeedLimit > 0 && plusDist > 0) {
                roadLimit.put("section_limit_speed", plusSpeedLimit)
                roadLimit.put("section_left_dist", plusDist)
            }

            roadLimit.put("cam_speed_factor", 1.05)

            out.put("road_limit", roadLimit)
            // 목적지 남은 거리/시간과 TBT 데이터를 콤마에 전달
            out.put("apilot", src)
        } catch (e: Exception) {
            // latestPayload가 아직 비어있으면(= "{}") road_limit 없이 active만 전송
        }

        return out
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        // v: 오늘 실주행 로그로 확인됨 - 카카오 화면 전환 직후 UDP 수신이 갑자기 조용히
        // 멈추고 다시는 안 살아났는데, onDestroy()에 로그가 단 한 줄도 없어서 "서비스
        // 자체가 죽은 건지, 다른 이유인지"조차 알 방법이 없었음. 최우선으로 로그부터
        // 남김 - 다음에 같은 증상 재현되면 이 로그 유무로 "OS가 서비스를 강제 종료한 것"과
        // "그 외의 원인"을 확실히 구분할 수 있음. 스택트레이스도 같이 남겨서 어떤 경로로
        // 불렸는지(직접 stopService 호출 vs 시스템 강제종료) 알 수 있게 함. #문제시 원복
        val trace = Thread.currentThread().stackTrace.joinToString("\n") { "  at $it" }
        NavLogger.e(this, "===== UdpSenderService.onDestroy() 호출됨 - 서비스 종료됨 =====\n$trace")
        super.onDestroy()
        // isRunning을 가장 먼저 false로 내려서, 각 수신 루프들이 while(isActive && isRunning.get())
        // 조건에서 스스로 빠져나오도록 신호를 준 뒤 소켓을 닫는다.
        // (신호 → 소켓 close 순서를 지키지 않으면, 루프가 아직 receive() 중인 소켓을
        //  다른 스레드가 close()해서 EBADF가 발생할 수 있다.)
        isRunning.set(false)

        CoroutineScope(Dispatchers.Main).launch {
            TmapUISDK.observableEDCData.removeObserver(edcObserver)
        }
        serviceScope.cancel()

        udpSocket?.close()
        receiveSocket?.close()
        ndaListenSocket?.close()
        ndaSendSocket?.close()

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TmapNda UDP Sender",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("TmapNda 실행 중")
        .setContentText("MD 가이드 기반 안전운행 정보 UDP 송신 중...")
        .setSmallIcon(R.mipmap.ic_launcher)
        .build()

    // Reflection Caching
    private var sdkManagerCompanion: Any? = null
    private var getInstanceMethod: java.lang.reflect.Method? = null
    private var getRecentRGDataMethod: java.lang.reflect.Method? = null
    private var nRoadLimitSpeedField: java.lang.reflect.Field? = null
    private var rgDataDumped = false

    private fun getRoadLimitSpeedFromEngine(): Int {
        try {
            if (sdkManagerCompanion == null) {
                val sdkManagerClass = Class.forName("com.skt.tmap.engine.navigation.SDKManager")
                val companionField = sdkManagerClass.getField("Companion")
                sdkManagerCompanion = companionField.get(null)
                getInstanceMethod = sdkManagerCompanion?.javaClass?.getMethod("getInstance")
            }

            val sdkManager = getInstanceMethod?.invoke(sdkManagerCompanion)
            if (sdkManager != null) {
                if (getRecentRGDataMethod == null) {
                    getRecentRGDataMethod = sdkManager.javaClass.getMethod("getRecentRGData")
                }
                val rgData = getRecentRGDataMethod?.invoke(sdkManager)

                if (rgData != null && !rgDataDumped) {
                    // 이전엔 10초마다 영구 반복 + stGuidePoint(TBTInfo) 같은 중첩 객체는 toString()만
                    // 찍혀서 내부 필드(진짜 "다음 안내지점까지 거리" 필드명)를 확인할 수 없었음.
                    // 세션당 1회만, 그리고 com.skt.tmap.* 중첩 객체는 한 단계 더 파고들어서 덤프. #문제시 원복
                    rgDataDumped = true
                    try {
                        NavLogger.e(this, "===== (UdpSenderService) rgData 전체 필드 덤프 =====")
                        for (field in rgData.javaClass.fields) {
                            try {
                                field.isAccessible = true
                                val value = field.get(rgData)
                                NavLogger.e(this, "rgData.${field.name} = $value (${field.type.simpleName})")
                                if (value != null && value.javaClass.name.startsWith("com.skt.tmap") && !value.javaClass.isArray) {
                                    for (innerField in value.javaClass.fields) {
                                        try {
                                            innerField.isAccessible = true
                                            val innerValue = innerField.get(value)
                                            NavLogger.e(this, "rgData.${field.name}.${innerField.name} = $innerValue (${innerField.type.simpleName})")
                                        } catch (ife: Exception) {
                                            NavLogger.e(this, "rgData.${field.name}.${innerField.name} 읽기 실패: ${ife.message}")
                                        }
                                    }
                                }
                            } catch (fe: Exception) {
                                NavLogger.e(this, "rgData.${field.name} 읽기 실패: ${fe.message}")
                            }
                        }
                    } catch (e: Exception) {
                        NavLogger.e(this, "rgData 전체 덤프 실패: ${e.message}")
                    }
                }
                if (rgData != null) {
                    if (nRoadLimitSpeedField == null) {
                        nRoadLimitSpeedField = rgData.javaClass.getField("nRoadLimitSpeed")
                    }
                    val rawLimitSpeed = nRoadLimitSpeedField?.getInt(rgData) ?: 0
                    if (rawLimitSpeed > 0) {
                        return (rawLimitSpeed - 20) / 10
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("UdpSenderService", "Reflection error: ${e.message}")
            NavLogger.e(this, "Reflection error (RoadLimitSpeed): ${e.message}")
        }
        return -1
    }
}
