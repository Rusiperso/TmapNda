package com.tmap.nda

import android.content.Context
import android.content.res.Configuration

/**
 * v19.3.25: 재억 제보(폴드4 외부화면, 사진) - 카카오/티맵 SDK가 그리는 내비 UI(회전 아이콘,
 * 표지판 등)가 화면 밖으로 넘칠 만큼 크게 그려짐. 원인 - 이 SDK들은 액티비티가 처음
 * 생성될 때의 smallestScreenWidthDp(줄여서 sw)값으로 "이 화면은 폰이냐 태블릿이냐"를
 * 판단해서 sw600/720/800dp별로 다른 크기의 리소스를 고름(예: 카카오 knsdk_ui aar의
 * values-sw600dp / -sw720dp / -sw800dp). 정상적인 태블릿/차량 헤드유닛이라면 sw가 커지는
 * 만큼 실제 세로 공간(screenHeightDp)도 넉넉해서 문제가 없는데, 폴드4의 접힌 상태 외부
 * (커버) 화면은 삼성이 호환성 모드로 실제 물리 크기보다 dp 값을 부풀려 보고하는 것으로
 * 보여 - sw는 태블릿급으로 커졌는데 실제 세로 공간(screenHeightDp)은 폰보다도 작음. 그래서
 * SDK가 큰 리소스를 고르지만 들어갈 공간이 없어 위아래가 잘려 보임.
 *
 * 대응 - "sw가 태블릿 문턱을 넘었는데 실제 세로 공간은 오히려 작다"는 조합은 정상적인
 * 폰(애초에 sw가 문턱을 못 넘음)에서도, 정상적인 태블릿/헤드유닛(sw가 크면 세로도 같이 큼)
 * 에서도 나올 수 없는 조합이라 이 기기(또는 이런 종류의 왜곡된 외부화면) 외에는 절대
 * 걸리지 않는다. 이 조합일 때만 SDK에게 "실제로는 작은 화면"이라고 알려주는 Configuration
 * 복제본을 만들어서 액티비티가 그 값으로 리소스를 고르게 한다.
 *
 * #문제시 원복: 이 파일과 각 액티비티의 attachBaseContext 오버라이드만 지우면 원래대로 복귀.
 */
object CoverScreenConfigFix {

    // 카카오 knsdk_ui aar의 첫 리소스 문턱(values-sw600dp) 아래로 확실히 내려서 기본(폰용)
    // 리소스가 선택되게 함
    private const val FORCED_SW_DP = 400

    // 이 문턱 이상이면 "SDK가 태블릿급 큰 UI를 고를 수 있는 상태"로 간주
    private const val SW_TRIGGER_DP = 600

    // 실제 세로 공간이 이 미만이면 "sw 값과 실제 화면이 안 맞는 왜곡 상태"로 간주.
    // 정상 태블릿/헤드유닛은 sw>=600dp일 때 어느 방향으로 들고 있어도 이보다 세로가 넉넉함.
    private const val REAL_HEIGHT_SUSPECT_DP = 500

    fun wrapIfDistorted(base: Context): Context {
        val config = base.resources.configuration
        val swDp = config.smallestScreenWidthDp
        val heightDp = config.screenHeightDp

        NavLogger.d(base, "[화면왜곡진단] sw=${swDp}dp height=${heightDp}dp widthDp=${config.screenWidthDp}dp")

        if (swDp < SW_TRIGGER_DP || heightDp >= REAL_HEIGHT_SUSPECT_DP) {
            return base
        }

        NavLogger.d(
            base,
            "[화면왜곡보정] sw=${swDp}dp height=${heightDp}dp 로 태블릿용 큰 UI가 선택될 상황 감지 -> " +
                "sw를 ${FORCED_SW_DP}dp로 낮춰 SDK에 전달"
        )

        val forced = Configuration(config)
        forced.smallestScreenWidthDp = FORCED_SW_DP
        if (forced.screenWidthDp > FORCED_SW_DP) forced.screenWidthDp = FORCED_SW_DP
        return base.createConfigurationContext(forced)
    }
}
