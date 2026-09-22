package com.tmap.nda

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener

/**
 * v: 재억 요청(2026-09-22) - Board에서 "로그 요청" 버튼을 누르면 이 기기가 지금 가진 로그를
 * 통째로(회전 안 된 최신 파일 기준, 최근 최대 200KB) 파이어베이스로 올림. NavLogger는 항상
 * 켜져서 계속 기록만 하고 있으므로(따로 켜고 끄는 스위치가 없음) "로그 요청" 시점은 새로
 * 기록을 시작하는 게 아니라 이미 쌓여 있던 걸 그 시점에 퍼올리는 것. #문제시 원복
 */
object FirebaseReport {

    fun watchLogRequests(context: Context) {
        if (!DiscordReporter.isEnabled(context)) return
        val appContextSafe = context.applicationContext
        try {
            val auth = FirebaseAuth.getInstance()
            fun afterAuth() = attachListener(appContextSafe)
            if (auth.currentUser != null) afterAuth()
            else auth.signInAnonymously().addOnSuccessListener { afterAuth() }
        } catch (e: Exception) {
            // 조용히 무시
        }
    }

    private fun attachListener(appContextSafe: Context) {
        try {
            val id = DiscordReporter.installId(appContextSafe)
            val db = FirebaseDatabase.getInstance()
            val reqRef = db.getReference("logRequests/$id")
            reqRef.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val requested = snapshot.getValue(Boolean::class.java) ?: return
                    if (!requested) return
                    reqRef.setValue(false)
                    val logText = try { DiscordReporter.tailOfLogFile(NavLogger.activeLogFile(appContextSafe)) } catch (e: Exception) { "" }
                    db.getReference("logs/$id").push().setValue(
                        mapOf(
                            "log" to logText,
                            "appVersion" to DiscordReporter.appVersion(appContextSafe),
                            "ts" to ServerValue.TIMESTAMP
                        )
                    )
                }

                override fun onCancelled(error: DatabaseError) {
                    // 조용히 무시
                }
            })
        } catch (e: Exception) {
            // 조용히 무시
        }
    }
}
