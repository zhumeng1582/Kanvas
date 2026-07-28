/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas.drawing

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

@Stable
class DrawingToolbarState internal constructor(
    initialPosition: Offset,
    private val onPositionChanged: (Offset) -> Unit,
) {
    var position by mutableStateOf(initialPosition)
        private set

    fun moveTo(position: Offset, containerSize: IntSize, toolbarSize: IntSize, keepFullyVisible: Boolean) {
        val resolved = if (keepFullyVisible && containerSize != IntSize.Zero && toolbarSize != IntSize.Zero) {
            Offset(
                x = position.x.coerceIn(0f, (containerSize.width - toolbarSize.width).coerceAtLeast(0).toFloat()),
                y = position.y.coerceIn(0f, (containerSize.height - toolbarSize.height).coerceAtLeast(0).toFloat()),
            )
        } else {
            position
        }
        this.position = resolved
        onPositionChanged(resolved)
    }
}

@Composable
fun rememberDrawingToolbarState(
    initialPosition: Offset = Offset.Zero,
    onPositionChanged: (Offset) -> Unit = {},
): DrawingToolbarState = remember(initialPosition) {
    DrawingToolbarState(initialPosition, onPositionChanged)
}

/** Draggable app-defined toolbar shown while an overlay is drawing or selected. */
@Composable
fun DrawingToolbar(
    controller: DrawingController,
    containerSize: IntSize,
    modifier: Modifier = Modifier,
    state: DrawingToolbarState = rememberDrawingToolbarState(),
    keepFullyVisible: Boolean = true,
    content: @Composable (DrawingController) -> Unit,
) {
    if (controller.snapshot.state is DrawingState.Exited || controller.snapshot.state is DrawingState.Prepared) return
    var toolbarSize by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier = modifier
            .offset { IntOffset(state.position.x.roundToInt(), state.position.y.roundToInt()) }
            .onSizeChanged { toolbarSize = it }
            .pointerInput(state, containerSize, toolbarSize, keepFullyVisible) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    state.moveTo(
                        position = state.position + dragAmount,
                        containerSize = containerSize,
                        toolbarSize = toolbarSize,
                        keepFullyVisible = keepFullyVisible,
                    )
                }
            },
    ) {
        content(controller)
    }
}
