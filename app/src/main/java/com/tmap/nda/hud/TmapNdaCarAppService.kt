package com.tmap.nda.hud

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.tmap.nda.KakaoRouteDataRepository
import com.tmap.nda.KakaoRouteSnapshot
import java.util.TimeZone

private const val TAG = "TmapNdaHud"
private const val STALE_MS = 5_000L
private const val MIN_STALE_CHECK_DELAY_MS = 250L

/**
 * Android Auto에서 TmapNda를 실행한 뒤 휴대폰 길안내가 시작되면 표준 Trip 정보를
 * 차량 클러스터/HUD로 전송한다. Android Auto 메인 화면도 NavigationTemplate을 사용해
 * 호스트가 이 앱을 실제 내비게이션 세션으로 처리할 수 있게 한다.
 */
class TmapNdaCarAppService : CarAppService() {

    // GitHub APK 사이드로드·개발 테스트용. Play 배포 전에는 공식 Host allowlist가 필요하다.
    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        Log.i(TAG, "Android Auto가 TmapNda 차량용 세션을 생성함: displayType=${sessionInfo.displayType}")
        return TmapNdaCarSession()
    }
}

private class TmapNdaCarSession : Session() {
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var navigationManager: NavigationManager
    private lateinit var navigationScreen: HudNavigationScreen

    private var navigating = false
    private var hostStopped = false
    private var lastSentUpdateTime = -1L

    private val routeListener: (KakaoRouteSnapshot) -> Unit = { value ->
        mainHandler.post { applySnapshot(value) }
    }

    private val expireRunnable = Runnable {
        val value = KakaoRouteDataRepository.snapshot()
        val ageMs = (System.currentTimeMillis() - value.lastUpdateTime).coerceAtLeast(0L)
        if (!value.isActive || ageMs >= STALE_MS) {
            endNavigation("길안내 데이터가 ${STALE_MS / 1000}초 이상 갱신되지 않음")
            navigationScreen.updateStatus("Android Auto 연결됨 · 휴대폰 길안내 대기 중")
        } else {
            scheduleExpireCheck(ageMs)
        }
    }

    override fun onCreateScreen(intent: Intent): Screen {
        Log.i(TAG, "TmapNda Android Auto 화면 연결됨: action=${intent.action}")

        navigationManager = carContext.getCarService(NavigationManager::class.java)
        val surfaceRenderer = TmapNdaHudSurfaceRenderer(carContext, lifecycle)
        navigationScreen = HudNavigationScreen(carContext, surfaceRenderer)

        navigationManager.setNavigationManagerCallback(
            object : NavigationManagerCallback {
                override fun onStopNavigation() {
                    hostStopped = true
                    navigating = false
                    lastSentUpdateTime = -1L
                    mainHandler.removeCallbacks(expireRunnable)
                    navigationScreen.updateStatus("차량이 HUD 안내를 중지했습니다 · 새 길안내를 시작해 주세요")
                    Log.i(TAG, "Android Auto 호스트가 길안내 중지를 요청함")
                }
            }
        )

        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    KakaoRouteDataRepository.removeListener(routeListener)
                    mainHandler.removeCallbacksAndMessages(null)
                    endNavigation("Android Auto 세션 종료")
                    runCatching { navigationManager.clearNavigationManagerCallback() }
                    Log.i(TAG, "TmapNda Android Auto 화면 연결 종료")
                }
            }
        )

        KakaoRouteDataRepository.addListener(routeListener)
        return navigationScreen
    }

    private fun applySnapshot(value: KakaoRouteSnapshot) {
        if (!value.isActive) {
            hostStopped = false
            endNavigation("휴대폰 길안내 종료")
            navigationScreen.updateStatus("Android Auto 연결됨 · 휴대폰 길안내 대기 중")
            return
        }

        if (hostStopped) {
            navigationScreen.updateStatus("차량이 HUD 안내를 중지했습니다 · 휴대폰에서 새 길안내를 시작해 주세요")
            Log.d(TAG, "차량의 중지 요청 이후 Trip 전송 보류")
            return
        }

        val ageMs = (System.currentTimeMillis() - value.lastUpdateTime).coerceAtLeast(0L)
        if (ageMs >= STALE_MS) {
            endNavigation("오래된 길안내 데이터 수신")
            navigationScreen.updateStatus("Android Auto 연결됨 · 새 길안내 데이터 대기 중")
            return
        }

        scheduleExpireCheck(ageMs)

        if (!navigating) {
            val started = runCatching {
                navigationManager.navigationStarted()
            }.onSuccess {
                navigating = true
                Log.i(TAG, "HUD 길안내 시작")
            }.onFailure { error ->
                navigationScreen.updateStatus("HUD 길안내 시작 실패 · TmapNdaHud 로그 확인")
                Log.e(TAG, "HUD 길안내 시작 실패", error)
            }.isSuccess

            if (!started) return
        }

        if (lastSentUpdateTime == value.lastUpdateTime) return

        val maneuverType = maneuverType(value.rgCodeName, value.directionAngle)
        runCatching {
            navigationManager.updateTrip(buildTrip(value, maneuverType))
            lastSentUpdateTime = value.lastUpdateTime
            navigationScreen.updateNavigation(value, maneuverType)
            Log.i(
                TAG,
                "Trip 전송 성공: maneuver=$maneuverType, turn=${value.tbtDist}m/${value.tbtMainText}, " +
                    "road=${value.roadName}, destination=${value.destinationName}, " +
                    "remain=${value.remainDist}m/${value.remainTime}s"
            )
        }.onFailure { error ->
            navigationScreen.updateStatus("HUD 정보 전송 실패 · TmapNdaHud 로그 확인")
            Log.e(TAG, "Trip 전송 실패", error)
        }
    }

    private fun scheduleExpireCheck(ageMs: Long) {
        mainHandler.removeCallbacks(expireRunnable)
        val delayMs = (STALE_MS - ageMs).coerceAtLeast(MIN_STALE_CHECK_DELAY_MS)
        mainHandler.postDelayed(expireRunnable, delayMs)
    }

    private fun endNavigation(reason: String) {
        if (!navigating) return
        runCatching {
            navigationManager.navigationEnded()
        }.onSuccess {
            Log.i(TAG, "HUD 길안내 종료: $reason")
        }.onFailure { error ->
            Log.e(TAG, "HUD 길안내 종료 알림 실패: $reason", error)
        }
        navigating = false
        lastSentUpdateTime = -1L
    }

    private fun buildTrip(value: KakaoRouteSnapshot, maneuverType: Int): Trip {
        val now = System.currentTimeMillis()
        val remainingDistance = value.remainDist.coerceAtLeast(0)
        val remainingSeconds = value.remainTime.coerceAtLeast(0)
        val turnDistance = value.tbtDist.coerceAtLeast(0)

        // KNSDK가 다음 회전 예상시간을 직접 주지 않아 전체 여정 비율로 근사한다.
        val turnSeconds = if (remainingDistance > 0) {
            (remainingSeconds.toLong() * turnDistance / remainingDistance)
                .coerceIn(0L, remainingSeconds.toLong())
                .toInt()
        } else {
            0
        }

        val tripBuilder = Trip.Builder()
            .addStep(
                buildNavigationStep(value, maneuverType),
                createTravelEstimate(turnDistance, turnSeconds, now)
            )
            .addDestination(
                Destination.Builder()
                    .setName(value.destinationName.ifBlank { "목적지" })
                    .build(),
                createTravelEstimate(remainingDistance, remainingSeconds, now)
            )

        if (value.roadName.isNotBlank()) {
            tripBuilder.setCurrentRoad(value.roadName)
        }

        return tripBuilder.build()
    }

    private fun maneuverType(code: String, angle: Int): Int {
        if (code.startsWith("KNRGCode_RotaryDirection_") || code.startsWith("KNRGCode_RoundaboutDirection_")) {
            return Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE
        }
        if (code.startsWith("KNRGCode_Direction_")) {
            return maneuverFromAngle(angle)
        }
        return when (code) {
            "KNRGCode_Start" -> Maneuver.TYPE_DEPART
            "KNRGCode_Goal" -> Maneuver.TYPE_DESTINATION
            "KNRGCode_LeftTurn", "KNRGCode_UnprotectedLeftTurn" -> Maneuver.TYPE_TURN_NORMAL_LEFT
            "KNRGCode_RightTurn" -> Maneuver.TYPE_TURN_NORMAL_RIGHT
            "KNRGCode_UTurn" -> Maneuver.TYPE_U_TURN_LEFT
            "KNRGCode_LeftDirection", "KNRGCode_LeftStraight", "KNRGCode_ChangeLeftHighway",
            "KNRGCode_LeftTunnel", "KNRGCode_LeftTunnelSide", "KNRGCode_LeftOverPath",
            "KNRGCode_LeftOverPathSide", "KNRGCode_LeftUnderPath", "KNRGCode_LeftUnderPathSide" ->
                Maneuver.TYPE_KEEP_LEFT
            "KNRGCode_RightDirection", "KNRGCode_RightStraight", "KNRGCode_ChangeRightHighway",
            "KNRGCode_RightTunnel", "KNRGCode_RightTunnelSide", "KNRGCode_RightOverPath",
            "KNRGCode_RightOverPathSide", "KNRGCode_RightUnderPath", "KNRGCode_RightUnderPathSide" ->
                Maneuver.TYPE_KEEP_RIGHT
            "KNRGCode_LeftInHighway", "KNRGCode_LeftInCityway" -> Maneuver.TYPE_ON_RAMP_SLIGHT_LEFT
            "KNRGCode_RightInHighway", "KNRGCode_RightInCityway" -> Maneuver.TYPE_ON_RAMP_SLIGHT_RIGHT
            "KNRGCode_LeftOutHighway", "KNRGCode_LeftOutCityway" -> Maneuver.TYPE_OFF_RAMP_SLIGHT_LEFT
            "KNRGCode_RightOutHighway", "KNRGCode_RightOutCityway" -> Maneuver.TYPE_OFF_RAMP_SLIGHT_RIGHT
            "KNRGCode_InFerry", "KNRGCode_OutFerry" -> Maneuver.TYPE_FERRY_BOAT
            else -> Maneuver.TYPE_STRAIGHT
        }
    }

    private fun maneuverFromAngle(rawAngle: Int): Int {
        val angle = ((rawAngle % 360) + 360) % 360
        return when (angle) {
            in 0..20, in 340..359 -> Maneuver.TYPE_STRAIGHT
            in 21..60 -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
            in 61..120 -> Maneuver.TYPE_TURN_NORMAL_RIGHT
            in 121..179 -> Maneuver.TYPE_TURN_SHARP_RIGHT
            180 -> Maneuver.TYPE_U_TURN_LEFT
            in 181..239 -> Maneuver.TYPE_TURN_SHARP_LEFT
            in 240..299 -> Maneuver.TYPE_TURN_NORMAL_LEFT
            else -> Maneuver.TYPE_TURN_SLIGHT_LEFT
        }
    }
}

private class HudNavigationScreen(
    carContext: CarContext,
    private val surfaceRenderer: TmapNdaHudSurfaceRenderer
) : Screen(carContext) {
    private var statusText = "Android Auto 연결됨 · 휴대폰 길안내 대기 중"
    private var route: KakaoRouteSnapshot? = null
    private var maneuverType = Maneuver.TYPE_STRAIGHT

    fun updateStatus(value: String) {
        if (statusText == value && route == null) return
        statusText = value
        route = null
        surfaceRenderer.updateRoute(null, value)
        invalidate()
    }

    fun updateNavigation(value: KakaoRouteSnapshot, maneuver: Int) {
        if (route == value && maneuverType == maneuver) return
        route = value
        maneuverType = maneuver
        statusText = "순정 HUD로 길안내 정보 전송 중"
        surfaceRenderer.updateRoute(value, statusText)
        invalidate()
    }

    override fun onGetTemplate(): Template {
        val currentRoute = route
        val builder = NavigationTemplate.Builder()
            .setBackgroundColor(CarColor.SECONDARY)
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("연동 상태")
                            .setOnClickListener {
                                CarToast.makeText(carContext, statusText, CarToast.LENGTH_LONG).show()
                            }
                            .build()
                    )
                    .build()
            )

        if (currentRoute != null && currentRoute.isActive) {
            builder.setNavigationInfo(
                RoutingInfo.Builder()
                    .setCurrentStep(
                        buildNavigationStep(currentRoute, maneuverType),
                        Distance.create(currentRoute.tbtDist.coerceAtLeast(0).toDouble(), Distance.UNIT_METERS)
                    )
                    .build()
            )
            builder.setDestinationTravelEstimate(
                createTravelEstimate(
                    currentRoute.remainDist,
                    currentRoute.remainTime,
                    System.currentTimeMillis()
                )
            )
        } else {
            builder.setNavigationInfo(MessageInfo.Builder(statusText).build())
        }

        return builder.build()
    }
}

private fun buildNavigationStep(value: KakaoRouteSnapshot, maneuverType: Int): Step {
    val maneuverBuilder = Maneuver.Builder(maneuverType)
    if (maneuverType == Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE) {
        maneuverBuilder.setRoundaboutExitAngle(roundaboutAngle(value))
    }

    val cue = value.tbtMainText.ifBlank { value.roadName.ifBlank { "경로 안내" } }
    val stepBuilder = Step.Builder(cue).setManeuver(maneuverBuilder.build())
    if (value.roadName.isNotBlank()) {
        stepBuilder.setRoad(value.roadName)
    }
    return stepBuilder.build()
}

private fun createTravelEstimate(distanceMeters: Int, seconds: Int, now: Long): TravelEstimate {
    val safeSeconds = seconds.coerceAtLeast(0)
    val etaMillis = now + safeSeconds.toLong() * 1000L
    return TravelEstimate.Builder(
        Distance.create(distanceMeters.coerceAtLeast(0).toDouble(), Distance.UNIT_METERS),
        DateTimeWithZone.create(etaMillis, TimeZone.getDefault())
    )
        .setRemainingTimeSeconds(safeSeconds.toLong())
        .build()
}

private fun roundaboutAngle(value: KakaoRouteSnapshot): Int {
    val normalized = ((value.directionAngle % 360) + 360) % 360
    if (normalized in 1..359) return normalized
    val clock = value.rgCodeName.substringAfterLast('_').toIntOrNull() ?: 6
    return (clock * 30).coerceIn(1, 360)
}
