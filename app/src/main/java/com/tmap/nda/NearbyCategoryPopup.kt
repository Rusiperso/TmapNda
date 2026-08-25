package com.tmap.nda

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * 티맵/카카오 화면 공용 "주변 카테고리 검색" 팝업. 왼쪽은 고정된 카테고리 목록, 오른쪽은
 * 선택된 카테고리의 근처 결과(거리순)를 보여줌. 결과를 탭하면 즉시 그 목적지로 안내 시작.
 *
 * SearchRanking.CATEGORY_KEYWORDS와 MapActivity.performCategorySearch()가 이미 검증해둔
 * 카카오 category.json API(반경 20km, sort=distance)를 그대로 재사용. #문제시 원복
 */
object NearbyCategoryPopup {
    data class CategoryItem(val label: String, val code: String)

    private const val PREF_LAST_CATEGORY = "nearby_category_last_selected"

    // v: 왼쪽 목록 - SearchRanking.CATEGORY_KEYWORDS 중 재억이 실제로 자주 쓸 법한 것만
    // 순서대로 고정. 순서 자체가 UI라 SearchRanking과 별도로 여기서 관리. #문제시 원복
    private val CATEGORIES = listOf(
        CategoryItem("편의점", "CS2"),
        CategoryItem("주유소", "OL7"),
        CategoryItem("전기차 충전소", "EV"),
        CategoryItem("주차장", "PK6"),
        CategoryItem("카페", "CE7"),
        CategoryItem("약국", "PM9"),
        CategoryItem("은행", "BK9"),
        CategoryItem("병원", "HP8"),
        CategoryItem("마트", "MT1")
    )

    fun show(
        context: Context,
        httpClient: OkHttpClient,
        restKey: String,
        curLat: Double,
        curLon: Double,
        // v: 신규기능(주변검색 진행/역방향 표시) - 현재 진행방향(bearing, 0~360도).
        // null이면(정지 중이라 아직 안 잡혔거나) 방향 표시를 생략. #문제시 원복
        currentBearing: Float? = null,
        onPick: (HistoryEntry) -> Unit
    ) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 340)
            )
        }

        // v: 재억 요청(2026-08-25) - 카테고리 버튼(왼쪽 목록)이 누르기 불편할 정도로 작아서
        // 폭/패딩/글자크기 다 키움(96dp->120dp, 패딩 12dp->16dp, 14sp->16sp). #문제시 원복
        val leftList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(context, 120), LinearLayout.LayoutParams.MATCH_PARENT)
        }
        val leftScroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 120), LinearLayout.LayoutParams.MATCH_PARENT)
            addView(leftList)
        }

        val rightList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val rightScroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            addView(rightList)
        }

        root.addView(leftScroll)
        root.addView(rightScroll)

        val dialog = AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("주변 검색")
            .setView(root)
            .setNegativeButton("닫기", null)
            .create()

        // v: 재억 요청(2026-08-25) - 팝업 제목에 지금 선택된 카테고리를 같이 표시.
        // 마지막으로 선택한 카테고리도 기억해서 다음에 열 때 그걸로 바로 검색(유종처럼). #문제시 원복
        fun setTitleFor(label: String) {
            dialog.setTitle("주변 검색 - $label")
        }

        fun saveLastCategory(label: String) {
            context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                .edit().putString(PREF_LAST_CATEGORY, label).apply()
        }

        // v: 재억 요청(2026-08-25) - 결과가 많을 때 한 화면에 다 몰아넣지 말고 10개씩
        // 페이지로 나눠서 "이전/다음" 버튼으로 넘겨보게 함(기존 목적지 검색과 동일 패턴). #문제시 원복
        fun <T> renderPaged(items: List<T>, page: Int, makeItemRow: (T) -> View, onPageChange: (Int) -> Unit) {
            rightList.removeAllViews()
            if (items.isEmpty()) {
                rightList.addView(makeRow(context, "검색 결과 없음", null, false, 16f) {})
                return
            }
            val pageSize = 10
            val totalPages = (items.size + pageSize - 1) / pageSize
            val safePage = page.coerceIn(0, totalPages - 1)
            val start = safePage * pageSize
            val end = (start + pageSize).coerceAtMost(items.size)
            items.subList(start, end).forEach { rightList.addView(makeItemRow(it)) }
            if (totalPages > 1) {
                val navRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setPadding(dp(context, 8), dp(context, 12), dp(context, 8), dp(context, 8))
                }
                navRow.addView(TextView(context).apply {
                    text = "이전"
                    textSize = 14f
                    setTextColor(if (safePage > 0) android.graphics.Color.WHITE else android.graphics.Color.GRAY)
                    setPadding(dp(context, 20), dp(context, 8), dp(context, 20), dp(context, 8))
                    if (safePage > 0) setOnClickListener { onPageChange(safePage - 1) }
                })
                navRow.addView(TextView(context).apply {
                    text = "${safePage + 1} / $totalPages"
                    textSize = 14f
                    setTextColor(android.graphics.Color.WHITE)
                    setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
                })
                navRow.addView(TextView(context).apply {
                    text = "다음"
                    textSize = 14f
                    setTextColor(if (safePage < totalPages - 1) android.graphics.Color.WHITE else android.graphics.Color.GRAY)
                    setPadding(dp(context, 20), dp(context, 8), dp(context, 20), dp(context, 8))
                    if (safePage < totalPages - 1) setOnClickListener { onPageChange(safePage + 1) }
                })
                rightList.addView(navRow)
            }
        }

        // v: 신규기능(주변검색 진행/역방향 표시) - GPS bearing과 "현재위치->후보" 방향을
        // 단순 비교(직선거리 기준, API 추가 호출 없음). 각도차 90도 이내면 진행방향으로
        // 대략 간주, 넘으면 역방향. 도로가 구불구불하면 부정확할 수 있음(직선 기준 추정치). #문제시 원복
        fun directionLabel(targetLat: Double, targetLon: Double): String? {
            val bearing = currentBearing ?: return null
            val dLon = Math.toRadians(targetLon - curLon)
            val lat1 = Math.toRadians(curLat)
            val lat2 = Math.toRadians(targetLat)
            val y = Math.sin(dLon) * Math.cos(lat2)
            val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon)
            val targetBearing = (Math.toDegrees(Math.atan2(y, x)) + 360) % 360
            var diff = Math.abs(targetBearing - bearing)
            if (diff > 180) diff = 360 - diff
            return if (diff <= 90) "진행방향" else "역방향"
        }

        fun renderResults(hits: List<HistoryEntry>, page: Int = 0) {
            renderPaged(hits, page, { entry ->
                val distText = entry.distanceMeters?.let { SearchRanking.formatDistance(it) }
                val dirLabel = directionLabel(entry.lat, entry.lon)
                val fullDist = if (dirLabel != null && distText != null) "$distText · $dirLabel" else distText
                makeRow(context, entry.name, fullDist, true, 14f) {
                    dialog.dismiss()
                    onPick(entry)
                }
            }) { newPage -> renderResults(hits, newPage) }
        }

        // v: 신규기능(주유소 브랜드/유가 검색) - 오피넷 API로 조회, 결과에 브랜드+가격 표시.
        // API 키 없으면 카카오 카테고리 검색으로 안내(선택 입력이라 안 넣는 사용자도 있음). #문제시 원복
        // v: 재억 요청(2026-08-25) - 순수 거리순 대신, 가격도 저렴하고 거리도 가까운 곳이
        // 위로 오게 종합 점수로 재정렬. 가격/거리 각각 순위를 매겨서 가중합(가격 70%, 거리
        // 30%) - 가격 차이가 크면 좀 멀어도 위로, 가격이 고만고만하면 가까운 게 위로 옴.
        // 가격 정보 없는 주유소는 순위 계산에서 제외하고 맨 뒤로. #문제시 원복
        fun sortByPriceAndDistance(stations: List<OpinetHelper.GasStation>): List<OpinetHelper.GasStation> {
            val (withPrice, withoutPrice) = stations.partition { it.gasolinePrice != null }
            if (withPrice.isEmpty()) return stations
            val priceRank = withPrice.sortedBy { it.gasolinePrice }.withIndex().associate { (i, s) -> s to i }
            val distRank = withPrice.sortedBy { it.distanceMeters }.withIndex().associate { (i, s) -> s to i }
            val sorted = withPrice.sortedBy { s -> (priceRank[s] ?: 0) * 0.7 + (distRank[s] ?: 0) * 0.3 }
            return sorted + withoutPrice.sortedBy { it.distanceMeters }
        }

        // v: renderGasStations와 runGasSearch가 서로를 호출하는 상호재귀라 선언 순서만으론
        // 안 풀림 - lateinit 함수변수로 미리 이름만 선언. #문제시 원복
        lateinit var runGasSearchFn: () -> Unit

        fun renderGasStations(stations: List<OpinetHelper.GasStation>, page: Int = 0) {
            val sorted = sortByPriceAndDistance(stations)
            val savedBrands = OpinetHelper.savedBrandFilter(context)
            val brandLabel = if (savedBrands.isNullOrEmpty()) {
                "전체"
            } else {
                OpinetHelper.BRANDS.filter { it.second in savedBrands }.joinToString(",") { it.first }
            }
            renderPaged(sorted, page, { st ->
                val distText = SearchRanking.formatDistance(st.distanceMeters)
                val dirLabel = directionLabel(st.lat, st.lon)
                val fullDist = if (dirLabel != null) "$distText · $dirLabel" else distText
                val fuelLabel = OpinetHelper.FUEL_TYPES.firstOrNull { it.second == OpinetHelper.savedFuelType(context) }?.first ?: "가격"
                val priceText = st.gasolinePrice?.let { "$fuelLabel ${it}원" }
                makeGasRow(context, "${st.brandName} ${st.name}", fullDist, priceText) {
                    dialog.dismiss()
                    onPick(HistoryEntry(st.name, "", st.lat, st.lon, st.distanceMeters))
                }
            }) { newPage -> renderGasStations(stations, newPage) }
            // v: 재억 지적(2026-08-25) - 유종/브랜드 확정 후엔 팝업 없이 바로 결과가 뜨는
            // 대신, 결과 화면 맨 위에 지금 필터를 보여주고 탭하면 다시 바꿀 수 있게 함. #문제시 원복
            val filterRow = makeRow(context, "브랜드 필터: $brandLabel (탭해서 변경)", null, true, 13f) {
                showBrandMultiChooser(context, OpinetHelper.FUEL_TYPES.firstOrNull { it.second == OpinetHelper.savedFuelType(context) }?.first) {
                    runGasSearchFn()
                }
            }
            rightList.addView(filterRow, 0)
        }

        runGasSearchFn = {
            val opinetKey = context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                .getString("opinet_api_key", null)
            if (opinetKey.isNullOrBlank()) {
                android.widget.Toast.makeText(context, "오피넷 API 키를 설정에서 먼저 입력하세요 (opinet.co.kr 각자 발급)", android.widget.Toast.LENGTH_LONG).show()
                rightList.removeAllViews()
                rightList.addView(makeRow(context, "오피넷 키 없음 - 일반 검색으로 대체", null, true, 14f) {
                    rightList.removeAllViews()
                    rightList.addView(makeRow(context, "검색 중...", null, false, 16f) {})
                    performCategorySearchShared(context, httpClient, restKey, "OL7", curLat, curLon) { hits ->
                        (context as? android.app.Activity)?.runOnUiThread { renderResults(hits) }
                    }
                })
            } else {
                val prodcd = OpinetHelper.savedFuelType(context) ?: OpinetHelper.FUEL_TYPES[0].second
                rightList.removeAllViews()
                rightList.addView(makeRow(context, "검색 중...", null, false, 16f) {})
                // v: 신규기능(브랜드 다중선택 필터) - 재억 요청 - 서버는 항상 전체로 받아오고,
                // 저장된 브랜드 필터(여러 개 가능)로 클라이언트에서 걸러냄. 오피넷 API의
                // pooltype은 브랜드 하나만 지원해서 다중선택은 서버가 아니라 앱에서 처리. #문제시 원복
                OpinetHelper.fetchNearby(context, httpClient, opinetKey, curLat, curLon, null, prodcd) { stations ->
                    val savedBrands = OpinetHelper.savedBrandFilter(context)
                    val filtered = if (savedBrands.isNullOrEmpty()) stations else stations.filter { it.brandCode in savedBrands }
                    NavLogger.d(context, "[오피넷] 주유소 검색 결과 ${stations.size}건 중 브랜드필터 후 ${filtered.size}건")
                    (context as? android.app.Activity)?.runOnUiThread { renderGasStations(filtered) }
                }
            }
        }

        fun runGasSearch() {
            runGasSearchFn()
        }

        // v: 재억 지적(2026-08-25) - 유종/브랜드가 이미 확정돼 있는데도 주유소 탭할 때마다
        // 팝업이 다시 떠서 불편하다는 지적 - 둘 다 확정됐으면 팝업 없이 바로 검색, 결과
        // 화면 위 "브랜드 필터 변경" 행으로만 재변경 가능하게 함. #문제시 원복
        fun showBrandChooserForGas() {
            val currentFuelLabel = OpinetHelper.FUEL_TYPES.firstOrNull { it.second == OpinetHelper.savedFuelType(context) }?.first
            showBrandMultiChooser(context, currentFuelLabel) { runGasSearch() }
        }

        fun runGasSearchFlow() {
            if (OpinetHelper.savedFuelType(context) == null) {
                showFuelChooser(context) { prodcd ->
                    OpinetHelper.saveFuelType(context, prodcd)
                    showBrandChooserForGas()
                }
            } else if (!OpinetHelper.isBrandFilterConfigured(context)) {
                showBrandChooserForGas()
            } else {
                runGasSearch()
            }
        }

        // v: 재억 요청(2026-08-25) - 충전용량(kW)이 있으면 그걸로 초급속(100kW+)/급속(40kW+)/
        // 완속(40kW 미만) 구분(법령상 40kW 기준과 동일). kW 정보가 없는 경우에만 타입 코드로
        // 대략 추정(01,03,04,05,07=급속 계열, 02,06=완속) - 확실치 않은 값이라 로그로 남김. #문제시 원복
        fun speedLabel(st: EvChargerHelper.ChargerStation): String {
            val kw = st.outputKw
            if (kw != null) {
                return when {
                    kw >= 100.0 -> "초급속"
                    kw >= 40.0 -> "급속"
                    else -> "완속"
                }
            }
            return when (st.chargerType) {
                "01", "03", "04", "05", "07" -> "급속"
                "02", "06" -> "완속"
                else -> {
                    NavLogger.d(context, "[전기차충전소] 미분류 충전기타입 코드=${st.chargerType}")
                    "종류미상"
                }
            }
        }

        // v: 신규기능(충전기 속도 필터) - 전체/초급속/급속/완속 중 골라서, 그 속도의 충전기가
        // 있는 충전소만 걸러서 보여줌. #문제시 원복
        var evSpeedFilter: String? = null // null = 전체

        // v: showEvSpeedChooser와 renderChargers가 서로를 호출하는 상호재귀라, 코틀린 로컬
        // 함수는 선언 순서로만 참조가 풀려서 어느 순서로 둬도 컴파일 에러가 남. lateinit
        // 함수변수로 미리 이름만 선언해두고 나중에 실제 구현을 대입해서 순환을 풂. #문제시 원복
        lateinit var renderChargersFn: (List<HistoryEntry>, List<EvChargerHelper.ChargerStation>, Int) -> Unit

        /** 급속/완속/초급속/전체 고르는 팝업. */
        fun showEvSpeedChooser(kakaoHits: List<HistoryEntry>, envStations: List<EvChargerHelper.ChargerStation>) {
            val options = arrayOf("전체", "초급속", "급속", "완속")
            AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("충전기 속도 선택")
                .setItems(options) { _, which ->
                    evSpeedFilter = if (which == 0) null else options[which]
                    renderChargersFn(kakaoHits, envStations, 0)
                }
                .show()
        }

        renderChargersFn = { kakaoHits, envStations, page ->
            fun distMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
                val dLat = Math.toRadians(lat2 - lat1)
                val dLon = Math.toRadians(lon2 - lon1)
                val a = Math.sin(dLat / 2).let { it * it } +
                    Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                    Math.sin(dLon / 2).let { it * it }
                return 6371000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
            }
            val filteredHits = if (evSpeedFilter == null) {
                kakaoHits
            } else {
                kakaoHits.filter { entry ->
                    envStations.any { st ->
                        distMeters(entry.lat, entry.lon, st.lat, st.lon) <= 150.0 && speedLabel(st) == evSpeedFilter
                    }
                }
            }
            val filterRow = makeRow(context, "속도 필터: ${evSpeedFilter ?: "전체"} (탭해서 변경)", null, true, 13f) {
                showEvSpeedChooser(kakaoHits, envStations)
            }
            renderPaged(filteredHits, page, { entry ->
                val distText = entry.distanceMeters?.let { SearchRanking.formatDistance(it) }
                val dirLabel = directionLabel(entry.lat, entry.lon)
                val fullDist = if (dirLabel != null && distText != null) "$distText · $dirLabel" else distText
                val nearby = envStations.filter { st -> distMeters(entry.lat, entry.lon, st.lat, st.lon) <= 150.0 }
                val statusText = if (nearby.isEmpty()) {
                    "충전기 정보 없음"
                } else {
                    val charging = nearby.count { it.statusText == "충전중" }
                    val waiting = nearby.count { it.statusText == "충전대기" }
                    val trouble = nearby.count { it.statusText == "통신이상" || it.statusText == "운영중지" }
                    val fastCount = nearby.count { speedLabel(it) == "급속" }
                    val slowCount = nearby.count { speedLabel(it) == "완속" }
                    val ultraCount = nearby.count { speedLabel(it) == "초급속" }
                    val speedParts = listOfNotNull(
                        if (ultraCount > 0) "초급속 $ultraCount" else null,
                        if (fastCount > 0) "급속 $fastCount" else null,
                        if (slowCount > 0) "완속 $slowCount" else null
                    )
                    val speedText = if (speedParts.isNotEmpty()) " (${speedParts.joinToString("·")})" else ""
                    "충전기 ${nearby.size}대$speedText · 대기 $waiting · 충전중 $charging" +
                        (if (trouble > 0) " · 이상 $trouble" else "")
                }
                makeGasRow(context, entry.name, fullDist, statusText) {
                    dialog.dismiss()
                    onPick(entry)
                }
            }) { newPage -> renderChargersFn(kakaoHits, envStations, newPage) }
            rightList.addView(filterRow, 0)
        }

        fun renderChargers(kakaoHits: List<HistoryEntry>, envStations: List<EvChargerHelper.ChargerStation>, page: Int = 0) {
            renderChargersFn(kakaoHits, envStations, page)
        }

        fun runEvSearch() {
            rightList.removeAllViews()
            rightList.addView(makeRow(context, "검색 중...", null, false, 16f) {})
            performKeywordSearchShared(context, httpClient, restKey, "전기차 충전소", curLat, curLon, maxPages = 5) { kakaoHits ->
                val evKey = context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                    .getString("ev_charger_api_key", null)
                if (evKey.isNullOrBlank()) {
                    // 상태 정보용 키가 없어도 위치 검색(카카오)은 그대로 보여줌
                    (context as? android.app.Activity)?.runOnUiThread { renderChargers(kakaoHits, emptyList()) }
                    return@performKeywordSearchShared
                }
                EvChargerHelper.fetchNearby(context, httpClient, evKey, restKey, curLat, curLon) { envStations ->
                    NavLogger.d(context, "[전기차충전소] 카카오 ${kakaoHits.size}건 + 환경공단 ${envStations.size}건 매칭")
                    (context as? android.app.Activity)?.runOnUiThread { renderChargers(kakaoHits, envStations) }
                }
            }
        }

        fun runSearch(item: CategoryItem) {
            setTitleFor(item.label)
            saveLastCategory(item.label)
            when (item.label) {
                "주유소" -> { runGasSearchFlow(); return }
                "전기차 충전소" -> { runEvSearch(); return }
            }
            rightList.removeAllViews()
            rightList.addView(makeRow(context, "검색 중...", null, false, 16f) {})
            performCategorySearchShared(context, httpClient, restKey, item.code, curLat, curLon) { hits ->
                NavLogger.d(context, "[주변카테고리검색] ${item.label} 결과 ${hits.size}건")
                (context as? android.app.Activity)?.runOnUiThread { renderResults(hits) }
            }
        }

        val lastCategoryLabel = context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
            .getString(PREF_LAST_CATEGORY, null)
        val startIndex = CATEGORIES.indexOfFirst { it.label == lastCategoryLabel }.takeIf { it >= 0 } ?: 0

        CATEGORIES.forEachIndexed { index, item ->
            val row = makeRow(context, item.label, null, true, 16f) { runSearch(item) }
            leftList.addView(row)
            if (index == startIndex) runSearch(item) // 마지막 선택 카테고리(없으면 첫 카테고리) 기본 선택
        }

        dialog.show()
    }

    /** 주유소 선택 시 브랜드 여러 개를 체크박스로 골라 저장하는 팝업. "유종 변경" 버튼도 같이 둠. */
    private fun showBrandMultiChooser(context: Context, currentFuelLabel: String?, onApply: () -> Unit) {
        val actualBrands = OpinetHelper.BRANDS.filter { it.second != null } // "전체" 항목 제외
        val currentSaved = OpinetHelper.savedBrandFilter(context) ?: emptySet()
        val checkedStates = BooleanArray(actualBrands.size) { i -> actualBrands[i].second in currentSaved }
        val labels = actualBrands.map { it.first }.toTypedArray()

        val builder = AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("주유소 브랜드 선택 (여러 개 가능, 전체 해제 시 전체표시)")
            .setMultiChoiceItems(labels, checkedStates) { _, which, isChecked -> checkedStates[which] = isChecked }
            .setPositiveButton("적용") { _, _ ->
                val selected = actualBrands.filterIndexed { i, _ -> checkedStates[i] }.mapNotNull { it.second }.toSet()
                OpinetHelper.saveBrandFilter(context, selected)
                onApply()
            }
            .setNeutralButton("유종 변경") { _, _ ->
                OpinetHelper.clearFuelType(context)
                showFuelChooser(context) { prodcd ->
                    OpinetHelper.saveFuelType(context, prodcd)
                    showBrandMultiChooser(context, OpinetHelper.FUEL_TYPES.firstOrNull { it.second == prodcd }?.first, onApply)
                }
            }
            .setNegativeButton("취소", null)
        builder.show()
    }

    /** 유종(휘발유/경유/LPG) 선택 팝업. 처음 한 번만 뜨고 이후엔 저장된 값을 계속 씀. */
    private fun showFuelChooser(context: Context, onPick: (String) -> Unit) {
        val labels = OpinetHelper.FUEL_TYPES.map { it.first }.toTypedArray()
        AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("유종 선택 (내 차량 기준)")
            .setCancelable(false)
            .setItems(labels) { _, which -> onPick(OpinetHelper.FUEL_TYPES[which].second) }
            .show()
    }

    private fun makeGasRow(context: Context, title: String, distText: String?, priceText: String?, onClick: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
            setOnClickListener { onClick() }
        }
        val topRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        topRow.addView(TextView(context).apply {
            text = title
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (distText != null) {
            topRow.addView(TextView(context).apply {
                text = distText
                textSize = 13f
                setTextColor(android.graphics.Color.WHITE)
            })
            // v: 재억 지적(2026-08-25) - 주유소/전기차충전소는 일반 카테고리와 달리 예상
            // 소요시간이 안 붙어 있었음. 같은 추정 로직(거리 기반, 시속 40km 가정) 적용. #문제시 원복
            val etaMinutes = estimateEtaMinutes(distText)
            if (etaMinutes != null) {
                topRow.addView(TextView(context).apply {
                    text = "  약 ${etaMinutes}분"
                    textSize = 13f
                    setTextColor(android.graphics.Color.parseColor("#FFD54F"))
                })
            }
        }
        row.addView(topRow)
        if (priceText != null) {
            row.addView(TextView(context).apply {
                text = priceText
                textSize = 13f
                setTextColor(android.graphics.Color.parseColor("#FFD54F"))
            })
        }
        return row
    }

    private fun makeRow(context: Context, title: String, subtitle: String?, clickable: Boolean, titleSize: Float, onClick: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16))
            if (clickable) setOnClickListener { onClick() }
        }
        // v: 재억 요청(2026-08-25) - "00m 이름 시간" 순서가 헷갈린다는 지적. "이름 00m 시간"
        // 순서로 변경(이름 먼저, 그다음 거리, 그다음 예상시간) - 편의점뿐 아니라 이 공용
        // 함수를 쓰는 모든 일반 카테고리(카페/약국/은행/병원/마트 등)에 다 적용됨. #문제시 원복
        val titleView = TextView(context).apply {
            text = title
            textSize = titleSize
            setTextColor(android.graphics.Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(titleView)
        if (subtitle != null) {
            val subtitleView = TextView(context).apply {
                text = subtitle
                textSize = 13f
                setTextColor(android.graphics.Color.WHITE)
                minWidth = dp(context, 56)
            }
            row.addView(subtitleView)
        }
        val etaMinutes = estimateEtaMinutes(subtitle)
        if (etaMinutes != null) {
            val etaView = TextView(context).apply {
                text = "약 ${etaMinutes}분"
                textSize = 13f
                setTextColor(android.graphics.Color.parseColor("#FFD54F"))
            }
            row.addView(etaView)
        }
        return row
    }

    /** 검색 API가 소요시간을 안 줘서, 거리 기반 대략치(시속 40km 가정)로 추정. 정확한 값이 아님. */
    private fun estimateEtaMinutes(distanceText: String?): Int? {
        if (distanceText == null) return null
        val meters = when {
            distanceText.endsWith("km") -> distanceText.removeSuffix("km").trim().toDoubleOrNull()?.times(1000)
            distanceText.endsWith("m") -> distanceText.removeSuffix("m").trim().toDoubleOrNull()
            else -> null
        } ?: return null
        val minutes = (meters / 1000.0 / 40.0 * 60.0).toInt()
        return minutes.coerceAtLeast(1)
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /** MapActivity.performCategorySearch()와 동일한 API 호출 - 두 화면이 같은 결과 형식을 쓰게 공용화. */
    /** 카카오 키워드 검색 - 카테고리 코드가 없는 "전기차 충전소" 같은 경우 사용. */
    /** 재억 요청(2026-08-25) - 전기차 충전소 등 결과가 부족한 경우, 카카오 페이지를
     * 여러 장(최대 3장=45건) 이어붙여서 더 많이 받아옴. #문제시 원복 */
    private fun performKeywordSearchShared(
        context: Context,
        httpClient: OkHttpClient,
        restKey: String,
        keyword: String,
        lat: Double,
        lon: Double,
        maxPages: Int = 1,
        onResult: (List<HistoryEntry>) -> Unit
    ) {
        val accumulated = mutableListOf<HistoryEntry>()
        fun fetchPage(page: Int) {
            NavLogger.d(context, "[주변카테고리검색] 키워드 요청 시작 keyword=$keyword page=$page lat=$lat lon=$lon")
            val encodedKeyword = java.net.URLEncoder.encode(keyword, "UTF-8")
            val url = "https://dapi.kakao.com/v2/local/search/keyword.json?query=$encodedKeyword" +
                "&x=$lon&y=$lat&radius=20000&sort=distance&size=15&page=$page"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "KakaoAK $restKey")
                .build()
            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    NavLogger.e(context, "[주변카테고리검색] 키워드 요청 실패: ${e.message}")
                    onResult(accumulated)
                }

                override fun onResponse(call: Call, response: okhttp3.Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            NavLogger.e(context, "[주변카테고리검색] 키워드 실패 code=${it.code}")
                            onResult(accumulated)
                            return@use
                        }
                        val json = JSONObject(it.body?.string() ?: "{}")
                        val documents = json.optJSONArray("documents")
                        val isEnd = json.optJSONObject("meta")?.optBoolean("is_end", true) ?: true
                        if (documents != null) {
                            for (idx in 0 until documents.length()) {
                                val d = documents.getJSONObject(idx)
                                accumulated.add(
                                    HistoryEntry(
                                        d.optString("place_name", "이름 없음"),
                                        d.optString("road_address_name", d.optString("address_name", "")),
                                        d.optDouble("y"),
                                        d.optDouble("x"),
                                        d.optString("distance").toDoubleOrNull()
                                    )
                                )
                            }
                        }
                        if (!isEnd && page < maxPages) {
                            fetchPage(page + 1)
                        } else {
                            NavLogger.d(context, "[주변카테고리검색] 키워드 최종 결과 ${accumulated.size}건")
                            onResult(accumulated)
                        }
                    }
                }
            })
        }
        fetchPage(1)
    }

    // v: 재억 요청(2026-08-25) - 카테고리별로 검색 범위가 제각각이었던 걸 통일 -
    // 일반 카테고리(편의점/카페/약국 등)도 전기차충전소와 동일하게 최대 5페이지(75건)까지
    // 이어받음. #문제시 원복
    private fun performCategorySearchShared(
        context: Context,
        httpClient: OkHttpClient,
        restKey: String,
        categoryCode: String,
        lat: Double,
        lon: Double,
        onResult: (List<HistoryEntry>) -> Unit
    ) {
        val accumulated = mutableListOf<HistoryEntry>()
        fun fetchPage(page: Int) {
            NavLogger.d(context, "[주변카테고리검색] 요청 시작 code=$categoryCode page=$page lat=$lat lon=$lon")
            val url = "https://dapi.kakao.com/v2/local/search/category.json?category_group_code=$categoryCode" +
                "&x=$lon&y=$lat&radius=20000&sort=distance&size=15&page=$page"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "KakaoAK $restKey")
                .build()
            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    NavLogger.e(context, "[주변카테고리검색] 요청 실패: ${e.message}")
                    onResult(accumulated)
                }

                override fun onResponse(call: Call, response: okhttp3.Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            NavLogger.e(context, "[주변카테고리검색] 실패 code=${it.code}")
                            onResult(accumulated)
                            return@use
                        }
                        val json = JSONObject(it.body?.string() ?: "{}")
                        val documents = json.optJSONArray("documents")
                        val isEnd = json.optJSONObject("meta")?.optBoolean("is_end", true) ?: true
                        if (documents != null) {
                            for (idx in 0 until documents.length()) {
                                val d = documents.getJSONObject(idx)
                                accumulated.add(
                                    HistoryEntry(
                                        d.optString("place_name", "이름 없음"),
                                        d.optString("road_address_name", d.optString("address_name", "")),
                                        d.optDouble("y"),
                                        d.optDouble("x"),
                                        d.optString("distance").toDoubleOrNull()
                                    )
                                )
                            }
                        }
                        if (!isEnd && page < 5) {
                            fetchPage(page + 1)
                        } else {
                            NavLogger.d(context, "[주변카테고리검색] 최종 결과 ${accumulated.size}건")
                            onResult(accumulated)
                        }
                    }
                }
            })
        }
        fetchPage(1)
    }
}
