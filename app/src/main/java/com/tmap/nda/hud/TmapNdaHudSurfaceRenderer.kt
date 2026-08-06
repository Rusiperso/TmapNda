package com.tmap.nda.hud

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.tmap.nda.KakaoRouteSnapshot

/**
 * Android Auto가 내비게이션 앱에 제공하는 메인 화면 Surface를 유지한다.
 * 실제 지도 SDK 화면을 복제하지 않고 휴대폰 길안내 연동 상태만 표시한다.
 * 회전·거리·ETA는 NavigationTemplate과 NavigationManager.updateTrip()이 담당한다.
 */
internal class TmapNdaHudSurfaceRenderer(
    private val carContext: CarContext,
    lifecycle: Lifecycle
) : DefaultLifecycleObserver {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var surface: Surface? = null
    private var visibleArea: Rect? = null
    private var route: KakaoRouteSnapshot? = null
    private var statusText: String = "휴대폰 TmapNda 길안내 대기 중"

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            synchronized(lock) {
                surface?.release()
                surface = surfaceContainer.surface
            }
            Log.i(HUD_TAG, "Android Auto 내비게이션 Surface 연결: ${surfaceContainer.width}x${surfaceContainer.height}")
            requestRender()
        }

        override fun onVisibleAreaChanged(visibleArea: Rect) {
            synchronized(lock) {
                this@TmapNdaHudSurfaceRenderer.visibleArea = Rect(visibleArea)
            }
            requestRender()
        }

        override fun onStableAreaChanged(stableArea: Rect) {
            // NavigationTemplate이 가리지 않는 영역 변경 시 다시 그려 좌표를 최신화한다.
            requestRender()
        }

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            synchronized(lock) {
                surface?.release()
                surface = null
            }
            Log.i(HUD_TAG, "Android Auto 내비게이션 Surface 연결 종료")
        }
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        runCatching {
            carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
        }.onSuccess {
            Log.i(HUD_TAG, "Android Auto SurfaceCallback 등록 성공")
        }.onFailure { error ->
            Log.e(HUD_TAG, "Android Auto SurfaceCallback 등록 실패", error)
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        mainHandler.removeCallbacksAndMessages(null)
        runCatching {
            carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
        }
        synchronized(lock) {
            surface?.release()
            surface = null
        }
    }

    fun updateRoute(value: KakaoRouteSnapshot?, status: String) {
        synchronized(lock) {
            route = value
            statusText = status
        }
        requestRender()
    }

    private fun requestRender() {
        mainHandler.removeCallbacks(renderRunnable)
        mainHandler.post(renderRunnable)
    }

    private val renderRunnable = Runnable { renderFrame() }

    private fun renderFrame() {
        synchronized(lock) {
            val target = surface ?: return
            if (!target.isValid) return

            var canvas: Canvas? = null
            try {
                canvas = target.lockCanvas(null)
                drawFrame(canvas, route, statusText)
            } catch (error: Exception) {
                Log.e(HUD_TAG, "Android Auto Surface 그리기 실패", error)
            } finally {
                val postedCanvas = canvas
                if (postedCanvas != null) {
                    runCatching { target.unlockCanvasAndPost(postedCanvas) }
                }
            }
        }
    }

    private fun drawFrame(canvas: Canvas, value: KakaoRouteSnapshot?, status: String) {
        canvas.drawColor(if (carContext.isDarkMode) Color.rgb(23, 26, 31) else Color.rgb(225, 229, 235))

        val area = visibleArea
            ?.takeUnless { it.isEmpty }
            ?: Rect(0, 0, canvas.width, canvas.height)
        val centerX = area.exactCenterX()

        textPaint.color = if (carContext.isDarkMode) Color.WHITE else Color.rgb(35, 39, 46)
        textPaint.textSize = (area.height() * 0.055f).coerceIn(24f, 52f)
        val displayText = if (value != null && value.isActive) {
            value.roadName.ifBlank { value.tbtMainText.ifBlank { "TmapNda 길안내 연동 중" } }
        } else {
            status
        }
        canvas.drawText(displayText.take(32), centerX, area.bottom - area.height() * 0.035f, textPaint)
    }

    private companion object {
        const val HUD_TAG = "TmapNdaHud"
    }
}
