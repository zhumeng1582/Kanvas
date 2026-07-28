package com.zhumeng.kanvas

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KlineLatestPriceMarkerGeometryTest {
    @Test
    fun `off-view marker uses rendered label bounds and density-scaled hit target`() {
        val marker = KlinePriceMarkerRenderConfig(
            spacingPx = 4f,
            hitTestMarginPx = 3f,
        )

        val geometry = resolveKlineLatestPriceMarkerGeometry(
            plotRect = Rect(10f, 20f, 210f, 120f),
            latestCenterX = 240f,
            latestPriceY = 15f,
            marker = marker,
            labelSize = Size(50f, 20f),
            densityScale = 2f,
        )

        requireNotNull(geometry)
        assertFalse(geometry.isInView)
        assertEquals(10f, geometry.lineStartX)
        assertEquals(30f, geometry.y)
        assertEquals(Rect(152f, 20f, 202f, 40f), geometry.labelRect)

        val hitTarget = geometry.labelRect.expandKlineHitTarget(marker.hitTestMarginPx, densityScale = 2f)
        assertEquals(Rect(146f, 14f, 208f, 46f), hitTarget)
        assertTrue(hitTarget.contains(androidx.compose.ui.geometry.Offset(207f, 45f)))
        assertFalse(hitTarget.contains(androidx.compose.ui.geometry.Offset(209f, 45f)))
    }

    @Test
    fun `in-view marker starts from candle and hidden marker has no geometry`() {
        val plotRect = Rect(10f, 20f, 210f, 120f)
        val inView = resolveKlineLatestPriceMarkerGeometry(
            plotRect = plotRect,
            latestCenterX = 160f,
            latestPriceY = 70f,
            marker = KlinePriceMarkerRenderConfig(spacingPx = 2f),
            labelSize = Size(40f, 20f),
            densityScale = 1f,
        )

        requireNotNull(inView)
        assertTrue(inView.isInView)
        assertEquals(160f, inView.lineStartX)
        assertNull(
            resolveKlineLatestPriceMarkerGeometry(
                plotRect = plotRect,
                latestCenterX = 240f,
                latestPriceY = 70f,
                marker = KlinePriceMarkerRenderConfig(show = false),
                labelSize = Size(40f, 20f),
                densityScale = 1f,
            ),
        )
    }
}
