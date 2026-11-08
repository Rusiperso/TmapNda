package com.tmap.nda

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 재억 요청(2026-11-08) - 즐겨찾기 첫 화면: 사용자가 만든 폴더 목록(+ 새 폴더).
 * 폴더를 누르면 [onOpen]으로 폴더 id를 넘기고(각 화면이 그 폴더의 장소 카드를 띄움),
 * 길게 누르면 이름 바꾸기 / 삭제 메뉴가 뜬다. 폴더 수 제한 없음, 스크롤. #문제시 원복
 */
object FavoriteFolderCard {
    fun show(activity: Activity, onOpen: (String) -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        fun dp(v: Int) = PopupCard.dp(activity, v)
        val cols = 4
        lateinit var dialog: PopupCard.CardDialog

        fun cell(line1: String, line2: String, dashed: Boolean = false): LinearLayout =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_quickslot_chip_rounded)
                setPadding(8, 20, 8, 20)
                addView(TextView(activity).apply {
                    text = line1
                    textSize = 12f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setTextColor(Color.parseColor(if (dashed) "#AAAAAA" else "#EEEEEE"))
                })
                if (line2.isNotEmpty()) addView(TextView(activity).apply {
                    text = line2
                    textSize = 9f
                    gravity = Gravity.CENTER
                    setTextColor(Color.parseColor("#FFD54F"))
                })
            }

        fun promptName(title: String, initial: String, ok: (String) -> Unit) {
            val input = EditText(activity).apply { setText(initial); setSingleLine(true); hint = "폴더 이름"; setSelectAllOnFocus(true); setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#999999")) }
            AlertDialog.Builder(activity, R.style.RoundedDialogTheme)
                .setTitle(title)
                .setView(LinearLayout(activity).apply {
                    setPadding(dp(20), dp(8), dp(20), 0)
                    addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                })
                .setNegativeButton("취소", null)
                .setPositiveButton("확인") { _, _ ->
                    val n = input.text.toString().trim()
                    if (n.isBlank()) Toast.makeText(activity, "이름을 적어주세요", Toast.LENGTH_SHORT).show() else ok(n)
                }.show()
        }

        val folders = FavoriteFolderStore.list(activity)
        val cells = ArrayList<View>()
        folders.forEach { f ->
            val count = f.slots.count { QuickSlotStore.get(activity, it) != null }
            cells.add(cell("📁 ${f.name}", "${count}곳").apply {
                setOnClickListener { dialog.dismiss(); onOpen(f.id) }
                setOnLongClickListener {
                    AlertDialog.Builder(activity, R.style.RoundedDialogTheme)
                        .setTitle(f.name)
                        .setItems(arrayOf("이름 바꾸기", "폴더 삭제")) { _, which ->
                            when (which) {
                                0 -> promptName("폴더 이름 바꾸기", f.name) { n ->
                                    FavoriteFolderStore.renameFolder(activity, f.id, n)
                                    dialog.dismiss(); show(activity, onOpen)
                                }
                                1 -> AlertDialog.Builder(activity, R.style.RoundedDialogTheme)
                                    .setTitle("폴더 삭제")
                                    .setMessage("'${f.name}' 폴더와 안의 ${count}곳이 모두 지워져요. 되돌릴 수 없어요.")
                                    .setNegativeButton("취소", null)
                                    .setPositiveButton("삭제") { _, _ ->
                                        FavoriteFolderStore.deleteFolder(activity, f.id)
                                        dialog.dismiss(); show(activity, onOpen)
                                    }.show()
                            }
                        }.show()
                    true
                }
            })
        }
        cells.add(cell("＋ 새 폴더", "", dashed = true).apply {
            setOnClickListener {
                promptName("새 폴더", "") { n ->
                    FavoriteFolderStore.createFolder(activity, n)
                    dialog.dismiss(); show(activity, onOpen)
                }
            }
        })

        val grid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        cells.chunked(cols).forEachIndexed { r, rowItems ->
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until cols) {
                val c = rowItems.getOrNull(i) ?: android.widget.Space(activity)
                c.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = if (i < cols - 1) dp(8) else 0
                }
                row.addView(c)
            }
            grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { if (r > 0) topMargin = dp(8) })
        }
        val rows = ((cells.size + cols - 1) / cols).coerceAtLeast(1)
        dialog = PopupCard.CardDialog(activity, activity.findViewById<ViewGroup>(android.R.id.content)).apply {
            setTitle("즐겨찾기")
            setContent(ScrollView(activity).apply { addView(grid) }, maxDp = rows * 70 + (rows - 1) * 8 + 8, reserveDp = 160)
            setButton(PopupCard.CardDialog.BUTTON_NEGATIVE, "닫기")
        }
        dialog.show()
    }
}
