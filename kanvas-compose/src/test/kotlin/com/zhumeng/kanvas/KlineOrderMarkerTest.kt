package com.zhumeng.kanvas

import androidx.compose.ui.geometry.Rect
import com.zhumeng.kanvas.core.IndexRange
import com.zhumeng.kanvas.core.KlineCandle
import com.zhumeng.kanvas.core.KlineViewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.system.measureTimeMillis

class KlineOrderMarkerTest {
    @Test
    fun `markers follow exact candle timestamps and skip missing timestamps`() {
        val candles = listOf(candle(300L), candle(200L), candle(100L))
        val placements = resolveKlineOrderMarkerPlacements(
            markers = listOf(
                KlineOrderMarker(200L, KlineOrderSide.Buy),
                KlineOrderMarker(300L, KlineOrderSide.Sell),
                KlineOrderMarker(250L, KlineOrderSide.Buy),
            ),
            candles = candles,
            paintRange = IndexRange(0, candles.size),
            plotRect = Rect(0f, 0f, 200f, 100f),
            valueRange = KlineValueRange(80.0, 120.0),
            viewport = KlineViewport(candleWidthPx = 7f, candleSpacingPx = 1f),
            config = KlineOrderMarkerRenderConfig(sizePx = 10f, candleGapPx = 0f),
            densityScale = 1f,
        )

        assertEquals(2, placements.size)
        assertEquals(188f, placements[0].center.x)
        assertEquals(KlineOrderSide.Buy, placements[0].marker.side)
        assertEquals(196f, placements[1].center.x)
        assertEquals(KlineOrderSide.Sell, placements[1].marker.side)
        assertTrue(placements[0].center.y > placements[1].center.y)
    }

    @Test
    fun `same-side orders stack and remain inside the plot`() {
        val candle = candle(300L)
        val placements = resolveKlineOrderMarkerPlacements(
            markers = List(2) { KlineOrderMarker(300L, KlineOrderSide.Sell) },
            candles = listOf(candle),
            paintRange = IndexRange(0, 1),
            plotRect = Rect(0f, 0f, 200f, 100f),
            valueRange = KlineValueRange(80.0, 140.0),
            viewport = KlineViewport(),
            config = KlineOrderMarkerRenderConfig(sizePx = 10f, candleGapPx = 2f, stackGapPx = 3f),
            densityScale = 1f,
        )

        assertEquals(2, placements.size)
        assertEquals(20f, placements[0].center.y - placements[1].center.y)
        assertTrue(placements.all { it.center.y >= 5f })
    }

    @Test
    fun `indexed marker lookup cost follows visible candles instead of total markers`() {
        val candles = List(100) { index -> candle(100_000L - index) }
        val markers = List(100_000) { index ->
            KlineOrderMarker(timestampMillis = 1_000_000L + index, side = KlineOrderSide.Buy)
        } + KlineOrderMarker(candles[20].timestampMillis, KlineOrderSide.Sell)
        val index = KlineOrderMarkerIndex(markers)
        lateinit var placements: List<KlineOrderMarkerPlacement>

        val elapsed = measureTimeMillis {
            repeat(1_000) {
                placements = resolveKlineOrderMarkerPlacements(
                    markerIndex = index,
                    candles = candles,
                    paintRange = IndexRange(0, candles.size),
                    plotRect = Rect(0f, 0f, 1_000f, 300f),
                    valueRange = KlineValueRange(80.0, 120.0),
                    viewport = KlineViewport(candleWidthPx = 7f, candleSpacingPx = 1f),
                    config = KlineOrderMarkerRenderConfig(),
                    densityScale = 1f,
                )
            }
        }

        assertEquals(1, placements.size)
        assertTrue(elapsed < 5_000, "Indexed marker lookup budget exceeded: ${elapsed}ms")
    }

    private fun candle(timestampMillis: Long) = KlineCandle(
        timestampMillis = timestampMillis,
        open = 99.0,
        high = 105.0,
        low = 95.0,
        close = 101.0,
        volume = 10.0,
    )
}
