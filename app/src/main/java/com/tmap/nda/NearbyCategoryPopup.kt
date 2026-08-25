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

    // v: 왼쪽 목록 - SearchRanking.CATEGORY_KEYWORDS 중 재억이 실제로 자주 쓸 법한 것만
    // 순서대로 고정. 순서 자체가 UI라 SearchRanking과 별도로 여기서 관리. #문제시 원복
    private val CATEGORIES = listOf(
        CategoryItem("편의점", "CS2"),
        CategoryItem("주유소", "OL7"),
        CategoryItem("전기차 충전", "EV"),
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

        fun renderResults(hits: List<HistoryEntry>) {
            rightList.removeAllViews()
            if (hits.isEmpty()) {
                rightList.addView(makeRow(context, "검색 결과 없음", null, false, 16f) {})
                return
            }
            hits.forEach { entry ->
                val distText = entry.distanceMeters?.let { SearchRanking.formatDistance(it) }
                rightList.addView(makeRow(context, entry.name, distText, true, 14f) {
                    dialog.dismiss()
                    onPick(entry)
                })
            }
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

        fun renderGasStations(stations: List<OpinetHelper.GasStation>) {
            rightList.removeAllViews()
            if (stations.isEmpty()) {
                rightList.addView(makeRow(context, "검색 결과 없음", null, false, 16f) {})
                return
            }
            sortByPriceAndDistance(stations).forEach { st ->
                val distText = SearchRanking.formatDistance(st.distanceMeters)
                val fuelLabel = OpinetHelper.FUEL_TYPES.firstOrNull { it.second == OpinetHelper.savedFuelType(context) }?.first ?: "가격"
                val priceText = st.gasolinePrice?.let { "$fuelLabel ${it}원" }
                rightList.addView(makeGasRow(context, "${st.brandName} ${st.name}", distText, priceText) {
                    dialog.dismiss()
                    onPick(HistoryEntry(st.name, "", st.lat, st.lon, st.distanceMeters))
                })
            }
        }

        fun runGasSearch(brandCode: String?) {
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
                return
            }
            val prodcd = OpinetHelper.savedFuelType(context) ?: OpinetHelper.FUEL_TYPES[0].second
            rightList.removeAllViews()
            rightList.addView(makeRow(context, "검색 중...", null, false, 16f) {})
            OpinetHelper.fetchNearby(context, httpClient, opinetKey, curLat, curLon, brandCode, prodcd) { stations ->
                (context as? android.app.Activity)?.runOnUiThread { renderGasStations(stations) }
            }
        }

        // v: 신규기능(유종 선택) - 처음 한 번만 물어보고 계속 그 유종으로 검색됨. 재억 요청 -
        // 다시 바꿀 수 있게 브랜드 선택 창 맨 위에 "유종 변경" 항목을 항상 넣어둠. #문제시 원복
        fun showBrandChooserForGas() {
            val currentFuelLabel = OpinetHelper.FUEL_TYPES.firstOrNull { it.second == OpinetHelper.savedFuelType(context) }?.first
            showBrandChooser(context, currentFuelLabel) { brandCode -> runGasSearch(brandCode) }
        }

        fun runGasSearchFlow() {
            if (OpinetHelper.savedFuelType(context) == null) {
                showFuelChooser(context) { prodcd ->
                    OpinetHelper.saveFuelType(context, prodcd)
                    showBrandChooserForGas()
                }
            } else {
                showBrandChooserForGas()
            }
        }

        // v: 신규기능(전기차 충전소) - 한국환경공단 API. 오피넷과 별개 키(ev_charger_api_key)
        // 필요. 카카오 REST 키로 좌표->시군구코드 변환 후 조회. #문제시 원복
        fun renderChargers(stations: List<EvChargerHelper.ChargerStation>) {
            rightList.removeAllViews()
            if (stations.isEmpty()) {
                rightList.addView(makeRow(context, "검색 결과 없음", null, false, 16f) {})
                return
            }
            stations.take(30).forEach { st ->
                val distText = SearchRanking.formatDistance(st.distanceMeters)
                val statusText = "${st.chargerType} · ${st.statusText}"
                rightList.addView(makeGasRow(context, st.name, distText, statusText) {
                    dialog.dismiss()
                    onPick(HistoryEntry(st.name, "", st.lat, st.lon, st.distanceMeters))
                })
            }
        }

        fun runEvSearch() {
            val evKey = context.getSharedPreferences("TmapNdaPrefs", Context.MODE_PRIVATE)
                .getString("ev_charger_api_key", null)
            if (evKey.isNullOrBlank()) {
                android.widget.Toast.makeText(context, "전기차 충전소 API 키를 설정에서 먼저 입력하세요 (공공데이터포털 각자 발급)", android.widget.Toast.LENGTH_LONG).show()
                rightList.removeAllViews()
                rightList.addView(makeRow(context, "충전소 API 키가 없습니다", null, false, 14f) {})
                return
            }
            rightList.removeAllViews()
            rightList.addView(makeRow(context, "검색 중...", null, false, 16f) {})
            EvChargerHelper.fetchNearby(context, httpClient, evKey, restKey, curLat, curLon) { stations ->
                (context as? android.app.Activity)?.runOnUiThread { renderChargers(stations) }
            }
        }

        fun runSearch(item: CategoryItem) {
            when (item.label) {
                "주유소" -> { runGasSearchFlow(); return }
                "전기차 충전" -> { runEvSearch(); return }
            }
            rightList.removeAllViews()
            rightList.addView(makeRow(context, "검색 중...", null, false, 16f) {})
            performCategorySearchShared(context, httpClient, restKey, item.code, curLat, curLon) { hits ->
                (context as? android.app.Activity)?.runOnUiThread { renderResults(hits) }
            }
        }

        CATEGORIES.forEachIndexed { index, item ->
            val row = makeRow(context, item.label, null, true, 16f) { runSearch(item) }
            leftList.addView(row)
            if (index == 0) runSearch(item) // 첫 카테고리 기본 선택
        }

        dialog.show()
    }

    /** 주유소 선택 시 브랜드 고르는 작은 팝업. 맨 위에 "유종 변경" 항목을 항상 넣어둠. */
    private fun showBrandChooser(context: Context, currentFuelLabel: String?, onPick: (String?) -> Unit) {
        val fuelChangeLabel = "유종 변경 (현재: ${currentFuelLabel ?: "미선택"})"
        val labels = (listOf(fuelChangeLabel) + OpinetHelper.BRANDS.map { it.first }).toTypedArray()
        AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("주유소 브랜드 선택")
            .setItems(labels) { _, which ->
                if (which == 0) {
                    OpinetHelper.clearFuelType(context)
                    showFuelChooser(context) { prodcd ->
                        OpinetHelper.saveFuelType(context, prodcd)
                        showBrandChooser(context, OpinetHelper.FUEL_TYPES.firstOrNull { it.second == prodcd }?.first, onPick)
                    }
                } else {
                    onPick(OpinetHelper.BRANDS[which - 1].second)
                }
            }
            .show()
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
        // v: 재억 제보(2026-08-25) - 배경(검정)에 글자색 지정을 안 해서 안 보였음. 흰색으로
        // 명시. 거리는 왼쪽에 고정폭으로 먼저 두고(가독성), 이름은 남는 공간을 채우게 함.
        // 예상 소요시간(거리 기반 대략치, 시속 40km 가정)을 노란색으로 같이 표시. #문제시 원복
        if (subtitle != null) {
            val subtitleView = TextView(context).apply {
                text = subtitle
                textSize = 13f
                setTextColor(android.graphics.Color.WHITE)
                minWidth = dp(context, 56)
            }
            row.addView(subtitleView)
        }
        val titleView = TextView(context).apply {
            text = title
            textSize = titleSize
            setTextColor(android.graphics.Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(titleView)
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
    private fun performCategorySearchShared(
        context: Context,
        httpClient: OkHttpClient,
        restKey: String,
        categoryCode: String,
        lat: Double,
        lon: Double,
        onResult: (List<HistoryEntry>) -> Unit
    ) {
        val url = "https://dapi.kakao.com/v2/local/search/category.json?category_group_code=$categoryCode" +
            "&x=$lon&y=$lat&radius=20000&sort=distance"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "KakaoAK $restKey")
            .build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                NavLogger.e(context, "[주변카테고리검색] 요청 실패: ${e.message}")
                onResult(emptyList())
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        NavLogger.e(context, "[주변카테고리검색] 실패 code=${it.code}")
                        onResult(emptyList())
                        return@use
                    }
                    val json = JSONObject(it.body?.string() ?: "{}")
                    val documents = json.optJSONArray("documents")
                    if (documents == null || documents.length() == 0) {
                        onResult(emptyList())
                        return@use
                    }
                    val hits = (0 until documents.length()).map { idx ->
                        val d = documents.getJSONObject(idx)
                        HistoryEntry(
                            d.optString("place_name", "이름 없음"),
                            d.optString("road_address_name", d.optString("address_name", "")),
                            d.optDouble("y"),
                            d.optDouble("x"),
                            d.optString("distance").toDoubleOrNull()
                        )
                    }
                    onResult(hits)
                }
            }
        })
    }
}
