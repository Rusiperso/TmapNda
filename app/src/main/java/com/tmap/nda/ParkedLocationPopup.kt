package com.tmap.nda

/** 메뉴의 "내 차 위치"를 누르면 팝업 없이 바로 저장된 차 위치를 지도에 보여주는 쪽으로 넘김. */
object ParkedLocationPopup {
    fun show(context: android.app.Activity, onShowOnMap: (lat: Double, lon: Double, savedAt: Long) -> Unit) {
        val saved = ParkedLocationRepository.getConfirmedLocation(context)
        if (saved == null) {
            android.widget.Toast.makeText(context, "저장된 주차 위치가 없습니다", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        onShowOnMap(saved.lat, saved.lon, saved.savedAt)
    }
}
