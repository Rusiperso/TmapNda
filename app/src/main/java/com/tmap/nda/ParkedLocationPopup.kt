package com.tmap.nda

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Locale

/** 더보기 메뉴의 "내 차 위치" 클릭 시 뜨는 팝업 - 저장된 위치 확인 + 지도앱 보기/도보 안내. */
object ParkedLocationPopup {
    fun show(context: android.app.Activity) {
        val saved = ParkedLocationRepository.getConfirmedLocation(context)
        if (saved == null) {
            android.widget.Toast.makeText(context, "저장된 주차 위치가 없습니다", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val timeText = SimpleDateFormat("M월 d일 a h:mm", Locale.KOREAN).format(java.util.Date(saved.savedAt))

        AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("내 차 위치")
            .setMessage("$timeText 저장됨")
            .setPositiveButton("여기로 안내") { _, _ ->
                // v: 도보 경로 - google.navigation walking mode 인텐트로 기본 지도앱 실행
                try {
                    val uri = Uri.parse("google.navigation:q=${saved.lat},${saved.lon}&mode=w")
                    val intent = Intent(Intent.ACTION_VIEW, uri)
                    context.startActivity(intent)
                } catch (e: Exception) {
                    android.widget.Toast.makeText(context, "지도 앱을 열 수 없습니다", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("지도에서 보기") { _, _ ->
                try {
                    val uri = Uri.parse("geo:${saved.lat},${saved.lon}?q=${saved.lat},${saved.lon}(내 차 위치)")
                    val intent = Intent(Intent.ACTION_VIEW, uri)
                    context.startActivity(intent)
                } catch (e: Exception) {
                    android.widget.Toast.makeText(context, "지도 앱을 열 수 없습니다", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("닫기", null)
            .show()
    }
}
