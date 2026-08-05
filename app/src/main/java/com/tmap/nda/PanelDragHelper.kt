package com.tmap.nda

import android.content.Context
import android.view.MotionEvent
import android.view.View

/**
 * 상단 HUD 바 등을 드래그로 옮기는 편집모드 로직. Tmap 화면(MapActivity)과 카카오
 * 화면(KakaoNaviActivity)이 완전히 동일하게 동작해야 한다는 요청(재억: "기본 UI는
 * 티맵/카카오맵 차등을 주지 말고 동일하게 적용해야돼")에 따라, 원래 MapActivity에만
 * private로 있던 로직을 여기로 옮겨서 두 화면이 같이 씀. #문제시 원복
 */
object PanelDragHelper {
    // 편집모드는 앱 전체에서 하나만 존재 - 화면(Activity)이 바뀌어도 같은 상태 유지
    var isEditMode = false

    private fun clampAndPreventOverlap(v: View, targetX: Float, targetY: Float, otherViews: List<View>): Pair<Float, Float> {
        var x = targetX
        var y = targetY

        val parent = v.parent as? View
        if (parent != null) {
            val maxX = (parent.width - v.width).toFloat().coerceAtLeast(0f)
            val maxY = (parent.height - v.height).toFloat().coerceAtLeast(0f)
            x = x.coerceIn(0f, maxX)
            y = y.coerceIn(0f, maxY)
        }

        val targetRect = android.graphics.RectF(x, y, x + v.width, y + v.height)
        for (other in otherViews) {
            if (other.visibility == View.VISIBLE) {
                val otherRect = android.graphics.RectF(other.x, other.y, other.x + other.width, other.y + other.height)
                if (android.graphics.RectF.intersects(targetRect, otherRect)) {
                    val rectX = android.graphics.RectF(x, v.y, x + v.width, v.y + v.height)
                    val rectY = android.graphics.RectF(v.x, y, v.x + v.width, y + v.height)

                    val canMoveX = !android.graphics.RectF.intersects(rectX, otherRect)
                    val canMoveY = !android.graphics.RectF.intersects(rectY, otherRect)

                    if (canMoveX && !canMoveY) {
                        y = v.y
                    } else if (!canMoveX && canMoveY) {
                        x = v.x
                    } else {
                        x = v.x
                        y = v.y
                    }
                    targetRect.set(x, y, x + v.width, y + v.height)
                }
            }
        }
        return Pair(x, y)
    }

    fun makeDraggable(context: Context, view: View, keyPrefix: String, isLandscape: Boolean, otherViews: List<View> = emptyList()) {
        var dX = 0f
        var dY = 0f

        view.setOnTouchListener { v, event ->
            if (!isEditMode) return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = v.x - event.rawX
                    dY = v.y - event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val rawX = event.rawX + dX
                    val rawY = event.rawY + dY
                    val (clampedX, clampedY) = clampAndPreventOverlap(v, rawX, rawY, otherViews)

                    v.animate()
                        .x(clampedX)
                        .y(clampedY)
                        .setDuration(0)
                        .start()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val sharedPref = context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                    val suffix = if (isLandscape) "land" else "port"
                    sharedPref.edit()
                        .putFloat("${keyPrefix}_x_${suffix}", v.x)
                        .putFloat("${keyPrefix}_y_${suffix}", v.y)
                        .apply()
                    true
                }
                else -> false
            }
        }
    }

    fun restorePosition(context: Context, view: View, keyPrefix: String, isLandscape: Boolean, otherViews: List<View> = emptyList()) {
        val sharedPref = context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
        val suffix = if (isLandscape) "land" else "port"
        val x = sharedPref.getFloat("${keyPrefix}_x_${suffix}", -1f)
        val y = sharedPref.getFloat("${keyPrefix}_y_${suffix}", -1f)

        if (x != -1f && y != -1f) {
            val (clampedX, clampedY) = clampAndPreventOverlap(view, x, y, otherViews)
            view.x = clampedX
            view.y = clampedY
        }
    }

    // v3.9: "UI 편집" 버튼 클릭 처리를 공용화 - Tmap/카카오 화면이 완전히 동일하게 동작.
    // v3.10: confirmButton(항상 화면에 떠있는 체크 버튼) 추가 - 편집모드 켜지면 더보기
    // 패널이 닫히고 ≡ 버튼도 비활성화돼서 "편집 끝내기"를 누를 방법이 아예 없어지는
    // 버그가 있었음(재억 지적). 이 체크 버튼은 항상 최상단에 떠서 언제든 편집을 끝낼
    // 수 있게 함 - toggleButton과 confirmButton 둘 다 같은 종료 로직을 공유. #문제시 원복
    // v4.6: 더보기 팝업을 항상 메뉴(≡) 버튼 근처에 붙임 - 상단바가 가로스크롤
    // 구조로 바뀌면서, 버튼의 실제 화면 위치가 "화면 오른쪽 끝"이 아닐 수 있는데
    // 팝업은 고정 마진(top|end)으로 화면 끝에 떠서 서로 멀리 떨어져 보이던 문제
    // (재억 지적: "메뉴 옆에 붙이라니까 화면 구석에 떠있다"). 버튼을 누르는 그
    // 순간의 실제 화면 좌표를 계산해서 팝업을 거기 맞춰 이동시킴. #문제시 원복
    fun positionPopupNearAnchor(root: View, anchor: View, popup: View) {
        val anchorLoc = IntArray(2)
        val rootLoc = IntArray(2)
        anchor.getLocationOnScreen(anchorLoc)
        root.getLocationOnScreen(rootLoc)

        // 팝업을 먼저 measure해서 실제 크기를 알아야 화면 밖으로 안 나가게 clamp 가능
        popup.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val popupWidth = popup.measuredWidth.takeIf { it > 0 } ?: popup.width
        val popupHeight = popup.measuredHeight.takeIf { it > 0 } ?: popup.height

        var targetX = (anchorLoc[0] - rootLoc[0] + anchor.width - popupWidth).toFloat()
        var targetY = (anchorLoc[1] - rootLoc[1] + anchor.height + 8).toFloat()

        val maxX = (root.width - popupWidth).toFloat().coerceAtLeast(0f)
        val maxY = (root.height - popupHeight).toFloat().coerceAtLeast(0f)
        targetX = targetX.coerceIn(0f, maxX)
        targetY = targetY.coerceIn(0f, maxY)

        popup.x = targetX
        popup.y = targetY
    }

    fun wireEditToggleButton(
        context: android.content.Context,
        button: android.widget.Button,
        secondaryPanel: View?,
        moreMenuButton: View?,
        confirmButton: View? = null,
        draggablePanel: android.view.ViewGroup? = null
    ) {
        fun setEditMode(enabled: Boolean) {
            isEditMode = enabled
            // 편집모드일 때 보조패널이 열려있으면 패널 전체 높이가 커져서 드래그 가능
            // 범위가 줄어드는 문제 방지 - 편집모드 켤 때도 강제로 접음
            secondaryPanel?.visibility = View.GONE
            moreMenuButton?.isEnabled = !isEditMode
            moreMenuButton?.alpha = if (isEditMode) 0.4f else 1.0f
            confirmButton?.visibility = if (isEditMode) View.VISIBLE else View.GONE
            // v4.0: 자식 버튼/입력창이 터치를 먼저 가로채서, 빈 공간(아이콘·글자가 없는
            // 곳)만 눌러야 드래그되던 문제(재억 지적 3번). 편집모드 켤 때 안의 모든 뷰를
            // 비활성화해서 어디를 눌러도 상단바 자체의 드래그가 먹히게 함. #문제시 원복
            draggablePanel?.let { setDescendantsEnabled(it, !isEditMode) }
            if (isEditMode) {
                button.text = "UI 편집 종료"
                android.widget.Toast.makeText(context, "패널 아무 곳이나 눌러서 드래그하고, 우측 상단 체크(✓)를 눌러 확정하세요", android.widget.Toast.LENGTH_LONG).show()
            } else {
                button.text = "UI 편집"
                android.widget.Toast.makeText(context, "위치가 저장됐습니다", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        button.setOnClickListener { setEditMode(!isEditMode) }
        confirmButton?.setOnClickListener { setEditMode(false) }
    }

    private fun setDescendantsEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        // enabled=false만으론 클릭 가능한 뷰가 터치를 여전히 소비해버려서(안드로이드
        // 표준 동작 - 비활성 상태여도 clickable이면 onTouchEvent가 true를 반환), 부모의
        // 드래그 리스너로 터치가 안 넘어감. clickable도 같이 꺼야 실제로 통과됨.
        if (view !is android.view.ViewGroup) {
            view.isClickable = enabled
            view.isLongClickable = enabled
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                setDescendantsEnabled(view.getChildAt(i), enabled)
            }
        }
    }

    // v3.9: 앱 설정 다이얼로그도 공용화. touchLockOverlay는 Tmap 화면에만 있는
    // 개념(카카오 화면은 자체 지도 제스처를 씀)이라 null이면 그 체크박스만 건너뜀. #문제시 원복
    fun showAppSettingsDialog(context: android.app.Activity, touchLockOverlay: View?) {
        val pref = context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
        val checkBox = android.widget.CheckBox(context).apply {
            text = "속도 10% 초과 시 경고음"
            isChecked = pref.getBoolean("over_speed_warning_enabled", false)
            setTextColor(android.graphics.Color.WHITE)
            setPadding(40, 30, 40, 30)
        }
        val disableMobileCamCheckBox = android.widget.CheckBox(context).apply {
            text = "이동식카메라 감속 끄기"
            isChecked = pref.getBoolean("mobile_cam_slowdown_disabled", false)
            setTextColor(android.graphics.Color.WHITE)
            setPadding(40, 0, 40, 30)
        }
        // v4.0: 상단바 이벤트(카메라/구간단속/방지턱) 표시 켜고 끄기 - 모바일 화면은
        // 좁아서 부담스러울 수 있어 옵션으로 제공 (재억 지적 6번). #문제시 원복
        val showTopBarEventCheckBox = android.widget.CheckBox(context).apply {
            text = "상단바에 이벤트(카메라/구간단속/방지턱) 표시"
            isChecked = pref.getBoolean("topbar_event_enabled", true)
            setTextColor(android.graphics.Color.WHITE)
            setPadding(40, 0, 40, 30)
        }
        // v4.13: 차선 안내(추천 차선 하이라이트) 오버레이 켜고 끄기 - 재억 요청 3번. #문제시 원복
        val showLaneOverlayCheckBox = android.widget.CheckBox(context).apply {
            text = "차선 안내 오버레이 표시"
            isChecked = pref.getBoolean("lane_overlay_enabled", true)
            setTextColor(android.graphics.Color.WHITE)
            setPadding(40, 0, 40, 30)
        }
        val unlockMapTouchCheckBox = if (touchLockOverlay != null) {
            android.widget.CheckBox(context).apply {
                text = "지도 터치 잠금 해제 (핀치줌/드래그 허용)"
                isChecked = pref.getBoolean("map_touch_unlocked", false)
                setTextColor(android.graphics.Color.WHITE)
                setPadding(40, 0, 40, 30)
            }
        } else null

        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(checkBox)
            addView(disableMobileCamCheckBox)
            addView(showTopBarEventCheckBox)
            addView(showLaneOverlayCheckBox)
            unlockMapTouchCheckBox?.let { addView(it) }
        }
        android.app.AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("앱 설정")
            .setView(container)
            .setPositiveButton("저장") { _, _ ->
                pref.edit()
                    .putBoolean("over_speed_warning_enabled", checkBox.isChecked)
                    .putBoolean("mobile_cam_slowdown_disabled", disableMobileCamCheckBox.isChecked)
                    .putBoolean("topbar_event_enabled", showTopBarEventCheckBox.isChecked)
                    .putBoolean("lane_overlay_enabled", showLaneOverlayCheckBox.isChecked)
                    .apply {
                        if (unlockMapTouchCheckBox != null) {
                            putBoolean("map_touch_unlocked", unlockMapTouchCheckBox.isChecked)
                        }
                    }
                    .apply()
                if (touchLockOverlay != null && unlockMapTouchCheckBox != null) {
                    if (unlockMapTouchCheckBox.isChecked) {
                        touchLockOverlay.setOnTouchListener { _, _ -> false }
                    } else {
                        touchLockOverlay.setOnTouchListener { _, _ -> true }
                    }
                }
                android.widget.Toast.makeText(context, "저장됨", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
