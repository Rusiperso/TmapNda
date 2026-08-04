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

    // v3.9: "UI 편집" 버튼 클릭 처리를 공용화 - Tmap/카카오 화면이 완전히 동일하게 동작. #문제시 원복
    fun wireEditToggleButton(
        context: android.content.Context,
        button: android.widget.Button,
        secondaryPanel: View?,
        moreMenuButton: View?
    ) {
        button.setOnClickListener {
            isEditMode = !isEditMode
            // 편집모드일 때 보조패널이 열려있으면 패널 전체 높이가 커져서 드래그 가능
            // 범위가 줄어드는 문제 방지 - 편집모드 켤 때도 강제로 접음
            secondaryPanel?.visibility = View.GONE
            moreMenuButton?.isEnabled = !isEditMode
            moreMenuButton?.alpha = if (isEditMode) 0.4f else 1.0f
            if (isEditMode) {
                button.text = "UI 편집 종료"
                android.widget.Toast.makeText(context, "패널을 드래그해서 원하는 위치로 옮기세요", android.widget.Toast.LENGTH_LONG).show()
            } else {
                button.text = "UI 편집"
                android.widget.Toast.makeText(context, "위치가 저장됐습니다", android.widget.Toast.LENGTH_SHORT).show()
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
            unlockMapTouchCheckBox?.let { addView(it) }
        }
        android.app.AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("앱 설정")
            .setView(container)
            .setPositiveButton("저장") { _, _ ->
                pref.edit()
                    .putBoolean("over_speed_warning_enabled", checkBox.isChecked)
                    .putBoolean("mobile_cam_slowdown_disabled", disableMobileCamCheckBox.isChecked)
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
