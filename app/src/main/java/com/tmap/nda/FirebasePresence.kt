package com.tmap.nda

import android.content.Context
import android.os.Build
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
                    if (!connected) return
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
