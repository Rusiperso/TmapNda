package com.tmap.nda

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

/**
 * v19.3.32: 재억 요청 - 실기기 테스트 중 앱 데이터가 통째로 날아가는 사고(pm clear로
 * 즐겨찾기/API키 전부 삭제)를 겪고 나서, "설정 백업/복원 기능을 넣어달라"는 요청.
 * TmapNdaPrefs(즐겨찾기 개수·API 키·화면 설정·UI 편집 위치 등 앱이 쓰는 SharedPreferences
 * 전부)를 JSON 파일 하나로 내보내고(공유 Intent로 재억이 원하는 곳에 보관), 다시
 * 그 파일을 골라 불러오면 그대로 복원됨. #문제시 원복
 *
 * v19.3.38: 재억 제보 - 복원해도 즐겨찾기(집/회사/fav1~10)가 하나도 안 돌아옴. 원인 확인됨:
 * 즐겨찾기는 QuickSlotStore가 TmapNdaPrefs가 아니라 완전히 별도 파일(TmapNdaQuickSlots)에
 * 저장하는데, 이 백업 기능은 처음부터 TmapNdaPrefs 한 파일만 알고 있었음(즐겨찾기 저장
 * 방식 자체를 안 보고 만듦). 이제 두 파일 다 백업/복원함. #문제시 원복
 */
object SettingsBackup {
    private const val PREFS_NAME = "TmapNdaPrefs"
    // v19.3.38: QuickSlotStore/ResumeGuidanceStore가 실제로 쓰는 파일 이름. #문제시 원복
    private const val QUICKSLOTS_PREFS_NAME = "TmapNdaQuickSlots"
    private const val BACKUP_FILE_NAME = "TmapNda_설정백업.json"

    // v19.3.35: 재억 제보 - 복원 직후 앱이 계속 강제종료됨. 원인 확인됨: UI 이동 위치처럼
    // Float로 저장된 값이 마침 정수와 똑같이 생긴 값(예: 570.0)이면, JSON에는 그냥 570으로
    // 찍혀서 원래 정수였는지 실수였는지 구분이 안 남. 복원할 때 "소수점 없으면 정수"로
    // 판단해 putInt로 잘못 저장했고, 그 값을 실제로 쓰는 자리(PanelDragHelper.restorePosition
    // 등)는 전부 getFloat()라 타입이 안 맞아 ClassCastException으로 죽었음. 내보낼 때
    // 원래 Float였던 키 목록을 이 마커 밑에 따로 적어두고, 불러올 때는 그 목록에 있는
    // 키는 JSON에 정수로 찍혀 있어도 무조건 Float로 되돌림. #문제시 원복
    private const val FLOAT_KEYS_MARKER = "__tmapnda_float_keys__"

    // v19.3.38: 백업 파일 구조를 "파일 이름 -> 그 안의 설정" 두 단계로 바꾸면서, 예전(v19.3.32~37)
    // 백업 파일(키가 바로 최상위에 있던 납작한 구조)과 구분하기 위한 표시. 이 마커가 있으면
    // 새 구조, 없으면 예전 구조(TmapNdaPrefs 하나만 있는 걸로 간주)로 읽음 - 예전에 만들어둔
    // 백업 파일도 계속 쓸 수 있게. #문제시 원복
    private const val FORMAT_VERSION_MARKER = "__tmapnda_backup_format__"
    private const val CURRENT_FORMAT_VERSION = 2

    private fun backupFile(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "backup")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, BACKUP_FILE_NAME)
    }

    /** 한 SharedPreferences 파일의 내용을 JSONObject 하나로 담아 돌려줌(Float였던 키 목록 포함). */
    private fun exportPrefsToJson(prefs: SharedPreferences): JSONObject {
        val json = JSONObject()
        val floatKeys = org.json.JSONArray()
        for ((key, value) in prefs.all) {
            if (value == null) continue
            // Set<String>(즐겨찾기 등 일부 항목이 쓸 수 있음)은 JSONArray로 변환해서 보존
            if (value is Set<*>) {
                val arr = org.json.JSONArray()
                value.forEach { arr.put(it) }
                json.put(key, arr)
            } else {
                json.put(key, value)
                if (value is Float) floatKeys.put(key)
            }
        }
        json.put(FLOAT_KEYS_MARKER, floatKeys)
        return json
    }

    /** exportPrefsToJson()으로 만든 JSONObject 하나를 실제 SharedPreferences에 되돌려 씀. 반영한 항목 수를 돌려줌. */
    private fun restoreJsonIntoPrefs(json: JSONObject, prefs: SharedPreferences): Int {
        val floatKeys = mutableSetOf<String>()
        json.optJSONArray(FLOAT_KEYS_MARKER)?.let { arr ->
            for (i in 0 until arr.length()) floatKeys.add(arr.getString(i))
        }
        val editor = prefs.edit()
        val keys = json.keys()
        var count = 0
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == FLOAT_KEYS_MARKER) continue
            if (key in floatKeys) {
                editor.putFloat(key, json.getDouble(key).toFloat())
                count++
                continue
            }
            when (val value = json.get(key)) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putInt(key, value.toInt())
                is Double -> editor.putFloat(key, value.toFloat())
                is String -> editor.putString(key, value)
                is org.json.JSONArray -> {
                    val set = mutableSetOf<String>()
                    for (i in 0 until value.length()) set.add(value.getString(i))
                    editor.putStringSet(key, set)
                }
                else -> continue
            }
            count++
        }
        editor.apply()
        return count
    }

    /** 지금 설정을 JSON 파일로 저장하고, 그 파일을 공유(이메일/드라이브 등)할 수 있는 Intent를 돌려줌. */
    fun exportAndShare(context: Context): Intent? {
        return try {
            val root = JSONObject()
            root.put(FORMAT_VERSION_MARKER, CURRENT_FORMAT_VERSION)
            root.put(PREFS_NAME, exportPrefsToJson(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)))
            root.put(QUICKSLOTS_PREFS_NAME, exportPrefsToJson(context.getSharedPreferences(QUICKSLOTS_PREFS_NAME, Context.MODE_PRIVATE)))

            val file = backupFile(context)
            file.writeText(root.toString(2))

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "TmapNda 설정 백업")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            NavLogger.e(context, "[설정백업] 내보내기 실패: ${e.message}")
            null
        }
    }

    /** 파일 선택기로 고른 백업 파일(uri)의 내용을 읽어 지금 설정에 덮어씀. 성공하면 true. */
    fun restoreFromUri(context: Context, uri: Uri): Boolean {
        return try {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: return false
            val root = JSONObject(text)

            val count = if (root.has(FORMAT_VERSION_MARKER)) {
                // v19.3.38부터의 새 구조: 파일 이름별로 나뉘어 있음
                var total = 0
                root.optJSONObject(PREFS_NAME)?.let {
                    total += restoreJsonIntoPrefs(it, context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
                }
                root.optJSONObject(QUICKSLOTS_PREFS_NAME)?.let {
                    total += restoreJsonIntoPrefs(it, context.getSharedPreferences(QUICKSLOTS_PREFS_NAME, Context.MODE_PRIVATE))
                }
                total
            } else {
                // v19.3.32~37 백업 파일(즐겨찾기 없이 TmapNdaPrefs 키가 최상위에 바로 있던 구조).
                // 계속 복원 가능하게 예전 방식 그대로 처리 - 단, 이 시절 백업엔 즐겨찾기 자체가
                // 애초에 안 담겨있었으므로 그건 복원 못 함(재억에게 안내 필요). #문제시 원복
                restoreJsonIntoPrefs(root, context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
            }

            NavLogger.d(context, "[설정백업] 복원 완료 (${count}개 항목)")
            true
        } catch (e: Exception) {
            NavLogger.e(context, "[설정백업] 복원 실패: ${e.message}")
            false
        }
    }
}

// v19.3.35: 위와 같은 이유로 이미 잘못된 타입(Int)으로 저장돼버린 폰은 이 수정 이후에도
// 그 값이 남아있는 한 계속 죽는다. getFloat() 대신 이걸로 읽으면 타입이 안 맞아도
// 죽지 않고, 읽은 값을 그 자리에서 바로 올바른 타입(Float)으로 고쳐 다시 저장해서
// 다음번부터는 정상적으로 읽히게 함(자가치유). #문제시 원복
fun android.content.SharedPreferences.getFloatSafe(key: String, default: Float): Float {
    return try {
        getFloat(key, default)
    } catch (e: ClassCastException) {
        val recovered = when (val raw = all[key]) {
            is Int -> raw.toFloat()
            is Long -> raw.toFloat()
            is Double -> raw.toFloat()
            is String -> raw.toFloatOrNull() ?: default
            else -> default
        }
        try {
            edit().putFloat(key, recovered).apply()
        } catch (_: Exception) {
        }
        recovered
    }
}
