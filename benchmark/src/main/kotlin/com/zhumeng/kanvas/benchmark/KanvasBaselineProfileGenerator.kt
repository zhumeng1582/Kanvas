/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas.benchmark

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.benchmark.macro.junit4.BaselineProfileRule
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

/** Generates startup and hot chart-gesture profile rules for the example integration target. */
@RunWith(AndroidJUnit4::class)
class KanvasBaselineProfileGenerator {
    @Before
    fun requireProfileBuild() {
        assumeTrue("Generate library rules with the baselineProfile variant", BuildConfig.BUILD_TYPE == "baselineProfile")
    }

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = TargetPackage,
        filterPredicate = ::isKanvasLibraryRule,
    ) {
        pressHome()
        startActivityAndWait(
            Intent(Intent.ACTION_MAIN)
                .setClassName(TargetPackage, "$TargetPackage.MainActivity")
                .putExtra(CandleCountExtra, 10_000)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val chart = checkNotNull(
            device.wait(Until.findObject(By.desc(ChartDescription)), UiTimeoutMillis),
        )
        checkNotNull(device.wait(Until.findObject(By.textContains(LoadedCandleText)), UiTimeoutMillis))
        SystemClock.sleep(InteractionSettleMillis)
        repeat(2) {
            chart.swipe(Direction.RIGHT, 0.6f, 900)
            chart.swipe(Direction.LEFT, 0.6f, 900)
        }
        chart.pinchOpen(0.5f, 700)
        chart.pinchClose(0.5f, 700)
        chart.dragCrosshair()
    }

    /** Profiles the same uninterrupted Cross gesture used by the macrobenchmark. */
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
            injectMotionEvent(
                action = MotionEvent.ACTION_MOVE,
                downTime = downTime,
                eventTime = SystemClock.uptimeMillis(),
                x = x,
                y = y,
            )
            SystemClock.sleep(CrossFrameDelayMillis)
        }
        injectMotionEvent(
            action = MotionEvent.ACTION_UP,
            downTime = downTime,
            eventTime = SystemClock.uptimeMillis(),
            x = endX,
            y = y,
        )
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
            ) { "Unable to inject Cross baseline-profile input" }
        } finally {
            event.recycle()
        }
    }

    private companion object {
        const val TargetPackage = "com.zhumeng.kanvas.example"
        const val ChartDescription = "Kanvas chart"
        const val LoadedCandleText = "10000 candles"
        const val CandleCountExtra = "com.zhumeng.kanvas.example.CANDLE_COUNT"
        const val UiTimeoutMillis = 20_000L
        const val InteractionSettleMillis = 500L
        const val CrossLongPressMillis = 650L
        const val CrossMoveFrames = 90
        const val CrossFrameDelayMillis = 8L
    }
}

private fun isKanvasLibraryRule(rule: String): Boolean {
    val descriptor = rule.dropWhile { prefix -> prefix == 'H' || prefix == 'S' || prefix == 'P' }
    return descriptor.startsWith("Lcom/zhumeng/kanvas/") &&
        !descriptor.startsWith("Lcom/zhumeng/kanvas/example/")
}
