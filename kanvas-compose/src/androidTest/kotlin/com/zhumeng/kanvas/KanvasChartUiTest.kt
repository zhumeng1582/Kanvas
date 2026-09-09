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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

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
            "12ddc74eb873a9302d649b3efb6e38f8edeecf554652290d1daf74b6d6dedf46",
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
    fun crossTooltipRemainsVisibleAfterLatestCandleUpdate() {
        var chartState by mutableStateOf(fixtureState())
        compose.setContent {
            KanvasChart(
                state = chartState,
                onViewportChange = {},
                style = KlineChartStyle(tooltipBackground = Color.Magenta),
                renderConfig = KlineChartRenderConfig(autoStartLastPriceCountdownTimer = false),
                modifier = Modifier.size(360.dp, 480.dp),
            )
        }
        fun tooltipVisible(): Boolean {
            val pixels = compose.onRoot().captureToImage().toPixelMap()
            for (y in 0 until pixels.height) {
                for (x in 0 until pixels.width) {
                    if (pixels[x, y] == Color.Magenta) return true
                }
            }
            return false
        }
        compose.onRoot().performTouchInput { longClick(Offset(centerX, centerY)) }
        compose.waitForIdle()
        assertTrue("Tooltip was not drawn", tooltipVisible())
        compose.runOnIdle {
            val candles = chartState.series.candles.toMutableList()
            candles[0] = candles[0].copy(close = candles[0].close + 0.1)
            chartState = chartState.copy(series = KlineSeries.of(candles))
        }
        compose.waitForIdle()
        assertTrue("Tooltip disappeared after a live update", tooltipVisible())
    }

    @Test
    fun latestCandleUpdateDoesNotScheduleAnotherMainDraw() {
        val draws = AtomicInteger()
        var chartState by mutableStateOf(fixtureState())
        val renderer = object : KlineMainChartRenderer {
            override fun drawChart(scope: DrawScope, context: KlineMainChartDrawContext) {
                draws.incrementAndGet()
                scope.drawRect(Color.Red)
            }
        }
        compose.setContent {
            KanvasChart(
                state = chartState,
                onViewportChange = {},
                mainChartRenderer = renderer,
                renderConfig = KlineChartRenderConfig(autoStartLastPriceCountdownTimer = false),
                modifier = Modifier.size(360.dp, 480.dp),
            )
        }
        compose.waitForIdle()
        val before = draws.get()
        compose.runOnIdle {
            val candles = chartState.series.candles.toMutableList()
            candles[0] = candles[0].copy(close = candles[0].close + 0.1)
            chartState = chartState.copy(series = KlineSeries.of(candles))
        }
        compose.waitForIdle()
        assertEquals("Publishing draw geometry scheduled an extra frame", before + 1, draws.get())
    }

    @Test
    fun crossMovementDoesNotRedrawMainContentButRendererStateStillDoes() {
        val draws = AtomicInteger()
        var color by mutableStateOf(Color.Red)
        var crossCount = 0
        val renderer = object : KlineMainChartRenderer {
            override fun drawChart(scope: DrawScope, context: KlineMainChartDrawContext) {
                draws.incrementAndGet()
                scope.drawRect(color)
            }
        }
        compose.setContent {
            KanvasChart(
                state = fixtureState(),
                onViewportChange = {},
                mainChartRenderer = renderer,
                onCrosshairChange = { if (it != null) crossCount++ },
                renderConfig = KlineChartRenderConfig(autoStartLastPriceCountdownTimer = false),
                modifier = Modifier.size(360.dp, 480.dp),
            )
        }
        // Keep one pointer session alive: a fresh drag dismisses a persistent
        // Cross, whereas a held long press owns subsequent MOVE events.
        compose.onRoot().performTouchInput { down(Offset(centerX, centerY)) }
        compose.mainClock.advanceTimeBy(700L)
        compose.waitForIdle()
        val settledDraws = draws.get()
        val settledCrossCount = crossCount
        compose.onRoot().performTouchInput {
            advanceEventTime(700L)
            moveTo(Offset(centerX + 30f, centerY + 20f), delayMillis = 16L)
        }
        compose.waitForIdle()
        // The new gesture can initialize range state. Observe only its MOVE.
        val heldDraws = draws.get()
        compose.onRoot().performTouchInput {
            moveTo(Offset(centerX + 60f, centerY + 40f), delayMillis = 16L)
        }
        compose.waitForIdle()
        assertTrue("Cross did not receive the drag", crossCount > settledCrossCount)
        assertTrue("Main renderer was never drawn", settledDraws > 0)
        assertEquals("Cross MOVE re-recorded main content", heldDraws, draws.get())

        compose.runOnIdle { color = Color.Blue }
        compose.waitForIdle()
        assertTrue("Dynamic renderer stopped observing its state", draws.get() > heldDraws)
        compose.onRoot().performTouchInput { up() }
    }

    @Test
    fun persistentCrossPriceRefreshesWhenLowLevelStateChanges() {
        var chartState by mutableStateOf(fixtureState())
        var sawCross = false
        val hiddenMarker = KlinePriceMarkerRenderConfig(show = false)
        val renderConfig = KlineChartRenderConfig(
            grid = KlineGridRenderConfig(showYAxisTicks = false),
            crosshair = KlineCrosshairRenderConfig(showTooltip = false),
            candleOverlay = KlineCandleOverlayRenderConfig(
                high = hiddenMarker,
                low = hiddenMarker,
                inViewPrice = hiddenMarker,
                offViewPrice = hiddenMarker,
                showLatestPoint = false,
                countdown = KlineCountdownRenderConfig(show = false),
            ),
        )
        compose.setContent {
            KanvasChart(
                state = chartState,
                onViewportChange = {},
                onCrosshairChange = { if (it != null) sawCross = true },
                renderConfig = renderConfig,
                modifier = Modifier.size(360.dp, 480.dp),
            )
        }

        compose.onRoot().performTouchInput { click(Offset(centerX, centerY)) }
        compose.waitForIdle()
        assertTrue("Cross did not enter its persistent state", sawCross)
        val before = compose.onRoot()
            .captureToImage()
            .toPixelMap()
            .partitionedFingerprint(columns = 24, rows = 32)

        compose.runOnIdle {
            val shifted = chartState.series.candles.map { candle ->
                candle.copy(
                    open = candle.open + 1_000.0,
                    high = candle.high + 1_000.0,
                    low = candle.low + 1_000.0,
                    close = candle.close + 1_000.0,
                )
            }
            chartState = chartState.copy(
                series = KlineSeries.of(shifted),
                revision = chartState.revision + 1L,
            )
        }
        compose.waitForIdle()
        val after = compose.onRoot()
            .captureToImage()
            .toPixelMap()
            .partitionedFingerprint(columns = 24, rows = 32)

        assertNotEquals("Cross price stayed on the previous value range", before, after)
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
    fun pinchKeepsMovingAverageVisibleAndRestoresStationaryRendering() {
        val initial = fixtureState()
        var chartState by mutableStateOf(initial)
        var chartLayout: KlineLayout? = null
        var sawCross = false
        val snapshot = com.zhumeng.kanvas.core.IndicatorRuntime().calculate(
            series = initial.series,
            definitions = listOf(
                com.zhumeng.kanvas.core.IndicatorDefinition(
                    key = com.zhumeng.kanvas.core.IndicatorKey.computed("ma"),
                    calculator = com.zhumeng.kanvas.core.MovingAverage(listOf(7)),
                ),
            ),
        )
        compose.setContent {
            KanvasChart(
                state = chartState,
                onViewportChange = { chartState = chartState.copy(viewport = it) },
                onLayoutChange = { chartLayout = it },
                onCrosshairChange = { sawCross = it != null },
                indicatorSnapshot = snapshot,
                style = KlineChartStyle(indicatorLines = listOf(Color.Magenta)),
                renderConfig = KlineChartRenderConfig(autoStartLastPriceCountdownTimer = false),
                modifier = Modifier.size(360.dp, 480.dp),
            )
        }
        fun curveMask(): Set<Pair<Int, Int>> {
            val pixels = compose.onRoot().captureToImage().toPixelMap()
            val plot = checkNotNull(chartLayout).mainPane.plotRect
            return buildSet {
                for (y in (plot.top.toInt() + 64) until (plot.bottom.toInt() - 16)) {
                    for (x in plot.left.toInt() until plot.right.toInt()) {
                        if (pixels[x, y] == Color.Magenta) add(x to y)
                    }
                }
            }
        }
        val originalCurve = curveMask()
        assertTrue("Initial MA is missing", originalCurve.size > 20)
        compose.onRoot().performTouchInput {
            down(0, Offset(centerX - 40f, centerY))
            down(1, Offset(centerX + 40f, centerY))
            updatePointerTo(0, Offset(centerX - 70f, centerY))
            updatePointerTo(1, Offset(centerX + 70f, centerY))
            move()
        }
        compose.waitForIdle()
        assertTrue("MA disappeared during pinch", curveMask().size > 20)
        compose.onRoot().performTouchInput {
            up(1)
            up(0)
        }
        compose.waitForIdle()
        compose.runOnIdle { chartState = initial }
        compose.waitForIdle()
        assertEquals("Stationary MA rendering was not restored", originalCurve, curveMask())
        compose.onRoot().performTouchInput { longClick(Offset(centerX, centerY)) }
        compose.waitForIdle()
        assertTrue("Cross did not resume after pinch", sawCross)
        assertTrue("MA disappeared under Cross", curveMask().size > 20)
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
