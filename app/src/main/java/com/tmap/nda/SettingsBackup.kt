package com.tmap.nda

import android.content.Context
import android.content.Intent
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
 */
object SettingsBackup {
    private const val PREFS_NAME = "TmapNdaPrefs"
    private const val BACKUP_FILE_NAME = "TmapNda_설정백업.json"

    // v19.3.35: 재억 제보 - 복원 직후 앱이 계속 강제종료됨. 원인 확인됨: UI 이동 위치처럼
    // Float로 저장된 값이 마침 정수와 똑같이 생긴 값(예: 570.0)이면, JSON에는 그냥 570으로
    // 찍혀서 원래 정수였는지 실수였는지 구분이 안 남. 복원할 때 "소수점 없으면 정수"로
    // 판단해 putInt로 잘못 저장했고, 그 값을 실제로 쓰는 자리(PanelDragHelper.restorePosition
    // 등)는 전부 getFloat()라 타입이 안 맞아 ClassCastException으로 죽었음. 내보낼 때
    // 원래 Float였던 키 목록을 이 마커 밑에 따로 적어두고, 불러올 때는 그 목록에 있는
    // 키는 JSON에 정수로 찍혀 있어도 무조건 Float로 되돌림. #문제시 원복
    private const val FLOAT_KEYS_MARKER = "__tmapnda_float_keys__"

    private fun backupFile(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "backup")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, BACKUP_FILE_NAME)
    }

    /** 지금 설정을 JSON 파일로 저장하고, 그 파일을 공유(이메일/드라이브 등)할 수 있는 Intent를 돌려줌. */
    fun exportAndShare(context: Context): Intent? {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
            val file = backupFile(context)
            file.writeText(json.toString(2))

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
            val json = JSONObject(text)
            val floatKeys = mutableSetOf<String>()
            json.optJSONArray(FLOAT_KEYS_MARKER)?.let { arr ->
                for (i in 0 until arr.length()) floatKeys.add(arr.getString(i))
            }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
