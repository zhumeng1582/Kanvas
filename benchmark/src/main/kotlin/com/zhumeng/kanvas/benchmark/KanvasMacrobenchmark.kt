/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas.benchmark

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class KanvasMacrobenchmark {
    @Before
    fun requireBenchmarkBuild() {
        assumeTrue("Measure the optimized benchmark variant", BuildConfig.BUILD_TYPE == "benchmark")
    }

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun coldStartupWithTenThousandCandles() = benchmarkRule.measureRepeated(
        packageName = TargetPackage,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.COLD,
        iterations = 5,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait(launchIntent(candleCount = 10_000))
        checkNotNull(device.wait(Until.findObject(By.desc(ChartDescription)), UiTimeoutMillis))
    }

    @Test
    fun panPinchAndCrossWithHundredThousandCandles() = benchmarkRule.measureRepeated(
        packageName = TargetPackage,
        metrics = listOf(
            FrameTimingMetric(),
            MemoryUsageMetric(MemoryUsageMetric.Mode.Max),
        ),
        compilationMode = CompilationMode.Partial(),
        iterations = 8,
        setupBlock = {
            startActivityAndWait(launchIntent(candleCount = 100_000))
            checkNotNull(device.wait(Until.findObject(By.desc(ChartDescription)), UiTimeoutMillis))
            checkNotNull(device.wait(Until.findObject(By.textContains(LoadedCandleText)), UiTimeoutMillis))
            SystemClock.sleep(InteractionSettleMillis)
        },
    ) {
        val chart = checkNotNull(device.findObject(By.desc(ChartDescription)))
        repeat(3) {
            chart.swipe(Direction.RIGHT, 0.65f, 900)
            chart.swipe(Direction.LEFT, 0.65f, 900)
        }
        chart.pinchOpen(0.55f, 700)
        chart.pinchClose(0.55f, 700)
        chart.longClick()
        chart.swipe(Direction.LEFT, 0.35f, 500)
    }

    @Test
    fun panOnlyWithHundredThousandCandles() = measureInteraction {
        repeat(4) {
            swipe(Direction.RIGHT, 0.65f, 900)
            swipe(Direction.LEFT, 0.65f, 900)
        }
    }

    @Test
    fun pinchOnlyWithHundredThousandCandles() = measureInteraction {
        repeat(3) {
            pinchOpen(0.55f, 700)
            pinchClose(0.55f, 700)
        }
    }

    @Test
    fun crossOnlyWithHundredThousandCandles() = measureInteraction {
        dragCrosshair()
    }

    private fun measureInteraction(block: UiObject2.() -> Unit) = benchmarkRule.measureRepeated(
        packageName = TargetPackage,
        metrics = listOf(
            FrameTimingMetric(),
            MemoryUsageMetric(MemoryUsageMetric.Mode.Max),
        ),
        compilationMode = CompilationMode.Partial(),
        iterations = InstrumentationRegistry.getArguments()
            .getString("kanvas.benchmark.iterations")?.toInt()?.also { require(it > 0) }
            ?: InteractionIterations,
        setupBlock = {
            startActivityAndWait(launchIntent(candleCount = 100_000))
            checkNotNull(device.wait(Until.findObject(By.desc(ChartDescription)), UiTimeoutMillis))
            checkNotNull(device.wait(Until.findObject(By.textContains(LoadedCandleText)), UiTimeoutMillis))
            SystemClock.sleep(InteractionSettleMillis)
        },
    ) {
        checkNotNull(device.findObject(By.desc(ChartDescription))).block()
    }

    /** Injects one uninterrupted long-press-and-drag stream so Cross owns every MOVE event. */
    private fun UiObject2.dragCrosshair() {
        val bounds = visibleBounds
        val startX = bounds.centerX().toFloat()
        val endX = bounds.left + bounds.width() * 0.78f
        val y = bounds.top + bounds.height() * 0.42f
        val downTime = SystemClock.uptimeMillis()
        injectMotionEvent(MotionEvent.ACTION_DOWN, downTime, downTime, startX, y)
        SystemClock.sleep(CrossLongPressMillis)
        repeat(CrossMoveFrames) { frame ->
            val fraction = (frame + 1f) / CrossMoveFrames
            val x = startX + (endX - startX) * fraction
            val eventTime = SystemClock.uptimeMillis()
            injectMotionEvent(MotionEvent.ACTION_MOVE, downTime, eventTime, x, y)
            SystemClock.sleep(CrossFrameDelayMillis)
        }
        val upTime = SystemClock.uptimeMillis()
        injectMotionEvent(MotionEvent.ACTION_UP, downTime, upTime, endX, y)
    }

    private fun injectMotionEvent(
        action: Int,
        downTime: Long,
        eventTime: Long,
        x: Float,
        y: Float,
    ) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        try {
            check(
                InstrumentationRegistry.getInstrumentation().uiAutomation
                    .injectInputEvent(event, true),
            ) { "Unable to inject Cross benchmark input" }
        } finally {
            event.recycle()
        }
    }

    private fun launchIntent(candleCount: Int): Intent = Intent(Intent.ACTION_MAIN)
        .setClassName(TargetPackage, "$TargetPackage.MainActivity")
        .putExtra(CandleCountExtra, candleCount)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    private companion object {
        const val TargetPackage = "com.zhumeng.kanvas.example"
        const val ChartDescription = "Kanvas chart"
        const val LoadedCandleText = "100000 candles"
        const val CandleCountExtra = "com.zhumeng.kanvas.example.CANDLE_COUNT"
        const val UiTimeoutMillis = 20_000L
        const val InteractionIterations = 8
        const val InteractionSettleMillis = 3_000L
        const val CrossLongPressMillis = 650L
        const val CrossMoveFrames = 90
        const val CrossFrameDelayMillis = 8L

    }
}
