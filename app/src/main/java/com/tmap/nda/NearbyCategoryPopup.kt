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
                dp(context, 320)
            )
        }

        val leftList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(context, 96), LinearLayout.LayoutParams.MATCH_PARENT)
        }
        val leftScroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 96), LinearLayout.LayoutParams.MATCH_PARENT)
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
                rightList.addView(makeRow(context, "검색 결과 없음", null, false) {})
                return
            }
            hits.forEach { entry ->
                val distText = entry.distanceMeters?.let { SearchRanking.formatDistance(it) }
                rightList.addView(makeRow(context, entry.name, distText, true) {
                    dialog.dismiss()
                    onPick(entry)
                })
            }
        }

        fun runSearch(item: CategoryItem) {
            rightList.removeAllViews()
            rightList.addView(makeRow(context, "검색 중...", null, false) {})
            performCategorySearchShared(context, httpClient, restKey, item.code, curLat, curLon) { hits ->
                (context as? android.app.Activity)?.runOnUiThread { renderResults(hits) }
            }
        }

        CATEGORIES.forEachIndexed { index, item ->
            val row = makeRow(context, item.label, null, true) { runSearch(item) }
            leftList.addView(row)
            if (index == 0) runSearch(item) // 첫 카테고리 기본 선택
        }

        dialog.show()
    }

    private fun makeRow(context: Context, title: String, subtitle: String?, clickable: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
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
            textSize = 14f
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
