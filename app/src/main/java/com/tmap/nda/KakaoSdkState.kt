package com.tmap.nda

import android.app.Application
import android.content.Context
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

    private const val KEY_FALLBACK_DELAY_MS = 1200L

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
    private var initializingFallbackAppKey: String? = null

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

        val restKeyFallback = application
            .getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
            .getString("kakao_rest_api_key", "")
            .orEmpty()
            .trim()
            .takeIf { it.isNotEmpty() && it != normalizedKey }

        synchronized(stateLock) {
            if (initialized) {
                val sameKey = initializedAppKey == normalizedKey

                if (sameKey) {
                    mainHandler.post {
                        callback(true, null)
                    }
                    return
                }

                // 설정에서 REST/네이티브 키를 잘못 넣었다가 정상 키로 되돌린 경우에도
                // 프로세스에 남은 이전 인증 상태 때문에 새 키를 영구 차단하지 않도록 한다.
                // 동시에 초기화하는 것은 계속 막되, 완료된 초기화 뒤의 키 변경은
                // install부터 순차적으로 다시 수행한다.
                initialized = false
                installed = false
                initializedAppKey = null
            }

            if (initializing) {
                val matchesActiveCandidate =
                    initializingAppKey == normalizedKey ||
                        initializingFallbackAppKey == normalizedKey

                if (matchesActiveCandidate) {
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
            initializingFallbackAppKey = restKeyFallback
            pendingCallbacks.add(callback)
        }

        initializeSdk(
            application = application,
            appKey = normalizedKey,
            fallbackAppKey = restKeyFallback,
            originalNativeAppKey = normalizedKey,
            isFallbackAttempt = false
        )
    }

    private fun initializeSdk(
        application: Application,
        appKey: String,
        fallbackAppKey: String?,
        originalNativeAppKey: String,
        isFallbackAttempt: Boolean
    ) {
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
                if (isFallbackAttempt) {
                    "KNSDK 단일 초기화 시작(REST 입력칸 키 대체 시도)"
                } else {
                    "KNSDK 단일 초기화 시작(네이티브 입력칸 키)"
                }
            )

            KNSDK.initializeWithAppKey(
                appKey,
                "1.0",
                "tmapnda_user",
                "ko",
                KNLanguageType.KNLanguageType_KOREAN
            ) { error ->
                if (error == null) {
                    if (isFallbackAttempt) {
                        persistCorrectedKeyOrder(
                            application = application,
                            previousNativeAppKey = originalNativeAppKey,
                            workingNativeAppKey = appKey
                        )
                    }

                    NavLogger.d(
                        application,
                        if (isFallbackAttempt) {
                            "KNSDK 대체 키 초기화 성공 - REST/네이티브 입력값 자동 정렬 완료"
                        } else {
                            "KNSDK 단일 초기화 성공"
                        }
                    )

                    finishInitialization(
                        success = true,
                        appKey = appKey,
                        message = null
                    )
                } else {
                    val message = "${error.code} / ${error.msg}"
                    val retryableAuthFailure =
                        message.contains("C103", ignoreCase = true) ||
                            message.contains("INVALID_TOKEN", ignoreCase = true)

                    NavLogger.e(
                        application,
                        "KNSDK 단일 초기화 실패: $message"
                    )

                    if (retryableAuthFailure && !isFallbackAttempt && fallbackAppKey != null) {
                        synchronized(stateLock) {
                            installed = false
                        }

                        NavLogger.d(
                            application,
                            "KNSDK C103 - REST 입력칸의 다른 키로 대체 시도 예약(${KEY_FALLBACK_DELAY_MS}ms 후)"
                        )

                        mainHandler.postDelayed({
                            val shouldRetry = synchronized(stateLock) {
                                initializing
                            }

                            if (shouldRetry) {
                                initializeSdk(
                                    application = application,
                                    appKey = fallbackAppKey,
                                    fallbackAppKey = null,
                                    originalNativeAppKey = originalNativeAppKey,
                                    isFallbackAttempt = true
                                )
                            }
                        }, KEY_FALLBACK_DELAY_MS)
                    } else {
                        finishInitialization(
                            success = false,
                            appKey = null,
                            message = message
                        )
                    }
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

    private fun persistCorrectedKeyOrder(
        application: Application,
        previousNativeAppKey: String,
        workingNativeAppKey: String
    ) {
        val prefs = application.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
        val savedRestKey = prefs.getString("kakao_rest_api_key", "").orEmpty().trim()
        val savedNativeKey = prefs.getString("kakao_native_app_key", "").orEmpty().trim()

        // 초기화 도중 사용자가 설정을 다시 바꾼 경우에는 새 값을 덮어쓰지 않는다.
        if (savedNativeKey != previousNativeAppKey || savedRestKey != workingNativeAppKey) {
            return
        }

        prefs.edit()
            .putString("kakao_rest_api_key", previousNativeAppKey)
            .putString("kakao_native_app_key", workingNativeAppKey)
            .apply()
    }

    /**
     * 카카오 로컬 REST API가 실제로 승인한 키와 KNSDK가 승인한 네이티브 키를
     * 최종 역할에 맞게 저장한다. 키 값 자체는 로그에 남기지 않는다.
     */
    fun persistValidatedKeyRoles(
        application: Application,
        workingRestApiKey: String
    ) {
        val normalizedRestKey = workingRestApiKey.trim()
        if (normalizedRestKey.isEmpty()) return

        val workingNativeAppKey = synchronized(stateLock) {
            initializedAppKey.takeIf { initialized && !it.isNullOrBlank() }
        }

        val editor = application
            .getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
            .edit()
            .putString("kakao_rest_api_key", normalizedRestKey)

        if (!workingNativeAppKey.isNullOrBlank() && workingNativeAppKey != normalizedRestKey) {
            editor.putString("kakao_native_app_key", workingNativeAppKey)
        }

        if (editor.commit()) {
            NavLogger.d(
                application,
                "카카오 REST/KNSDK 검증 결과로 키 역할 확정 저장 완료"
            )
        } else {
            NavLogger.e(
                application,
                "카카오 REST/KNSDK 검증 결과 저장 실패"
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
            if (!success) {
                // C103/INVALID_TOKEN 등 인증 실패 뒤 같은 정상 키로 다시 저장해도
                // install 단계부터 깨끗하게 재시도할 수 있게 한다.
                installed = false
            }
            initializedAppKey = if (success) appKey else null
            initializingAppKey = null
            initializingFallbackAppKey = null

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
