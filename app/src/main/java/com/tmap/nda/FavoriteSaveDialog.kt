package com.tmap.nda

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 재억 요청(2026-11-08) - 지도를 찍었을 때 "저장"을 누르면 뜨는 창.
 * 위에서 이름을 정하고(처음엔 찍은 곳 이름이 들어가 있음), 아래 폴더 중 하나를 누르면 바로 저장된다.
 * "＋ 새 폴더"로 그 자리에서 폴더를 만들어 넣을 수도 있다. 폴더 이름은 전부 사용자가 직접 짓는다. #문제시 원복
 */
object FavoriteSaveDialog {
    fun show(activity: Activity, entry: HistoryEntry) {
        if (activity.isFinishing || activity.isDestroyed) return
        fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
        val nameInput = EditText(activity).apply {
            setText(entry.name)
            setSelectAllOnFocus(true)
            setSingleLine(true)
            hint = "이름"
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.parseColor("#999999"))
        }
        val listBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(nameInput)
            addView(TextView(activity).apply {
                text = "어느 폴더에 넣을까요? (누르면 바로 저장)"
                textSize = 12f
                setTextColor(android.graphics.Color.parseColor("#CCCCCC"))
                setPadding(0, dp(12), 0, dp(4))
            })
            addView(ScrollView(activity).apply { addView(listBox) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200)))
        }
        val dlg = AlertDialog.Builder(activity, R.style.RoundedDialogTheme)
            .setTitle("즐겨찾기에 저장")
            .setView(root)
            .setNegativeButton("취소", null)
            .create()

        fun saveInto(folderId: String, folderName: String) {
            val name = nameInput.text.toString().trim().ifBlank { entry.name }
            FavoriteFolderStore.addEntry(activity, folderId, entry.copy(name = name))
            Toast.makeText(activity, "'$name' → $folderName 에 저장했어요", Toast.LENGTH_SHORT).show()
            dlg.dismiss()
        }
        fun row(label: String, onTap: () -> Unit) = Button(activity).apply {
            text = label
            isAllCaps = false
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setOnClickListener { onTap() }
        }
        fun rebuild() {
            listBox.removeAllViews()
            FavoriteFolderStore.list(activity).forEach { f ->
                listBox.addView(row("📁 ${f.name}  (${f.slots.size}곳)") { saveInto(f.id, f.name) })
            }
            listBox.addView(row("＋ 새 폴더 만들어서 저장") {
                val input = EditText(activity).apply { setSingleLine(true); hint = "폴더 이름"; setTextColor(android.graphics.Color.WHITE); setHintTextColor(android.graphics.Color.parseColor("#999999")) }
                AlertDialog.Builder(activity, R.style.RoundedDialogTheme)
                    .setTitle("새 폴더")
                    .setView(LinearLayout(activity).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input,
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) })
                    .setNegativeButton("취소", null)
                    .setPositiveButton("만들고 저장") { _, _ ->
                        val n = input.text.toString().trim()
                        if (n.isBlank()) {
                            Toast.makeText(activity, "폴더 이름을 적어주세요", Toast.LENGTH_SHORT).show()
                        } else {
                            val f = FavoriteFolderStore.createFolder(activity, n)
                            saveInto(f.id, f.name)
                        }
                    }.show()
            })
        }
        rebuild()
        dlg.show()
    }
}
