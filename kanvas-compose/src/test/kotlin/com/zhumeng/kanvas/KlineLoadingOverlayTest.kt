package com.zhumeng.kanvas

import com.zhumeng.kanvas.core.KlineLoadingState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KlineLoadingOverlayTest {
    @Test
    fun `overlay uses visible loading states and the auto-load gate`() {
        assertTrue(shouldShowKlineLoadingOverlay(KlineLoadingState.InitLoading, autoLoadMore = true))
        assertTrue(shouldShowKlineLoadingOverlay(KlineLoadingState.LoadingMore, autoLoadMore = true))
        assertFalse(shouldShowKlineLoadingOverlay(KlineLoadingState.LoadMore, autoLoadMore = true))
        assertFalse(shouldShowKlineLoadingOverlay(KlineLoadingState.None, autoLoadMore = true))
        assertFalse(shouldShowKlineLoadingOverlay(KlineLoadingState.InitLoading, autoLoadMore = false))
    }

    @Test
    fun `root loading render uses documented defaults`() {
        val config = KlineLoadingRenderConfig()

        assertEquals(26f, config.sizePx)
        assertEquals(4f, config.strokeWidthPx)
    }
}
