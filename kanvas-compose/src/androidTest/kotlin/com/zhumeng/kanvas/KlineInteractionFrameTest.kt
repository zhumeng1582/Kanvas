/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import android.graphics.Bitmap
import android.view.Choreographer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zhumeng.kanvas.core.IndicatorDefinition
import com.zhumeng.kanvas.core.IndicatorKey
import com.zhumeng.kanvas.core.IndicatorRuntime
import com.zhumeng.kanvas.core.KlineCandle
import com.zhumeng.kanvas.core.KlineSeries
import com.zhumeng.kanvas.core.KlineUiState
import com.zhumeng.kanvas.core.KlineViewport
import com.zhumeng.kanvas.core.MovingAverage
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KlineInteractionFrameTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun topTipsStayVisibleThroughoutContinuousViewportUpdates() {
        val initial = KlineUiState(
            series = KlineSeries.of(
                List(240) { index ->
                    val close = 100.0 + kotlin.math.sin(index / 5.0) * 8.0
                    KlineCandle(
                        timestampMillis = 1_800_000_000_000L - index * 60_000L,
                        open = close - 1.0,
                        high = close + 3.0,
                        low = close - 3.0,
                        close = close,
                        volume = 100.0,
                    )
                },
            ),
            viewport = KlineViewport(candleWidthPx = 7f, candleSpacingPx = 1f),
        )
        val liveState = mutableStateOf(initial)
        val indicators = IndicatorRuntime().calculate(
            series = initial.series,
            definitions = listOf(
                IndicatorDefinition(
                    key = IndicatorKey.computed("ma"),
                    calculator = MovingAverage(listOf(7, 25)),
                ),
            ),
        )
        var chartBounds = Rect.Zero
        compose.setContent {
            CompositionLocalProvider(LocalKanvasLiveChartState provides liveState) {
                KanvasChart(
                    state = initial,
                    onViewportChange = {},
                    indicatorSnapshot = indicators,
                    style = KlineChartStyle(indicatorLines = listOf(Color.Magenta, Color.Cyan)),
                    renderConfig = KlineChartRenderConfig(autoStartLastPriceCountdownTimer = false),
                    modifier = Modifier.size(360.dp, 480.dp)
                        .onGloballyPositioned { chartBounds = it.boundsInWindow() },
                )
            }
        }
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun screenshot(): Bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())

        val reference = screenshot()
        val tipPixels = buildList {
            val bottom = (chartBounds.top + chartBounds.width * 20f / 360f).toInt()
            for (y in chartBounds.top.toInt() until bottom) {
                for (x in chartBounds.left.toInt() until chartBounds.right.toInt()) {
                    if (reference.getPixel(x, y) == android.graphics.Color.MAGENTA) add(x to y)
                }
            }
        }
        reference.recycle()
        assertTrue("Initial MA text is missing", tipPixels.size > 20)

        val frames = AtomicInteger()
        val driver = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val frame = frames.incrementAndGet()
                liveState.value = initial.copy(
                    viewport = initial.viewport.copy(rightEdgeOffsetPx = -80f - frame % 160),
                )
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        instrumentation.runOnMainSync { Choreographer.getInstance().postFrameCallback(driver) }
        try {
            // Capture presented frames without waiting for Compose to become idle:
            // idle-only captures miss the disappearing overlay during a live pan.
            repeat(16) { sample ->
                val bitmap = screenshot()
                val visible = tipPixels.count { (x, y) ->
                    bitmap.getPixel(x, y) == android.graphics.Color.MAGENTA
                }
                bitmap.recycle()
                assertTrue("MA text disappeared in moving sample $sample", visible >= tipPixels.size * 0.95)
            }
            assertTrue("The viewport did not keep moving during capture", frames.get() > 16)
        } finally {
            instrumentation.runOnMainSync { Choreographer.getInstance().removeFrameCallback(driver) }
        }
    }
}
