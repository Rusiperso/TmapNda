package com.tmap.nda

/**
 * 목적지 검색 결과 정렬/카테고리 판별 로직 - MapActivity(Tmap 화면)와 KakaoNaviActivity(카카오
 * 화면)가 완전히 똑같은 규칙을 쓰도록 여기 하나로 모음. 예전엔 두 파일에 똑같은 코드를
 * 각각 복사해서 넣다 보니 한쪽만 고치고 한쪽은 안 고치는 실수가 날 수 있었음. #문제시 원복
 */
object SearchRanking {

    /**
     * v10.9-4: "스타벅스송탄점"처럼 띄어쓰기 없이 붙여 쳐도, 그 글자들이 순서만 지키며
     * 이름 안에 다 들어있으면(중간에 다른 글자가 껴도 됨) 인정함. 예전엔 검색어를 띄어쓰기
     * 기준 낱말로 나눠서 확인했는데, 그 방식은 띄어쓰기를 안 하면 아예 안 먹혔음
     * (재억 지적). 이 방식은 띄어쓰기 여부와 무관하게 동일하게 동작함. #문제시 원복
     */
    fun isOrderedSubsequence(query: String, target: String): Boolean {
        if (query.isEmpty()) return false
        var qi = 0
        for (ch in target) {
            if (qi < query.length && ch == query[qi]) qi++
            if (qi == query.length) return true
        }
        return qi == query.length
    }

    /**
     * v10.9-4: "주유소", "약국", "편의점"처럼 상호명이 아니라 "종류"로 찾는 검색은,
     * 카카오의 일반 키워드검색보다 종류 전용 검색(category.json)이 훨씬 정확하고 결과도
     * 안정적임. 재억이 자주 쓸 만한 종류만 우선 등록해둠 - 더 필요하면 추가 가능. #문제시 원복
     */
    private val CATEGORY_KEYWORDS: Map<String, String> = mapOf(
        "편의점" to "CS2",
        "주유소" to "OL7",
        "충전소" to "OL7",
        "주차장" to "PK6",
        "은행" to "BK9",
        "약국" to "PM9",
        "병원" to "HP8",
        "응급실" to "HP8",
        "마트" to "MT1",
        "대형마트" to "MT1",
        "카페" to "CE7",
        "지하철역" to "SW8",
        "지하철" to "SW8",
        "학교" to "SC4",
        "숙박" to "AD5",
        "호텔" to "AD5",
        "모텔" to "AD5"
    )

    /** 검색어가 등록된 "종류" 이름과 정확히 같으면 그 카테고리 코드를 돌려주고, 아니면 null. */
    fun categoryGroupCodeFor(query: String): String? {
        val normalized = query.trim().replace(" ", "")
        return CATEGORY_KEYWORDS[normalized]
    }

    /**
     * 검색 결과 하나에 대한 정렬 우선순위 키. (이름일치단계, DT우선여부, 이름길이차이) 순으로
     * 비교하면 되고, 숫자가 작을수록 위로 올라감. 최종적으로는 이 키 다음에 거리를 마지막
     * 기준으로 붙여서 정렬함.
     *
     * 이름일치단계: 0=완전히 같은 이름, 1=검색어로 시작(부속시설류), 2=순서만 지키며 다
     * 포함(붙여쓰기 포함), 3=그 외.
     * v10.9-2: "DT"(드라이브스루)가 붙은 이름은 1·2단계 안에서도 길이와 무관하게 항상
     * 먼저 오도록 함(재억: 프랜차이즈는 DT점을 우선하고 싶어함). #문제시 원복
     */
    fun rankKey(query: String, placeName: String): Triple<Int, Int, Int> {
        val normalizedQuery = query.trim().replace(" ", "")
        val normalizedName = placeName.trim().replace(" ", "")
        val nameTier = when {
            normalizedName == normalizedQuery -> 0
            normalizedName.startsWith(normalizedQuery) -> 1
            isOrderedSubsequence(normalizedQuery, normalizedName) -> 2
            else -> 3
        }
        val dtPreferred = if (normalizedName.contains("DT", ignoreCase = true)) 0 else 1
        val extraLength = normalizedName.length - normalizedQuery.length
        return Triple(nameTier, dtPreferred, extraLength)
    }

    /**
     * v11.1: 검색 결과 목록에 거리도 같이 보여줌(재억 요청). 1km 미만은 미터로,
     * 1km 이상은 소수점 한 자리 킬로미터로 표시. 거리 정보가 없으면(위치를 못 구했거나
     * 카카오가 안 줬을 때) null.
     */
    fun formatDistance(distanceMeters: Double?): String? {
        if (distanceMeters == null || distanceMeters == Double.MAX_VALUE) return null
        return if (distanceMeters < 1000) {
            "${distanceMeters.toInt()}m"
        } else {
            String.format("%.1fkm", distanceMeters / 1000)
        }
    }
}
