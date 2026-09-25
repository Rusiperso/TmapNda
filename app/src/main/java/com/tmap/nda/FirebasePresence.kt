package com.tmap.nda

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.ServerValue
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener

/**
 * v: 재억 요청(2026-09-22) - 디스코드 웹훅이 서버 관리 권한 탈취로 반복해서 삭제당하는 문제 때문에,
 * "지금 켜져 있는 사람" 표시를 파이어베이스 Realtime Database로 옮김. 디스코드 방식(5분마다 신호를
 * 보내고 15분 안이면 켜진 걸로 침)과 달리, 여기서는 파이어베이스의 onDisconnect() 기능을 써서
 * 앱의 인터넷 연결이 끊기는 "그 순간" 서버가 알아서 오프라인으로 바꿔줌 - 클라이언트가 죽어도
 * (강제종료, 배터리 방전 등) 서버가 감지하므로 훨씬 정확함. 설치ID/닉네임은 기존 DiscordReporter가
 * 쓰던 것과 그대로 공유해서 두 시스템이 서로 다른 사용자로 잡히지 않게 함.
 *
 * v: 재억 요청(2026-09-22) - devices 경로를 "누구나 읽기 가능"으로 열어두면, 공개 저장소에 같이
 * 들어있는 google-services.json(파이어베이스 접속 정보)만 있으면 앱 없이도 전체 사용자 목록을
 * 그냥 읽어갈 수 있어서, 익명 로그인(파이어베이스 계정 생성 없이 자동으로 "인증된 상태"만 얻는
 * 기능)을 최소 문턱으로 걸어둠 - 파이어베이스 규칙도 "auth != null"로 같이 바꿔야 함(README/콘솔 안내 참고). #문제시 원복
 */
object FirebasePresence {
    private const val ROOT = "devices"
    // v: 재억 요청(2026-09-23) - lastSeen이 연결 맺어질 때 딱 한 번만 찍혀서, 연결이 오래 유지되면
    // "마지막 신호"가 몇 시간 전에 멈춰 보이는 문제 - 연결돼 있는 동안은 이 주기로 계속 lastSeen을
    // 갱신함(연결 끊기면 heartbeat도 같이 멈추고, onDisconnect가 마지막으로 한 번 더 찍어줌). #문제시 원복
    private const val HEARTBEAT_MS = 5 * 60 * 1000L
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private var heartbeatRunnable: Runnable? = null
    // v: 재억 지시(2026-09-24) - "오늘 몇 시에 켜서 몇 시에 껐는지" 하루 단위로 보려고, 연결될 때마다
    // sessions/{설치ID}/{날짜(KST)}에 새 줄을 만들어 start를 찍고, onDisconnect로 그 줄의 end를
    // 예약해둠. 연결이 끊기면(강제종료·배터리방전·네트워크끊김 등) 그 줄은 그대로 두고 다음 연결부터는
    // 새 줄로 시작(짧은 순단으로 잠깐 끊겼다 바로 재연결돼도 별도 줄로 취급 - 실제로 끊긴 게 맞음).
    // 날짜 키가 KST 자정 기준이라 다음날로 넘어가면 자연히 새 날짜에 쌓임. #문제시 원복
    private var currentSessionRef: com.google.firebase.database.DatabaseReference? = null
    // v: 재억 제보(보드 앱에서 "14:20 종료/14:20 시작"처럼 종료=시작인 줄이 계속 보임) - 파이어베이스
    // .info/connected는 진짜 앱 종료가 아니라 화면꺼짐/절전모드/전파 순간 끊김 같은 사소한 이유로도
    // 수시로 false->true를 반복함. 그런데 기존 코드는 끊길 때마다 currentSessionRef를 null로 지워서,
    // 재연결될 때마다 무조건 새 줄(새 start)을 만들었음 - onDisconnect가 예약해둔 "end"가 거의 같은
    // 시각에 찍히니 종료=시작으로 보였던 것. 끊긴 지 2분 이내에 다시 붙으면 새 줄을 만들지 않고
    // 방금 끝난 줄을 그대로 이어씀(end를 지우고 start는 원래 값 유지). #문제시 원복
    private var lastEndedSessionRef: com.google.firebase.database.DatabaseReference? = null
    private var lastDisconnectAtMs: Long = 0L
    private const val SESSION_RESUME_WINDOW_MS = 2 * 60 * 1000L

    fun start(context: Context) {
        if (!DiscordReporter.isEnabled(context)) return
        val appContextSafe = context.applicationContext
        try {
            val auth = FirebaseAuth.getInstance()
            fun afterAuth() = beginPresence(appContextSafe)
            if (auth.currentUser != null) {
                afterAuth()
            } else {
                auth.signInAnonymously().addOnSuccessListener { afterAuth() }
            }
        } catch (e: Exception) {
            // 조용히 무시
        }
    }

    private fun beginPresence(appContextSafe: Context) {
        try {
            val db = FirebaseDatabase.getInstance()
            val id = DiscordReporter.installId(appContextSafe)
            val deviceRef = db.getReference("$ROOT/$id")
            val connectedRef = db.getReference(".info/connected")

            connectedRef.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val connected = snapshot.getValue(Boolean::class.java) ?: false
                    heartbeatRunnable?.let { heartbeatHandler.removeCallbacks(it) }
                    if (!connected) {
                        if (currentSessionRef != null) {
                            lastEndedSessionRef = currentSessionRef
                            lastDisconnectAtMs = System.currentTimeMillis()
                        }
                        currentSessionRef = null
                        return
                    }
                    val info = mapOf(
                        "nickname" to DiscordReporter.getNickname(appContextSafe).ifBlank { "(미입력)" },
                        "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
                        "appVersion" to DiscordReporter.appVersion(appContextSafe),
                        "online" to true,
                        "lastSeen" to ServerValue.TIMESTAMP
                    )
                    // 연결이 끊기는 순간(강제종료, 배터리 방전, 전파 끊김 등) 서버가 대신 써줄 값을 미리 등록
                    deviceRef.onDisconnect().updateChildren(
                        mapOf("online" to false, "lastSeen" to ServerValue.TIMESTAMP)
                    )
                    deviceRef.updateChildren(info)
                    if (currentSessionRef == null) {
                        val resumable = lastEndedSessionRef
                        if (resumable != null && System.currentTimeMillis() - lastDisconnectAtMs < SESSION_RESUME_WINDOW_MS) {
                            // 잠깐 끊겼다 바로 붙은 것으로 보고, 새 줄 대신 방금 끝난 줄을 그대로 이어씀
                            resumable.child("end").removeValue()
                            resumable.onDisconnect().updateChildren(mapOf("end" to ServerValue.TIMESTAMP))
                            currentSessionRef = resumable
                        } else {
                            val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).apply {
                                timeZone = java.util.TimeZone.getTimeZone("Asia/Seoul")
                            }.format(java.util.Date())
                            val sessionRef = db.getReference("sessions/$id/$today").push()
                            sessionRef.child("start").setValue(ServerValue.TIMESTAMP)
                            sessionRef.onDisconnect().updateChildren(mapOf("end" to ServerValue.TIMESTAMP))
                            currentSessionRef = sessionRef
                        }
                        lastEndedSessionRef = null
                    }
                    // v: 재억 요청(2026-09-22) - "처음 신호"가 항상 "마지막 신호"와 같게 나오는 문제 수정.
                    // lastSeen은 매번 덮어쓰지만 firstSeen은 비어있을 때 딱 한 번만 채움(트랜잭션으로
                    // 이미 값이 있으면 그대로 둠). ServerValue.TIMESTAMP는 트랜잭션 안에서 제대로 안 풀려서
                    // 클라이언트 시각을 씀 - 몇 초 오차는 "처음 신호" 용도엔 문제 없음. #문제시 원복
                    deviceRef.child("firstSeen").runTransaction(object : Transaction.Handler {
                        override fun doTransaction(currentData: MutableData): Transaction.Result {
                            if (currentData.value == null) currentData.value = System.currentTimeMillis()
                            return Transaction.success(currentData)
                        }
                        override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                            // 조용히 무시
                        }
                    })

                    val runnable = object : Runnable {
                        override fun run() {
                            deviceRef.child("lastSeen").setValue(ServerValue.TIMESTAMP)
                            heartbeatHandler.postDelayed(this, HEARTBEAT_MS)
                        }
                    }
                    heartbeatRunnable = runnable
                    heartbeatHandler.postDelayed(runnable, HEARTBEAT_MS)
                }

                override fun onCancelled(error: DatabaseError) {
                    // 조용히 무시 - 이 신호 실패가 앱 동작에 영향을 주면 안 됨
                }
            })
        } catch (e: Exception) {
            // 조용히 무시
        }
    }
}
