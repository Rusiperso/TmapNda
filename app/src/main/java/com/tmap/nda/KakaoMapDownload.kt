package com.tmap.nda

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.kakaomobility.knsdk.KNSDK
import com.kakaomobility.knsdk.common.objects.KNError
import com.kakaomobility.knsdk.common.util.OnDownloadStateListener
import com.kakaomobility.knsdk.common.util.OnKNDownloadCommandListener
import com.kakaomobility.knsdk.common.util.ProcessType

/**
 * 카카오 SDK의 지도 데이터를 미리 폰에 받아두는 기능.
 * SDK의 localMapDownload를 그대로 부르고, 진행률을 글자로 돌려준다. 설정 창 항목과
 * "새 지도 있음" 팝업이 같은 받기 동작을 쓴다.
 */
object KakaoMapDownload {
    private val main = Handler(Looper.getMainLooper())
    private var command: OnKNDownloadCommandListener? = null
    private var running = false

    val isRunning: Boolean get() = running

    private const val MAP_PREFS = "com.kakaomobility.knsdk.map.prefs"

    private fun versionText(v: Long): String =
        v.toString().let { s -> if (s.length == 8) s.chunked(2).joinToString(".") else s }

    /** 폰에 받아둔 지도 버전. 이 기능으로 받은 적이 없으면 0. */
    private fun localVersion(context: Context): Long = try {
        val p = context.getSharedPreferences(MAP_PREFS, Context.MODE_PRIVATE)
        if (p.getFloat("dw_download_process", 0f) >= 100f) p.getLong("dw_final_version", 0L) else 0L
    } catch (e: Throwable) { 0L }

    /**
     * 카카오 서버의 최신 지도 버전. SDK가 켜질 때 받아온 설정(map_full.minor_version)을 읽는다.
     * SDK 안쪽 이름이라 SDK를 올리면 바뀔 수 있어서, 못 읽으면 0을 돌려주고 조용히 넘어간다.
     */
    private fun serverVersion(context: Context): Long = try {
        val config = KNSDK.javaClass.getMethod("getConfiguration\$app_knsdk_publicKnsdk_uiRelease").invoke(KNSDK)
        val f = config?.javaClass?.getField("f")?.getInt(config)?.toLong() ?: 0L
        val url = config?.javaClass?.getField("e")?.get(config) as? String
        NavLogger.e(context, "[지도알림] 서버 지도 버전 f=$f, 주소에 든 버전=${Regex("\\d{8}").find(url ?: "")?.value}")
        f
    } catch (e: Throwable) {
        NavLogger.e(context, "[지도알림] 서버 지도 버전 읽기 실패: ${e.javaClass.simpleName} ${e.message}")
        0L
    }

    // SDK의 getDownloadedMapState()는 저장값을 전부 문자열로 읽으려다 숫자 값에서 터지므로 직접 읽는다.
    private fun stateText(context: Context): String = try {
        val p = context.getSharedPreferences(MAP_PREFS, Context.MODE_PRIVATE)
        val version = p.getLong("dw_final_version", 0L)
        val process = p.getFloat("dw_download_process", 0f)
        when {
            version > 0L && process >= 100f -> "받아둔 지도: 있음(" + versionText(version) + ")"
            p.all.isNotEmpty() -> "받아둔 지도: 일부만 있음"
            else -> "받아둔 지도: 받은 적 없음"
        }
    } catch (e: Throwable) { "받아둔 지도: 확인 못 함" }

    // 다 받으면 새 지도는 map.realm_rollback 자리에서 기다리고, swapMapDb로 정식 자리에 올리면 이전
    // 지도가 rollback 자리로 간다. 파일 수정 시각으로 어느 쪽이 새 것인지 가린다(크기는 같을 수 있음).
    // rollback이 더 새것이면 아직 적용 전이라 먼저 올리고, 이미 올라간 뒤의 이전 지도는 용량만 차지하니 지운다.
    private fun applyAndCleanup(context: Context, done: (String) -> Unit) {
        val dir = java.io.File(context.filesDir, "knsdk/KNSDK/Map/Realm")
        val active = java.io.File(dir, "map.realm")
        val rollback = java.io.File(dir, "map.realm_rollback")
        fun deleteOld() {
            if (rollback.exists() && active.exists() && active.lastModified() >= rollback.lastModified()) {
                val mb = rollback.length() / 1048576
                NavLogger.e(context, "[지도받기] 이전 지도 삭제=${rollback.delete()} (${mb}MB)")
            }
        }
        try {
            if (rollback.exists() && rollback.lastModified() > active.lastModified()) {
                KNSDK.swapMapDb { error ->
                    if (error != null) {
                        NavLogger.e(context, "[지도받기] 적용 오류: ${error.code} / ${error.msg}")
                        done("받았지만 적용하지 못했어요")
                    } else {
                        NavLogger.e(context, "[지도받기] 적용 완료: map.realm=${active.length() / 1048576}MB")
                        deleteOld()
                        done("다 받았어요")
                    }
                }
            } else {
                deleteOld()
                done("다 받았어요")
            }
        } catch (e: Throwable) {
            NavLogger.e(context, "[지도받기] 적용 예외: ${e.message}")
            done("받았지만 적용하지 못했어요")
        }
    }

    /**
     * 지도 받기를 시작한다. onProgress는 "받는 중 37%" 같은 글자를, onDone은 끝난 결과 글자를 돌려준다
     * (둘 다 메인 스레드). 시작하지 못하면 false.
     */
    fun start(context: Context, onProgress: (String) -> Unit, onDone: (String) -> Unit): Boolean {
        if (running) return false
        if (!KakaoSdkState.initialized) return false
        fun finish(msg: String) {
            running = false; command = null
            main.post { onDone(msg) }
        }
        val listener = object : OnDownloadStateListener {
            override fun onProgress(name: String, progress: Float, type: ProcessType, size: Long) {
                main.post {
                    val phase = if (type == ProcessType.Unzip) "푸는 중" else "받는 중"
                    onProgress("$phase ${(progress * 100).toInt().coerceIn(0, 100)}%")
                }
            }
            override fun onError(error: KNError) {
                NavLogger.e(context, "[지도받기] 오류: ${error.code} / ${error.msg}")
                finish("오류가 났어요(${error.code})")
            }
            override fun onFinish(size: Long) {
                NavLogger.d(context, "[지도받기] 완료 size=$size")
                main.post { onProgress("새 지도 적용하는 중") }
                applyAndCleanup(context) { finish(it) }
            }
        }
        // 카카오 지도 압축 파일 안의 경로가 "/poly/..."처럼 "/"로 시작해서, 안드로이드 14 이상의
        // 압축 경로 검사가 전부 막는다. 이 앱 안에서만 그 검사를 꺼야 SDK가 지도 조각을 풀 수 있다.
        try {
            Class.forName("dalvik.system.ZipPathValidator").getMethod("clearCallback").invoke(null)
            NavLogger.d(context, "[지도받기] 압축 경로 검사 끔")
        } catch (e: Throwable) {
            NavLogger.e(context, "[지도받기] 압축 경로 검사 끄기 실패: ${e.javaClass.simpleName} ${e.message}")
        }
        return try {
            running = true
            onProgress("시작하는 중")
            command = KNSDK.localMapDownload(listener, false)
            command?.onStart()
            NavLogger.d(context, "[지도받기] 시작 command=${command != null}")
            if (running && command == null) finish("시작하지 못했어요")
            true
        } catch (e: Throwable) {
            NavLogger.e(context, "[지도받기] 시작 예외: ${e.message}")
            finish("시작하지 못했어요")
            false
        }
    }

    fun stop(context: Context) {
        try { command?.onStop() } catch (e: Throwable) { NavLogger.e(context, "[지도받기] 중지 예외: ${e.message}") }
        running = false; command = null
    }

    fun buildViews(context: Context): List<View> {
        val title = TextView(context).apply {
            setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
            text = "카카오 지도 미리 받기"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            setPadding(40, 10, 40, 2)
        }
        val hint = TextView(context).apply {
            setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
            text = "지도 데이터를 폰에 받아둬요. 용량이 클 수 있으니 와이파이에서 받아주세요. 길찾기는 인터넷이 필요해요."
            setTextColor(android.graphics.Color.parseColor("#999999"))
            textSize = 12f
            setPadding(40, 0, 40, 4)
        }
        val status = TextView(context).apply {
            setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
            text = stateText(context)
            setTextColor(android.graphics.Color.WHITE)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val button = Button(context).apply { text = "받기" }

        fun done(msg: String) {
            button.text = "받기"
            status.text = "$msg · ${stateText(context)}"
        }

        button.setOnClickListener {
            if (running) {
                stop(context)
                done("중지했어요")
                return@setOnClickListener
            }
            if (!KakaoSdkState.initialized) {
                Toast.makeText(context, "카카오 길안내를 한 번 켠 뒤에 눌러주세요", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            button.text = "중지"
            start(context, { status.text = it }) { done(it) }
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(40, 0, 40, 24)
            addView(status)
            addView(button)
        }
        return listOf(title, hint, row)
    }

    private fun onWifi(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val unmetered = !cm.isActiveNetworkMetered
            unmetered
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 카카오 SDK가 켜진 직후 한 번 부른다. 전에 지도를 받아둔 적이 있고 서버에 더 새 지도가 있으면
     * 같은 버전에 대해 한 번만 팝업으로 알려준다. 받은 적이 없으면 알리지 않는다.
     */
    fun maybeNotifyNewMap(activity: Activity) {
        try {
            if (activity.isFinishing || activity.isDestroyed || running) return
            val local = localVersion(activity)
            if (local <= 0L) return
            val server = serverVersion(activity)
            if (server <= local) return
            val prefs = activity.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
            if (prefs.getLong("kakao_map_update_notified", 0L) >= server) return
            prefs.edit().putLong("kakao_map_update_notified", server).apply()
            showNewMapDialog(activity, local, server)
        } catch (e: Throwable) {
            NavLogger.e(activity, "[지도알림] 예외: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun showNewMapDialog(activity: Activity, local: Long, server: Long) {
        val dialog = android.app.AlertDialog.Builder(activity, R.style.RoundedDialogTheme)
            .setTitle("새 카카오 지도가 있어요")
            .setMessage("${versionText(local)} → ${versionText(server)}\n용량이 크니(약 800MB) 와이파이에서 받는 걸 권해요.")
            .setPositiveButton("받기", null)
            .setNegativeButton("나중에", null)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        val positive = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
        val negative = dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
        negative.setOnClickListener {
            if (running) stop(activity)
            dialog.dismiss()
        }
        positive.setOnClickListener {
            if (running) return@setOnClickListener
            if (!onWifi(activity)) {
                Toast.makeText(activity, "와이파이에 연결한 뒤에 받아주세요", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            positive.isEnabled = false
            negative.text = "중지"
            start(activity, { dialog.setMessage(it) }) { msg ->
                dialog.setMessage(msg)
                positive.isEnabled = true
                positive.text = "다시 받기"
                negative.text = "닫기"
            }
        }
    }
}
