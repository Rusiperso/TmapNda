package com.tmap.nda

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 티맵 응답(실제 호출에서 받은 모양을 줄인 것)이 카카오 모양으로 잘 바뀌는지. */
class TmapPoiConverterTest {

    private val keyword = """
    {"searchPoiInfo":{"totalCount": "28","count": "2","page":"1","pois":{"poi":[
      {"id":"352470","name":"양구군청","telNo":"033-481-2191","frontLat":"38.10957632","frontLon":"127.98999625",
       "noorLat":"38.10999294","noorLon":"127.98994068","upperAddrName":"강원","middleAddrName":"양구군",
       "lowerAddrName":"양구읍","detailAddrName":"하리","firstNo":"34","secondNo":"5","roadName":"관공서로",
       "firstBuildNo":"38","secondBuildNo":"0","radius":"0.0","upperBizName":"공공편의","middleBizName":"행정기관","lowerBizName":"도/시/구/군청"},
      {"id":"2","name":"양구군청주차장","noorLat":"38.1100","noorLon":"127.9900","radius":"0.0"}
    ]}}}
    """.trimIndent()

    private val around = """
    { "searchPoiInfo" : { "totalCount" : 4435, "count" : 3, "page" : 1, "pois" : { "poi" : [
      {"id":"1","name":"행복플러스가게","noorLat":"37.56656530","noorLon":"126.97815994","radius":"0.016","roadName":"세종대로","firstBuildNo":"110","secondBuildNo":"","upperAddrName":"서울","middleAddrName":"중구","lowerAddrName":"태평로1가","firstNo":"31"},
      {"id":"2","name":"카페아트주차장","noorLat":"37.56787069","noorLon":"126.97763217","radius":"0.099"},
      {"id":"3","name":"카페아트","noorLat":"37.56739853","noorLon":"126.97785439","radius":"0.099"}
    ] } } }
    """.trimIndent()

    @Test
    fun keyword_maps_fields_and_computes_distance() {
        val out = JsonParser.parseString(TmapPoiConverter.poisToKakao(keyword, 38.1096, 127.9900, 1, 15, false)).asJsonObject
        val docs = out.getAsJsonArray("documents")
        assertEquals("주차장은 걸러져 1건", 1, docs.size())
        val d = docs[0].asJsonObject
        assertEquals("양구군청", d["place_name"].asString)
        assertEquals("강원 양구군 양구읍 하리 34-5", d["address_name"].asString)
        assertEquals("강원 양구군 관공서로 38", d["road_address_name"].asString)
        assertEquals("127.98994068", d["x"].asString)
        assertEquals("38.10999294", d["y"].asString)
        assertTrue("거리는 수십 m: ${d["distance"].asString}", d["distance"].asString.toInt() in 0..100)
        assertFalse("28건 중 1쪽(15건)이면 아직 끝이 아님", out.getAsJsonObject("meta")["is_end"].asBoolean)
    }

    @Test
    fun keyword_keeps_parking_lots_when_asked() {
        val out = JsonParser.parseString(TmapPoiConverter.poisToKakao(keyword, null, null, 1, 15, true)).asJsonObject
        assertEquals(2, out.getAsJsonArray("documents").size())
        assertEquals("거리 정보가 없으면 빈 문자열", "", out.getAsJsonArray("documents")[0].asJsonObject["distance"].asString)
    }

    @Test
    fun last_page_is_end() {
        val out = JsonParser.parseString(TmapPoiConverter.poisToKakao(keyword, null, null, 2, 15, true)).asJsonObject
        assertTrue(out.getAsJsonObject("meta")["is_end"].asBoolean)
    }

    @Test
    fun around_keeps_order_and_filters_parking() {
        val out = JsonParser.parseString(TmapPoiConverter.poisToKakao(around, 37.5665, 126.978, 1, 15, false)).asJsonObject
        val docs = out.getAsJsonArray("documents")
        assertEquals(listOf("행복플러스가게", "카페아트"), docs.map { it.asJsonObject["place_name"].asString })
        val dist = docs.map { it.asJsonObject["distance"].asString.toInt() }
        assertTrue("가까운 순 유지: $dist", dist[0] <= dist[1])
    }

    @Test
    fun empty_or_garbage_gives_empty_documents() {
        for (body in listOf("", "not json", "{}", """{"error":{"id":"403"}}""")) {
            val out = JsonParser.parseString(TmapPoiConverter.poisToKakao(body, null, null, 1, 15, true)).asJsonObject
            assertEquals(0, out.getAsJsonArray("documents").size())
            assertTrue(out.getAsJsonObject("meta")["is_end"].asBoolean)
        }
    }

    @Test
    fun address_geocode_maps() {
        val body = """{"coordinateInfo":{"coordinate":[{"lat":"38.11004","lon":"127.990438","city_do":"강원","gu_gun":"양구군","eup_myun":"양구읍","ri":"하리","bunji":"34-5"},{"lat":"","lon":"","newLat":"","newLon":""}]}}"""
        val docs = JsonParser.parseString(TmapPoiConverter.addressToKakao(body)).asJsonObject.getAsJsonArray("documents")
        assertEquals(1, docs.size())
        val d = docs[0].asJsonObject
        assertEquals("강원 양구군 양구읍 하리 34-5", d["address_name"].asString)
        assertEquals("127.990438", d["x"].asString)
        assertEquals("38.11004", d["y"].asString)
    }

    @Test
    fun region_maps_to_h_type() {
        val body = """{"addressInfo":{"city_do":"서울특별시","gu_gun":"중구","adminDong":"명동","legalDong":"태평로1가"}}"""
        val d = JsonParser.parseString(TmapPoiConverter.regionToKakao(body)).asJsonObject.getAsJsonArray("documents")[0].asJsonObject
        assertEquals("H", d["region_type"].asString)
        assertEquals("중구", d["region_2depth_name"].asString)
        assertEquals("명동", d["region_3depth_name"].asString)
    }

    @Test
    fun category_codes() {
        assertEquals("주유소", TmapPoiConverter.tmapCategory("OL7"))
        assertEquals("충전소", TmapPoiConverter.tmapCategory("EV"))
        assertEquals("약국", TmapPoiConverter.tmapCategory("PM9"))
        assertEquals(null, TmapPoiConverter.tmapCategory("ZZZ"))
    }
}
