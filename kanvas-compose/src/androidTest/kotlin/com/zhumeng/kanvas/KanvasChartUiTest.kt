/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhumeng.kanvas.core.KlineCandle
import com.zhumeng.kanvas.core.KlineInterval
import com.zhumeng.kanvas.core.KlineSeries
import com.zhumeng.kanvas.core.KlineSpec
import com.zhumeng.kanvas.core.KlineTimeUnit
import com.zhumeng.kanvas.core.KlineUiState
import com.zhumeng.kanvas.core.KlineViewport
import com.zhumeng.kanvas.core.KlineViewportConstraints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class KanvasChartUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun fixedFixtureProducesChartGoldenColorFamilies() {
        compose.setContent {
            KanvasChart(
                state = fixtureState(),
                onViewportChange = {},
                modifier = Modifier.size(360.dp, 480.dp),
            )
        }
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val colors = buildSet {
            for (y in 0 until pixels.height) {
                for (x in 0 until pixels.width) add(pixels[x, y])
            }
        }
        assertTrue("background missing", colors.any { it == KlineChartStyle().background })
        assertTrue("bull candle missing", colors.any { it == KlineChartStyle().bullish })
        assertTrue("bear candle missing", colors.any { it == KlineChartStyle().bearish })
        assertTrue("grid missing", colors.any { it == KlineChartStyle().gridLine })
    }

    @Test
    fun fixedFixtureMatchesDensityIndependentPixelGolden() {
        compose.setContent {
            KanvasChart(
                state = fixtureState(),
                onViewportChange = {},
                modifier = Modifier.size(360.dp, 480.dp),
            )
        }

        val fingerprint = compose.onRoot()
            .captureToImage()
            .toPixelMap()
            .partitionedFingerprint(columns = 24, rows = 32)

        assertEquals(
            "1709a59c443f2c9793bc7a6f5b0486d72e9a06f5c33418df6aa8453940440e59",
            fingerprint,
        )
    }

    @Test
    fun longPressRoutesToCrossBeforePan() {
        var sawCross = false
        compose.setContent {
            KanvasChart(
                state = fixtureState(),
                onViewportChange = {},
                onCrosshairChange = { if (it != null) sawCross = true },
                modifier = Modifier.size(360.dp, 480.dp),
            )
        }
        compose.onRoot().performTouchInput {
            longClick(Offset(centerX, centerY))
        }
        compose.waitForIdle()
        assertTrue(sawCross)
    }

    @Test
    fun doubleTapSuppressesSingleCrossTapAndCallsHostOnce() {
        var doubleTapCount = 0
        var crossCount = 0
        compose.setContent {
            KanvasChart(
                state = fixtureState(),
                onViewportChange = {},
                onDoubleTap = { doubleTapCount++ },
                onCrosshairChange = { if (it != null) crossCount++ },
                modifier = Modifier.size(360.dp, 480.dp),
            )
        }
        compose.onRoot().performTouchInput { doubleClick(Offset(centerX, centerY)) }
        compose.waitForIdle()
        assertEquals(1, doubleTapCount)
        assertEquals(0, crossCount)
    }

    @Test
    fun pinchDismissesPersistentCrossAndContinuesScaling() {
        var crosshairVisible = false
        var viewportChangeCount = 0
        compose.setContent {
            KanvasChart(
                state = fixtureState(),
                onViewportChange = { viewportChangeCount++ },
                onCrosshairChange = { crosshairVisible = it != null },
                modifier = Modifier.size(360.dp, 480.dp),
            )
        }

        compose.onRoot().performTouchInput { click(Offset(centerX, centerY)) }
        compose.waitForIdle()
        assertTrue(crosshairVisible)

        compose.onRoot().performTouchInput {
            down(0, Offset(centerX - 40f, centerY))
            down(1, Offset(centerX + 40f, centerY))
            updatePointerTo(0, Offset(centerX - 90f, centerY))
            updatePointerTo(1, Offset(centerX + 90f, centerY))
            move()
            up(1)
            up(0)
        }
        compose.waitForIdle()

        assertFalse(crosshairVisible)
        assertTrue("pinch did not update the viewport", viewportChangeCount > 0)
    }

    @Test
    fun panDismissesPersistentCrossInsteadOfDraggingIt() {
        var crosshairVisible = false
        val crosshairVisibilityChanges = mutableListOf<Boolean>()
        var latestViewport: KlineViewport? = null
        compose.setContent {
            KanvasChart(
                state = fixtureState(),
                onViewportChange = { latestViewport = it },
                onCrosshairChange = {
                    crosshairVisible = it != null
                    crosshairVisibilityChanges += crosshairVisible
                },
                renderConfig = KlineChartRenderConfig(
                    gesture = KlineGestureConfig(enableInertialPan = false),
                ),
                modifier = Modifier.size(360.dp, 480.dp),
            )
        }

        compose.onRoot().performTouchInput { click(Offset(centerX, centerY)) }
        compose.waitForIdle()
        assertTrue(crosshairVisible)

        compose.onRoot().performTouchInput {
            swipe(
                start = Offset(centerX - 50f, centerY),
                end = Offset(centerX + 50f, centerY),
                durationMillis = 300,
            )
        }
        compose.waitForIdle()

        assertFalse(
            "Cross callbacks=$crosshairVisibilityChanges viewport=$latestViewport",
            crosshairVisible,
        )
        assertEquals(40f, checkNotNull(latestViewport).rightEdgeOffsetPx, 1f)
    }

    @Test
    fun touchPanAppliesDefaultSensitivity() {
        var latestViewport: KlineViewport? = null
        var visibleCrossCount = 0
        compose.setContent {
            KanvasChart(
                state = fixtureState(),
                onViewportChange = { latestViewport = it },
                onCrosshairChange = { if (it != null) visibleCrossCount++ },
                renderConfig = KlineChartRenderConfig(
                    gesture = KlineGestureConfig(enableInertialPan = false),
                ),
                modifier = Modifier.size(360.dp, 480.dp),
            )
        }

        compose.onRoot().performTouchInput {
            swipe(
                start = Offset(centerX - 50f, centerY),
                end = Offset(centerX + 50f, centerY),
                durationMillis = 300,
            )
        }
        compose.waitForIdle()

        assertEquals(40f, checkNotNull(latestViewport).rightEdgeOffsetPx, 1f)
        assertEquals(0, visibleCrossCount)
    }

    @Test
    fun inertialPanContinuesFromFingerReleaseWithoutRebound() {
        val viewportOffsets = mutableListOf<Float>()
        compose.setContent {
            KanvasChart(
                state = fixtureState(),
                onViewportChange = { viewportOffsets += it.rightEdgeOffsetPx },
                renderConfig = KlineChartRenderConfig(
                    gesture = KlineGestureConfig(panSensitivity = 1f),
                ),
                modifier = Modifier.size(360.dp, 480.dp),
            )
        }

        compose.onRoot().performTouchInput {
            swipe(
                start = Offset(centerX - 50f, centerY),
                end = Offset(centerX + 50f, centerY),
                durationMillis = 300,
            )
        }
        compose.waitForIdle()

        assertTrue(viewportOffsets.isNotEmpty())
        assertTrue(
            "inertial pan rebounded: $viewportOffsets",
            viewportOffsets.zipWithNext().all { (previous, next) -> next >= previous - 0.5f },
        )
    }

    @Test
    fun leftInertialPanContinuesInTheDragDirection() {
        val viewportOffsets = mutableListOf<Float>()
        compose.setContent {
            KanvasChart(
                state = fixtureState(),
                onViewportChange = { viewportOffsets += it.rightEdgeOffsetPx },
                renderConfig = KlineChartRenderConfig(
                    gesture = KlineGestureConfig(panSensitivity = 1f),
                ),
                modifier = Modifier.size(360.dp, 480.dp),
            )
        }

        compose.onRoot().performTouchInput {
            swipe(
                start = Offset(centerX + 50f, centerY),
                end = Offset(centerX - 50f, centerY),
                durationMillis = 300,
            )
        }
        compose.waitForIdle()

        assertTrue(viewportOffsets.isNotEmpty())
        assertTrue(
            "left inertial pan rebounded: $viewportOffsets",
            viewportOffsets.zipWithNext().all { (previous, next) -> next <= previous + 0.5f },
        )
    }

    @Test
    fun verticalZoomShowsAndDismissesTouchExitAffordance() {
        compose.setContent {
            KanvasChart(
                state = fixtureState(),
                onViewportChange = {},
                renderConfig = KlineChartRenderConfig(
                    gesture = KlineGestureConfig(enableZoom = true),
                ),
                modifier = Modifier.size(360.dp, 480.dp),
            )
        }
        compose.onRoot().performTouchInput {
            swipe(
                start = Offset(width - 8f, centerY),
                end = Offset(width - 8f, centerY + 120f),
                durationMillis = 300,
            )
        }
        compose.onNodeWithContentDescription("Exit vertical zoom").assertExists().performTouchInput { click() }
        compose.onNodeWithContentDescription("Exit vertical zoom").assertDoesNotExist()
    }

    private fun fixtureState(): KlineUiState {
        val candles = List(80) { index ->
            val close = 100.0 + kotlin.math.sin(index / 5.0) * 8.0
            KlineCandle(
                timestampMillis = 1_800_000_000_000L - index * 60_000L,
                open = close + if (index % 2 == 0) -2.0 else 2.0,
                high = close + 4.0,
                low = close - 4.0,
                close = close,
                volume = 100.0 + index,
            )
        }
        return KlineUiState(
            spec = KlineSpec("TEST", KlineInterval(1, KlineTimeUnit.Minute), precision = 2),
            series = KlineSeries.of(candles),
            viewport = KlineViewport(candleWidthPx = 7f, candleSpacingPx = 1f, rightEdgeOffsetPx = -80f),
            viewportConstraints = KlineViewportConstraints(plotWidthPx = 296f),
        )
    }

    private fun androidx.compose.ui.graphics.PixelMap.partitionedFingerprint(
        columns: Int,
        rows: Int,
    ): String {
        val fingerprint = ByteArray(columns * rows * 3)
        var outputIndex = 0
        for (row in 0 until rows) {
            val top = row * height / rows
            val bottom = (row + 1) * height / rows
            for (column in 0 until columns) {
                val left = column * width / columns
                val right = (column + 1) * width / columns
                var red = 0.0
                var green = 0.0
                var blue = 0.0
                var count = 0
                for (y in top until bottom) {
                    for (x in left until right) {
                        val color = this[x, y]
                        red += color.red
                        green += color.green
                        blue += color.blue
                        count++
                    }
                }
                fingerprint[outputIndex++] = quantize(red / count).toByte()
                fingerprint[outputIndex++] = quantize(green / count).toByte()
                fingerprint[outputIndex++] = quantize(blue / count).toByte()
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(fingerprint)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun quantize(channel: Double): Int =
        (channel.coerceIn(0.0, 1.0) * 15.0).toInt()
}
