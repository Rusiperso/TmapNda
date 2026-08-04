package com.tmap.nda

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.kakaomobility.knsdk.KNLanguageType
import com.kakaomobility.knsdk.KNSDK

/**
 * KNSDK 설치·초기화를 프로세스 전체에서 한 번만 수행한다.
 *
 * MapActivity와 KakaoNaviActivity가 동시에 초기화를 요청하면
 * 첫 번째 초기화가 끝날 때까지 나머지 요청을 대기시킨다.
 */
object KakaoSdkState {

    private val stateLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val pendingCallbacks =
        mutableListOf<(success: Boolean, message: String?) -> Unit>()

    @Volatile
    var initialized: Boolean = false
        private set

    @Volatile
    var initializing: Boolean = false
        private set

    @Volatile
    private var installed: Boolean = false

    @Volatile
    private var initializedAppKey: String? = null

    @Volatile
    private var initializingAppKey: String? = null

    @Volatile
    var lastAppliedTmapVolume: Int? = null

    fun ensureInitialized(
        application: Application,
        appKey: String,
        callback: (success: Boolean, message: String?) -> Unit
    ) {
        val normalizedKey = appKey.trim()

        if (normalizedKey.isEmpty()) {
            mainHandler.post {
                callback(false, "카카오 네이티브 앱 키가 비어 있습니다.")
            }
            return
        }

        synchronized(stateLock) {
            if (initialized) {
                val sameKey = initializedAppKey == normalizedKey

                mainHandler.post {
                    callback(
                        sameKey,
                        if (sameKey) {
                            null
                        } else {
                            "카카오 네이티브 앱 키가 변경되었습니다. 앱을 완전히 종료한 뒤 다시 실행하세요."
                        }
                    )
                }
                return
            }

            if (initializing) {
                if (initializingAppKey == normalizedKey) {
                    pendingCallbacks.add(callback)
                } else {
                    mainHandler.post {
                        callback(
                            false,
                            "다른 카카오 네이티브 앱 키로 SDK 초기화가 진행 중입니다."
                        )
                    }
                }
                return
            }

            initializing = true
            initializingAppKey = normalizedKey
            pendingCallbacks.add(callback)
        }

        try {
            if (!installed) {
                val dbPath = application.filesDir.absolutePath + "/knsdk"

                NavLogger.d(
                    application,
                    "KNSDK 최초 install 시도: $dbPath"
                )

                KNSDK.install(application, dbPath)
                installed = true
            }

            NavLogger.d(
                application,
                "KNSDK 단일 초기화 시작"
            )

            KNSDK.initializeWithAppKey(
                normalizedKey,
                "1.0",
                "tmapnda_user",
                "ko",
                KNLanguageType.KNLanguageType_KOREAN
            ) { error ->
                if (error == null) {
                    NavLogger.d(
                        application,
                        "KNSDK 단일 초기화 성공"
                    )

                    finishInitialization(
                        success = true,
                        appKey = normalizedKey,
                        message = null
                    )
                } else {
                    val message = "${error.code} / ${error.msg}"

                    NavLogger.e(
                        application,
                        "KNSDK 단일 초기화 실패: $message"
                    )

                    finishInitialization(
                        success = false,
                        appKey = null,
                        message = message
                    )
                }
            }
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName

            NavLogger.e(
                application,
                "KNSDK install/init 예외: $message"
            )

            finishInitialization(
                success = false,
                appKey = null,
                message = message
            )
        }
    }

    private fun finishInitialization(
        success: Boolean,
        appKey: String?,
        message: String?
    ) {
        val callbacks = synchronized(stateLock) {
            initialized = success
            initializing = false
            initializedAppKey = if (success) appKey else null
            initializingAppKey = null

            pendingCallbacks.toList().also {
                pendingCallbacks.clear()
            }
        }

        mainHandler.post {
            callbacks.forEach { callback ->
                callback(success, message)
            }
        }
    }
}