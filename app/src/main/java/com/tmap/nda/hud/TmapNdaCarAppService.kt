package com.tmap.nda.hud

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.tmap.nda.KakaoRouteDataRepository
import com.tmap.nda.KakaoRouteSnapshot
import java.util.TimeZone

/**
 * 순정 차량 Android Auto 클러스터/HUD로 회전·거리·ETA를 전송.
 * 화면(Screen)은 상태 안내용 최소 화면만 두고, 실제 정보는 NavigationManager.updateTrip()으로
 * 차량 자체 내비게이션 UI에 그려지게 한다.
 *
 * 주의: guidance.trip / KNDirection 같은 KNSDK 공식 API는 아직 컴파일 검증이 안 돼서
 * 여기서는 쓰지 않고, 이미 검증된 KakaoRouteDataRepository(리플렉션으로 채워지는 값)만 읽는다.
 * 그 결과 회전 화살표 종류(Maneuver)는 아직 정확하지 않음 - mapKakaoTurnTypeToOpenpilot()이
 * 지금은 항상 51(기본값)만 리턴해서 실제 좌/우회전 구분이 안 됨. 거리/시간/목적지명은 정확함.
 * #문제시 원복
 */
class TmapNdaCarAppService : CarAppService() {

    // 사이드로드·개발 테스트용. Play 배포 전에는 공식 Host allowlist 방식으로 변경 권장.
    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = TmapNdaCarSession()
}

private class TmapNdaCarSession : Session() {
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var navigationManager: NavigationManager

    private var navigating = false
    private var hostStopped = false
    private var lastSentUpdateTime = -1L

    private val routeListener: (KakaoRouteSnapshot) -> Unit = { value ->
        mainHandler.post { applySnapshot(value) }
    }

    private val expireRunnable = Runnable {
        val value = KakaoRouteDataRepository.snapshot()
        if (!value.isActive || System.currentTimeMillis() - value.lastUpdateTime >= STALE_MS) {
            endNavigation()
        }
    }

    override fun onCreateScreen(intent: Intent): Screen {
        navigationManager = carContext.getCarService(NavigationManager::class.java)

        navigationManager.setNavigationManagerCallback(
            object : NavigationManagerCallback {
                override fun onStopNavigation() {
                    hostStopped = true
                    navigating = false
                    mainHandler.removeCallbacks(expireRunnable)
                    Log.i(TAG, "Android Auto host가 길안내 중지를 요청함")
                }
            }
        )

        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    KakaoRouteDataRepository.removeListener(routeListener)
                    mainHandler.removeCallbacks(expireRunnable)
                    endNavigation()
                    runCatching { navigationManager.clearNavigationManagerCallback() }
                }
            }
        )

        KakaoRouteDataRepository.addListener(routeListener)

        return HudStatusScreen(carContext)
    }

    private fun applySnapshot(value: KakaoRouteSnapshot) {
        if (!value.isActive) {
            hostStopped = false
            endNavigation()
            return
        }

        if (hostStopped) return

        val stale = System.currentTimeMillis() - value.lastUpdateTime >= STALE_MS
        if (stale) {
            endNavigation()
            return
        }

        mainHandler.removeCallbacks(expireRunnable)
        mainHandler.postDelayed(expireRunnable, STALE_MS)

        if (!navigating) {
            navigationManager.navigationStarted()
            navigating = true
        }

        if (lastSentUpdateTime == value.lastUpdateTime) return

        runCatching {
            navigationManager.updateTrip(buildTrip(value))
            lastSentUpdateTime = value.lastUpdateTime
            Log.d(
                TAG,
                "Trip 전송: turn=${value.tbtDist}m/${value.tbtMainText}, " +
                    "remain=${value.remainDist}m/${value.remainTime}s"
            )
        }.onFailure { error ->
            Log.e(TAG, "Trip 전송 실패", error)
        }
    }

    private fun endNavigation() {
        if (!navigating) return
        runCatching { navigationManager.navigationEnded() }
        navigating = false
        lastSentUpdateTime = -1L
    }

    private fun buildTrip(value: KakaoRouteSnapshot): Trip {
        val now = System.currentTimeMillis()

        val remainingDistance = value.remainDist.coerceAtLeast(0)
        val remainingSeconds = value.remainTime.coerceAtLeast(0)
        val turnDistance = value.tbtDist.coerceAtLeast(0)

        // 남은거리 대비 다음 회전까지 거리 비율로 회전 예상시간을 근사 계산 (Kakao SDK가 직접 안 줌)
        val turnSeconds = if (remainingDistance > 0) {
            (remainingSeconds.toLong() * turnDistance / remainingDistance)
                .coerceIn(0L, remainingSeconds.toLong())
                .toInt()
        } else {
            0
        }

        // v2.2: rgCodeName/directionAngle이 이제 KakaoHudBridge(공식 API)로 실제 채워지므로,
        // 전체 방향 매핑 테이블 적용 - 더 이상 항상 TYPE_STRAIGHT가 아님. #문제시 원복
        val maneuverType = maneuverType(value.rgCodeName, value.directionAngle)
        val maneuverBuilder = Maneuver.Builder(maneuverType)
        if (maneuverType == Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE) {
            maneuverBuilder.setRoundaboutExitAngle(roundaboutAngle(value))
        }
        val maneuver = maneuverBuilder.build()

        val cue = value.tbtMainText.ifBlank { "경로 안내" }
        val stepBuilder = Step.Builder(cue).setManeuver(maneuver)
        if (value.roadName.isNotBlank()) {
            stepBuilder.setRoad(value.roadName)
        }

        val tripBuilder = Trip.Builder()
            .addStep(stepBuilder.build(), travelEstimate(turnDistance, turnSeconds, now))
            .addDestination(
                Destination.Builder()
                    .setName(value.destinationName.ifBlank { "목적지" })
                    .build(),
                travelEstimate(remainingDistance, remainingSeconds, now)
            )

        if (value.roadName.isNotBlank()) {
            tripBuilder.setCurrentRoad(value.roadName)
        }

        return tripBuilder.build()
    }

    private fun travelEstimate(distanceMeters: Int, seconds: Int, now: Long): TravelEstimate {
        val etaMillis = now + seconds.coerceAtLeast(0).toLong() * 1000L
        return TravelEstimate.Builder(
            Distance.create(distanceMeters.coerceAtLeast(0).toDouble(), Distance.UNIT_METERS),
            DateTimeWithZone.create(etaMillis, TimeZone.getDefault())
        )
            .setRemainingTimeSeconds(seconds.coerceAtLeast(0).toLong())
            .build()
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

    private fun roundaboutAngle(value: KakaoRouteSnapshot): Int {
        val normalized = ((value.directionAngle % 360) + 360) % 360
        if (normalized in 1..359) return normalized
        val clock = value.rgCodeName.substringAfterLast('_').toIntOrNull() ?: 6
        return (clock * 30).coerceIn(1, 360)
    }

    companion object {
        private const val TAG = "TmapNdaHud"
        private const val STALE_MS = 5_000L
    }
}

private class HudStatusScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template =
        MessageTemplate.Builder(
            "휴대폰 TmapNda에서 길안내를 시작하면 차량 HUD로 거리·ETA를 전송합니다."
        )
            .setTitle("TmapNda HUD")
            .build()
}
