package com.zhumeng.kanvas.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KlineSpecTest {
    @Test
    fun `default precision is two decimal places`() {
        val spec = KlineSpec(
            symbol = "BTC-USDT",
            interval = KlineInterval(1, KlineTimeUnit.Minute),
        )

        assertEquals(4, spec.precision)
    }

    @Test
    fun `spec identity ignores request limit and display label`() {
        val base = KlineSpec(
            symbol = "BTC-USDT",
            interval = KlineInterval(1, KlineTimeUnit.Hour),
            limit = 200,
            precision = 2,
            label = "BTC",
        )
        val equivalent = base.copy(limit = 500, label = "Bitcoin")

        assertEquals(base, equivalent)
        assertEquals(base.hashCode(), equivalent.hashCode())
        assertEquals("BTC-USDT-1hour", base.key)
        assertEquals("BTC-USDT-1hour-null-null", base.rangeKey)
        assertEquals("1H", base.interval.debugLabel)
        assertTrue(base.interval.isValid)
        assertFalse(KlineInterval.Invalid.isValid)
    }

    @Test
    fun `loading visibility distinguishes silent prefetch from visible loading`() {
        assertTrue(KlineLoadingState.InitLoading.showLoading)
        assertTrue(KlineLoadingState.LoadingMore.showLoading)
        assertFalse(KlineLoadingState.None.showLoading)
        assertFalse(KlineLoadingState.LoadMore.showLoading)
        assertTrue(KlineLoadingState.LoadMore.isLoadMore)
        assertTrue(KlineLoadingState.LoadingMore.isLoadMore)
    }
}
