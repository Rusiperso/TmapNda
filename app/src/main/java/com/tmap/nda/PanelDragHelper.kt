package com.tmap.nda

import android.content.Context
import android.view.MotionEvent
import android.view.View

/**
 * 상단 HUD 바 등을 드래그로 옮기는 편집모드 로직. Tmap 화면(MapActivity)과 카카오
 * 화면(KakaoNaviActivity)이 완전히 동일하게 동작해야 한다는 요청(사용자: "기본 UI는
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
    // 버그가 있었음(사용자 지적). 이 체크 버튼은 항상 최상단에 떠서 언제든 편집을 끝낼
    // 수 있게 함 - toggleButton과 confirmButton 둘 다 같은 종료 로직을 공유. #문제시 원복
    // v4.6: 더보기 팝업을 항상 메뉴(≡) 버튼 근처에 붙임 - 상단바가 가로스크롤
    // 구조로 바뀌면서, 버튼의 실제 화면 위치가 "화면 오른쪽 끝"이 아닐 수 있는데
    // 팝업은 고정 마진(top|end)으로 화면 끝에 떠서 서로 멀리 떨어져 보이던 문제
    // (사용자 지적: "메뉴 옆에 붙이라니까 화면 구석에 떠있다"). 버튼을 누르는 그
    // 순간의 실제 화면 좌표를 계산해서 팝업을 거기 맞춰 이동시킴. #문제시 원복
    fun positionPopupNearAnchor(root: View, anchor: View, popup: View) {
        // v8.8: 로그 전송 버튼이 메뉴 왼쪽에 추가되면서 팝업이 버튼 아래가 아니라 살짝
        // 왼쪽에 뜨는 문제 제보(재억) - popup.post{} 한 번만으로는 팝업이 GONE에서
        // VISIBLE로 막 전환된 직후라 measuredWidth가 실제 최종 레이아웃 폭과 다를 수
        // 있었음(특히 ScrollView는 UNSPECIFIED 측정 시 내부 LinearLayout 폭을 그대로
        // 못 반영하는 경우가 있음). doOnPreDraw로 실제 레이아웃이 한 번 더 확정된
        // 뒤의 진짜 width/height를 쓰도록 변경. #문제시 원복
        popup.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                popup.viewTreeObserver.removeOnPreDrawListener(this)
                val anchorLoc = IntArray(2)
                val rootLoc = IntArray(2)
                anchor.getLocationOnScreen(anchorLoc)
                root.getLocationOnScreen(rootLoc)

                val popupWidth = popup.width.takeIf { it > 0 } ?: popup.measuredWidth
                val popupHeight = popup.height.takeIf { it > 0 } ?: popup.measuredHeight

                var targetX = (anchorLoc[0] - rootLoc[0] + anchor.width - popupWidth).toFloat()
                var targetY = (anchorLoc[1] - rootLoc[1] + anchor.height + 8).toFloat()

                val maxX = (root.width - popupWidth).toFloat().coerceAtLeast(0f)
                val maxY = (root.height - popupHeight).toFloat().coerceAtLeast(0f)
                targetX = targetX.coerceIn(0f, maxX)
                targetY = targetY.coerceIn(0f, maxY)

                popup.x = targetX
                popup.y = targetY
                return true
            }
        })
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
            // 곳)만 눌러야 드래그되던 문제(사용자 지적 3번). 편집모드 켤 때 안의 모든 뷰를
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
    fun showAppSettingsDialog(context: android.app.Activity, touchLockOverlay: View?, onSaved: (() -> Unit)? = null) {
        val pref = context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
        // v: 체크박스 -> 토글 스위치로 전환(재억 요청). 아울러 "체크=활성화, 빈칸=비활성화"로
        // 의미를 전부 통일. 이전엔 이름에 "끄기"가 들어간 두 항목(경고음, 이동식카메라 감속)이
        // 반대 의미였는데(체크할수록 기능이 꺼짐), 이게 혼란의 원인이었음. 저장되는 값(키 이름)은
        // 기존 코드와의 호환을 위해 그대로 두고, 화면에 보이는 스위치 상태/저장 시 대입만
        // 뒤집어서 "스위치 켜짐=그 줄 이름대로 작동"이 되도록 맞춤. #문제시 원복
        val checkBox = android.widget.Switch(context).apply {
            text = "속도 10% 초과 시 경고음"
            // v: 재억 요청 - 기본값을 켜짐으로 변경(기존 false -> true)
            isChecked = pref.getBoolean("over_speed_warning_enabled", true)
            setTextColor(android.graphics.Color.WHITE)
            setPadding(40, 30, 40, 30)
        }
        val disableMobileCamCheckBox = android.widget.Switch(context).apply {
            text = "이동식카메라 감속"
            // 저장값(mobile_cam_slowdown_disabled)은 "꺼졌는지 여부"라 의미가 반대이므로
            // 화면에는 반전해서 보여줌(스위치 켜짐 = 감속 기능이 켜짐)
            isChecked = !pref.getBoolean("mobile_cam_slowdown_disabled", false)
            setTextColor(android.graphics.Color.WHITE)
            setPadding(40, 0, 40, 30)
        }
        // v4.0: 상단바 이벤트(카메라/구간단속/방지턱) 표시 켜고 끄기 - 모바일 화면은
        // 좁아서 부담스러울 수 있어 옵션으로 제공 (사용자 지적 6번). #문제시 원복
        val showTopBarEventCheckBox = android.widget.Switch(context).apply {
            text = "상단바에 이벤트(카메라/구간단속/방지턱) 표시"
            isChecked = pref.getBoolean("topbar_event_enabled", true)
            setTextColor(android.graphics.Color.WHITE)
            setPadding(40, 0, 40, 30)
        }
        // v4.13: 차선 안내(추천 차선 하이라이트) 오버레이 켜고 끄기 - 사용자 요청 3번.
        // v5.4: Tmap 쪽에서 켜고/끄기, 카카오 쪽에서 켜고/끄기를 각각 독립적으로 할 수
        // 있게(사용자 설명: 티맵 ON+카카오 OFF → 티맵 오버레이만, 티맵 OFF+카카오 ON →
        // 카카오 오버레이만, 둘 다 ON → 둘 다 가능 - 화면에 뜨는 오버레이 자체는 하나).
        // #문제시 원복
        // v: 사용자 요청(2026-08-10) - 카카오 화면에는 차선 오버레이를 아예 안 띄우기로 해서,
        // 이 체크박스(카카오용)는 UI에서 제거. Tmap용만 남김. #문제시 원복
        val showLaneOverlayTmapCheckBox = android.widget.Switch(context).apply {
            text = "차선 안내 오버레이 표시 (Tmap 화면 한정)"
            isChecked = pref.getBoolean("lane_overlay_tmap_enabled", true)
            setTextColor(android.graphics.Color.WHITE)
            setPadding(40, 0, 40, 30)
        }
        // v5.2: 초기 설정화면(MainActivity)에 있던 항목을 여기로 이동 - 매번 앱 처음 켤 때만
        // 보이는 화면이라 여기 있을 이유가 없었음(사용자 지적). SharedPreferences 키는
        // 그대로(USE_KM_DISTANCE_FORMAT) 써서 기존 저장값/읽는 쪽 코드는 안 건드림. #문제시 원복
        val distanceFormatKmCheckBox = android.widget.Switch(context).apply {
            text = "1000m 이상일 때 km 단위로 거리 표시"
            isChecked = pref.getBoolean("USE_KM_DISTANCE_FORMAT", true)
            setTextColor(android.graphics.Color.WHITE)
            setPadding(40, 0, 40, 30)
        }
        val unlockMapTouchCheckBox = if (touchLockOverlay != null) {
            android.widget.Switch(context).apply {
                text = "티맵 터치 잠금 해제 (핀치줌/드래그 허용)"
                isChecked = pref.getBoolean("map_touch_unlocked", false)
                setTextColor(android.graphics.Color.WHITE)
                setPadding(40, 0, 40, 30)
            }
        } else null

        // v: 신규기능(목적지 반경 도착알림) - 재억 요청으로 기본 기능에 바로 넣지 않고
        // 켜고 끄는 옵션으로 추가. ArrivalRadiusAlert.KEY_ENABLED와 동일한 키 사용. #문제시 원복
        val arrivalRadiusAlertCheckBox = android.widget.Switch(context).apply {
            text = "목적지 근처 도착 알림 (소리+진동)"
            isChecked = pref.getBoolean("arrival_radius_alert_enabled", true)
            setTextColor(android.graphics.Color.WHITE)
            setPadding(40, 0, 40, 30)
        }

        // v8.7: v8.5 조사로 확인된 Tmap MapLayerType(Default/Aerial) API를 사용자가 켜고 끌 수
        // 있게 노출. 카카오 화면엔 이런 API 자체가 없어서 Tmap 화면 한정 문구를 명시. #문제시 원복
        val satelliteViewCheckBox = android.widget.Switch(context).apply {
            text = "티맵 위성지도 보기"
            isChecked = pref.getBoolean("tmap_satellite_view_enabled", false)
            setTextColor(android.graphics.Color.WHITE)
            setPadding(40, 0, 40, 30)
        }

        // v9.2: 재억 요청 - 도로 위 초록/주황/빨강 실시간 정체 표시 켜고 끄기. SDK 안에 실제
        // 스위치가 있는지는 아직 조사 중이라(dumpTrafficApiCandidates), 우선 체크박스와 저장값만
        // 만들어둠 - 조사 결과 나오면 applyTmapSatelliteViewSetting()처럼 실제로 연결 예정. #문제시 원복
        val trafficInfoCheckBox = android.widget.Switch(context).apply {
            text = "티맵 교통 정보 (도로 정체 색깔 표시)"
            isChecked = pref.getBoolean("tmap_traffic_info_enabled", true)
            setTextColor(android.graphics.Color.WHITE)
            setPadding(40, 0, 40, 30)
        }

        // v: 신규기능(재억 요청) - 콤마 화면에 카카오 경로선을 표시할지 켜고 끄는 토글.
        // 기본값은 꺼짐(신규 기능이라 재억이 필요할 때만 켜도록). #문제시 원복
        val routeLineDisplayCheckBox = android.widget.Switch(context).apply {
            text = "경로선 콤마 화면에 표시"
            isChecked = pref.getBoolean("route_line_display_enabled", false)
            setTextColor(android.graphics.Color.WHITE)
            setPadding(40, 0, 40, 30)
        }

        // v13.0-4: 재억 요청 - 즐겨찾기 5칸이 다 필요없는 사람도 있어서, 표시 개수를
        // -/+ 버튼으로 0~5까지 조절 가능하게 함. 집/회사는 상단바 고정이라 이 설정과
        // 무관하게 항상 보임. #문제시 원복
        var favoriteCount = pref.getInt("quickslot_favorite_count", 5).coerceIn(0, 5)
        val favoriteCountValueText = android.widget.TextView(context).apply {
            text = favoriteCount.toString()
            setTextColor(android.graphics.Color.WHITE)
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            minWidth = 40
        }
        val favoriteMinusButton = android.widget.TextView(context).apply {
            text = "−"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(android.graphics.Color.parseColor("#262626"))
            setPadding(24, 12, 24, 12)
            setOnClickListener {
                if (favoriteCount > 0) {
                    favoriteCount--
                    favoriteCountValueText.text = favoriteCount.toString()
                }
            }
        }
        val favoritePlusButton = android.widget.TextView(context).apply {
            text = "+"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(android.graphics.Color.parseColor("#262626"))
            setPadding(24, 12, 24, 12)
            setOnClickListener {
                if (favoriteCount < 5) {
                    favoriteCount++
                    favoriteCountValueText.text = favoriteCount.toString()
                }
            }
        }
        val favoriteCountRow = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(40, 10, 40, 30)
            addView(android.widget.TextView(context).apply {
                text = "즐겨찾기 표시 개수"
                setTextColor(android.graphics.Color.WHITE)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            addView(favoriteMinusButton)
            addView(favoriteCountValueText, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 16; marginEnd = 16 })
            addView(favoritePlusButton)
        }

        // v13.8: 재억 요청 - "볼륨을 아무리 맞춰놔도 다음에 켜면 다시 돌아간다"는 지적에 대응.
        // 기존엔 마지막으로 잡힌 시스템 볼륨을 자동으로만 저장했는데(VolumeHelper), 이번엔
        // 여기서 슬라이더로 직접 값을 고르고 "지금 음량으로 저장" 눌러서 명시적으로 고정할
        // 수 있게 함. 저장한 값은 다음 안내부터 unmuteTmapVolume()/applySavedSystemVolume()이
        // 그대로 읽어감(기존 로직 그대로 재사용). #문제시 원복
        // v15.3: 재억 요청 - 음량은 따로 "지금 음량으로 저장" 버튼을 누를 필요 없이,
        // 다른 설정들처럼 슬라이더로 값만 정해두고 맨 아래 "저장" 버튼 누를 때 한 번에
        // 같이 저장되도록 통합. #문제시 원복
        val volumeSectionTitle = android.widget.TextView(context).apply {
            text = "길안내 음량"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 15f
            setPadding(40, 20, 40, 10)
        }
        var pendingVolumePercent = VolumeHelper.savedVolumePercent(context)
        val volumeValueText = android.widget.TextView(context).apply {
            text = "$pendingVolumePercent%"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            minWidth = 70
            gravity = android.view.Gravity.END
        }
        val volumeSeekBar = android.widget.SeekBar(context).apply {
            max = 100
            progress = pendingVolumePercent
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                    pendingVolumePercent = value
                    volumeValueText.text = "$value%"
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            })
        }
        val volumeSeekRow = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(40, 0, 40, 4)
            addView(volumeSeekBar, android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ))
            addView(volumeValueText)
        }
        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            // v: 재억 요청(2026-08-24) - 설정 항목을 글자 길이 짧은 순 -> 긴 순으로 재배치.
            // 즐겨찾기 표시 개수(라벨은 짧지만 -/+ 버튼까지 있어 예외적으로 맨 위). 음량
            // 조절은 슬라이더가 있어서 항상 맨 아래 고정. #문제시 원복
            addView(favoriteCountRow)               // 즐겨찾기 표시 개수
            addView(satelliteViewCheckBox)          // 티맵 위성지도 보기
            addView(disableMobileCamCheckBox)       // 이동식카메라 감속
            addView(routeLineDisplayCheckBox)       // 경로선 콤마 화면에 표시
            addView(checkBox)                       // 속도 10% 초과 시 경고음
            addView(arrivalRadiusAlertCheckBox)     // 목적지 근처 도착 알림 (소리+진동)
            addView(trafficInfoCheckBox)            // 티맵 교통 정보 (도로 정체 색깔 표시)
            addView(distanceFormatKmCheckBox)       // 1000m 이상일 때 km 단위로 거리 표시
            unlockMapTouchCheckBox?.let { addView(it) } // 티맵 터치 잠금 해제 (핀치줌/드래그 허용)
            addView(showLaneOverlayTmapCheckBox)    // 차선 안내 오버레이 표시 (Tmap 화면 한정)
            addView(showTopBarEventCheckBox)        // 상단바에 이벤트(카메라/구간단속/방지턱) 표시
            addView(volumeSectionTitle)
            addView(volumeSeekRow)
        }
        // v15.3: 설정 항목이 많아져서 다이얼로그 세로 길이가 화면을 넘길 수 있으므로
        // ScrollView로 감쌈. AlertDialog는 setView(content)의 버튼 줄을 항상 콘텐츠
        // 바깥에 별도로 그리기 때문에, 콘텐츠만 스크롤되고 취소/저장 버튼은 화면에
        // 고정된 채로 유지됨. #문제시 원복
        val scrollableContainer = android.widget.ScrollView(context).apply {
            addView(container)
        }
        android.app.AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("앱 설정")
            .setView(scrollableContainer)
            .setPositiveButton("저장") { _, _ ->
                pref.edit()
                    .putBoolean("over_speed_warning_enabled", checkBox.isChecked)
                    // 저장 키(mobile_cam_slowdown_disabled)는 "꺼졌는지 여부"라 스위치 상태를
                    // 반전해서 저장(스위치 켜짐=감속 기능 켜짐이므로 disabled=!isChecked)
                    .putBoolean("mobile_cam_slowdown_disabled", !disableMobileCamCheckBox.isChecked)
                    .putBoolean("topbar_event_enabled", showTopBarEventCheckBox.isChecked)
                    .putBoolean("lane_overlay_tmap_enabled", showLaneOverlayTmapCheckBox.isChecked)
                    .putBoolean("USE_KM_DISTANCE_FORMAT", distanceFormatKmCheckBox.isChecked)
                    .putBoolean("arrival_radius_alert_enabled", arrivalRadiusAlertCheckBox.isChecked)
                    .putBoolean("tmap_satellite_view_enabled", satelliteViewCheckBox.isChecked)
                    .putBoolean("tmap_traffic_info_enabled", trafficInfoCheckBox.isChecked)
                    .putBoolean("route_line_display_enabled", routeLineDisplayCheckBox.isChecked)
                    .putInt("quickslot_favorite_count", favoriteCount)
                    .apply {
                        if (unlockMapTouchCheckBox != null) {
                            putBoolean("map_touch_unlocked", unlockMapTouchCheckBox.isChecked)
                        }
                    }
                    .apply()
                // v15.3: 음량도 다른 항목들과 같은 저장 버튼 한 번으로 같이 저장(별도 버튼 제거)
                VolumeHelper.saveExplicitVolumePercent(context, pendingVolumePercent)
                if (touchLockOverlay != null && unlockMapTouchCheckBox != null) {
                    if (unlockMapTouchCheckBox.isChecked) {
                        touchLockOverlay.setOnTouchListener { _, _ -> false }
                    } else {
                        touchLockOverlay.setOnTouchListener { _, _ -> true }
                    }
                }
                android.widget.Toast.makeText(context, "저장됨", android.widget.Toast.LENGTH_SHORT).show()
                onSaved?.invoke()
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
