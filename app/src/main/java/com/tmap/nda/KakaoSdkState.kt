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
                    // v11.7: 로그로 다시 확인한 원인 - remainTime/remainDist는 "이미 안내를
                    // 시작한 상태에서 운전하며 실시간으로 남은 시간/거리"를 추적하는 값이라,
                    // 안내를 시작 안 하고 계산만 한 이 흐름에서는 계속 0으로 남아있었음
                    // (로그: remainTime(raw)=0 remainDist(raw)=0). trip 함수 목록에 있던
                    // getGuessTime/getGuessDist(예상 시간/거리)로 교체 - 이름 그대로 안내
                    // 시작 전 미리 계산되는 예상치일 가능성이 높음. 이것도 internal
                    // 함수라 실제 이름 뒤에 꼬리표가 붙어있을 수 있어서 startsWith로 찾음.
                    // 혹시 이것도 0이면(예상 밖 케이스 대비), routes 안의 개별 경로 요약값도
                    // 같이 시도해서 둘 중 값이 있는 쪽을 씀. #문제시 원복
                    val guessTimeMethod = trip.javaClass.methods.firstOrNull {
                        (it.name == "guessTime" || it.name.startsWith("getGuessTime")) && it.parameterCount == 0
                    }
                    val guessDistMethod = trip.javaClass.methods.firstOrNull {
                        (it.name == "guessDist" || it.name.startsWith("getGuessDist")) && it.parameterCount == 0
                    }
                    var durationSeconds = (guessTimeMethod?.invoke(trip) as? Number)?.toInt()
                    var distanceMeters = (guessDistMethod?.invoke(trip) as? Number)?.toInt()
                    NavLogger.d(context, "[소요시간계산] guessTime(raw)=${guessTimeMethod?.invoke(trip)} guessDist(raw)=${guessDistMethod?.invoke(trip)}")

                    if (durationSeconds == null || durationSeconds == 0) {
                        val routesMethod = trip.javaClass.methods.firstOrNull {
                            it.name.startsWith("getRoutes") && it.parameterCount == 0
                        }
                        val routes = routesMethod?.invoke(trip) as? List<*>
                        val firstRoute = routes?.firstOrNull()
                        if (firstRoute != null) {
                            val summaryMethod = firstRoute.javaClass.methods.firstOrNull {
                                it.name.startsWith("getSummary") && it.parameterCount == 0
                            }
                            val summary = summaryMethod?.invoke(firstRoute)
                            if (summary != null) {
                                val durationMethod = summary.javaClass.methods.firstOrNull {
                                    (it.name.startsWith("getDuration") || it.name.startsWith("getTime")) && it.parameterCount == 0
                                }
                                val distanceMethod = summary.javaClass.methods.firstOrNull {
                                    (it.name.startsWith("getDistance") || it.name.startsWith("getDist")) && it.parameterCount == 0
                                }
                                val fallbackDuration = (durationMethod?.invoke(summary) as? Number)?.toInt()
                                val fallbackDistance = (distanceMethod?.invoke(summary) as? Number)?.toInt()
                                NavLogger.d(context, "[소요시간계산] guessTime이 0이라 routes/summary(${summary.javaClass.name}) 보조 시도: duration=$fallbackDuration distance=$fallbackDistance, summary가진함수들=${summary.javaClass.methods.joinToString(", ") { it.name }}")
                                if (fallbackDuration != null && fallbackDuration > 0) {
                                    durationSeconds = fallbackDuration
                                    distanceMeters = fallbackDistance
                                }
                            } else {
                                NavLogger.e(context, "[소요시간계산] guessTime이 0이고 summary도 못 찾음(route=${firstRoute.javaClass.name})")
                            }
                        } else {
                            // v11.8: guessTime/routes 둘 다 비어있다는 건 makeTripWithStart가
                            // 돌려주는 trip이 "출발지/도착지만 담긴 빈 그릇"이고, 실제 경로
                            // 계산은 trip.routeWithPriority(...)를 따로 불러야 시작되는 구조로
                            // 보임(재억 지적으로 재확인). 근데 이 함수가 콜백을 어떤 모양으로
                            // 받는지 문서가 없어서, 무작정 추측해서 호출하면 크래시 위험이 있음
                            // - 이번엔 그 함수의 정확한 파라미터 개수/타입만 안전하게(호출은
                            // 안 하고) 로그로 찍어서, 다음 버전에서 정확히 맞춰 부를 수 있게 함. #문제시 원복
                            val routeMethods = trip.javaClass.methods.filter { it.name.startsWith("routeWithPriority") }
                            if (routeMethods.isEmpty()) {
                                NavLogger.e(context, "[소요시간계산] guessTime이 0이고 routes도 못 찾음, routeWithPriority 함수도 없음")
                            } else {
                                routeMethods.forEach { m ->
                                    val paramTypes = m.parameterTypes.joinToString(", ") { it.name }
                                    NavLogger.e(context, "[소요시간계산] guessTime이 0이고 routes도 못 찾음. routeWithPriority 후보: ${m.name}(${paramTypes})")
                                }
                            }
                        }
                    }
                    NavLogger.d(context, "[소요시간계산] 최종: durationSeconds=$durationSeconds distanceMeters=$distanceMeters")
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


