package com.tmap.nda

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * 앱 재시작용 중간 화면. 별도 프로세스(":restart")에서 떠서, 본 앱 프로세스가 완전히 끝난 뒤에 앱을 다시 켠다.
 * (본 프로세스에서 새 화면을 띄운 직후 그 프로세스를 끄면 새 화면까지 같이 죽기 때문.)
 */
class RestartTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = intent.getParcelableExtra<Intent>("target")
        Handler(Looper.getMainLooper()).postDelayed({
            try { if (target != null) startActivity(target) } finally { finish() }
        }, 1300L)
    }
}
