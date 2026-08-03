package com.tmap.nda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HudScaleTest {

    @Test
    fun smallVehicleDisplay_keepsPanelReadableAndMapWide() {
        val spec = HudScale.calculateSpec(widthPx = 800, heightPx = 480, density = 1f)

        assertEquals(HudScale.Profile.STANDARD, spec.profile)
        assertEquals(160f, spec.panelWidthDp, 0.1f)
        assertTrue(spec.panelWidthDp <= spec.availableWidthDp * 0.28f)
    }

    @Test
    fun highDensityDisplay_usesDpInsteadOfRawPixels() {
        val spec = HudScale.calculateSpec(widthPx = 1280, heightPx = 720, density = 2f)

        assertEquals(640f, spec.availableWidthDp, 0.1f)
        assertEquals(360f, spec.availableHeightDp, 0.1f)
        assertEquals(HudScale.Profile.STANDARD, spec.profile)
        assertTrue(spec.panelWidthDp <= spec.availableWidthDp * 0.28f)
    }

    @Test
    fun curvedUltraWideDisplay_preservesMostSpaceForMap() {
        val spec = HudScale.calculateSpec(widthPx = 1920, heightPx = 720, density = 1f)

        assertEquals(HudScale.Profile.ULTRAWIDE, spec.profile)
        assertEquals(288f, spec.panelWidthDp, 0.1f)
        assertTrue(spec.panelWidthDp / spec.availableWidthDp <= 0.15f)
    }

    @Test
    fun panelNeverExceedsTwentyEightPercent() {
        listOf(
            Triple(480, 320, 1f),
            Triple(800, 480, 1f),
            Triple(1024, 600, 1f),
            Triple(1280, 720, 1.5f),
            Triple(1920, 720, 1f)
        ).forEach { (width, height, density) ->
            val spec = HudScale.calculateSpec(width, height, density)
            assertTrue(spec.panelWidthDp <= spec.availableWidthDp * 0.28f + 0.1f)
        }
    }
}
