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

    /**
     * 대체 키로 초기화했을 때 최초로 요청받았던 설정상의 네이티브 키.
     *
     * 설정값을 자동으로 바꾸지 않아도 같은 실행 중 MapActivity와
     * KakaoNaviActivity가 현재 검증된 SDK 상태를 재사용할 수 있게 한다.
     */
    @Volatile
    private var initializedRequestedAppKey: String? = null

    /**
     * KNSDK가 실제로 승인한 REST 입력칸의 대체 키.
     *
     * 사용자가 설정에서 REST 키를 변경하면 이전 대체 인증 상태를 재사용하지
     * 않도록 현재 SharedPreferences 값과 비교하는 용도다.
     */
    @Volatile
    private var initializedFallbackAppKey: String? = null

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
                val sameInitializedKey = initializedAppKey == normalizedKey
                val sameFallbackRequest =
                    initializedRequestedAppKey == normalizedKey &&
                        initializedFallbackAppKey != null &&
                        initializedFallbackAppKey == restKeyFallback

                if (sameInitializedKey || sameFallbackRequest) {
                    mainHandler.post {
                        callback(true, null)
                    }
                    return
                }

                // 사용자가 설정에서 키를 실제로 변경한 경우에는 이전 실행 중 인증
                // 상태를 재사용하지 않고 install부터 새 후보로 다시 확인한다.
                initialized = false
                installed = false
                initializedAppKey = null
                initializedRequestedAppKey = null
                initializedFallbackAppKey = null
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
                    NavLogger.d(
                        application,
                        if (isFallbackAttempt) {
                            "KNSDK 대체 키 초기화 성공 - 현재 실행에만 적용(설정값 유지)"
                        } else {
                            "KNSDK 단일 초기화 성공"
                        }
                    )

                    finishInitialization(
                        success = true,
                        appKey = appKey,
                        message = null,
                        requestedAppKey = originalNativeAppKey,
                        fallbackSourceKey = if (isFallbackAttempt) appKey else null
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

    /**
     * REST 재시도 성공 결과는 현재 요청에만 사용한다.
     *
     * 예전 구현은 REST/네이티브 후보를 자동으로 SharedPreferences에 다시 저장해
     * 두 입력칸이 같은 값으로 덮이는 문제가 있었다. 설정값은 사용자가
     * '안전운행 시작하기'에서 명시적으로 저장할 때만 변경한다.
     */
    fun persistValidatedKeyRoles(
        application: Application,
        workingRestApiKey: String
    ) {
        if (workingRestApiKey.isBlank()) return

        NavLogger.d(
            application,
            "카카오 REST 대체 키 검증 성공 - 현재 요청에만 적용(설정값 유지)"
        )
    }

    private fun finishInitialization(
        success: Boolean,
        appKey: String?,
        message: String?,
        requestedAppKey: String? = appKey,
        fallbackSourceKey: String? = null
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
            initializedRequestedAppKey = if (success) requestedAppKey else null
            initializedFallbackAppKey = if (success) fallbackSourceKey else null
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

    /**
     * v11.4: 집/회사/즐겨찾기 칸에 "몇 분 걸리는지" 표시하려고(재억 요청), 실제로 안내를
     * 시작하지는 않고 경로 계산만 조용히 해서 소요시간/거리만 받아옴.
     * makeTripWithStart()는 원래 길안내 시작 흐름(KakaoNaviActivity.resolveCurrentPositionThenRequestRoute)
     * 에서도 쓰는 바로 그 함수인데, 그 함수는 계산 결과(trip)를 받은 뒤에 naviView에 얹어서
     * 화면까지 띄우는 것까지 하는 거고, 여기서는 그 앞 단계(계산)까지만 하고 화면에는
     * 안 띄움 - 그래서 새 API/키 없이 같은 부품으로 계산만 재사용 가능함.
     * trip 안에서 소요시간/거리를 꺼내는 정확한 필드명이 문서로 확인 안 된 상태라, 다른
     * 불확실한 SDK 필드 다룰 때처럼(dumpNavigationApiCandidates 등과 동일한 패턴) 클래스를
     * 직접 참조하지 않고 이름표(리플렉션)로 후보 필드들을 찾아서 시도함. #문제시 원복
     */
    fun computeEta(
        context: Context,
        startLat: Double,
        startLon: Double,
        destLat: Double,
        destLon: Double,
        callback: (etaMinutes: Int?, distanceMeters: Int?) -> Unit
    ) {
        NavLogger.d(context, "[소요시간계산] 시작: start=($startLat,$startLon) dest=($destLat,$destLon)")
        try {
            val startKatec = KNSDK.convertWGS84ToKATEC(startLon, startLat)
            val destKatec = KNSDK.convertWGS84ToKATEC(destLon, destLat)
            val startPoi = com.kakaomobility.knsdk.common.objects.KNPOI("현재위치", startKatec.x.toInt(), startKatec.y.toInt(), "")
            val goalPoi = com.kakaomobility.knsdk.common.objects.KNPOI("목적지", destKatec.x.toInt(), destKatec.y.toInt(), "")
            KNSDK.makeTripWithStart(startPoi, goalPoi, null) { error, trip ->
                if (error != null || trip == null) {
                    NavLogger.e(context, "[소요시간계산] makeTripWithStart 실패: ${error?.msg ?: "trip=null"}")
                    callback(null, null)
                    return@makeTripWithStart
                }
                try {
                    // v11.6: 실제 로그로 확인한 정확한 원인 - getRoutes()는 코틀린 internal
                    // 함수라 실제 이름 뒤에 "$app_knsdk_publicKnsdk_uiRelease" 꼬리표가 붙어서
                    // (예: getRoutes$app_knsdk_publicKnsdk_uiRelease) 정확히 일치하는 이름만
                    // 찾던 기존 코드가 못 찾았음. 근데 trip 안에 훨씬 간단한 remainTime(남은
                    // 시간, 초)/remainDist(남은 거리, m) 함수가 이미 바로 있어서, 그걸로
                    // 대체함 - routes/summary까지 파고들 필요가 아예 없었음. #문제시 원복
                    val remainTimeMethod = trip.javaClass.methods.firstOrNull {
                        it.name == "remainTime" && it.parameterCount == 0
                    }
                    val remainDistMethod = trip.javaClass.methods.firstOrNull {
                        it.name == "remainDist" && it.parameterCount == 0
                    }
                    if (remainTimeMethod == null) {
                        val allMethods = trip.javaClass.methods.joinToString(", ") { it.name }
                        NavLogger.e(context, "[소요시간계산] trip(${trip.javaClass.name})에서 remainTime 함수 못 찾음. 가진 함수들: $allMethods")
                        callback(null, null)
                        return@makeTripWithStart
                    }
                    val durationSecondsRaw = remainTimeMethod.invoke(trip)
                    val distanceMetersRaw = remainDistMethod?.invoke(trip)
                    val durationSeconds = (durationSecondsRaw as? Number)?.toInt()
                    val distanceMeters = (distanceMetersRaw as? Number)?.toInt()
                    NavLogger.d(context, "[소요시간계산] 성공: remainTime(raw)=$durationSecondsRaw remainDist(raw)=$distanceMetersRaw")
                    callback(durationSeconds?.let { (it + 30) / 60 }, distanceMeters)
                } catch (e: Exception) {
                    NavLogger.e(context, "[소요시간계산] 필드 읽기 예외: ${e.message}")
                    callback(null, null)
                }
            }
        } catch (e: Exception) {
            NavLogger.e(context, "[소요시간계산] 경로계산 요청 자체 예외: ${e.message}")
            callback(null, null)
        }
    }
}


