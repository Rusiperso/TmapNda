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
import com.tmap.nda.navdy.NavdyAutoConnect
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
    // v: 재억 제보(2026-08-22) - 위 lastValidGoPosTime 우회는 티맵 자체 값(rawGoPosTime)에만
    // 적용돼 있었음. 카카오로 안내 중일 땐 이 값과 별개로 카카오 쪽 remainTime이 정지 시
    // 순간 0이 되는데, 그때 카카오 값 갱신을 건너뛰고 바로 위에서 이미 0/0으로 정해진
    // 티맵 값을 그대로 내보내서 목적지 정보가 사라졌음. 카카오 전용 직전값을 따로 둠. #문제시 원복
    private var lastValidKakaoGoPosTime = 0
    private var lastValidKakaoGoPosTimeAt = 0L

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
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    // v: 재억 요청(2026-08-27) - "핫스팟 끊었다 다시 켰는데 콤마랑 재연결이 안 된다"는
    // 제보를 로그로 확인하려 했으나, 기존 로그엔 "Wi-Fi가 실제로 언제 끊기고 언제 다시
    // 잡혔는지"를 직접 보여주는 줄이 하나도 없어서, 재연결 실패가 폰 쪽(끊긴 뒤 새
    // 네트워크로 안 갈아탐) 문제인지 콤마 쪽(재연결 후 상태 전송 프로세스가 안 살아남)
    // 문제인지 추론만 가능하고 확정을 못 했음. Wi-Fi 네트워크 자체의 생성/소실 시점을
    // ConnectivityManager.NetworkCallback으로 직접 감시해서 그 순간을 로그에 명시적으로
    // 남김 - 다음 재현 로그에서 이 줄과 "openpilot 연결 상태 변경"/"5초간 UDP 수신 없음"
    // 줄의 시간차만 비교하면 바로 원인이 갈림. 이 콜백은 로그만 남기고 기존 동작(소켓
    // 재바인딩 등)은 전혀 건드리지 않음. #문제시 원복
    private var wifiNetworkCallback: ConnectivityManager.NetworkCallback? = null

    private fun describeWifiNetwork(cm: ConnectivityManager, network: Network): String {
        val linkProps = cm.getLinkProperties(network)
        val ifaceName = linkProps?.interfaceName ?: "알수없음"
        val addrs = linkProps?.linkAddresses?.joinToString(", ") { it.address.hostAddress ?: "?" } ?: "없음"
        val netId = try {
            val f = network.javaClass.getDeclaredField("netId")
            f.isAccessible = true
            f.getInt(network)
        } catch (e: Exception) { -1 }
        return "netId=$netId, iface=$ifaceName, 주소=$addrs"
    }

    private fun registerWifiNetworkWatcher() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    NavLogger.d(this@UdpSenderService, "[네트워크 감지] Wi-Fi 네트워크 새로 잡힘 (${describeWifiNetwork(cm, network)})")
                }
                override fun onLost(network: Network) {
                    NavLogger.d(this@UdpSenderService, "[네트워크 감지] Wi-Fi 네트워크 끊김 (${describeWifiNetwork(cm, network)})")
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    NavLogger.d(this@UdpSenderService, "[네트워크 감지] Wi-Fi 네트워크 상태 변경 (${describeWifiNetwork(cm, network)})")
                }
            }
            cm.registerNetworkCallback(request, callback)
            wifiNetworkCallback = callback
            NavLogger.d(this, "[네트워크 감지] Wi-Fi 변경 감시 등록 완료")
        } catch (e: Exception) {
            NavLogger.e(this, "[네트워크 감지] Wi-Fi 변경 감시 등록 실패: ${e.message}")
        }
    }

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
    // v: 재억 요청(2026-09-02) - "카카오 안내 중엔 카카오 제한속도 우선" 채택 로그 스로틀용. #문제시 원복
    private var lastKakaoRoadLimitLogTime = 0L

    // v: 재억 제보(2026-08-28, 실기기 로그로 확인) - 분기점(예: IC/JC)을 지나칠 때
    // Tmap 엔진 리플렉션(getRoadLimitSpeedFromEngine)이 순간적으로 진출로의 낮은
    // 제한속도(예: 40)로 잘못 매칭됐다가 몇 초 뒤 본선 값(예: 80)으로 스스로 돌아오는
    // 경우가 있음 - 그 순간 openpilot한테는 40이 그대로 나가서 실제로 감속했다가
    // 다시 가속하는 일이 생김. 화면 표시용(MapActivity)에는 "분기 오매칭 방지"
    // 히스테리시스(연속 2번 확인해야 반영, 단 실제 분기점 250m 이내면 즉시 반영)가
    // 이미 있었는데, 정작 openpilot한테 나가는 이 값 계산 쪽(UdpSenderService)엔
    // 안 붙어있었음 - 그래서 "예전에 고친 문제가 또 재발"한 것처럼 보였던 것.
    // MapActivity와 동일한 로직을 여기에도 적용. #문제시 원복
    private var pendingGeneralRoadLimit = 0
    private var pendingGeneralRoadLimitCount = 0
    private val GENERAL_ROAD_LIMIT_REQUIRED_CONSECUTIVE = 2
    // v: 재억 제안(2026-08-30) - 경로상 진출/회전이 예정돼 있지 않을 때(그냥 직진 중이거나
    // 다음 회전이 한참 멀 때)는, 낮은 값이 몇 번 연속으로 나와야 "진짜 도로 제한속도가
    // 바뀐 것"으로 볼지를 훨씬 더 엄격하게 잡음. 분기 오매칭(GPS 순간 튐)은 보통 1~2초
    // 안에 사라지지만, 진짜 제한속도 변화(예: 80→60 구간 진입)는 몇 초씩 계속 유지됨.
    // 이 판단이 도는 루프(sendSdiData)가 300ms 주기라서, 15번 ≈ 4.5초로 설정. #문제시 원복
    private val GENERAL_ROAD_LIMIT_REQUIRED_CONSECUTIVE_FAR_FROM_ROUTE_TURN = 15
    private val GENERAL_ROAD_LIMIT_TBT_NEAR_THRESHOLD_M = 250

    // v: 재억 재설명(2026-09-02) - "잘 가다가 옆도로를 잡는 게 계속 문제. 70도로를 직진 중인데
    // 고가도로 아래 도로나 분기점으로 빠지는 도로의 속도를 갑자기 잡는다."
    // 지금까지의 방어는 "낮은 값이 15번(약 4.5초) 연속으로 나오면 진짜로 보고 받아들인다"였는데,
    // 티맵이 옆도로에 한 번 붙으면 4.5초쯤은 그대로 붙어있기 때문에 결국 잘못된 값이 통과했음
    // (로그의 "확인횟수 n/15 - 보류"가 계속 나오다가 결국 넘어감).
    // 카카오는 우리가 지금 경로의 어느 지점을 달리는지(경로 매칭)와 다음 안내가 뭔지를 정확히
    // 알고 있으므로, 카카오 안내 중이라면 이걸 기준으로 삼음:
    //   - 카카오가 곧 진출/회전한다고 하거나(기존 판단), 카카오가 알려주는 도로명이 실제로
    //     방금 바뀌었으면 -> 도로가 진짜 바뀌는 중이므로 낮아지는 값을 받아들임
    //   - 둘 다 아니면(= 카카오 기준 본선 직진 중) -> 티맵이 뭐라고 하든 낮추지 않음(횟수 무관)
    // 카카오 안내 중이 아닐 땐 기존 히스테리시스 그대로. #문제시 원복
    private var lastKakaoRoadNameForLimit = ""
    private var kakaoRoadNameChangedAt = 0L
    private var lastMainlineHoldLogTime = 0L
    private val KAKAO_ROAD_NAME_CHANGE_GRACE_MS = 15_000L
    // v: 재억 지적(2026-09-02) - 카카오 판정과 어긋나는 값도 이만큼 계속 유지되면 결국 반영.
    // "영구 거부"를 없애기 위한 상한. 실주행 로그상 옆도로 오매칭은 값이 계속 튀어서 이 시간을
    // 못 채우고, 진짜 제한속도 변화(100->80 등)는 계속 유지되므로 정상 반영됨. #문제시 원복
    private val LIMIT_CHANGE_HOLD_MS = 6_000L
    private var pendingLimitValue = 0
    private var pendingLimitSince = 0L
    private var lastTmapSdiSuppressLogTime = 0L

    /**
     * v: 재억 재정리(2026-09-02) - 원하는 규칙은 정확히 두 줄이고, 방향이 서로 반대임:
     *   1) 카카오가 본선 직진 중이면(예: 70) 분기점 값(예: 50)을 "잡지 마라" = 하향 거부
     *   2) 카카오가 분기점으로 나가는 중이면(예: 50) 본선 값(예: 70)을 "잡지 마라" = 상향 거부
     * 그래서 "지금 도로가 바뀌는 중인지"만 보고 양쪽을 다 허용/거부하면 안 되고, 상황에 따라
     * 방향을 나눠서 판단해야 함:
     *   - 진출/회전 구간(임박 또는 막 통과한 직후) : 낮아지는 건 받고, 높아지는 건 거부
     *     -> 진출로를 타는 중엔 본선 값으로 다시 안 튀어오름
     *   - 본선 직진 중                              : 높아지는 건 받고, 낮아지는 건 거부
     *     -> 옆도로/진출로 값으로 안 떨어짐(급감속 방지). 본선 값 복귀는 즉시 반영
     * 어느 쪽이든 "잘못 잡아서 급감속"은 안 생기고, 반대로 진짜 변화는 카카오가 알려줄 때
     * 바로 따라감. 카카오 안내 중이 아닐 땐 이 판단 자체를 안 씀(기존 히스테리시스). #문제시 원복
     *
     * v: 재억 지적(2026-09-02, 중요) - "아무리 같은 길이래도 카메라(제한속도)가 변화하는 건
     * 받아와야지. 직진 100 구간이 100/80/100/60으로 바뀌는데 죽어도 100을 잡고 가면 안 되지."
     * 정확한 지적이고, 직전 버전은 실제로 그렇게 동작했음(카카오가 직진 중이라고 하면 하향을
     * "영구히" 거부). 급감속은 막히지만 반대로 진짜 하향 구간을 통째로 놓쳐서 과속 방향이 됨.
     *
     * 그래서 "거부"를 전부 시간 제한이 있는 "보류"로 바꿈. 어떤 값이든 결국은 반영되고,
     * 다만 오매칭으로 의심되는 값은 잠깐 기다렸다가 반영함:
     *   - 근처 후보 도로가 하나뿐이면(nearLinks==1) 오매칭 자체가 성립 불가 -> 즉시 수용
     *   - 카카오 경로 판정과 방향이 맞으면 -> 즉시 수용
     *     (본선 직진 중 상향 / 진출·회전 중 하향 = 진짜 도로 변화와 일치)
     *   - 방향이 어긋나면 -> 같은 값이 HOLD(6초) 이상 계속 유지될 때만 수용
     *     실제 로그상 옆도로 오매칭은 값이 계속 튀어서(카운트가 1에서 안 올라감) 6초를
     *     못 채우고, 진짜 제한속도 변화는 계속 유지되므로 6초 뒤 정상 반영됨.
     * 최악의 경우에도 6초 늦게 반영될 뿐, 영영 안 바뀌는 일은 없음. #문제시 원복
     *
     * @return true = 지금 반영, false = 아직 보류(계속 유지되면 6초 뒤 자동 반영)
     */
    private fun kakaoVetsLimitChange(newLimit: Int, currentLimit: Int, nearManeuverPoint: Boolean): Boolean {
        val now = System.currentTimeMillis()
        val kakaoRoadName = KakaoRouteDataRepository.roadName
        if (kakaoRoadName != lastKakaoRoadNameForLimit) {
            lastKakaoRoadNameForLimit = kakaoRoadName
            kakaoRoadNameChangedAt = now
        }
        val roadNameJustChanged =
            kakaoRoadNameChangedAt > 0 && now - kakaoRoadNameChangedAt < KAKAO_ROAD_NAME_CHANGE_GRACE_MS
        // 진출/회전 구간 = 카카오가 곧 빠진다고 하거나, 방금 도로가 바뀐 직후(진출로 진입 직후)
        val onRampOrTurn = nearManeuverPoint || roadNameJustChanged
        val directionMatchesKakao = if (onRampOrTurn) {
            newLimit <= currentLimit   // 규칙 2: 진출 중이면 낮아지는 게 맞음
        } else {
            newLimit >= currentLimit   // 규칙 1: 본선 직진 중이면 높아지는 게 맞음
        }

        // 같은 값이 얼마나 계속 유지되고 있는지 추적(값이 바뀌면 처음부터 다시)
        if (newLimit != pendingLimitValue) {
            pendingLimitValue = newLimit
            pendingLimitSince = now
        }
        val heldMs = now - pendingLimitSince

        // 근처 후보 도로가 하나뿐이면 옆도로를 잘못 잡는 것 자체가 불가능 -> 바로 수용.
        // (이 값이 안 채워지는 기기/상황에서는 0이나 -1이 나오므로 이 분기는 그냥 안 탐)
        val nearLinks = readTmapNearLinkCount()
        if (nearLinks == 1) return true

        if (directionMatchesKakao) return true
        return heldMs >= LIMIT_CHANGE_HOLD_MS
    }

    /**
     * 티맵 엔진이 보는 "지금 위치에 겹치는 후보 도로 링크 수". 2 이상이면 고가도로/평행도로/
     * 진출로가 겹치는 구간 = 오매칭이 실제로 일어나는 지점. 못 읽으면 -1. #문제시 원복
     */
    private fun readTmapNearLinkCount(): Int {
        return try {
            val sdkManager = getInstanceMethod?.invoke(sdkManagerCompanion) ?: return -1
            if (getRecentRGDataMethod == null) {
                getRecentRGDataMethod = sdkManager.javaClass.getMethod("getRecentRGData")
            }
            val rgData = getRecentRGDataMethod?.invoke(sdkManager) ?: return -1
            val f = rgData.javaClass.getField("nearLinks")
            f.isAccessible = true
            f.getInt(rgData)
        } catch (e: Exception) {
            -1
        }
    }

    /** 지금 이 순간 카카오도 경로 위에서 안전이벤트(카메라/방지턱 등)를 잡고 있는지. #문제시 원복 */
    private fun kakaoConfirmsSafetyEventNow(): Boolean {
        val kr = KakaoRouteDataRepository
        return kr.safetyType >= 0 && kr.safetyDist > 0
    }
    private var lastCameraLimitBlockLogTime = 0L
    // v: 재억 요청(2026-09-02) - 티맵 자체 도로매칭 진단 로그 스로틀용. #문제시 원복
    private var lastMatchingDiagLogTime = 0L
    private var lastMatchingBaselineLogTime = 0L
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
        // v: [초안 - 미커밋] 카카오 길안내 시작을 안드로이드 오토에게 알려서 클러스터/HUD
        // 세션(TmapNdaCarAppService)이 스스로 깨어나게 함. #문제시 원복
        com.tmap.nda.hud.TmapNdaCarNotifier.start(this)
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
        registerWifiNetworkWatcher()

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TmapNda::UdpSenderWakelock")
            wakeLock?.acquire()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // v10.7: 사용자 제보(재억, 2026-08-17) - "카메라/방지턱 없는데 갑자기 감속하려 함".
        // 로그 분석 결과 openpilot과의 UDP 비콘 연결이 3~4분마다 8초 넘게 완전히 끊겼다
        // 재연결되는 패턴이 반복됨(전체 40분 주행에 재연결 11회) - 재연결 시점마다 크루즈
        // 상태가 리셋되면서 순간적으로 이상한 목표속도가 잡히는 것으로 추정. CPU
        // WakeLock만 걸려있고 와이파이 절전모드 방지는 안 되어 있어서, 와이파이 라디오가
        // 주기적으로 저전력모드로 빠지며 짧은 UDP 비콘 패킷을 놓치는 게 원인일 가능성이
        // 높음. WifiLock(FULL_HIGH_PERF)을 추가로 걸어서 와이파이가 절전모드로 안 빠지게
        // 막음 - 재연결 자체가 줄어들길 기대.
        // v: 재억 제보(2026-08-28, 실기기 로그로 확인) - "순정 티맵 켜면 나브디가 안 끊기고
        // 잘 붙는데, TmapNda 켜면 나브디가 안 됨"이라는 결정적 비교 관찰. 갤럭시 S23 계열은
        // Wi-Fi/블루투스가 콤보 칩+안테나를 공유하는 게 보통이라, WIFI_MODE_FULL_HIGH_PERF로
        // Wi-Fi 라디오를 절대 저전력으로 안 빠지게 강제로 잡아두면 블루투스 쪽(나브디 RFCOMM
        // 연결)이 무선 자원을 제때 못 받아 계속 실패할 가능성이 높음 - 원인 진단을 위해
        // 일단 완전히 꺼봄. 나브디가 이걸로 정상화되면 범인 확정, 대신 openpilot 비콘이
        // 다시 끊기기 시작하면 절충안(나브디 연결 중에만 풀어주는 등) 필요. #문제시 원복
        /*
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            wifiLock = wifiManager.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TmapNda::UdpSenderWifiLock")
            wifiLock?.acquire()
            NavLogger.d(this, "[전원관리] WifiLock(FULL_HIGH_PERF) 획득 성공 - 와이파이 절전모드 방지")
        } catch (e: Exception) {
            NavLogger.e(this, "[전원관리] WifiLock 획득 실패: ${e.message}")
        }
        */
        NavLogger.d(this, "[전원관리] WifiLock 진단용 비활성화 상태 - 나브디 연결 원인 확인 중")

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
        val volumePercent = try { VolumeHelper.guideVolumePercent(ctx) } catch (e: Exception) { -1 }
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
            // v: 재억 제보(2026-08-28, 실기기 로그로 확인) - onCreate()에서 이 루프를 바로
            // 띄웠더니, isRunning이 아직 true로 안 바뀐 시점에 while(isActive &&
            // isRunning.get()) 첫 조건 검사가 걸려서 루프 자체가 시작하자마자 조용히
            // 끝나버리는 경우가 있었음(에러 로그도 안 남음) - 그래서 앱 켤 때 1번 시도한
            // 이후로 재시도가 영영 안 됐던 것. 다른 루프들과 같이 isRunning이 true로
            // 확정된 이 블록 안에서 시작하도록 옮김. #문제시 원복
            startNavdyReconnectLoop()
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
                    // v: 재억 제보(2026-08-22) - "300~500m 구간에서 계속 띵띵 울림". 원인:
                    // 이 판단이 "카메라에 자기 제한속도(nSdiSpeedLimit) 숫자가 딸려있을 때"만
                    // 카메라 근처로 인식했음. 방지턱이나 제한속도 숫자가 안 딸린 카메라는 이
                    // 판단을 통과해버려서, 도로 기본 제한속도가 그 순간 카메라 근처값으로
                    // 잘못 갱신되고 그걸로 10% 초과 경고음이 오탐 발생. 이제 제한속도 숫자
                    // 유무와 무관하게, 이벤트 종류가 있고 거리가 500m 이내면 전부 카메라
                    // 근처로 인식하게 넓힘. #문제시 원복
                    if (currentLimitSpeed >= 30) {
                        lastRoadLimitUpdateTime = System.currentTimeMillis()
                        // v: 재억 재제보(2026-08-28, 실기기 로그로 확인) - realRoadLimit(엔진
                        // 리플렉션) 경로와 완전히 똑같은 구멍이 이 번들 값(limitSpeed) 경로에도
                        // 있었음: roadLimitSpeed를 무조건 즉시 반영하고 있었음. 같은 히스테리시스를
                        // 여기도 동일하게 적용. pending 상태는 realRoadLimit 경로와 공유해도
                        // 안전함(둘 다 "지금 도로의 기본 제한속도"라는 같은 개념을 다른 소스에서
                        // 가져오는 것뿐이라, 어느 쪽에서 오든 똑같이 연속 확인이 필요함). #문제시 원복
                        val routeExpectsExitOrTurnSoonForLimitSpeed = KakaoRouteDataRepository.tbtDist < 500 &&
                            KakaoRouteDataRepository.tbtTurnType in setOf(12, 13, 6, 7, 101, 102)
                        val nearManeuverPointForLimitSpeed = routeExpectsExitOrTurnSoonForLimitSpeed &&
                            KakaoRouteDataRepository.tbtDist in 0..GENERAL_ROAD_LIMIT_TBT_NEAR_THRESHOLD_M
                        val requiredConsecutiveNowForLimitSpeed = if (routeExpectsExitOrTurnSoonForLimitSpeed) GENERAL_ROAD_LIMIT_REQUIRED_CONSECUTIVE else GENERAL_ROAD_LIMIT_REQUIRED_CONSECUTIVE_FAR_FROM_ROUTE_TURN
                        // v: 재억 재설명(2026-09-02) - 카카오 안내 중이고 카카오 기준 본선 직진
                        // 중이면, 티맵이 낮은 값을 아무리 여러 번 줘도 받아들이지 않음. #문제시 원복
                        val kakaoGuidingForLimitSpeed = KakaoRouteDataRepository.isFresh()
                        val kakaoBlocksForLimitSpeed = kakaoGuidingForLimitSpeed &&
                            generalRoadLimitSpeed > 0 &&
                            currentLimitSpeed != generalRoadLimitSpeed &&
                            !kakaoVetsLimitChange(currentLimitSpeed, generalRoadLimitSpeed, nearManeuverPointForLimitSpeed)
                        val vettedCurrentLimitSpeed: Int? = if (kakaoBlocksForLimitSpeed) {
                            // 값을 유지하는 게 지금의 "판단"이므로, 유지되는 동안 값이 8초 만료로
                            // 0이 되어버리지 않도록 기준 시각도 같이 갱신. #문제시 원복
                            lastRoadLimitUpdateTime = System.currentTimeMillis()
                            lastGeneralRoadLimitUpdateTime = System.currentTimeMillis()
                            pendingGeneralRoadLimitCount = 0
                            if (System.currentTimeMillis() - lastMainlineHoldLogTime > 5000L) {
                                lastMainlineHoldLogTime = System.currentTimeMillis()
                                NavLogger.d(
                                    this@UdpSenderService,
                                    "[도로제한][카카오기준보류][limitSpeed] 티맵=$currentLimitSpeed 기존=$generalRoadLimitSpeed " +
                                        "(${if (currentLimitSpeed < generalRoadLimitSpeed) "하향" else "상향"}) - 카카오 기준 " +
                                        "${if (nearManeuverPointForLimitSpeed) "진출/회전 중인데 본선값" else "본선 직진 중인데 분기값"} - " +
                                        "유지시간=${System.currentTimeMillis() - pendingLimitSince}ms/${LIMIT_CHANGE_HOLD_MS}ms 보류(계속 유지되면 자동 반영) " +
                                        "nearLinks=${readTmapNearLinkCount()} " +
                                        "(도로=${KakaoRouteDataRepository.roadName} 다음안내=${KakaoRouteDataRepository.tbtTurnType}/${KakaoRouteDataRepository.tbtDist}m)"
                                )
                            }
                            null
                        } else if (currentLimitSpeed < generalRoadLimitSpeed && !nearManeuverPointForLimitSpeed) {
                            if (currentLimitSpeed == pendingGeneralRoadLimit) {
                                pendingGeneralRoadLimitCount++
                            } else {
                                pendingGeneralRoadLimit = currentLimitSpeed
                                pendingGeneralRoadLimitCount = 1
                            }
                            if (pendingGeneralRoadLimitCount >= requiredConsecutiveNowForLimitSpeed) {
                                pendingGeneralRoadLimitCount = 0
                                currentLimitSpeed
                            } else {
                                NavLogger.d(this@UdpSenderService, "[도로제한][분기오매칭방지][limitSpeed] currentLimitSpeed=$currentLimitSpeed < 기존=$generalRoadLimitSpeed, 경로상진출예정=$routeExpectsExitOrTurnSoonForLimitSpeed, 확인횟수=$pendingGeneralRoadLimitCount/$requiredConsecutiveNowForLimitSpeed - 보류")
                                null
                            }
                        } else {
                            pendingGeneralRoadLimitCount = 0
                            currentLimitSpeed
                        }
                        // v: 재억 재제보(2026-08-29) - realRoadLimit 경로와 동일한 오실레이션
                        // 버그가 여기도 있었음(확정된 값을 카메라 이벤트 있으면 기준값에는
                        // 반영 안 해서, 카메라가 몇 초씩 계속 잡히는 상황에서 확정→미반영→
                        // 재확정을 반복하며 roadLimitSpeed가 신구 값 사이를 오갔음). #문제시 원복
                        if (vettedCurrentLimitSpeed != null) {
                            roadLimitSpeed = vettedCurrentLimitSpeed
                            generalRoadLimitSpeed = vettedCurrentLimitSpeed
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
                            // v: 재억 재설명(2026-09-02) - "잘 가다가 갑자기 속도를 확 줄이려 해서
                            // 뒤차가 크락션 누른다"의 남은 통로가 여기였음. 위쪽 도로제한 경로는
                            // 카카오 기준으로 막아놨는데, 옆도로 카메라의 개별 제한속도(예: 진출로
                            // 50)는 이 줄에서 곧바로 roadLimitSpeed(=콤마로 나가는 nRoadLimitSpeed)를
                            // 덮어써서 그대로 급감속을 유발할 수 있었음. 카카오가 안내 중인데 카카오
                            // 경로엔 그 이벤트가 없다면, 카메라 정보 자체는 그대로 내보내되(콤마가
                            // nSdiType/nSdiDist로 알아서 판단) 도로제한속도까지 끌어내리지는 않음.
                            // 카카오 안내 중이 아니면 예전 동작 그대로. #문제시 원복
                            val kakaoGuidingNowForCamera = KakaoRouteDataRepository.isFresh()
                            if (kakaoGuidingNowForCamera && !kakaoConfirmsSafetyEventNow()) {
                                if (System.currentTimeMillis() - lastCameraLimitBlockLogTime > 5000L) {
                                    lastCameraLimitBlockLogTime = System.currentTimeMillis()
                                    NavLogger.d(
                                        this@UdpSenderService,
                                        "[도로제한][카카오기준보류][카메라] 티맵 카메라 제한속도=$sdiSpeedLimit 이지만 카카오 경로엔 " +
                                            "해당 이벤트 없음 - 도로제한속도(현재=$roadLimitSpeed)는 안 낮춤(카메라 정보 자체는 그대로 전송)"
                                    )
                                }
                            } else {
                                roadLimitSpeed = sdiSpeedLimit
                                lastRoadLimitUpdateTime = System.currentTimeMillis()
                            }
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
                    // v: 재억 요청(2026-09-02) - 티맵 단독 주행에서도 같은 방어를 걸 수 있는지
                    // 확인하기 위한 진단(동작 변경 없음). 값이 실제로 바뀌려는 순간에 남겨야
                    // 의미가 있으므로, 제한속도가 지금과 다른 프레임에서만(그리고 3초 스로틀)
                    // 남김. 값이 안정된 구간에서는 30초에 한 번 기준선만 남김. #문제시 원복
                    if (realRoadLimit >= 30 && realRoadLimit != generalRoadLimitSpeed) {
                        if (System.currentTimeMillis() - lastMatchingDiagLogTime > 3000L) {
                            lastMatchingDiagLogTime = System.currentTimeMillis()
                            logTmapMatchingDiagnostics("제한속도 변화시도 티맵=$realRoadLimit 기존=$generalRoadLimitSpeed")
                        }
                    } else if (System.currentTimeMillis() - lastMatchingBaselineLogTime > 30_000L) {
                        lastMatchingBaselineLogTime = System.currentTimeMillis()
                        logTmapMatchingDiagnostics("기준선(값 안정, 현재=$generalRoadLimitSpeed)")
                    }
                    if (realRoadLimit >= 30) {
                        lastRoadLimitUpdateTime = System.currentTimeMillis()
                        // v: 재억 제보(2026-08-28, 실기기 로그로 확인) - 예전엔 roadLimitSpeed(실제
                        // openpilot에 나가는 값)를 여기서 무조건 즉시 반영해두고, 나중에 "이번 프레임에
                        // 카메라 이벤트가 없으면" generalRoadLimitSpeed로 되돌리는 뒤늦은 보정에만
                        // 의존했음. 그 보정은 카메라/방지턱 같은 안전정보가 마침 같은 순간에 겹치면
                        // 건너뛰어져서, 분기 오매칭으로 인한 잘못된 값이 그대로 openpilot에 나갈 수
                        // 있었음(화면 표시용 generalRoadLimitSpeed만 보호되고 있었던 것). 이제
                        // roadLimitSpeed 자체에 처음부터 히스테리시스를 적용해서, 카메라 정보 유무와
                        // 무관하게 항상 보호되게 함. #문제시 원복
                        // v: 재억 제안(2026-08-30) - "지금까지는 GPS/도로매칭 기반으로만
                        // 판단했는데, 카카오가 이미 알고 있는 경로 정보(다음 안내가 뭐고
                        // 얼마나 남았는지)를 직접 물어보면 분기점 오매칭 자체를 원천 차단할
                        // 수 있지 않겠냐"는 제안. 정확히 그 방향으로 구현: 지금 안내 중인
                        // 다음 지점이 실제로 진출로/회전(고속도로 나가기, 좌우회전, 좌우측
                        // 분기)이고 그게 가까이(500m 이내) 있을 때만 "지금 속도가 낮아지는
                        // 게 진짜일 수 있다"고 보고 기존 판단(히스테리시스/즉시반영)을 그대로
                        // 적용. 그게 아니면(그냥 직진 중이거나, 다음 회전이 한참 멀리 있으면)
                        // 낮은 값 자체를 아예 무시 - GPS가 순간적으로 옆 도로에 붙어도
                        // "지금 나갈 계획 자체가 없다"는 걸 경로가 알고 있으니 절대 안 잡힘. #문제시 원복
                        // v: 재억 제안(2026-08-30) - "카카오 경로를 보고 실제 진출/회전
                        // 예정일 때만 속도를 잡게 하자"는 제안을 반영하되, 완전히 무시하는
                        // 방식은 위험함을 스스로 재검토함: 진출로가 없는 일반 도로에서도
                        // 제한속도가 바뀌는 구간(80→60 등)이 흔한데, 그런 정상적인 변화까지
                        // 영원히 막아버리면 안 됨. 대신 "지금 경로상 진출/회전 예정이 없으면
                        // 확인 횟수 요구치를 훨씬 늘림"으로 바꿈 - 분기 오매칭은 보통 GPS
                        // 순간 튐이라 1~2초면 사라지는데, 진짜 도로 제한속도 변화는 몇 초씩
                        // 계속 유지되므로 이 방식으로 안전하게 구분됨. #문제시 원복
                        val routeExpectsExitOrTurnSoon = KakaoRouteDataRepository.tbtDist < 500 &&
                            KakaoRouteDataRepository.tbtTurnType in setOf(12, 13, 6, 7, 101, 102)
                        val nearManeuverPoint = routeExpectsExitOrTurnSoon &&
                            KakaoRouteDataRepository.tbtDist in 0..GENERAL_ROAD_LIMIT_TBT_NEAR_THRESHOLD_M
                        val requiredConsecutiveNow = if (routeExpectsExitOrTurnSoon) GENERAL_ROAD_LIMIT_REQUIRED_CONSECUTIVE else GENERAL_ROAD_LIMIT_REQUIRED_CONSECUTIVE_FAR_FROM_ROUTE_TURN
                        // v: 재억 재설명(2026-09-02) - limitSpeed 경로와 동일하게, 카카오 기준
                        // 본선 직진 중이면 티맵 엔진의 하향 값도 받아들이지 않음. #문제시 원복
                        val kakaoBlocksForRealLimit = KakaoRouteDataRepository.isFresh() &&
                            generalRoadLimitSpeed > 0 &&
                            realRoadLimit != generalRoadLimitSpeed &&
                            !kakaoVetsLimitChange(realRoadLimit, generalRoadLimitSpeed, nearManeuverPoint)
                        val vettedRealRoadLimit: Int? = if (kakaoBlocksForRealLimit) {
                            lastRoadLimitUpdateTime = System.currentTimeMillis()
                            lastGeneralRoadLimitUpdateTime = System.currentTimeMillis()
                            pendingGeneralRoadLimitCount = 0
                            if (System.currentTimeMillis() - lastMainlineHoldLogTime > 5000L) {
                                lastMainlineHoldLogTime = System.currentTimeMillis()
                                NavLogger.d(
                                    this@UdpSenderService,
                                    "[도로제한][카카오기준보류][realRoadLimit] 티맵=$realRoadLimit 기존=$generalRoadLimitSpeed " +
                                        "(${if (realRoadLimit < generalRoadLimitSpeed) "하향" else "상향"}) - 카카오 기준 " +
                                        "${if (nearManeuverPoint) "진출/회전 중인데 본선값" else "본선 직진 중인데 분기값"} - " +
                                        "유지시간=${System.currentTimeMillis() - pendingLimitSince}ms/${LIMIT_CHANGE_HOLD_MS}ms 보류(계속 유지되면 자동 반영) " +
                                        "nearLinks=${readTmapNearLinkCount()} " +
                                        "(도로=${KakaoRouteDataRepository.roadName} 다음안내=${KakaoRouteDataRepository.tbtTurnType}/${KakaoRouteDataRepository.tbtDist}m)"
                                )
                            }
                            null
                        } else if (realRoadLimit < generalRoadLimitSpeed && !nearManeuverPoint) {
                            if (realRoadLimit == pendingGeneralRoadLimit) {
                                pendingGeneralRoadLimitCount++
                            } else {
                                pendingGeneralRoadLimit = realRoadLimit
                                pendingGeneralRoadLimitCount = 1
                            }
                            if (pendingGeneralRoadLimitCount >= requiredConsecutiveNow) {
                                pendingGeneralRoadLimitCount = 0
                                realRoadLimit
                            } else {
                                NavLogger.d(this@UdpSenderService, "[도로제한][분기오매칭방지] realRoadLimit=$realRoadLimit < 기존=$generalRoadLimitSpeed, 경로상진출예정=$routeExpectsExitOrTurnSoon, 확인횟수=$pendingGeneralRoadLimitCount/$requiredConsecutiveNow - 보류")
                                null
                            }
                        } else {
                            pendingGeneralRoadLimitCount = 0
                            realRoadLimit
                        }
                        // v: 재억 재제보(2026-08-29, 실기기 로그로 확인) - "19버전부터 분기점에서
                        // 급감속하려 한다"는 재발 제보로 다시 조사. 원인: 위에서 히스테리시스로
                        // "연속 2번 확인"까지 다 통과해서 값이 확정됐는데도, 그 확정된 값을
                        // generalRoadLimitSpeed(비교 기준)에는 "카메라 이벤트가 없을 때만"
                        // 반영하고 있었음. 근처에 과속카메라가 몇 초씩 계속 감지되는 흔한
                        // 상황에서는 이 기준값이 계속 옛날 값에 멈춰있게 되고, 그러면 매번
                        // 새로 "연속 2번"을 처음부터 다시 세면서 확정→기준값 미반영→확정→...을
                        // 반복 - roadLimitSpeed(실제 openpilot 전송값)가 옛값과 새값 사이를
                        // 계속 오갔던 것으로 보임(로그의 "확인횟수 1/2"가 8초 내내 안 풀리던
                        // 이유). 히스테리시스를 이미 통과한 값은 카메라 이벤트 여부와 무관하게
                        // 기준값도 같이 확정해야 이 오실레이션이 안 생김. #문제시 원복
                        if (vettedRealRoadLimit != null) {
                            roadLimitSpeed = vettedRealRoadLimit
                            generalRoadLimitSpeed = vettedRealRoadLimit
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

                    // v: 재억 요청(2026-09-02) - "카카오 길안내 중에는 카카오 제한속도를
                    // 우선으로". 지금까지 콤마로 나가는 도로제한속도는 100% 티맵 소스(EDCData
                    // limitSpeed + 티맵 엔진 리플렉션)였고, 티맵 GPS 매칭이 옆 도로를 물면
                    // 카카오 경로가 70인 구간에서도 50이 그대로 나갔음(재억 제보 4번).
                    // 카카오 안내 중이고 카카오 도로제한속도가 신선하면 그 값을 우선 채택.
                    // 카카오가 값을 못 주면(현재 게터 미확정 상태) 기존 티맵 값 그대로 -
                    // 즉 나빠질 일은 없고, 게터가 확정되는 순간 자동으로 카카오 우선이 됨.
                    // 단, 이번 프레임에 카메라 개별 제한속도(sdiSpeedLimit)가 잡혀 있으면
                    // 그건 카메라 감속용이라 덮지 않음(기존 동작 유지). #문제시 원복
                    val kakaoGuidingNow = KakaoRouteDataRepository.isFresh()
                    val kakaoLimitFresh = SdiDataRepository.isKakaoRoadLimitFresh()
                    val cameraLimitActiveNow = lastSdiJsonStr != null &&
                        JSONObject(lastSdiJsonStr!!).optInt("nSdiSpeedLimit", 0) >= 30
                    if (kakaoGuidingNow && kakaoLimitFresh && !cameraLimitActiveNow) {
                        val kakaoLimit = SdiDataRepository.kakaoRoadLimitSpeed
                        if (kakaoLimit != roadLimitSpeed &&
                            System.currentTimeMillis() - lastKakaoRoadLimitLogTime > 10_000L
                        ) {
                            lastKakaoRoadLimitLogTime = System.currentTimeMillis()
                            NavLogger.d(this@UdpSenderService, "[도로제한][카카오우선] 카카오=$kakaoLimit 티맵=$roadLimitSpeed -> 카카오 값 채택")
                        }
                        roadLimitSpeed = kakaoLimit
                        generalRoadLimitSpeed = kakaoLimit
                        lastRoadLimitUpdateTime = System.currentTimeMillis()
                        lastGeneralRoadLimitUpdateTime = System.currentTimeMillis()
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
                    // v: 신규기능(목적지 반경 도착알림) - 티맵 화면 안내 중에도 동일하게 적용. #문제시 원복
                    if (nGoPosDist > 0 && ArrivalRadiusAlert.checkShouldWarn(this@UdpSenderService, nGoPosDist)) {
                        ArrivalRadiusAlert.playAlert(this@UdpSenderService)
                    }

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
                    json.put("szTBTMainText", prefix)

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
                        // v10.2: "60도로 65 주행(10% 안넘음)인데도 경고음"(재억 제보) - 원인은
                        // 여기서 limitSpeed를 SdiDataRepository.roadLimitSpeed 자기 자신을 그대로
                        // 되읽어와서 넣고 있었다는 것(=실질적으로 갱신이 전혀 안 되고 MapActivity가
                        // 마지막으로 써넣은 값에 계속 고정). MapActivity 쪽 갱신은 observe(this@MapActivity,
                        // ...)라 카카오 화면이 위로 뜨면(=MapActivity가 화면 밖으로 밀리면) 아예
                        // 멈춰버려서, 그 순간의 낮은 값(예: 카메라 개별 제한속도)에 고정된 채로
                        // 실제 도로 제한속도가 올라가도 안 따라감. 이 블록(edcObserver)은
                        // observeForever라 화면 전환과 무관하게 항상 도는 유일한 경로이므로,
                        // 여기서 이미 안정화 로직(8초 타임아웃 등)을 거친 generalRoadLimitSpeed를
                        // 진짜 출처로 써서 SdiDataRepository.roadLimitSpeed를 직접 갱신함. #문제시 원복
                        if (generalRoadLimitSpeed >= 30) {
                            SdiDataRepository.roadLimitSpeed = generalRoadLimitSpeed
                        }
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
                            lastValidKakaoGoPosTime = kr.remainTime
                            lastValidKakaoGoPosTimeAt = System.currentTimeMillis()
                            // v: 신규기능(목적지 반경 도착알림) - 카카오 길안내 중 남은거리가
                            // 설정된 반경 안으로 처음 들어오는 순간 알림. #문제시 원복
                            if (ArrivalRadiusAlert.checkShouldWarn(this@UdpSenderService, kr.remainDist)) {
                                ArrivalRadiusAlert.playAlert(this@UdpSenderService)
                            }
                        } else if (kr.remainDist > 0 && lastValidKakaoGoPosTime > 0 &&
                            System.currentTimeMillis() - lastValidKakaoGoPosTimeAt < 5 * 60 * 1000L
                        ) {
                            // 카카오 거리는 살아있는데(정지 등으로) 시간만 순간 0 - 티맵 값으로
                            // 덮어써지지 않도록 카카오 직전 값으로 채워서 계속 목적지 정보 유지
                            json.put("nGoPosDist", kr.remainDist)
                            json.put("nGoPosTime", lastValidKakaoGoPosTime)
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
                        json.put("szTBTMainText", kakaoPrefix)
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
                        // v10.1: Tmap 분기(위쪽)엔 "sdiType==0인데 speedLimit/dist는 있으면 1로
                        // 강제"하는 안전장치가 있는데 여기(카카오 채택 분기)엔 없어서, 향후 또
                        // 다른 카카오 안전코드가 실수로 0에 매핑되면 여기서도 똑같이 조용히
                        // 무시(=카메라없음으로 오인식)될 수 있음. 동일한 안전장치를 걸어둠. #문제시 원복
                        val safeKakaoSdiType = if (kr.safetyType == 0 && kr.safetySpeedLimit > 0 && kr.safetyDist > 0) 1 else kr.safetyType

                        // v: 신규기능(안전정보 불일치 감지) - 티맵/카카오 둘 다 이 지점에 안전정보를
                        // 주고 있는데 서로 다른 값(카메라 있음/없음, 종류, 제한속도)을 말할 때를
                        // 별도 로그로 자동 기록. 지금까지는 재억이 직접 "여기 이상하다" 제보해야만
                        // 알 수 있었는데, 이제 두 SDK 값이 갈리는 순간을 놓치지 않고 잡아둠 -
                        // 나중에 어느 쪽이 더 믿을만한지 패턴 분석하는 데 씀. 우선순위 판단 로직
                        // 자체엔 영향 없음(로그만 남김). #문제시 원복
                        if (tmapHasSdi && kakaoHasSdi) {
                            val tmapSdiType = json.optInt("nSdiType", 0)
                            val tmapSpeedLimit = json.optInt("nSdiSpeedLimit", 0)
                            val mismatch = tmapSdiType != safeKakaoSdiType ||
                                (tmapSpeedLimit > 0 && kr.safetySpeedLimit > 0 && kotlin.math.abs(tmapSpeedLimit - kr.safetySpeedLimit) > 5)
                            if (mismatch && System.currentTimeMillis() - lastSdiMismatchLogTime > 3000L) {
                                lastSdiMismatchLogTime = System.currentTimeMillis()
                                NavLogger.e(
                                    this@UdpSenderService,
                                    "[안전정보불일치] Tmap(type=$tmapSdiType limit=$tmapSpeedLimit dist=${json.optInt("nSdiDist", 0)}) " +
                                        "vs Kakao(type=${kr.safetyType} limit=${kr.safetySpeedLimit} dist=${kr.safetyDist} trusted=${kr.safetyDistTrusted})"
                                )
                            }
                        }

                        if (kakaoHasSdi && kr.safetyDistTrusted) {
                            json.put("nSdiType", safeKakaoSdiType)
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
                            NavLogger.dIfChanged(this@UdpSenderService, "sdi_priority", "[안전정보 우선순위] 검증된 카카오값 우선 채택: type=${kr.safetyType} speedLimit=${kr.safetySpeedLimit}")
                        } else if (!tmapHasSdi && kakaoHasSdi) {
                            if (kr.safetyDistTrusted) {
                                json.put("nSdiType", safeKakaoSdiType)
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

                        // v: 재억 재설명(2026-09-02) - "카카오 길안내를 기반으로 카메라를 매칭하고
                        // 싶다". 위 분기들은 "카카오가 잡은 게 있으면 카카오 우선"까지는 하는데,
                        // 카카오가 이 경로에 아무 이벤트도 없다고 할 때(kakaoHasSdi=false) 티맵이
                        // 옆도로/고가도로 아래에서 잡아온 카메라가 그대로 콤마로 나갔음 - 이게
                        // 바로 "직진 중인데 옆으로 빠지는 도로 카메라/속도를 잡는" 증상의 카메라
                        // 쪽 절반임. 카카오가 경로 위 이벤트를 실제로 관리하고 있는 상태(안내 중)
                        // 라면, 카카오가 "없다"고 하는 건 진짜로 경로상에 없다는 뜻으로 보고
                        // 티맵 단독 이벤트는 내보내지 않음. 혹시 카카오가 놓치는 카메라가 있으면
                        // 설정에서 이 항목을 꺼서 예전 동작(티맵 폴백)으로 즉시 되돌릴 수 있음. #문제시 원복
                        // v: 재억 질문(2026-09-02, "기본을 꺼짐으로 두면 문제가 되나?") - 안 됨.
                        // 이 옵션은 "카카오가 못 잡은 카메라를 아예 안 보낼 것인가"만 정하는데,
                        // 꺼두면 예전과 똑같이 티맵 카메라도 그대로 나가므로 카메라를 놓칠 위험이
                        // 없는 쪽(보수적)임. 급감속의 실제 원인이던 "티맵 카메라 제한속도가 도로
                        // 제한속도를 끌어내리는 통로"는 이 옵션과 무관하게 위쪽에서 항상 막히므로,
                        // 기본을 꺼짐으로 둬도 원하는 효과는 그대로 남음. 기본값 false로 변경. #문제시 원복
                        val kakaoOnlySdi = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                            .getBoolean("kakao_only_sdi_when_guiding", false)
                        if (kakaoOnlySdi && !kakaoHasSdi && tmapHasSdi) {
                            val suppressedType = json.optInt("nSdiType", 0)
                            val suppressedLimit = json.optInt("nSdiSpeedLimit", 0)
                            val suppressedDist = json.optInt("nSdiDist", 0)
                            json.put("nSdiType", 0)
                            json.put("nSdiSpeedLimit", 0)
                            json.put("nSdiDist", 0)
                            json.put("nSdiBlockType", 0)
                            json.put("nSdiSection", 0)
                            if (System.currentTimeMillis() - lastTmapSdiSuppressLogTime > 5000L) {
                                lastTmapSdiSuppressLogTime = System.currentTimeMillis()
                                NavLogger.d(
                                    this@UdpSenderService,
                                    "[안전정보 우선순위][티맵단독 억제] 카카오 경로엔 이벤트 없음 - 티맵이 잡은 " +
                                        "type=$suppressedType limit=$suppressedLimit dist=${suppressedDist}m 이벤트는 옆도로 오매칭으로 보고 전송 생략"
                                )
                            }
                        }
                        // v: 재억 요청(2026-08-22) - 이 로그가 UDP 전송 주기마다 매번 찍혀서
                        // 로그 파일 용량을 많이 차지하고 있었음(1800줄 이상). 값 자체는
                        // 자주 안 바뀌니 10초 간격으로 줄임. #문제시 원복
                        if (System.currentTimeMillis() - lastKakaoOverwriteLogTime > 10_000L) {
                            lastKakaoOverwriteLogTime = System.currentTimeMillis()
                            NavLogger.dIfChanged(this@UdpSenderService, "kakao_udp", "[카카오->openpilot] UDP 페이로드 카카오 데이터로 덮어씀: turnType=${kr.tbtTurnType}")
                        }
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
                            // v: 신규기능(주차위치 자동저장) - 오픈파일럿 왕복 GPS보다 폰 자체
                            // GPS가 더 안정적으로 계속 들어오므로, 이쪽도 임시버퍼 갱신에 사용. #문제시 원복
                            ParkedLocationRepository.updateLiveLocation(phoneLoc.latitude, phoneLoc.longitude)
                        }
                    } catch (e: Exception) {
                        NavLogger.e(this@UdpSenderService, "[GPS 백업] 폰 위치 조회 실패: ${e.message}")
                    }

                    // v9.9: "이동식카메라 감속 끄기" 옵션이 카카오 길안내 중엔 안 먹힌다는
                    // 제보(재억) - 원인은 이 필터가 메인 전송 경로(포트 7706, latestPayload를
                    // 그대로 내보내는 carrot 프로토콜)엔 안 걸려 있고 buildNdaRoadLimitJson()
                    // (보조 프로토콜 전용)에만 걸려 있었기 때문. json이 최종 완성되는 이 지점
                    // (Tmap이든 카카오 폴백이든 nSdiType이 여기서 다 확정된 뒤) 딱 한 곳에서
                    // 공통으로 걸어서 두 경로 모두 동일하게 적용되게 함. #문제시 원복
                    val mobileCamSlowdownDisabled = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                        .getBoolean("mobile_cam_slowdown_disabled", false)
                    if (mobileCamSlowdownDisabled && json.optInt("nSdiType", 0) == 7) {
                        json.put("nSdiType", 0)
                        json.put("nSdiSpeedLimit", 0)
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
                            // v: 재억 요청(2026-09-03) - 정체 구간에선 active가 수백 번 토글돼서
                            // 이 원본 JSON(한 줄 400바이트)만 60KB를 차지했음. 파싱된 [OP상태]
                            // 줄에 핵심 값은 이미 다 있으니, 원본은 1분에 한 번만 표본으로 남김. #문제시 원복
                            NavLogger.dThrottled(this@UdpSenderService, "op_raw", 60_000L, "[OP상태? 원본] $data")
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
                            NavLogger.dIfChanged(this@UdpSenderService, "op_state", "[OP상태] active=$active, xState=$xState, trafficState=$trafficState, ip=$ip, carrot2=$carrot2")
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

            // v: 재억 지적(2026-09-03) - 주소 목록(127.0.0.1 / 255.255.255.255 / 각
            // 인터페이스 전용 브로드캐스트)을 애써 만들어놓고 전체를 try 하나로 묶어
            // 돌리고 있어서, 목록 두 번째인 255.255.255.255가 EINVAL로 막히면 거기서
            // 반복문이 통째로 중단됐음. 정작 정상적으로 나갔을 인터페이스 전용 주소
            // (172.27.16.255 등)는 시도조차 못 하고 있었음. 주소마다 따로 감싸서
            // 하나가 막혀도 나머지는 계속 시도되게 함. #문제시 원복
            var lastError: Exception? = null
            var anySucceeded = false
            for (address in targetAddresses) {
                try {
                    udpSocket?.send(DatagramPacket(buffer, buffer.size, address, UDP_PORT))
                    anySucceeded = true
                } catch (e: Exception) {
                    lastError = e
                }
            }
            if (!anySucceeded && lastError != null) throw lastError
        } catch (e: Exception) {
            val now = System.currentTimeMillis()
            if (now - lastSendErrorLogTime > 5000) {
                lastSendErrorLogTime = now
                NavLogger.e(this, "openpilot UDP 전송 실패: ${e.message}")
            }
            // v: ENETUNREACH가 뜨면 소켓이 죽은 네트워크에 묶여서 다시는 자연 복구가
            // 안 되는 걸로 확인됨(1시간 15분 26,619번 연속 실패 사례). 너무 자주 재생성하면
            // 오히려 부담이 되니 5초에 한 번씩만 시도. #문제시 원복
            // v: 재억 제보(2026-09-03, v19.2.53 로그) - 감시 대상에 EINVAL이 빠져 있어서,
            // 소켓이 EINVAL 상태에 빠지면 되살리기를 아예 시도조차 안 하고 무한히 같은
            // 실패만 반복했음(수 분간 실패=10/10/10). EINVAL도 같이 감시함. #문제시 원복
            if (isRecoverableSendError(e) && now - lastSocketRecreateTime > 5000) {
                lastSocketRecreateTime = now
                NavLogger.d(this, "[자가치유] 전송 불가 오류 감지(${e.message}) - 전송 소켓 재생성 시도")
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
                    // v: 신규기능(주차위치 자동저장) - 연결 끊김이 감지되는 이 시점을 "시동 꺼짐"
                    // 추정 신호로 사용. 끊긴 뒤 기다리지 않고 즉시 마지막 위치를 저장(안드로이드가
                    // 백그라운드 서비스를 강제종료할 수 있는 폰 사용자 환경 고려 - 지연 저장 시
                    // 저장 전에 서비스가 죽을 위험). 일시적 끊김(터널 등) 오탐은 재연결 시
                    // ParkedLocationRepository.onConnectionRestored()에서 사후에 걸러냄. #문제시 원복
                    ParkedLocationRepository.onConnectionLost(this@UdpSenderService)
                } else if (lastPacketReceivedTime > 0L && elapsed <= 5000) {
                    ParkedLocationRepository.onConnectionRestored(this@UdpSenderService)
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
                            // v: 신규기능(주차위치 자동저장) - 연결이 살아있는 동안 최신 위치를
                            // 계속 임시버퍼에 갱신해둠. 연결이 끊기는 순간 이 마지막 값이 그대로
                            // "주차 위치"로 확정 저장됨(위 감시타이머 참고). #문제시 원복
                            ParkedLocationRepository.updateLiveLocation(lat, lon)

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
    private var lastKakaoOverwriteLogTime = 0L
    private var lastSdiMismatchLogTime = 0L
    private var lastHttpNaviErrorMsg: String? = null

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
                            // v: 신규기능(경로선) - 토글 켜져있고, 카카오 쪽에서 좌표를 뽑는 데
                            // 성공한 게 10초 이내로 신선하면 같이 실어보냄. 이미 잘 되던 rgdata
                            // 전송 자체는 이 블록과 무관하게 그대로 나감(여기서 실패해도 위
                            // put("rgdata",...)는 이미 끝난 뒤라 영향 없음). #문제시 원복
                            try {
                                val routeToggleOn = getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                                    .getBoolean("route_line_display_enabled", false)
                                val coords = KakaoRouteDataRepository.routeCoordinates
                                val fresh = (System.currentTimeMillis() - KakaoRouteDataRepository.routeCoordinatesUpdatedAt) < 10_000L
                                if (routeToggleOn && fresh && coords.isNotEmpty()) {
                                    val vrtxArray = org.json.JSONArray()
                                    for ((lon, lat) in coords) {
                                        vrtxArray.put(JSONObject().apply {
                                            put("x", lon)
                                            put("y", lat)
                                            put("valid", true)
                                        })
                                    }
                                    put("vrtx", vrtxArray)
                                }
                            } catch (e: Exception) {
                                // 경로선 전송 실패해도 rgdata 전송엔 영향 없어야 하므로 조용히 무시
                            }
                        }
                        val body = bodyJson.toString().toRequestBody("application/json".toMediaTypeOrNull())
                        val request = Request.Builder()
                            .url("http://$commaIp:7713/api/navi/tmapnda")
                            .post(body)
                            .build()
                        try {
                            httpClient.newCall(request).execute().use { resp ->
                                if (resp.isSuccessful) httpNaviSuccessCount++ else {
                                    httpNaviFailCount++
                                    lastHttpNaviErrorMsg = "응답코드=${resp.code}"
                                }
                            }
                        } catch (e: Exception) {
                            httpNaviFailCount++
                            // v: 재억 제보(2026-08-22) - 지금까지 실패 이유를 안 남기고 그냥 넘어가서,
                            // "매번 실패하는데 왜인지"를 로그만 보고는 알 방법이 없었음(결국 안드로이드
                            // 평문 http 차단 문제였는데 실제 원인 메시지가 하나도 안 남아있었음). 다음에
                            // 또 실패하면 5초 요약 옆에 마지막 실패 이유를 같이 남겨서 바로 보이게 함. #문제시 원복
                            lastHttpNaviErrorMsg = "${e.javaClass.simpleName}: ${e.message}"
                        }
                    }
                } catch (e: Exception) {
                    NavLogger.e(this@UdpSenderService, "[콤마 HTTP API] 전송 루프 예외: ${e.message}")
                }

                if (System.currentTimeMillis() - lastHttpNaviLogTime > 5000) {
                    NavLogger.dIfChanged(this@UdpSenderService, "http_api", "[콤마 HTTP API] 성공=$httpNaviSuccessCount 실패=$httpNaviFailCount ${if (httpNaviFailCount > 0) "마지막실패이유=$lastHttpNaviErrorMsg" else ""}")
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
    /**
     * 소켓이 되살리기(재생성)로 회복될 수 있는 종류의 송신 오류인지.
     * ENETUNREACH/ENETDOWN은 묶여있던 네트워크가 죽은 경우, EINVAL은 그 네트워크에
     * 해당 주소로 가는 경로가 없는 경우 - 셋 다 소켓을 다시 만들면 회복될 수 있음.
     */
    private fun isRecoverableSendError(e: Exception): Boolean {
        val msg = e.message ?: return false
        return msg.contains("ENETUNREACH") || msg.contains("ENETDOWN") || msg.contains("EINVAL")
    }

    /**
     * v: 재억 지적(2026-09-03) - "192든 172든 다 대응되게 만들어놓은 거 아니냐". 맞는
     * 지적이고 carrot 전송 경로(sendUdp)는 실제로 인터페이스마다 전용 브로드캐스트 주소를
     * 계산해서 쓰고 있었는데, NDA 경로(843/2843/3843)만 255.255.255.255 하나로 고정돼
     * 있었음. 255.255.255.255는 소켓이 특정 네트워크에 묶여 있으면 "그 네트워크에 이
     * 주소로 가는 길이 없다"며 EINVAL/ENETUNREACH로 막히는데, 인터페이스 전용 주소
     * (172.27.16.255, 192.168.43.255 등)는 그 인터페이스의 실제 라우팅을 그대로 타서
     * 정상적으로 나감. 주소 대역이 뭐든 인터페이스가 알려주는 값을 그대로 쓰므로
     * 대역에 관계없이 동작함. #문제시 원복
     */
    private fun ndaBroadcastTargets(): List<InetAddress> {
        val targets = mutableSetOf<InetAddress>()
        try {
            targets.add(InetAddress.getByName("255.255.255.255"))
        } catch (e: Exception) {
            // 무시 - 아래 인터페이스 전용 주소만으로도 동작함
        }
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                for (ifaceAddr in iface.interfaceAddresses) {
                    ifaceAddr.broadcast?.let { targets.add(it) }
                }
            }
        } catch (e: Exception) {
            NavLogger.dIfChanged(this, "nda_bcast_scan", "[NDA] 인터페이스 브로드캐스트 주소 조회 실패: ${e.message}")
        }
        return targets.toList()
    }

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

    // v: 재억 요청(2026-08-27) - "권한만 주면 그 뒤로는 알아서 계속 연결 시도해야 편하지"
    // 라는 지적. 지금까지는 MainActivity가 뜰 때(또는 설정화면 버튼을 눌렀을 때) 딱
    // 한 번만 NavdyAutoConnect.tryConnect()가 불려서, 그 순간 Navdy가 꺼져있거나 범위
    // 밖이면 다시는 재시도가 없었음. 백그라운드 서비스에서 주기적으로 계속 시도하게 해서,
    // 권한만 한 번 주면 그 뒤로는 Navdy가 켜지고 범위 안에 들어오는 대로 자동으로 붙게 함.
    // 이미 연결돼있으면 NavdyAutoConnect.tryConnect()가 바로 조용히 리턴하므로 계속 불러도 부담 없음. #문제시 원복
    private fun startNavdyReconnectLoop() {
        serviceScope.launch(Dispatchers.IO) {
            // v: 재억 요청(2026-08-28) - "몇 초 늦추는 게 아니라, 앱이 완전히 구동되고
            // 티맵 안전운전 화면이 뜰 때(또는 카카오 길안내 시작할 때) 붙게 해야 하지 않냐"는
            // 지적. 정확히 그렇게 되어 있음: 이 서비스 자체가 MapActivity의
            // startSafeDriveMode()와 나란히(startUdpSenderService()) 호출되므로, 이 루프가
            // 시작되는 시점 자체가 이미 "티맵 안전운전 화면이 뜨는 시점"임. 그래서 임의의
            // 고정 대기시간(5초) 없이 루프 시작과 동시에 바로 1차 시도. 추가로 "카카오
            // 길안내 시작" 시점에도 별도로 즉시 1번 더 시도하도록
            // KakaoGuidanceDelegate.guidanceGuideStarted()에도 연결해둠. #문제시 원복
            while (isActive && isRunning.get()) {
                try {
                    NavdyAutoConnect.tryConnect(this@UdpSenderService)
                } catch (e: Exception) {
                    NavLogger.e(this@UdpSenderService, "[Navdy] 백그라운드 재연결 루프 예외: ${e.message}")
                }
                delay(15000)
            }
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
                // 상대 주소를 이미 알면 그쪽으로만, 아직 모르면 255.255.255.255와
                // 인터페이스별 전용 브로드캐스트 주소 전부에 뿌려서 찾음.
                val targets = ndaRemoteAddr?.let { listOf(it) } ?: ndaBroadcastTargets()
                if (targets.isNotEmpty()) {
                    val json = buildNdaRoadLimitJson()
                    val bytes = json.toString().toByteArray(Charsets.UTF_8)

                    // request_gps가 포함된 패킷은 일부 carrot 수신부에서
                    // 일반 apilot 데이터 처리를 건너뛰므로 별도 패킷으로 분리
                    val gpsRequestBytes = JSONObject()
                        .put("request_gps", 1)
                        .toString()
                        .toByteArray(Charsets.UTF_8)

                    for ((idx, port) in NDA_SEND_PORTS.withIndex()) {
                        var portSucceeded = false
                        var portLastError: Exception? = null
                        for (addr in targets) {
                            try {
                                // 1. 길안내·안전운전 데이터 전송
                                ndaSendSocket?.send(DatagramPacket(bytes, bytes.size, addr, port))

                                // 2. GPS 요청은 별도 패킷으로 전송
                                ndaSendSocket?.send(
                                    DatagramPacket(gpsRequestBytes, gpsRequestBytes.size, addr, port)
                                )
                                portSucceeded = true
                            } catch (e: Exception) {
                                portLastError = e
                            }
                        }
                        if (portSucceeded) {
                            successCount[idx]++
                        } else if (portLastError != null) {
                            failCount[idx]++
                            NavLogger.dIfChanged(
                                this@UdpSenderService,
                                "nda_send_fail_$port",
                                "[NDA] 송신 실패 port=$port 대상=${targets.joinToString { it.hostAddress ?: "?" }}: ${portLastError.message}"
                            )
                            val nowRecreate = System.currentTimeMillis()
                            if (isRecoverableSendError(portLastError) &&
                                nowRecreate - lastNdaSocketRecreateTime > 5000
                            ) {
                                lastNdaSocketRecreateTime = nowRecreate
                                NavLogger.d(this@UdpSenderService, "[자가치유] NDA 송신 불가 오류 감지(${portLastError.message}) - 소켓 재생성 시도")
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
                        // v: 재억 요청(2026-09-03) - 5초마다 무조건 남겨서 실기기 로그의
                        // 가장 큰 덩어리였음(956줄 중 623줄이 직전 줄과 완전 동일). 송신
                        // 성공/실패 숫자가 실제로 바뀔 때만 남김 - 실패가 생기는 순간은
                        // 값이 바뀌는 순간이라 그대로 잡힘. #문제시 원복
                        val targetLabel = targets.joinToString { it.hostAddress ?: "?" }
                        NavLogger.dIfChanged(this@UdpSenderService, "nda_send_summary", "[NDA] 송신 요약(최근 5초): target=$targetLabel $summary")
                        for (i in NDA_SEND_PORTS.indices) {
                            successCount[i] = 0
                            failCount[i] = 0
                        }
                    }

                    // 8초 넘게 비콘이 없으면 연결 끊긴 것으로 보고 초기화
                    if (System.currentTimeMillis() - ndaLastBeaconTime > NDA_ACTIVE_TIMEOUT_MS) {
                        if (ndaRemoteAddr != null) {
                            NavLogger.d(this@UdpSenderService, "[NDA] 비콘 타임아웃, openpilot 연결 해제")
                            DiscordReporter.reportVehicleDisconnect(
                                this@UdpSenderService,
                                "비콘 타임아웃 - ${NDA_ACTIVE_TIMEOUT_MS}ms 이상 openpilot 패킷 없음"
                            )
                        }
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
                NavLogger.dIfChanged(this@UdpSenderService, "nda_sdi", "[NDA] nSdiType=$sdiType (openpilot cam_type으로 그대로 전달됨) speedLimit=$sdiSpeedLimit")
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
        // v: [초안 - 미커밋] TmapNdaCarNotifier도 같이 정리 (리스너 해제 + 알림 취소). #문제시 원복
        com.tmap.nda.hud.TmapNdaCarNotifier.stop()
        super.onDestroy()
        // isRunning을 가장 먼저 false로 내려서, 각 수신 루프들이 while(isActive && isRunning.get())
        // 조건에서 스스로 빠져나오도록 신호를 준 뒤 소켓을 닫는다.
        // (신호 → 소켓 close 순서를 지키지 않으면, 루프가 아직 receive() 중인 소켓을
        //  다른 스레드가 close()해서 EBADF가 발생할 수 있다.)
        isRunning.set(false)

        try {
            wifiNetworkCallback?.let {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

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

        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
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

    /**
     * v: 재억 질문(2026-09-02) - "카카오 길안내를 안 하고 티맵으로만 갈 때도 옆도로 튀는 게
     * 줄어드느냐" -> 지금은 안 줄어듦. 이번에 넣은 방어는 전부 카카오 경로를 심판으로 쓰기
     * 때문에, 카카오 안내가 없으면 통째로 건너뜀.
     *
     * 다만 티맵 엔진(rgData)도 같은 판정에 쓸 수 있는 재료를 갖고 있음:
     *   - linkId / nLinkIdx : 지금 매칭된 도로 링크. 바뀌면 도로가 진짜 바뀐 것(카카오 도로명 대체)
     *   - roadcate          : 도로 등급(고속/국도/일반). 등급 변화도 실제 도로 변경 신호
     *   - nearLinks         : 근처 후보 링크 개수. 2개 이상 = 고가도로/평행도로/진출로가 겹치는
     *                         구간 = 바로 오매칭이 일어나는 지점
     * 즉 "후보가 여러 개인데 링크는 그대로 -> 하향 거부"로 카카오 없이도 같은 방어가 가능함.
     *
     * 그런데 직전 로그의 rgData 덤프는 안내가 안 돌던 순간에 찍혀서 이 값들이 전부 0이었음.
     * 안전운전 모드에서 실제로 채워지는지 확인이 안 된 상태로 규칙을 걸면, 값이 0인 채로
     * "항상 오매칭 위험"으로 판정해서 제한속도가 영영 안 바뀌는 더 나쁜 상황이 됨.
     * 그래서 이번 버전은 동작을 전혀 바꾸지 않고 값만 남김. 다음 실주행 로그에서
     * (1) 값이 채워지는지 (2) 실제로 튀는 순간 nearLinks가 2 이상으로 올라가는지
     * 두 가지가 확인되면, 그때 카카오와 동일한 방어를 티맵 단독 모드에도 붙일 예정. #문제시 원복
     */
    private fun logTmapMatchingDiagnostics(reason: String) {
        try {
            val sdkManager = getInstanceMethod?.invoke(sdkManagerCompanion) ?: return
            if (getRecentRGDataMethod == null) {
                getRecentRGDataMethod = sdkManager.javaClass.getMethod("getRecentRGData")
            }
            val rgData = getRecentRGDataMethod?.invoke(sdkManager) ?: return
            fun intOf(name: String): String = try {
                val f = rgData.javaClass.getField(name)
                f.isAccessible = true
                "${f.get(rgData)}"
            } catch (e: Exception) { "없음" }

            val nearLinkCount = try {
                val f = rgData.javaClass.getField("nearLinkInfos")
                f.isAccessible = true
                (f.get(rgData) as? Array<*>)?.size ?: -1
            } catch (e: Exception) { -1 }

            NavLogger.d(
                this,
                "[도로매칭진단] $reason | linkId=${intOf("linkId")} nLinkIdx=${intOf("nLinkIdx")} " +
                    "roadcate=${intOf("roadcate")} nearLinks=${intOf("nearLinks")} nearLinkInfos크기=$nearLinkCount " +
                    "linkLength=${intOf("linkLength")} currentLinkAngle=${intOf("currentLinkAngle")} " +
                    "nPosAngle=${intOf("nPosAngle")} nPosSpeed=${intOf("nPosSpeed")} " +
                    "카카오안내중=${KakaoRouteDataRepository.isFresh()}"
            )
        } catch (e: Exception) {
            NavLogger.e(this, "[도로매칭진단] 실패: ${e.message}")
        }
    }
}
