package com.tmap.nda

import android.content.Context
import com.kakaomobility.knsdk.KNCarFuel
import com.kakaomobility.knsdk.KNCarType
import com.kakaomobility.knsdk.KNCarUsage
import com.kakaomobility.knsdk.trip.knrouteconfiguration.KNRouteConfiguration
import com.kakaomobility.knsdk.trip.knrouteconfiguration.KNRouteConfiguration_AutonomousDrivingOption
import com.kakaomobility.knsdk.trip.knrouteconfiguration.KNRouteConfiguration_FreightOption
import com.kakaomobility.knsdk.trip.knrouteconfiguration.KNRouteConfiguration_PreferredRouteOption

/**
 * 재억 요청(2026-09-15) - 카카오 경로 계산에 쓸 차종/연료를 앱 설정에서 한 번 골라두면
 * 계속 그 값이 유지되게 저장. TmapNdaPrefs에 저장해서 SettingsBackup(설정 백업)이
 * 그 파일 전체를 통째로 백업/복원할 때 이 값도 자동으로 같이 포함됨(따로 손 안 대도 됨). #문제시 원복
 */
object CarFuelSettings {
    private const val PREFS_NAME = "TmapNdaPrefs"
    private const val KEY_CAR_TYPE = "kakao_car_type"
    private const val KEY_CAR_FUEL = "kakao_car_fuel"

    val CAR_TYPE_LABELS: LinkedHashMap<KNCarType, String> = linkedMapOf(
        KNCarType.KNCarType_1 to "승용차",
        KNCarType.KNCarType_Bike to "이륜차",
        KNCarType.KNCarType_2 to "화물 2종",
        KNCarType.KNCarType_3 to "화물 3종",
        KNCarType.KNCarType_4 to "화물 4종",
        KNCarType.KNCarType_5 to "화물 5종",
        KNCarType.KNCarType_6 to "화물 6종"
    )

    val CAR_FUEL_LABELS: LinkedHashMap<KNCarFuel, String> = linkedMapOf(
        KNCarFuel.KNCarFuel_Gasoline to "휘발유",
        KNCarFuel.KNCarFuel_Premium_Gasoline to "고급휘발유",
        KNCarFuel.KNCarFuel_Diesel to "경유",
        KNCarFuel.KNCarFuel_LPG to "LPG",
        KNCarFuel.KNCarFuel_Electric to "전기",
        KNCarFuel.KNCarFuel_HybridElectric to "하이브리드",
        KNCarFuel.KNCarFuel_PlugInHybridElectric to "플러그인하이브리드",
        KNCarFuel.KNCarFuel_Hydrogen to "수소"
    )

    fun getCarType(context: Context): KNCarType {
        val name = prefs(context).getString(KEY_CAR_TYPE, null)
        return CAR_TYPE_LABELS.keys.find { it.name == name } ?: KNCarType.KNCarType_1
    }

    fun getCarFuel(context: Context): KNCarFuel {
        val name = prefs(context).getString(KEY_CAR_FUEL, null)
        return CAR_FUEL_LABELS.keys.find { it.name == name } ?: KNCarFuel.KNCarFuel_Gasoline
    }

    fun save(context: Context, carType: KNCarType, carFuel: KNCarFuel) {
        prefs(context).edit()
            .putString(KEY_CAR_TYPE, carType.name)
            .putString(KEY_CAR_FUEL, carFuel.name)
            .apply()
    }

    /** 저장해둔 차종/연료로 KNRouteConfiguration을 만듦(그 외 항목은 SDK 기본값과 동일하게 둠). */
    fun buildRouteConfiguration(context: Context): KNRouteConfiguration {
        return KNRouteConfiguration(
            getCarType(context),
            getCarFuel(context),
            false,
            KNCarUsage.KNCarUsage_Default,
            0, 0, 0, 0,
            KNRouteConfiguration_FreightOption(),
            KNRouteConfiguration_AutonomousDrivingOption(),
            KNRouteConfiguration_PreferredRouteOption()
        )
    }

    fun summaryLabel(context: Context): String {
        return "${CAR_TYPE_LABELS[getCarType(context)]} · ${CAR_FUEL_LABELS[getCarFuel(context)]}"
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
