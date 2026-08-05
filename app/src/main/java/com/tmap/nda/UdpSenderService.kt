package com.tmap.nda

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Observer
import com.google.gson.Gson
import com.tmapmobility.tmap.tmapsdk.ui.util.TmapUISDK
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import android.os.PowerManager

class UdpSenderService : Service() {

    private val CHANNEL_ID = "TmapNdaChannel"
    private val UDP_PORT = 7706
    private var targetIp = "255.255.255.255"

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
    private var roadLimitSpeed = 0
    // v4.2: roadLimitSpeed가 한 번 값이 들어오면(30 이상) 절대 안 낮아지는 버그 수정용 -
    // 마지막으로 유효한 값을 받은 시각. #문제시 원복
    private var lastRoadLimitUpdateTime = 0L
    private var sdiType = 0
    private var sdiSpeedLimit = 0
    private var sdiDistance = 0
    private var sdiBlockType = 0
    private var sdiBlockSpeed = 0
    private var sdiBlockDist = 0

    @Volatile
    private var currentGpsStatusText = "탐색 중"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, createNotification())
        }

        try {
            udpSocket = DatagramSocket()
            udpSocket?.broadcast = true
            NavLogger.d(this, "openpilot 전송용 UDP 소켓 생성 성공 (port=$UDP_PORT)")
        } catch (e: Exception) {
            NavLogger.e(this, "openpilot 전송용 UDP 소켓 생성 실패: ${e.message}")
        }

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        }

        return START_STICKY
    }

    private var lastSdiJsonStr: String? = null
    private var lastSdiUpdateTime = 0L
    private var lastSdiPlusJsonStr: String? = null
    private var lastSdiPlusUpdateTime = 0L

    private var fullBundleDumped = false
    private var lastSendErrorLogTime = 0L

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
                    if (currentLimitSpeed >= 30) {
                        roadLimitSpeed = currentLimitSpeed
                        lastRoadLimitUpdateTime = System.currentTimeMillis()
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
                            roadLimitSpeed = sdiSpeedLimit
                            lastRoadLimitUpdateTime = System.currentTimeMillis()
                        }

                        if (sdiType == 0 && sdiSpeedLimit > 0 && sdiDist > 0) {
                            json.put("nSdiType", 1) // 강제로 1로 세팅
                        }
                    }

                    // 1.5. Reflection을 통한 도로 기본 제한속도 추출 (TMAP 코어 엔진)
                    val realRoadLimit = getRoadLimitSpeedFromEngine()
                    if (realRoadLimit >= 30) {
                        roadLimitSpeed = realRoadLimit
                        lastRoadLimitUpdateTime = System.currentTimeMillis()
                    }

                    // v4.2: roadLimitSpeed가 한 번 값이 들어오면(예: 고속도로 100) 그 뒤로
                    // 국도/제한속도 정보 없는 구간으로 빠져도 절대 안 낮아지고 계속 옛날
                    // 값을 openpilot에 보내던 버그. 8초 이상 새로 유효한 값(30 이상)이
                    // 안 들어오면 0으로 리셋 - 방지턱/카메라 감속(별개 로직, SdiDataRepository
                    // 기반)에는 영향 없음. #문제시 원복
                    if (roadLimitSpeed > 0 && System.currentTimeMillis() - lastRoadLimitUpdateTime > 8000) {
                        roadLimitSpeed = 0
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
                    // 실제 경로값이 없으면 0을 보내 콤마의 도착정보 창이 숨겨지도록 함
                    val nGoPosDist = bundle.getInt(
                        "nGoPosDist",
                        bundle.getInt("remainDistanceToGoPositionInMeter", 0)
                    )

                    val nGoPosTime = bundle.getInt(
                        "nGoPosTime",
                        bundle.getInt("remainTimeToGoPositionInSec", 0)
                    )

                    json.put(
                        "nGoPosDist",
                        if (nGoPosDist > 0 && nGoPosTime > 0) nGoPosDist else 0
                    )

                    json.put(
                        "nGoPosTime",
                        if (nGoPosDist > 0 && nGoPosTime > 0) nGoPosTime else 0
                    )

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
                        SdiDataRepository.updateCurrentSdiState(
                            limitSpeed = roadLimitSpeed,
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
                        val kakaoPrefix = when {
                            kr.safetyType in intArrayOf(1, 2, 3, 4, 7) -> "단속구간"
                            kr.safetyType == 22 -> "방지턱"
                            kr.safetyType == 33 -> "스쿨존"
                            // v3.6: 현재 도로명(kr.roadName) 대신 목적지 이름을 표시 -
                            // "지산동(현재위치) 말고 대구광역시청(목적지)이 떠야지" (사용자 지적 6번). #문제시 원복
                            kr.destinationName.isNotBlank() && kr.destinationName != "목적지" -> kr.destinationName
                            kr.roadName.isNotEmpty() -> kr.roadName
                            else -> "카카오안내"
                        }
                        json.put("szTBTMainText", "$kakaoPrefix | GPS: $currentGpsStatusText")
                        if (kr.safetySpeedLimit > 0 && kr.safetyDist > 0) {
                            json.put("nSdiType", kr.safetyType)
                            json.put("nSdiSpeedLimit", kr.safetySpeedLimit)
                            json.put("nSdiDist", kr.safetyDist)
                        }
                        NavLogger.d(this@UdpSenderService, "[카카오->openpilot] UDP 페이로드 카카오 데이터로 덮어씀: nGoPosDist=${kr.remainDist} nTBTDist=$kakaoTbtDist turnType=${kr.tbtTurnType}")
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

    private fun startReceivingLoop() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket(null)
                socket.reuseAddress = true
                socket.bind(java.net.InetSocketAddress("0.0.0.0", 7705))
                socket.soTimeout = 5000
                receiveSocket = socket
                NavLogger.d(this@UdpSenderService, "openpilot UDP 수신 소켓 bind 성공: 0.0.0.0:7705")

                val buffer = ByteArray(4096)
                var lastActive: Boolean? = null
                while (isActive && isRunning.get()) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: java.net.SocketTimeoutException) {
                        // 상태가 바뀔 때만 로그 (매 5초 반복 스팸 방지). 계속 끊긴 상태면 조용히 재시도만.
                        if (lastActive != false) {
                            NavLogger.e(this@UdpSenderService, "openpilot으로부터 5초간 UDP 수신 없음 (연결 끊김 또는 openpilot 미실행 가능성)")
                            OpenpilotStateRepository.updateState("-", "-", 0, 0, false)
                            lastActive = false
                        }
                        continue
                    } catch (e: java.io.IOException) {
                        // 소켓이 외부(onDestroy 등)에서 close()된 경우 EBADF 등이 여기로 들어옴.
                        // 더 이상 죽은 소켓을 붙잡고 반복하지 말고 조용히 루프 종료.
                        if (!isActive || !isRunning.get()) {
                            break
                        }
                        NavLogger.e(this@UdpSenderService, "openpilot UDP 수신 중 오류: ${e.message}")
                        break
                    }
                    val data = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    try {
                        val json = JSONObject(data)
                        val carrot2 = json.optString("Carrot2", "-")
                        val ip = json.optString("ip", "-")
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

                        OpenpilotStateRepository.updateState(carrot2, ip, trafficState, xState, active)
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
        }
    }

    // ===== NDA(EON:ROAD_LIMIT_SERVICE:v1) 브릿지 =====

    /** openpilot이 5초마다 뿌리는 비콘("EON:ROAD_LIMIT_SERVICE:v1")과, request_gps=1 응답으로 오는
     *  GPS 위치 JSON({"location":[lat,lon,alt,speed,bearing,acc,time,vAcc,bAcc,sAcc]})을 같은 2899 포트에서 수신. */
    private fun startNdaListenLoop() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress("0.0.0.0", NDA_LISTEN_PORT))
                    soTimeout = 8000
                }
                ndaListenSocket = socket
                NavLogger.d(this@UdpSenderService, "[NDA] 비콘/GPS 수신 소켓 bind 성공: 0.0.0.0:$NDA_LISTEN_PORT")

                val buffer = ByteArray(2048)
                while (isActive && isRunning.get()) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: java.net.SocketTimeoutException) {
                        continue
                    } catch (e: java.io.IOException) {
                        // 외부에서 close()된 경우(EBADF 등) 죽은 소켓을 계속 붙잡지 말고 루프 종료
                        if (!isActive || !isRunning.get()) {
                            break
                        }
                        NavLogger.e(this@UdpSenderService, "[NDA] 수신 오류: ${e.message}")
                        break
                    }

                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)

                    if (text.trim() == NDA_BEACON) {
                        val isNewHost = ndaRemoteAddr == null || ndaRemoteAddr != packet.address
                        ndaRemoteAddr = packet.address
                        ndaRemotePort = NDA_SEND_PORT_PRIMARY
                        ndaLastBeaconTime = System.currentTimeMillis()
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

    /**
     * 발견된 openpilot IP의 843/2843/3843 세 포트 모두에 매 사이클 동시 송신 (fork마다 리슨 포트가
     * 다를 수 있어 "가능성을 전부 열어놓는" 방식). 어느 포트가 실제로 살아있는지는 여기서 알 수 없고
     * (UDP send()는 상대가 없어도 거의 예외를 안 던짐), 5초 요약 로그로 "포트별 sendto 시도/실패 횟수"만
     * 확인 가능. 실제 도달 여부 확인은 openpilot 쪽 수신 로그가 있어야 함.
     */
    private fun startNdaSendLoop() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                ndaSendSocket = DatagramSocket()
            } catch (e: Exception) {
                NavLogger.e(this@UdpSenderService, "[NDA] 송신 소켓 생성 실패: ${e.message}")
                return@launch
            }

            // 포트별 성공/실패 카운트 (순서: NDA_SEND_PORTS와 동일)
            val successCount = IntArray(NDA_SEND_PORTS.size)
            val failCount = IntArray(NDA_SEND_PORTS.size)
            var lastNdaSendLogTime = 0L

            while (isActive && isRunning.get()) {
                val addr = ndaRemoteAddr
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
