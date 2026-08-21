package com.zhumeng.kanvas.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IndicatorOutputTest {
    @Test
    fun `latest-only output identity rejects rebuilt historical columns`() {
        val key = IndicatorKey.computed("history_identity")
        val originalColumn = IndicatorColumn.of("value", doubleArrayOf(3.0, 2.0, 1.0))
        val original = IndicatorOutput.of(key, 3, listOf(originalColumn))
        val latestOnly = IndicatorOutput.of(
            key,
            3,
            listOf(originalColumn.withLatestValue(4.0)),
        )
        val rebuilt = IndicatorOutput.of(
            key,
            3,
            listOf(IndicatorColumn.of("value", doubleArrayOf(4.0, 2.0, 1.0))),
        )

        assertTrue(latestOnly.differsOnlyAtLatestFrom(original))
        assertFalse(rebuilt.differsOnlyAtLatestFrom(latestOnly))
        assertFalse(latestOnly.differsOnlyAtLatestFrom(null))
    }
}
