package com.tmap.nda.naver

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.tmap.nda.HistoryEntry
import com.tmap.nda.NavLogger
import java.io.File

/**
 * 인터넷이 없을 때 폰 안의 목록에서 장소·주소를 찾는다. 결과는 서버 검색과 같은 [HistoryEntry]라 검색 결과 창을 그대로 쓴다.
 *  - valhalla/juso.sqlite : 행정안전부 위치정보요약DB(도로명주소 640만 건, 건물이름 70만 건)
 *  - valhalla/poi.sqlite  : OpenStreetMap에서 뽑은 이름 있는 장소 56만 곳
 */
object OfflinePlaceSearch {

    @Volatile private var poiDb: SQLiteDatabase? = null
    @Volatile private var jusoDb: SQLiteDatabase? = null

    private fun dir(context: Context) = File(context.getExternalFilesDir(null), "valhalla")

    fun available(context: Context): Boolean = File(dir(context), "poi.sqlite").exists() || File(dir(context), "juso.sqlite").exists()

    private fun open(context: Context, name: String): SQLiteDatabase? {
        val f = File(dir(context), name)
        if (!f.exists()) return null
        return try {
            SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: Throwable) {
            NavLogger.e(context, "[오프라인검색] $name 열기 실패: ${e.message}")
            null
        }
    }

    private fun poi(context: Context): SQLiteDatabase? = poiDb ?: synchronized(this) { poiDb ?: open(context, "poi.sqlite").also { poiDb = it } }
    private fun juso(context: Context): SQLiteDatabase? = jusoDb ?: synchronized(this) { jusoDb ?: open(context, "juso.sqlite").also { jusoDb = it } }

    private class Hit(val name: String, val addr: String, val lat: Double, val lon: Double, val rank: Int, val dist: Double)

    /** 이름에 [query]가 들어간 장소, 도로명주소(예: "송탄로361번길 29")를 찾는다. 같은 값이면 가까운 순. */
    fun search(context: Context, query: String, lat: Double?, lon: Double?): List<HistoryEntry> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val t0 = System.currentTimeMillis()
        val hits = ArrayList<Hit>()
        hits.addAll(searchAddress(context, q, lat, lon))
        val addrCount = hits.size
        hits.addAll(searchPlaces(context, q, lat, lon))
        val out = hits.sortedWith(compareBy<Hit>({ it.rank }, { it.dist })).take(30).map {
            HistoryEntry(it.name, it.addr, it.lat, it.lon, if (lat != null) it.dist else null)
        }
        NavLogger.d(context, "[오프라인검색] '$q' → 주소 ${addrCount}건 + 장소 ${hits.size - addrCount}건 중 ${out.size}건 보여줌 (${System.currentTimeMillis() - t0}ms)")
        return out
    }

    /**
     * 인터넷이 되는 때도 폰 안 자료를 먼저 쓴다(티맵 검색에 기대지 않기). 자료가 없거나 종류 검색("편의점")이거나
     * 찾은 게 없으면 null을 돌려줘서 기존 온라인 검색으로 넘어간다.
     */
    fun localFirst(context: Context, query: String, lat: Double?, lon: Double?): List<HistoryEntry>? {
        if (!available(context)) return null
        if (com.tmap.nda.SearchRanking.categoryGroupCodeFor(query.trim()) != null) return null
        val r = search(context, query, lat, lon)
        return r.takeIf { it.isNotEmpty() }
    }

    /** 폰 안 결과 중 이름이 검색어와 똑같은 게 있는지(띄어쓰기는 무시). 도로명주소 결과는 이미 정확히 맞은 것이라 똑같은 것으로 본다. */
    fun hasExact(query: String, hits: List<HistoryEntry>): Boolean {
        val q = query.replace(" ", "")
        return hits.any { it.name.replace(" ", "").equals(q, true) }
    }

    /**
     * 지도에서 찍은 자리 주변(약 60m)에서 가장 가까운 행안부 건물 주소를 찾는다. 네트워크 없이 폰 안 자료만 쓰며 1~2초 걸릴 수 있어서
     * 백그라운드 스레드에서 불러야 한다. 못 찾으면 null.
     */
    fun nearestAddress(context: Context, lat: Double, lon: Double): HistoryEntry? {
        val d = juso(context) ?: return null
        val dLat = 0.00055; val dLon = 0.0007
        return try {
            var best: HistoryEntry? = null; var bestDist = Double.MAX_VALUE
            d.rawQuery(
                "SELECT r.name, r.area, b.main, b.sub, b.ug, b.lat, b.lon FROM bld b JOIN road r ON r.id = b.road " +
                    "WHERE b.lat BETWEEN ? AND ? AND b.lon BETWEEN ? AND ? LIMIT 400",
                arrayOf(((lat - dLat) * 1e6).toLong().toString(), ((lat + dLat) * 1e6).toLong().toString(),
                    ((lon - dLon) * 1e6).toLong().toString(), ((lon + dLon) * 1e6).toLong().toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val la = c.getInt(5) / 1e6; val lo = c.getInt(6) / 1e6
                    val dist = approxMeters(lat, lon, la, lo)
                    if (dist < bestDist) {
                        bestDist = dist
                        val m = c.getInt(2); val s = c.getInt(3)
                        best = HistoryEntry(c.getString(0) + " " + (if (c.getInt(4) == 1) "지하 " else "") + m + (if (s > 0) "-$s" else ""),
                            c.getString(1), la, lo)
                    }
                }
            }
            best
        } catch (e: Throwable) { null }
    }

    /** 도로명+번호처럼 보이는 검색어일 때 폰 안 주소 자료에서 찾은 결과(없거나 주소 자료가 없으면 빈 목록). 온라인 검색에 보태 쓴다. */
    fun addressHits(context: Context, query: String, lat: Double?, lon: Double?): List<HistoryEntry> {
        val q = query.trim()
        if (q.isEmpty() || !File(dir(context), "juso.sqlite").exists()) return emptyList()
        return try {
            searchAddress(context, q, lat, lon).sortedBy { it.dist }.take(10).map {
                HistoryEntry(it.name, it.addr, it.lat, it.lon, if (lat != null) it.dist else null)
            }
        } catch (e: Throwable) { emptyList() }
    }

    /** 폰 안 주소 결과를 맨 위에 놓고, 온라인 결과 중 같은 자리(40m 안)는 빼서 합친다. */
    fun mergeAddress(address: List<HistoryEntry>, online: List<HistoryEntry>): List<HistoryEntry> {
        if (address.isEmpty()) return online
        val rest = online.filter { o -> address.none { a -> approxMeters(a.lat, a.lon, o.lat, o.lon) < 40.0 } }
        return address + rest
    }

    // ---------- 주소 ----------

    private val numRe = Regex("^(\\d+)(?:-(\\d+))?$")
    private val roadRe = Regex("(.*[로길])(\\d+)?$")

    /**
     * "송탄로361번길 29", "평택시 송탄로 29", "세종대로 110"처럼 도로명 + 번호를 해석한다.
     * 도로 이름은 '로'나 '길'로 끝나는 말 하나, 번호는 숫자(또는 숫자-숫자), 나머지 말은 지역 이름(시·군·구·동)으로 본다.
     */
    private fun searchAddress(context: Context, q: String, lat: Double?, lon: Double?): List<Hit> {
        val d = juso(context) ?: return emptyList()
        val cleaned = q.replace("번지", "").replace("지하", "").trim()
        val tokens = cleaned.split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        // "송탄로361번길29" 처럼 붙여 쓴 번호는 마지막 숫자를 떼어낸다
        var main = -1; var sub = 0
        val numIdx = tokens.indexOfLast { numRe.matches(it) }
        if (numIdx >= 0) {
            val m = numRe.find(tokens[numIdx])!!
            main = m.groupValues[1].toInt(); sub = m.groupValues[2].toIntOrNull() ?: 0
            tokens.removeAt(numIdx)
        } else {
            val m = Regex("^(.*[로길])(\\d+)(?:-(\\d+))?$").find(tokens.lastOrNull().orEmpty())
            if (m != null && tokens.isNotEmpty()) {
                main = m.groupValues[2].toInt(); sub = m.groupValues[3].toIntOrNull() ?: 0
                tokens[tokens.size - 1] = m.groupValues[1]
            }
        }
        val roadIdx = tokens.indexOfFirst { it.endsWith("로") || it.endsWith("길") }
        if (roadIdx < 0) return emptyList()
        val road = tokens.removeAt(roadIdx)
        val areaTokens = tokens
        val hits = ArrayList<Hit>()
        try {
            val sql = if (main >= 0)
                "SELECT r.name, r.area, b.main, b.sub, b.ug, b.lat, b.lon FROM road r JOIN bld b ON b.road = r.id " +
                    "WHERE r.name = ? AND b.main = ?" + (if (sub > 0) " AND b.sub = ?" else "") + " LIMIT 300"
            else
                "SELECT r.name, r.area, MIN(b.main), 0, 0, b.lat, b.lon FROM road r JOIN bld b ON b.road = r.id " +
                    "WHERE r.name LIKE ? GROUP BY r.id LIMIT 300"
            val args = if (main >= 0) (if (sub > 0) arrayOf(road, main.toString(), sub.toString()) else arrayOf(road, main.toString()))
                       else arrayOf("$road%")
            d.rawQuery(sql, args).use { c ->
                while (c.moveToNext()) {
                    val area = c.getString(1)
                    val m = c.getInt(2); val s = c.getInt(3); val ug = c.getInt(4)
                    val la = c.getInt(5) / 1e6; val lo = c.getInt(6) / 1e6
                    val areaMatch = areaTokens.isEmpty() || areaTokens.all { area.contains(it) }
                    if (!areaMatch) continue
                    val label = c.getString(0) + (if (main >= 0) " " + (if (ug == 1) "지하 " else "") + m + (if (s > 0) "-$s" else "") else "")
                    val dist = if (lat != null && lon != null) approxMeters(lat, lon, la, lo) else 0.0
                    hits.add(Hit(label, area, la, lo, if (main >= 0) 0 else 1, dist))
                }
            }
        } catch (e: Throwable) {
            NavLogger.e(context, "[오프라인검색] 주소 검색 실패: ${e.message}")
        }
        return hits
    }

    // ---------- 장소 이름 ----------

    private fun searchPlaces(context: Context, q: String, lat: Double?, lon: Double?): List<Hit> {
        // 여러 단어면 모든 단어가 (이름 또는 주소)에 들어 있는 곳만(예: "파주 롯데마트")
        val tokens = q.replace("%", "").replace("_", "").split(Regex("\\s+")).filter { it.isNotEmpty() }.ifEmpty { listOf(q) }
        val hits = ArrayList<Hit>()
        fun rank(name: String) = when {
            name.equals(q, true) -> 2
            name.startsWith(q, true) -> 3
            else -> 4
        }
        try {
            poi(context)?.rawQuery(
                "SELECT name, addr, lat, lon FROM poi WHERE " + tokens.joinToString(" AND ") { "(name LIKE ? OR name_en LIKE ? OR addr LIKE ?)" } + " LIMIT 3000",
                tokens.flatMap { t -> listOf("%$t%", "%$t%", "%$t%") }.toTypedArray()
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0)
                    val la = c.getDouble(2); val lo = c.getDouble(3)
                    val dist = if (lat != null && lon != null) approxMeters(lat, lon, la, lo) else 0.0
                    hits.add(Hit(name, c.getString(1).orEmpty(), la, lo, rank(name), dist))
                }
            }
            // 건물 이름(아파트, 상가 등): 행정안전부 주소 자료에 이름이 있는 건물
            juso(context)?.rawQuery(
                "SELECT name, area, road, num, lat, lon FROM bname WHERE " + tokens.joinToString(" AND ") { "(name LIKE ? OR area LIKE ? OR road LIKE ?)" } + " LIMIT 3000",
                tokens.flatMap { t -> listOf("%$t%", "%$t%", "%$t%") }.toTypedArray()
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0)
                    val la = c.getInt(4) / 1e6; val lo = c.getInt(5) / 1e6
                    val dist = if (lat != null && lon != null) approxMeters(lat, lon, la, lo) else 0.0
                    hits.add(Hit(name, c.getString(1) + " " + c.getString(2) + " " + c.getString(3), la, lo, rank(name), dist))
                }
            }
        } catch (e: Throwable) {
            NavLogger.e(context, "[오프라인검색] 장소 검색 실패: ${e.message}")
        }
        return hits
    }

    private fun approxMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dy = (lat2 - lat1) * 111_000.0
        val dx = (lon2 - lon1) * 111_000.0 * Math.cos(Math.toRadians((lat1 + lat2) / 2))
        return Math.sqrt(dx * dx + dy * dy)
    }
}
