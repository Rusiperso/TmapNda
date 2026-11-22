package com.tmap.nda

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 재억 요청(2026-11-08) - 지도를 찍었을 때/최근 목적지에서 "저장"을 누르면 뜨는 창.
 * 위에서 이름을 정하고(처음엔 찍은 곳 이름이 들어가 있음), 아래 폴더 중 하나를 누르면 바로 저장된다.
 * "새 폴더"로 그 자리에서 폴더를 만들어 넣을 수도 있다. 폴더 이름은 전부 사용자가 직접 짓는다.
 * v19.4.87: 재억 요청(2026-11-22) - 길게 늘어선 막대 목록 대신 폴더 그림이 격자로 나란히 놓이는 모양으로 변경
 * (폴더 그림 + 이름 + 오른쪽 위 작은 숫자, 맨 끝에 점선 "새 폴더", 취소는 오른쪽 위). #문제시 원복
 */
object FavoriteSaveDialog {
    fun show(activity: Activity, entry: HistoryEntry) {
        if (activity.isFinishing || activity.isDestroyed) return
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        fun box(color: Int, radiusDp: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

        // 화면이 넓으면(차량 화면 등) 한 줄에 4개, 좁으면 3개
        val widthDp = activity.resources.displayMetrics.widthPixels / density
        val cols = if (widthDp >= 600f) 4 else 3

        val nameInput = EditText(activity).apply {
            setText(entry.name)
            setSelectAllOnFocus(true)
            setSingleLine(true)
            hint = "이름"
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#999999"))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val nameRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = box(Color.parseColor("#26FFFFFF"), 8)
            setPadding(dp(10), 0, dp(10), 0)
            addView(TextView(activity).apply { text = "📍"; textSize = 14f })
            addView(nameInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(activity).apply {
                text = "✎"
                textSize = 16f
                setTextColor(Color.parseColor("#BBBBBB"))
            })
        }

        val dlg = AlertDialog.Builder(activity, R.style.RoundedDialogTheme).create()

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = "즐겨찾기에 저장"
                textSize = 17f
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(activity).apply {
                text = "취소"
                textSize = 14f
                setTextColor(Color.parseColor("#BBBBBB"))
                setPadding(dp(12), dp(8), dp(4), dp(8))
                setOnClickListener { dlg.dismiss() }
            })
        }

        val listBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply { addView(listBox) }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            addView(header)
            addView(nameRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8); bottomMargin = dp(12) })
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(100)))
        }
        dlg.setView(root)

        fun saveInto(folderId: String, folderName: String) {
            val name = nameInput.text.toString().trim().ifBlank { entry.name }
            FavoriteFolderStore.addEntry(activity, folderId, entry.copy(name = name))
            Toast.makeText(activity, "'$name' → $folderName 에 저장했어요", Toast.LENGTH_SHORT).show()
            dlg.dismiss()
        }

        fun tile(icon: String, iconSize: Float, iconColor: Int, label: String, labelColor: Int,
                 count: String?, dashed: Boolean, onTap: () -> Unit): View {
            val bg = box(if (dashed) Color.TRANSPARENT else Color.parseColor("#26FFFFFF"), 12)
            if (dashed) bg.setStroke(dp(1), Color.parseColor("#66A0C8F0"), dp(4).toFloat(), dp(3).toFloat())
            val column = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(10), dp(4), dp(8))
                addView(TextView(activity).apply {
                    text = icon
                    textSize = iconSize
                    setTextColor(iconColor)
                    gravity = Gravity.CENTER
                })
                addView(TextView(activity).apply {
                    text = label
                    textSize = 12f
                    setTextColor(labelColor)
                    gravity = Gravity.CENTER
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                })
            }
            return FrameLayout(activity).apply {
                background = bg
                isClickable = true
                setOnClickListener { onTap() }
                addView(column, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                if (count != null) {
                    addView(TextView(activity).apply {
                        text = count
                        textSize = 11f
                        setTextColor(Color.parseColor("#BBBBBB"))
                    }, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.END
                    ).apply { topMargin = dp(6); marginEnd = dp(8) })
                }
            }
        }

        fun askNewFolder() {
            val input = EditText(activity).apply {
                setSingleLine(true)
                hint = "폴더 이름"
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#999999"))
            }
            AlertDialog.Builder(activity, R.style.RoundedDialogTheme)
                .setTitle("새 폴더")
                .setView(LinearLayout(activity).apply {
                    setPadding(dp(20), dp(8), dp(20), 0)
                    addView(input, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                })
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
        }

        fun rebuild() {
            listBox.removeAllViews()
            val tiles = mutableListOf<View>()
            FavoriteFolderStore.list(activity).forEach { f ->
                tiles.add(tile("📁", 30f, Color.parseColor("#FFC83D"), f.name, Color.WHITE,
                    f.slots.size.toString(), false) { saveInto(f.id, f.name) })
            }
            tiles.add(tile("＋", 28f, Color.parseColor("#A0C8F0"), "새 폴더", Color.parseColor("#A0C8F0"),
                null, true) { askNewFolder() })

            val tileHeight = dp(84)
            val rows = tiles.chunked(cols)
            rows.forEach { rowTiles ->
                val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                rowTiles.forEach { t ->
                    row.addView(t, LinearLayout.LayoutParams(0, tileHeight, 1f).apply {
                        setMargins(dp(4), dp(4), dp(4), dp(4))
                    })
                }
                repeat(cols - rowTiles.size) {
                    row.addView(View(activity), LinearLayout.LayoutParams(0, tileHeight, 1f).apply {
                        setMargins(dp(4), dp(4), dp(4), dp(4))
                    })
                }
                listBox.addView(row)
            }
            // 줄 수만큼 높이를 잡되 너무 길어지면 스크롤
            val wanted = rows.size * (tileHeight + dp(8))
            scroll.layoutParams = scroll.layoutParams.apply { height = minOf(wanted, dp(270)) }
        }
        rebuild()
        dlg.show()
    }
}
