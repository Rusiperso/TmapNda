package com.tmap.nda.naver

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Directions 15 응답 JSON → [NaverDirectionsResult]. 네트워크와 무관한 순수 변환이라 단위 시험이 가능하다. */
object NaverDirectionsParser {

    fun parse(json: String): NaverDirectionsResult {
        val root = try {
            JsonParser.parseString(json).asJsonObject
        } catch (e: Exception) {
            return NaverDirectionsResult(-1, "응답 해석 실패: ${e.message}", emptyList())
        }
        val code = root.int("code", -1)
        val message = root.str("message")
        val routeObj = root.get("route")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return NaverDirectionsResult(code, message, emptyList())

        val routes = ArrayList<NaverRoute>()
        for ((option, value) in routeObj.entrySet()) {
            val arr = value.takeIf { it.isJsonArray }?.asJsonArray ?: continue
            for (el in arr) {
                parseRoute(option, el)?.let { routes.add(it) }
            }
        }
        return NaverDirectionsResult(code, message, routes)
    }

    private fun parseRoute(option: String, el: JsonElement): NaverRoute? {
        val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val path = ArrayList<LonLat>()
        o.get("path")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { p ->
            val a = p.asJsonArray
            if (a.size() >= 2) path.add(LonLat(a[0].asDouble, a[1].asDouble))
        }
        if (path.size < 2) return null

        val guides = o.arr("guide").mapNotNull { g ->
            val go = g.asJsonObject
            NaverGuide(
                pointIndex = go.int("pointIndex", -1).takeIf { it in 0 until path.size } ?: return@mapNotNull null,
                type = go.int("type", 0),
                instructions = go.str("instructions"),
                distance = go.int("distance", 0),
                durationMs = go.long("duration", 0L)
            )
        }.sortedBy { it.pointIndex }

        val sections = o.arr("section").map { s ->
            val so = s.asJsonObject
            NaverSection(
                pointIndex = so.int("pointIndex", 0),
                pointCount = so.int("pointCount", 0),
                distance = so.int("distance", 0),
                name = so.str("name"),
                congestion = so.int("congestion", 0),
                speed = so.int("speed", 0)
            )
        }.sortedBy { it.pointIndex }

        val summary = o.get("summary")?.takeIf { it.isJsonObject }?.asJsonObject
        return NaverRoute(
            option = option,
            path = path,
            guides = guides,
            sections = sections,
            distanceMeters = summary?.int("distance", 0) ?: 0,
            durationMs = summary?.long("duration", 0L) ?: 0L,
            tollFare = summary?.int("tollFare", 0) ?: 0,
            taxiFare = summary?.int("taxiFare", 0) ?: 0,
            fuelPrice = summary?.int("fuelPrice", 0) ?: 0
        )
    }

    private fun JsonObject.str(k: String): String =
        get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonObject.int(k: String, d: Int): Int =
        get(k)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() } ?: d

    private fun JsonObject.long(k: String, d: Long): Long =
        get(k)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() } ?: d

    private fun JsonObject.arr(k: String): JsonArray =
        get(k)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
}
