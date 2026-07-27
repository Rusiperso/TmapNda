package com.tmap.nda

import android.content.Context
import com.kakaomobility.knsdk.KNError
import com.kakaomobility.knsdk.KNGuideRouteChangeReason
import com.kakaomobility.knsdk.guidance.knguidance.*
import com.kakaomobility.knsdk.guidance.knguidance.citsguide.KNGuide_Cits
import com.kakaomobility.knsdk.guidance.knguidance.common.KNLocation
import com.kakaomobility.knsdk.guidance.knguidance.locationguide.KNGuide_Location
import com.kakaomobility.knsdk.guidance.knguidance.routeguide.KNGuide_Route
import com.kakaomobility.knsdk.guidance.knguidance.routeguide.objects.KNMultiRouteInfo
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.KNGuide_Safety
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.objects.KNSafety
import com.kakaomobility.knsdk.guidance.knguidance.voiceguide.KNGuide_Voice
import com.kakaomobility.knsdk.trip.kntrip.knroute.KNRoute

/**
 * 카카오내비 SDK(KNSDK) 실시간 안내 콜백 모음.
 * 지금은 NavLogger로만 남김 - 다음 단계에서 UdpSenderService의 road_limit 스키마에
 * 맞춰 rgData 필드(제한속도/카메라/차선 등)로 변환해서 UDP로 내보내는 작업이 남아있음. #TODO
 *
 * onGuideEnded: 경로안내가 자연 종료(도착)됐을 때 오버레이를 걷어내기 위한 콜백.
 */
class KakaoGuidanceDelegate(
    private val context: Context,
    private val onGuideEnded: () -> Unit
) : KNGuidance_GuideStateDelegate,
    KNGuidance_LocationGuideDelegate,
    KNGuidance_RouteGuideDelegate,
    KNGuidance_SafetyGuideDelegate,
    KNGuidance_VoiceGuideDelegate,
    KNGuidance_CitsGuideDelegate {

    // ===== GuideStateDelegate =====
    override fun guidanceGuideStarted(guidance: KNGuidance) {
        NavLogger.d(context, "[카카오안내] 시작됨")
    }

    override fun guidanceGuideEnded(guidance: KNGuidance) {
        NavLogger.d(context, "[카카오안내] 종료됨(도착) - Tmap으로 복귀")
        onGuideEnded()
    }

    override fun guidanceOutOfRoute(guidance: KNGuidance) {
        NavLogger.d(context, "[카카오안내] 경로이탈 감지, 재탐색 위임")
    }

    override fun guidanceCheckingRouteChange(guidance: KNGuidance) {
        NavLogger.d(context, "[카카오안내] 경로변경 확인 중")
    }

    override fun guidanceRouteUnchanged(guidance: KNGuidance) {
        NavLogger.d(context, "[카카오안내] 경로 변경 없음")
    }

    override fun guidanceRouteUnchangedWithError(guidance: KNGuidance, error: KNError) {
        NavLogger.e(context, "[카카오안내] 경로 재탐색 실패: ${error.msg}")
    }

    override fun guidanceRouteChanged(
        guidance: KNGuidance,
        fromRoute: KNRoute,
        fromLocation: KNLocation,
        toRoute: KNRoute,
        toLocation: KNLocation,
        changeReason: KNGuideRouteChangeReason
    ) {
        NavLogger.d(context, "[카카오안내] 경로 변경됨: 사유=$changeReason")
    }

    override fun guidanceDidUpdateRoutes(
        guidance: KNGuidance,
        routes: List<KNRoute>,
        multiRouteInfo: KNMultiRouteInfo?
    ) {
        // no-op
    }

    override fun guidanceDidUpdateIndoorRoute(guidance: KNGuidance, route: KNRoute?) {
        // no-op
    }

    // ===== LocationGuideDelegate =====
    override fun guidanceDidUpdateLocation(guidance: KNGuidance, locationGuide: KNGuide_Location) {
        // #TODO: locationGuide.location, aheadDistance 등을 UdpSenderService의 road_limit 필드로 변환
    }

    // ===== RouteGuideDelegate =====
    override fun guidanceDidUpdateRouteGuide(guidance: KNGuidance, routeGuide: KNGuide_Route) {
        // #TODO: 턴바이턴(TBT) 정보를 carrot의 회전타입 넘버링으로 변환해서 UDP로 전송
    }

    // ===== SafetyGuideDelegate =====
    override fun guidanceDidUpdateSafetyGuide(guidance: KNGuidance, safetyGuide: KNGuide_Safety?) {
        // #TODO: 카메라/제한속도(SDI) 정보를 UdpSenderService road_limit 스키마로 변환
    }

    override fun guidanceDidUpdateAroundSafeties(guidance: KNGuidance, safeties: List<KNSafety>?) {
        // no-op
    }

    // ===== VoiceGuideDelegate =====
    override fun shouldPlayVoiceGuide(
        guidance: KNGuidance,
        voiceGuide: KNGuide_Voice,
        newData: List<ByteArray>
    ): Boolean = true

    override fun willPlayVoiceGuide(guidance: KNGuidance, voiceGuide: KNGuide_Voice) {
        // no-op (필요시 AudioFocusHacker 연동 지점)
    }

    override fun didFinishPlayVoiceGuide(guidance: KNGuidance, voiceGuide: KNGuide_Voice) {
        // no-op
    }

    // ===== CitsGuideDelegate =====
    override fun didUpdateCitsGuide(guidance: KNGuidance, citsGuide: KNGuide_Cits) {
        // no-op
    }
}
