package com.tmap.nda

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.tmap.nda.navdy.KakaoToNavdyTurn
import com.tmap.nda.navdy.NavdyTurn
import kotlin.math.cos
import kotlin.math.sin

/**
 * 재억 요청(2026-08-27) - 순정 Tmap의 "다른 앱 위에 표시"(미니 길안내 모드)와 같은 개념.
 * TmapNda의 안내 화면(MapActivity/KakaoNaviActivity)이 전부 백그라운드로 내려가도,
 * 다음 방향/거리를 작은 오버레이 창으로 계속 볼 수 있게 함. 콤마 화면과는 별개로
 * "폰 화면"에 뜨는 창이라 SYSTEM_ALERT_WINDOW 권한이 필요.
 *
 * v2(재억 요청, 2026-08-27) - "화살표+숫자 2줄" 스타일(순정 내비 미니안내창 참고)로
 * 다시 그림: 위 줄은 지금 회전(굵고 큼), 아래 줄은 그 다음 회전(작고 흐리게) 미리보기.
 * 안내 데이터는 KakaoRouteDataRepository(HUD/Navdy와 동일한 소스)를 그대로 구독해서 씀.
 */
object NavOverlayManager {

    private const val PREF_KEY_ENABLED = "background_overlay_enabled"
    private const val PREF_KEY_POS_X = "overlay_pos_x"
    private const val PREF_KEY_POS_Y = "overlay_pos_y"

    // 앱 자신의 안내 화면이 몇 개나 "started" 상태인지 셈. 0이 되는 순간 = 완전히
    // 백그라운드로 내려간 순간. Tmap<->카카오 화면 전환 중에는 새 화면의 onStart가
    // 옛 화면의 onStop보다 먼저 불려서 0을 거치지 않으므로 깜빡임 없이 전환됨. #문제시 원복
    @Volatile private var startedScreenCount = 0

    private var windowManager: WindowManager? = null
    private var overlayView: LinearLayout? = null
    private var primaryIcon: TurnHookIconView? = null
    private var primaryDistText: TextView? = null
    private var primaryRoadText: TextView? = null
    private var primaryLaneText: TextView? = null
    private var secondaryRow: View? = null
    private var secondaryIcon: TurnHookIconView? = null
    private var secondaryDistText: TextView? = null
    private var secondaryRoadText: TextView? = null
    private var guidanceListener: ((KakaoRouteSnapshot) -> Unit)? = null

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
            .getBoolean(PREF_KEY_ENABLED, false)

    fun hasPermission(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** 초기 화면 체크박스를 켤 때 호출 - 시스템 권한 화면으로 안내 */
    fun requestPermission(activity: Activity) {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivity(intent)
        } catch (e: Exception) {
            NavLogger.e(activity, "[오버레이] 권한 설정 화면 이동 실패: ${e.message}")
        }
    }

    /** MapActivity/KakaoNaviActivity의 onStart에서 호출 */
    fun activityStarted() {
        startedScreenCount++
        if (startedScreenCount == 1) {
            hide()
        }
    }

    /** MapActivity/KakaoNaviActivity의 onStop에서 호출 */
    fun activityStopped(context: Context) {
        startedScreenCount = (startedScreenCount - 1).coerceAtLeast(0)
        if (startedScreenCount == 0) {
            maybeShow(context)
        }
    }

    private fun maybeShow(context: Context) {
        if (!isEnabled(context) || !hasPermission(context)) return
        if (!KakaoRouteDataRepository.isActive) return
        show(context)
    }

    private fun show(context: Context) {
        if (overlayView != null) return
        val appContext = context.applicationContext
        try {
            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = buildView(appContext)
            val prefs = appContext.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
            val savedX = prefs.getInt(PREF_KEY_POS_X, Int.MIN_VALUE)
            val savedY = prefs.getInt(PREF_KEY_POS_Y, Int.MIN_VALUE)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            // v: 신규기능(재억 요청, 2026-08-27) - 카드를 손으로 끌어서 아무 데나 옮길 수 있게.
            // 한 번이라도 옮긴 적이 있으면 그 좌표를 기억해서 다음에도 거기서 뜸. 옮긴 적
            // 없으면 기존처럼 위쪽 가운데에 뜸. #문제시 원복
            if (savedX != Int.MIN_VALUE && savedY != Int.MIN_VALUE) {
                params.gravity = Gravity.TOP or Gravity.START
                params.x = savedX
                params.y = savedY
            } else {
                params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                params.y = (24 * appContext.resources.displayMetrics.density).toInt()
            }
            attachDragHandling(appContext, wm, view, params)
            wm.addView(view, params)
            windowManager = wm
            overlayView = view
            applySnapshot(KakaoRouteDataRepository.snapshot())
            val listener: (KakaoRouteSnapshot) -> Unit = { snapshot -> applySnapshot(snapshot) }
            guidanceListener = listener
            KakaoRouteDataRepository.addListener(listener)
            NavLogger.d(appContext, "[오버레이] 표시 시작")
        } catch (e: Exception) {
            NavLogger.e(appContext, "[오버레이] 표시 실패: ${e.message}")
            windowManager = null
            overlayView = null
        }
    }

    /** 카드를 눌러서 끄는 순간 화면 아무 데나 옮길 수 있게 함(더블탭/롱프레스 없이 바로 드래그). */
    private fun attachDragHandling(context: Context, wm: WindowManager, view: View, params: WindowManager.LayoutParams) {
        var downRawX = 0f
        var downRawY = 0f
        var downX = 0
        var downY = 0
        var moved = false
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // CENTER_HORIZONTAL 중력일 땐 params.x/y가 실제 화면 좌표가 아니라
                    // 중력이 알아서 오프셋을 계산해주는 값이라, 드래그를 시작하는 순간
                    // 실제 화면 좌표(START 기준)로 바꿔야 이후 델타 계산이 맞음. #문제시 원복
                    if (params.gravity != (Gravity.TOP or Gravity.START)) {
                        val loc = IntArray(2)
                        v.getLocationOnScreen(loc)
                        params.gravity = Gravity.TOP or Gravity.START
                        params.x = loc[0]
                        params.y = loc[1]
                    }
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downX = params.x
                    downY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (kotlin.math.abs(dx) > 4 || kotlin.math.abs(dy) > 4) moved = true
                    val dm = context.resources.displayMetrics
                    val maxX = (dm.widthPixels - v.width).coerceAtLeast(0)
                    val maxY = (dm.heightPixels - v.height).coerceAtLeast(0)
                    params.x = (downX + dx).coerceIn(0, maxX)
                    params.y = (downY + dy).coerceIn(0, maxY)
                    try { wm.updateViewLayout(view, params) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE).edit()
                            .putInt(PREF_KEY_POS_X, params.x)
                            .putInt(PREF_KEY_POS_Y, params.y)
                            .apply()
                    } else {
                        // v: 재억 제보(2026-08-27) - "오버레이 눌러도 반응이 없다" - 끌지 않고
                        // 그냥 터치만 뗀 경우(=탭)는 앱을 다시 앞으로 가져옴. #문제시 원복
                        reopenApp(context)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    /**
     * 오버레이를 그냥 탭(드래그 없이)했을 때 앱을 다시 앞으로 가져옴.
     *
     * v: 재억 제보(2026-08-27, 실기기로 확인) - 처음엔 launcher intent(MainActivity)를
     * 다시 띄우는 방식으로 만들었는데, 카카오 안내 화면(KakaoNaviActivity)을 보던 중
     * 탭해도 MainActivity가 새로 시작되면서 자동 실행 로직 때문에 티맵 기본 화면(MapActivity)
     * 으로 넘어가버렸음 - 안내 중이던 카카오 화면이 무시됨. ActivityManager.appTasks로
     * "지금 이 앱의 태스크"를 그대로 앞으로 가져오면, 그 안에 실제로 떠 있던 화면(Tmap이든
     * 카카오든)이 재생성 없이 그대로 복원됨. #문제시 원복
     */
    private fun reopenApp(context: Context) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val tasks = am?.appTasks
            if (!tasks.isNullOrEmpty()) {
                tasks[0].moveToFront()
            } else {
                // 앱 태스크가 아예 없으면(완전히 종료된 상태) 새로 시작할 수밖에 없음
                val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                }
            }
        } catch (e: Exception) {
            NavLogger.e(context, "[오버레이] 탭으로 앱 재실행 실패: ${e.message}")
        }
    }

    fun hide() {
        val wm = windowManager
        val view = overlayView
        guidanceListener?.let { KakaoRouteDataRepository.removeListener(it) }
        guidanceListener = null
        if (wm != null && view != null) {
            try { wm.removeView(view) } catch (_: Exception) {}
        }
        windowManager = null
        overlayView = null
    }

    // v: 재억 지적(2026-08-28, 사진 제보) - 1000m 넘는데도 "6105m"처럼 계속 m 단위로만
    // 표시되고 있었음. 1km 이상이면 소수점 1자리 km로, 미만이면 그대로 m로 표시하도록
    // 수정. 예: 6105 -> "6.1km", 850 -> "850m". #문제시 원복
    private fun formatDist(meters: Int): String {
        return if (meters >= 1000) {
            String.format(java.util.Locale.KOREA, "%.1fkm", meters / 1000.0)
        } else {
            "${meters}m"
        }
    }

    private fun applySnapshot(snapshot: KakaoRouteSnapshot) {
        if (!snapshot.isActive) {
            hide()
            return
        }
        primaryIcon?.kind = kindFor(snapshot.rgCodeName, snapshot.directionAngle)
        primaryIcon?.invalidate()
        primaryDistText?.text = formatDist(snapshot.tbtDist)
        primaryRoadText?.apply {
            if (snapshot.tbtMainText.isNotBlank()) {
                text = "${snapshot.tbtMainText} 방면"
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }
        // v: 신규기능(재억 요청, 2026-08-28) - 지금 회전 기준 추천 차선 번호를 배지로 표시.
        // 데이터가 15초 넘게 안 갱신됐으면(자리 지남/구간 벗어남) 안 보여줌. #문제시 원복
        val recommendedLaneNums = LaneSignalRepository.lanes
            .mapIndexedNotNull { idx, info -> if (info.recommended) (idx + 1).toString() else null }
        if (LaneSignalRepository.isFresh() && recommendedLaneNums.isNotEmpty()) {
            primaryLaneText?.text = "${recommendedLaneNums.joinToString(", ")}차로"
            primaryLaneText?.visibility = View.VISIBLE
        } else {
            primaryLaneText?.visibility = View.GONE
        }

        if (snapshot.hasNextDirection) {
            secondaryRow?.visibility = View.VISIBLE
            secondaryIcon?.kind = kindFor(snapshot.nextRgCodeName, snapshot.nextDirectionAngle)
            secondaryIcon?.invalidate()
            secondaryDistText?.text = formatDist(snapshot.nextTbtDist)
            secondaryRoadText?.apply {
                if (snapshot.nextTbtMainText.isNotBlank()) {
                    text = "${snapshot.nextTbtMainText} 방면"
                    visibility = View.VISIBLE
                } else {
                    visibility = View.GONE
                }
            }
        } else {
            secondaryRow?.visibility = View.GONE
        }
    }

    private fun kindFor(rgCodeName: String, directionAngle: Int): TurnHookIconView.Kind {
        return when (KakaoToNavdyTurn.from(rgCodeName, directionAngle)) {
            NavdyTurn.LEFT, NavdyTurn.SHARP_LEFT, NavdyTurn.KEEP_LEFT,
            NavdyTurn.EASY_LEFT, NavdyTurn.MERGE_LEFT, NavdyTurn.EXIT_LEFT -> TurnHookIconView.Kind.LEFT
            NavdyTurn.RIGHT, NavdyTurn.SHARP_RIGHT, NavdyTurn.KEEP_RIGHT,
            NavdyTurn.EASY_RIGHT, NavdyTurn.MERGE_RIGHT, NavdyTurn.EXIT_RIGHT -> TurnHookIconView.Kind.RIGHT
            NavdyTurn.UTURN_LEFT, NavdyTurn.UTURN_RIGHT -> TurnHookIconView.Kind.UTURN
            NavdyTurn.END -> TurnHookIconView.Kind.ARRIVE
            else -> TurnHookIconView.Kind.STRAIGHT
        }
    }

    private class TurnBlockViews(
        val block: LinearLayout,
        val icon: TurnHookIconView,
        val dist: TextView,
        val road: TextView,
        val lane: TextView
    )

    // v3(재억 요청, 2026-08-27) - "좌회전/우회전" 글자는 빼고 아이콘+거리만 한 줄로, 그
    // 아래 작은 글씨로 "OOO 방면"(도로명)을 붙이는 구조로 다시 그림. 미리보기(Artifact)로
    // 확인 후 확정. #문제시 원복
    private fun buildBlock(context: Context, iconSizeDp: Int, distSp: Float, roadSp: Float, dimmed: Boolean): TurnBlockViews {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        fun wrapParams() = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )

        val distColor = if (dimmed) Color.parseColor("#999999") else Color.WHITE
        val roadColor = if (dimmed) Color.parseColor("#888888") else Color.parseColor("#B8C4E0")

        val icon = TurnHookIconView(context).apply { tint = distColor }
        val dist = TextView(context).apply {
            textSize = distSp
            setTextColor(distColor)
            setTypeface(typeface, if (dimmed) android.graphics.Typeface.NORMAL else android.graphics.Typeface.BOLD)
        }
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(icon, LinearLayout.LayoutParams(dp(iconSizeDp), dp(iconSizeDp)))
            addView(dist, wrapParams().apply { marginStart = dp(9) })
        }
        val road = TextView(context).apply {
            textSize = roadSp
            setTextColor(roadColor)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = dp(270)
        }
        // v: 신규기능(재억 요청, 2026-08-28) - "방면" 텍스트 아래에 추천 차선 번호를
        // 작은 배지로 표시. 왼쪽부터 1,2,3...으로 세어 추천 차선(이 경로대로 가려면
        // 타야 하는 차선)에 해당하는 번호만 골라 "1, 2차로"처럼 보여줌. 회전 방향(좌/우/직진)과
        // 무관하게 동작 - 그 시점의 추천 차선 목록만 보고 그대로 표시. #문제시 원복
        val lane = TextView(context).apply {
            textSize = roadSp - 1.5f
            setTextColor(Color.parseColor("#8FE0A8"))
            setBackgroundColor(Color.parseColor("#1B4D2C"))
            setPadding(dp(7), dp(2), dp(7), dp(2))
            visibility = View.GONE
        }
        val block = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(topRow, wrapParams())
            addView(road, wrapParams())
            addView(lane, wrapParams().apply { topMargin = dp(5) })
        }
        return TurnBlockViews(block, icon, dist, road, lane)
    }

    private fun buildView(context: Context): LinearLayout {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        fun wrapParams() = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )

        // v: 재억 제보(2026-08-27, 실기기로 확인) - 화면에 뜬 걸 보니 너무 작아서 안 보임.
        // 처음엔 2배로 키웠다가, 미리보기(1x/1.5x/2x 비교)로 확인한 뒤 1.5배로 최종 조정. #문제시 원복
        val primary = buildBlock(context, iconSizeDp = 30, distSp = 30f, roadSp = 18f, dimmed = false)
        val secondary = buildBlock(context, iconSizeDp = 21, distSp = 21f, roadSp = 16.5f, dimmed = true)
        secondary.block.visibility = View.GONE

        val divider = View(context).apply {
            setBackgroundColor(Color.parseColor("#3DFFFFFF"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { topMargin = dp(12); bottomMargin = dp(12) }
        }

        primaryIcon = primary.icon
        primaryDistText = primary.dist
        primaryRoadText = primary.road
        primaryLaneText = primary.lane
        secondaryRow = secondary.block
        secondaryIcon = secondary.icon
        secondaryDistText = secondary.dist
        secondaryRoadText = secondary.road

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(18)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(21).toFloat()
                setColor(Color.parseColor("#E613264A"))
            }
            // v: 재억 제보(2026-08-27, 실기기 캡처로 확인) - LinearLayout.addView(view)를
            // LayoutParams 없이 부르면, VERTICAL 부모에서는 기본값이 WRAP_CONTENT가 아니라
            // MATCH_PARENT라서(LinearLayout.generateDefaultLayoutParams 참고) 이 줄들이
            // 화면 가로 전체로 늘어나 있었음(카드가 화면을 가로지르는 긴 막대로 보인 원인).
            // HTML 미리보기는 이 규칙이 안드로이드와 달라서 문제가 안 보였음. 명시적으로
            // WRAP_CONTENT로 지정. #문제시 원복
            addView(primary.block, wrapParams())
            addView(divider)
            addView(secondary.block, wrapParams())
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(card, wrapParams())
        }
    }
}

/**
 * 순정 내비 미니안내창처럼 생긴 "꺾인 화살표" 아이콘을 직접 그리는 View. 리소스로 방향별
 * 벡터 이미지를 만드는 대신, 좌/우 회전은 하나의 곡선 경로를 좌우반전(scale -1)해서
 * 재사용하고 유턴은 반원으로 그림 - 세밀한 회전각(급좌회전/완만한 우회전 등) 구분 없이
 * 좌/우/직진/유턴/도착 5가지만 표시(작은 창에서는 그 이상 구분해도 잘 안 보임). #문제시 원복
 */
class TurnHookIconView(context: Context) : View(context) {

    enum class Kind { STRAIGHT, LEFT, RIGHT, UTURN, ARRIVE }

    var kind: Kind = Kind.STRAIGHT
    var tint: Int = Color.WHITE

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val strokeW = w * 0.14f
        strokePaint.color = tint
        strokePaint.strokeWidth = strokeW
        fillPaint.color = tint

        when (kind) {
            Kind.ARRIVE -> canvas.drawCircle(w / 2f, h / 2f, w * 0.28f, fillPaint)
            Kind.STRAIGHT -> drawStraight(canvas, w, h, strokeW)
            Kind.RIGHT -> drawHook(canvas, w, h, strokeW)
            Kind.LEFT -> {
                val count = canvas.save()
                canvas.scale(-1f, 1f, w / 2f, h / 2f)
                drawHook(canvas, w, h, strokeW)
                canvas.restoreToCount(count)
            }
            Kind.UTURN -> drawUturn(canvas, w, h, strokeW)
        }
    }

    private fun drawStraight(canvas: Canvas, w: Float, h: Float, strokeW: Float) {
        val cx = w / 2f
        val bottom = h * 0.85f
        val top = h * 0.2f
        canvas.drawLine(cx, bottom, cx, top, strokePaint)
        drawArrowHead(canvas, cx, top, 0f, strokeW)
    }

    /** 아래에서 위로 올라가다가 오른쪽으로 90도 꺾여서 끝나는 "훅" 모양(↱ 느낌) */
    private fun drawHook(canvas: Canvas, w: Float, h: Float, strokeW: Float) {
        val bottom = h * 0.88f
        val cx = w * 0.30f
        val bendTop = h * 0.40f
        val radius = h * 0.28f
        val rightEndX = w * 0.84f

        val path = Path()
        path.moveTo(cx, bottom)
        path.lineTo(cx, bendTop + radius)
        val rect = RectF(cx, bendTop, cx + 2 * radius, bendTop + 2 * radius)
        path.arcTo(rect, 180f, 90f, false)
        path.lineTo(rightEndX, bendTop)
        canvas.drawPath(path, strokePaint)
        drawArrowHead(canvas, rightEndX, bendTop, 90f, strokeW)
    }

    /** 오른쪽에서 올라가 반원을 그리며 왼쪽으로 내려오는 유턴 모양 */
    private fun drawUturn(canvas: Canvas, w: Float, h: Float, strokeW: Float) {
        val bottom = h * 0.85f
        val leftX = w * 0.28f
        val rightX = w * 0.72f
        val topY = h * 0.22f
        val radius = (rightX - leftX) / 2f

        val path = Path()
        path.moveTo(rightX, bottom)
        path.lineTo(rightX, topY + radius)
        val rect = RectF(leftX, topY, rightX, topY + 2 * radius)
        path.arcTo(rect, 0f, -180f, false)
        path.lineTo(leftX, bottom * 0.72f)
        canvas.drawPath(path, strokePaint)
        drawArrowHead(canvas, leftX, bottom * 0.72f, 180f, strokeW)
    }

    /** angleDeg: 0=위쪽, 90=오른쪽, 180=아래쪽을 가리키는 삼각형 화살촉을 (tipX,tipY)에 그림 */
    private fun drawArrowHead(canvas: Canvas, tipX: Float, tipY: Float, angleDeg: Float, strokeW: Float) {
        val headLen = strokeW * 1.9f
        val rad = Math.toRadians(angleDeg.toDouble())
        val dirX = sin(rad).toFloat()
        val dirY = -cos(rad).toFloat()
        val perpX = -dirY
        val perpY = dirX
        val backX = tipX - dirX * headLen
        val backY = tipY - dirY * headLen
        val spread = headLen * 0.62f
        val path = Path().apply {
            moveTo(tipX, tipY)
            lineTo(backX + perpX * spread, backY + perpY * spread)
            lineTo(backX - perpX * spread, backY - perpY * spread)
            close()
        }
        canvas.drawPath(path, fillPaint)
    }
}
