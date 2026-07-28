/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KlineLineGradientTest {
    @Test
    fun `latest-price baseline splits a crossing segment at its exact intersection`() {
        val segments = splitKlineLineAtBaseline(
            points = listOf(
                Offset(0f, 5f),
                Offset(1f, 3f),
                Offset(2f, 7f),
                Offset(3f, 8f),
            ),
            baselineY = 5f,
        )

        assertEquals(2, segments.size)
        assertTrue(segments[0].isBullish)
        assertEquals(listOf(Offset(0f, 5f), Offset(1f, 3f), Offset(1.5f, 5f)), segments[0].points)
        assertFalse(segments[1].isBullish)
        assertEquals(listOf(Offset(1.5f, 5f), Offset(2f, 7f), Offset(3f, 8f)), segments[1].points)
    }

    @Test
    fun `a point touching the baseline belongs to both adjacent color regions`() {
        val segments = splitKlineLineAtBaseline(
            points = listOf(Offset(0f, 3f), Offset(1f, 5f), Offset(2f, 7f)),
            baselineY = 5f,
        )

        assertEquals(2, segments.size)
        assertEquals(listOf(Offset(0f, 3f), Offset(1f, 5f)), segments[0].points)
        assertEquals(listOf(Offset(1f, 5f), Offset(2f, 7f)), segments[1].points)
    }

    @Test
    fun `gradient configuration validates static colors and ordered stops`() {
        assertFailsWith<IllegalArgumentException> {
            KlineLineGradientRenderConfig(colors = listOf(Color.Red))
        }
        assertFailsWith<IllegalArgumentException> {
            KlineLineGradientRenderConfig(stops = listOf(1f, 0f))
        }
        assertFailsWith<IllegalArgumentException> {
            KlineLineGradientRenderConfig(
                colors = listOf(Color.Red, Color.Green, Color.Blue),
                stops = listOf(0f, 1f),
            )
        }
    }
}
