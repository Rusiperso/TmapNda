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
    private val NDA_ACTIVE_TIMEOUT_MS = 8000L

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var udpSocket: DatagramSocket? = null

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

        isRunning.set(true)

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

        startObservingEDC()
        startSendingLoop()
        startReceivingLoop()
        startNdaListenLoop()
        startNdaSendLoop()

        return START_STICKY
    }

    private var lastSdiJsonStr: String? = null
    private var lastSdiUpdateTime = 0L
    private var lastSdiPlusJsonStr: String? = null
    private var lastSdiPlusUpdateTime = 0L

    private var lastFullBundleDumpTime = 0L
    private var lastSendErrorLogTime = 0L

    private val edcObserver = Observer<Bundle> { bundle ->
        if (bundle != null && isRunning.get()) {
            // Tmap이 실제로 넘겨주는 EDCData bundle 전체 key/value를 10초 간격으로 로그에 남김
            // (차선/신호등 등 우리가 안 쓰고 있는 필드가 있는지 확인하기 위함)
            val now = System.currentTimeMillis()
            if (now - lastFullBundleDumpTime > 10000) {
                lastFullBundleDumpTime = now
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
                        }
                        
                        if (sdiType == 0 && sdiSpeedLimit > 0 && sdiDist > 0) {
                            json.put("nSdiType", 1) // 강제로 1로 세팅
                        }
                    }
                    
                    // 1.5. Reflection을 통한 도로 기본 제한속도 추출 (TMAP 코어 엔진)
                    val realRoadLimit = getRoadLimitSpeedFromEngine()
                    if (realRoadLimit >= 30) {
                        roadLimitSpeed = realRoadLimit
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
                    
                    // 목적지 남은 거리 및 소요 시간 처리 (안심주행 모드 대응을 위해 값이 없거나 0이면 더미 값 주입)
                    val nGoPosDist = bundle.getInt("nGoPosDist", bundle.getInt("remainDistanceToGoPositionInMeter", 0))
                    val nGoPosTime = bundle.getInt("nGoPosTime", bundle.getInt("remainTimeToGoPositionInSec", 0))
                    if (nGoPosDist > 0 && nGoPosTime > 0) {
                        json.put("nGoPosDist", nGoPosDist)
                        json.put("nGoPosTime", nGoPosTime)
                    } else {
                        // 오픈파일럿 HUD TBT 패널을 항상 띄우기 위해 최소 dummy 값 주입
                        json.put("nGoPosDist", 1)
                        json.put("nGoPosTime", 1)
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
                    json.put("szTBTMainText", "$prefix | GPS: $currentGpsStatusText")

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
                receiveSocket = DatagramSocket(null)
                receiveSocket?.reuseAddress = true
                receiveSocket?.bind(java.net.InetSocketAddress("0.0.0.0", 7705))
                receiveSocket?.soTimeout = 5000
                NavLogger.d(this@UdpSenderService, "openpilot UDP 수신 소켓 bind 성공: 0.0.0.0:7705")

                val buffer = ByteArray(4096)
                var lastActive: Boolean? = null
                while (isActive && isRunning.get()) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        receiveSocket?.receive(packet)
                    } catch (e: java.net.SocketTimeoutException) {
                        NavLogger.e(this@UdpSenderService, "openpilot으로부터 5초간 UDP 수신 없음 (연결 끊김 또는 openpilot 미실행 가능성)")
                        if (lastActive != false) {
                            OpenpilotStateRepository.updateState("-", "-", 0, 0, false)
                            lastActive = false
                        }
                        continue
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
                ndaListenSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress("0.0.0.0", NDA_LISTEN_PORT))
                    soTimeout = 8000
                }
                NavLogger.d(this@UdpSenderService, "[NDA] 비콘/GPS 수신 소켓 bind 성공: 0.0.0.0:$NDA_LISTEN_PORT")

                val buffer = ByteArray(2048)
                while (isActive && isRunning.get()) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        ndaListenSocket?.receive(packet)
                    } catch (e: java.net.SocketTimeoutException) {
                        continue
                    } catch (e: Exception) {
                        NavLogger.e(this@UdpSenderService, "[NDA] 수신 오류: ${e.message}")
                        continue
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

    /** 발견된 openpilot IP의 843(안되면 2843)으로 road_limit/active/request_gps JSON을 2Hz로 송신. */
    private fun startNdaSendLoop() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                ndaSendSocket = DatagramSocket()
            } catch (e: Exception) {
                NavLogger.e(this@UdpSenderService, "[NDA] 송신 소켓 생성 실패: ${e.message}")
                return@launch
            }

            while (isActive && isRunning.get()) {
                val addr = ndaRemoteAddr
                if (addr != null) {
                    val json = buildNdaRoadLimitJson()
                    val bytes = json.toString().toByteArray(Charsets.UTF_8)
                    try {
                        ndaSendSocket?.send(DatagramPacket(bytes, bytes.size, addr, ndaRemotePort))
                    } catch (e: Exception) {
                        // 기본 포트 실패 시 fallback 포트로 한번 시도
                        try {
                            ndaSendSocket?.send(DatagramPacket(bytes, bytes.size, addr, NDA_SEND_PORT_FALLBACK))
                            ndaRemotePort = NDA_SEND_PORT_FALLBACK
                        } catch (e2: Exception) {
                            NavLogger.e(this@UdpSenderService, "[NDA] 송신 실패(843/2843 모두): ${e2.message}")
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
        out.put("request_gps", 1) // 오파 GPS를 역으로 받고 싶으면 계속 요청 유지

        try {
            val src = JSONObject(latestPayload)
            val roadLimit = JSONObject()

            val sdiType = src.optInt("nSdiType", 0)
            val sdiSpeedLimit = src.optInt("nSdiSpeedLimit", 0)
            val sdiDist = src.optInt("nSdiDist", 0)

            roadLimit.put("road_limit_speed", src.optInt("nRoadLimitSpeed", 0))
            roadLimit.put("is_highway", false)
            roadLimit.put("cam_type", sdiType)
            roadLimit.put("cam_limit_speed", sdiSpeedLimit)
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
        } catch (e: Exception) {
            // latestPayload가 아직 비어있으면(= "{}") road_limit 없이 active/request_gps만 전송
        }

        return out
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
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
        }
        return -1
    }
}
