package com.tmap.nda

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 재억 요청(2026-11-08) - 즐겨찾기 폴더. 폴더 수·장소 수 제한 없음, 이름은 전부 사용자가 직접 정함.
 * 장소 자체는 예전처럼 [QuickSlotStore]에 슬롯 이름(키)으로 저장하고, 이 저장소는
 * "어느 폴더에 어떤 슬롯이 어떤 순서로 있는지"만 기억한다.
 * 처음 쓸 때 예전 즐겨찾기(fav1~fav10)에 등록된 게 있으면 "기본" 폴더 하나로 그대로 옮겨 담는다(데이터 이동 없음). #문제시 원복
 */
object FavoriteFolderStore {
    data class Folder(val id: String, val name: String, val slots: List<String>)

    private const val KEY = "folders"
    private fun prefs(context: Context) =
        context.getSharedPreferences("TmapNdaFavFolders", Context.MODE_PRIVATE)

    @Synchronized
    fun list(context: Context): List<Folder> {
        val raw = prefs(context).getString(KEY, null)
        if (raw == null) {
            val legacy = QuickSlotStore.favoriteSlots(QuickSlotStore.MAX_FAVORITE_SLOTS)
                .filter { QuickSlotStore.get(context, it) != null }
            val init = if (legacy.isEmpty()) emptyList() else listOf(Folder("default", "기본", legacy))
            write(context, init)
            return init
        }
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val sl = o.optJSONArray("slots") ?: JSONArray()
                Folder(o.getString("id"), o.optString("name"), (0 until sl.length()).map { sl.getString(it) })
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun write(context: Context, folders: List<Folder>) {
        val arr = JSONArray()
        folders.forEach { f ->
            arr.put(JSONObject().put("id", f.id).put("name", f.name).put("slots", JSONArray(f.slots)))
        }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    @Synchronized
    private fun mutate(context: Context, block: (MutableList<Folder>) -> Unit) {
        val l = list(context).toMutableList()
        block(l)
        write(context, l)
    }

    fun get(context: Context, id: String): Folder? = list(context).firstOrNull { it.id == id }

    fun createFolder(context: Context, name: String): Folder {
        val f = Folder("d" + java.lang.Long.toString(System.nanoTime(), 36), name.trim(), emptyList())
        mutate(context) { it.add(f) }
        return f
    }

    fun renameFolder(context: Context, id: String, name: String) = mutate(context) { l ->
        val i = l.indexOfFirst { it.id == id }
        if (i >= 0) l[i] = l[i].copy(name = name.trim())
    }

    /** 폴더를 지우면 안의 장소도 함께 지운다. */
    fun deleteFolder(context: Context, id: String) {
        val f = get(context, id) ?: return
        mutate(context) { l -> l.removeAll { it.id == id } }
        f.slots.forEach { QuickSlotStore.delete(context, it) }
    }

    fun moveFolder(context: Context, from: Int, to: Int) = mutate(context) { l ->
        if (from in l.indices && to in l.indices) l.add(to, l.removeAt(from))
    }

    /** 새 장소를 폴더 맨 끝에 넣고 슬롯 이름을 돌려준다. */
    fun addEntry(context: Context, folderId: String, entry: HistoryEntry): String {
        val slot = "fx" + java.lang.Long.toString(System.currentTimeMillis(), 36) + (0..99).random()
        QuickSlotStore.save(context, slot, entry)
        mutate(context) { l ->
            val i = l.indexOfFirst { it.id == folderId }
            if (i >= 0) l[i] = l[i].copy(slots = l[i].slots + slot)
        }
        return slot
    }

    /** 검색해서 등록할 빈 슬롯을 폴더 끝에 미리 붙여 이름을 돌려준다(등록 안 하면 화면에서 알아서 빠짐). */
    fun newSlot(context: Context, folderId: String): String {
        val slot = "fx" + java.lang.Long.toString(System.currentTimeMillis(), 36) + (0..99).random()
        attachSlot(context, folderId, slot)
        return slot
    }

    /** 이미 만든 슬롯(예: 검색 등록으로 저장된 것)을 폴더에 붙인다. */
    fun attachSlot(context: Context, folderId: String, slot: String) = mutate(context) { l ->
        val i = l.indexOfFirst { it.id == folderId }
        if (i >= 0 && slot !in l[i].slots) l[i] = l[i].copy(slots = l[i].slots + slot)
    }

    fun setOrder(context: Context, folderId: String, slots: List<String>) = mutate(context) { l ->
        val i = l.indexOfFirst { it.id == folderId }
        if (i >= 0) l[i] = l[i].copy(slots = slots)
    }

    fun moveSlotToFolder(context: Context, slot: String, toFolderId: String) = mutate(context) { l ->
        for (i in l.indices) l[i] = l[i].copy(slots = l[i].slots - slot)
        val j = l.indexOfFirst { it.id == toFolderId }
        if (j >= 0) l[j] = l[j].copy(slots = l[j].slots + slot)
    }

    /** 예전 방식(fav번호)으로 저장된 장소가 어느 폴더에도 없으면 "기본" 폴더에 넣어 폴더 화면에서 보이게 한다. */
    fun onSlotSaved(context: Context, slot: String) {
        if (!slot.startsWith("fav")) return
        val l = list(context)
        if (l.any { slot in it.slots }) return
        mutate(context) { cur ->
            val i = cur.indexOfFirst { it.id == "default" }
            if (i >= 0) cur[i] = cur[i].copy(slots = cur[i].slots + slot)
            else cur.add(0, Folder("default", "기본", listOf(slot)))
        }
    }

    /** [QuickSlotStore.delete]에서 불러서, 어디서 지워도 폴더 목록에서 같이 빠지게 한다. */
    fun onSlotDeleted(context: Context, slot: String) {
        if (list(context).none { slot in it.slots }) return
        mutate(context) { l -> for (i in l.indices) l[i] = l[i].copy(slots = l[i].slots - slot) }
    }
}
