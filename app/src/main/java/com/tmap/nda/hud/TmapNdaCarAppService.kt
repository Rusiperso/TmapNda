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

        // TODO: mapKakaoTurnTypeToOpenpilot()이 실제 카카오 turnType 표를 채우기 전까지는
        // 항상 TYPE_STRAIGHT로 표시됨(방향 구분 불가). #문제시 원복
        val maneuver = Maneuver.Builder(Maneuver.TYPE_STRAIGHT).build()

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
