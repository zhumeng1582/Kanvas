/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas.drawing

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.zhumeng.kanvas.core.KlineCandle
import java.util.UUID
import java.util.RandomAccess

/** O(1) immutable replacement used by repeated drags of the same overlay. */
private class DrawingOverlayReplacementList(
    private val source: List<DrawingOverlay>,
    private val replacementIndex: Int,
    private val replacement: DrawingOverlay,
) : AbstractList<DrawingOverlay>(), RandomAccess {
    override val size: Int get() = source.size

    override fun get(index: Int): DrawingOverlay =
        if (index == replacementIndex) replacement else source[index]

    companion object {
        fun replace(source: List<DrawingOverlay>, index: Int, overlay: DrawingOverlay): List<DrawingOverlay> {
            val flattenedSource = (source as? DrawingOverlayReplacementList)
                ?.takeIf { it.replacementIndex == index }
                ?.source
                ?: source
            return DrawingOverlayReplacementList(flattenedSource, index, overlay)
        }
    }
}

@Immutable
data class DrawingSnapshot(
    val symbol: String = "",
    val overlays: List<DrawingOverlay> = emptyList(),
    val state: DrawingState = DrawingState.Exited,
    val magnetMode: DrawingMagnetMode = DrawingMagnetMode.Normal,
    val pointer: Offset? = null,
    val visible: Boolean = true,
    val continuous: Boolean = false,
)

fun interface DrawingOverlayStore {
    fun save(symbol: String, overlays: List<DrawingOverlay>)
}

class DrawingController(
    private val registry: DrawingToolRegistry = DrawingToolRegistry(),
    private val store: DrawingOverlayStore = DrawingOverlayStore { _, _ -> },
) {
    private val undoStack = ArrayDeque<List<DrawingOverlay>>()
    private val redoStack = ArrayDeque<List<DrawingOverlay>>()

    // Every controller mutation is explicit and already equality-guarded where
    // needed. Avoid structural List equality on gesture-rate overlay updates.
    var snapshot: DrawingSnapshot by mutableStateOf(DrawingSnapshot(), neverEqualPolicy())
        private set

    private var cachedOverlayId: String? = null
    private var cachedOverlayIndex: Int = -1

    val canUndo: Boolean get() = undoStack.size > 1
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun toolFor(type: DrawingTypeDescriptor): DrawingTool? = registry[type]

    fun switchSymbol(symbol: String, restored: List<DrawingOverlay> = emptyList()) {
        if (symbol == snapshot.symbol) return
        persist()
        snapshot = DrawingSnapshot(
            symbol = symbol,
            overlays = restored.filter { it.symbol == symbol }.sortedBy(DrawingOverlay::zIndex),
            state = if (snapshot.visible) DrawingState.Prepared else DrawingState.Exited,
            magnetMode = snapshot.magnetMode,
            visible = snapshot.visible,
            continuous = snapshot.continuous,
        )
        resetHistory(snapshot.overlays)
    }

    fun prepare(type: DrawingTypeDescriptor, line: DrawingLineStyle = DrawingLineStyle()): DrawingOverlay {
        requireNotNull(registry[type]) { "No drawing tool registered for $type." }
        require(snapshot.symbol.isNotBlank()) { "Select a symbol before drawing." }
        val overlay = DrawingOverlay(
            id = UUID.randomUUID().toString(),
            symbol = snapshot.symbol,
            type = type,
            line = line,
            zIndex = (snapshot.overlays.maxOfOrNull(DrawingOverlay::zIndex) ?: -1) + 1,
        )
        snapshot = snapshot.copy(
            overlays = snapshot.overlays + overlay,
            state = DrawingState.Drawing(overlay.id, 0),
        )
        return overlay
    }

    fun prepare(type: DrawingTypeDescriptor, config: DrawingRenderConfig): DrawingOverlay =
        prepare(type, config.drawLine)

    fun updatePointer(position: Offset?) {
        if (snapshot.pointer == position) return
        snapshot = snapshot.copy(pointer = position)
    }

    fun confirmPoint(point: DrawingPoint, continueDrawing: Boolean = false): DrawingState {
        val drawing = snapshot.state as? DrawingState.Drawing ?: return snapshot.state
        val overlay = snapshot.overlays[overlayIndex(drawing.overlayId)]
        val points = overlay.points.toMutableList().apply { this[drawing.pointerIndex] = point }
        val updated = overlay.copy(points = points)
        val next = points.indexOfFirst { it == null }
        val state = if (next >= 0) {
            DrawingState.Drawing(updated.id, next)
        } else {
            DrawingState.Editing(updated.id)
        }
        replace(updated, state, pointer = null)
        if (state is DrawingState.Editing) {
            persist()
            recordHistory()
            if (continueDrawing || snapshot.continuous) {
                prepare(updated.type, updated.line)
                return snapshot.state
            }
        }
        return snapshot.state
    }

    fun select(position: Offset, space: DrawingCoordinateSpace, maxDistancePx: Float = 10f): DrawingOverlay? {
        val selected = snapshot.overlays.asReversed().firstOrNull { overlay ->
            registry[overlay.type]?.let { tool ->
                tool.isVisible(overlay, space, marginPx = maxDistancePx) &&
                    tool.hitTest(overlay, space, position, maxDistancePx)
            } == true
        }
        snapshot = snapshot.copy(
            state = selected?.let { DrawingState.Editing(it.id) } ?: DrawingState.Exited,
            pointer = null,
        )
        return selected
    }

    fun selectPoint(position: Offset, space: DrawingCoordinateSpace, maxDistancePx: Float = 10f): Int? {
        val editing = snapshot.state as? DrawingState.Editing ?: return null
        val overlay = snapshot.overlays[overlayIndex(editing.overlayId)]
        val pointIndex = overlay.points.indices.minByOrNull { index ->
            overlay.points[index]?.let(space::project)?.let { (it - position).getDistance() } ?: Float.MAX_VALUE
        }?.takeIf { index ->
            overlay.points[index]?.let(space::project)?.let { (it - position).getDistance() <= maxDistancePx } == true
        }
        snapshot = snapshot.copy(state = editing.copy(selectedPointIndex = pointIndex), pointer = position)
        return pointIndex
    }

    fun moveSelectedPoint(point: DrawingPoint): Boolean {
        return moveSelectedPoint(point, snapshot.pointer)
    }

    /** Moves one handle and publishes its pointer position in the same snapshot. */
    fun moveSelectedPoint(point: DrawingPoint, pointer: Offset?): Boolean {
        val editing = snapshot.state as? DrawingState.Editing ?: return false
        val index = editing.selectedPointIndex ?: return false
        val overlayIndex = overlayIndex(editing.overlayId)
        if (overlayIndex < 0) return false
        val overlay = snapshot.overlays[overlayIndex]
        if (overlay.locked) return false
        val points = overlay.points.toMutableList().apply { this[index] = point }
        replace(overlay.copy(points = points), editing, pointer = pointer, currentIndex = overlayIndex)
        return true
    }

    fun finishPointMove() {
        val editing = snapshot.state as? DrawingState.Editing ?: return
        persist()
        recordHistory()
        snapshot = snapshot.copy(
            state = editing.copy(selectedPointIndex = null),
            pointer = null,
        )
    }

    fun finishOverlayMove() {
        if (snapshot.state !is DrawingState.Editing) return
        persist()
        recordHistory()
        snapshot = snapshot.copy(pointer = null)
    }

    fun moveSelectedBy(deltaTimestampMillis: Long, deltaValue: Double): Boolean {
        return moveSelectedBy(deltaTimestampMillis, deltaValue, snapshot.pointer)
    }

    /** Moves the selected overlay and pointer atomically for one drag event. */
    fun moveSelectedBy(
        deltaTimestampMillis: Long,
        deltaValue: Double,
        pointer: Offset?,
    ): Boolean {
        val editing = snapshot.state as? DrawingState.Editing ?: return false
        val overlayIndex = overlayIndex(editing.overlayId)
        if (overlayIndex < 0) return false
        val overlay = snapshot.overlays[overlayIndex]
        if (overlay.locked || !overlay.isComplete) return false
        val points = overlay.points.map { point ->
            point?.copy(
                timestampMillis = point.timestampMillis + deltaTimestampMillis,
                value = point.value + deltaValue,
            )
        }
        replace(overlay.copy(points = points), editing, pointer = pointer, currentIndex = overlayIndex)
        return true
    }

    fun finishEditing() {
        persist()
        snapshot = snapshot.copy(state = DrawingState.Exited, pointer = null)
    }

    fun cancel() {
        val drawing = snapshot.state as? DrawingState.Drawing
        snapshot = if (drawing != null) {
            snapshot.copy(
                overlays = snapshot.overlays.filterNot { it.id == drawing.overlayId },
                state = DrawingState.Exited,
                pointer = null,
            )
        } else {
            snapshot.copy(state = DrawingState.Exited, pointer = null)
        }
    }

    fun removeSelected(): Boolean {
        val id = (snapshot.state as? DrawingState.Editing)?.overlayId ?: return false
        snapshot = snapshot.copy(
            overlays = snapshot.overlays.filterNot { it.id == id },
            state = DrawingState.Exited,
            pointer = null,
        )
        persist()
        recordHistory()
        return true
    }

    fun removeAll() {
        snapshot = snapshot.copy(
            overlays = emptyList(),
            state = DrawingState.Prepared,
            pointer = null,
        )
        persist()
        recordHistory()
    }

    fun setVisible(visible: Boolean) {
        snapshot = snapshot.copy(
            visible = visible,
            state = if (visible) DrawingState.Prepared else DrawingState.Exited,
            pointer = null,
        )
    }

    fun isSelectedOnTop(): Boolean = selectedOverlay()?.let { selected ->
        snapshot.overlays.maxByOrNull(DrawingOverlay::zIndex)?.id == selected.id
    } ?: false

    fun isSelectedOnBottom(): Boolean = selectedOverlay()?.let { selected ->
        snapshot.overlays.minByOrNull(DrawingOverlay::zIndex)?.id == selected.id
    } ?: false

    fun updateSelected(transform: (DrawingOverlay) -> DrawingOverlay): Boolean {
        val editing = snapshot.state as? DrawingState.Editing ?: return false
        val overlay = snapshot.overlays[overlayIndex(editing.overlayId)]
        replace(transform(overlay).copy(id = overlay.id, symbol = overlay.symbol, type = overlay.type), editing)
        persist()
        recordHistory()
        return true
    }

    fun setSelectedLocked(locked: Boolean): Boolean =
        updateSelected { it.copy(locked = locked) }

    fun setSelectedLineStyle(
        color: androidx.compose.ui.graphics.Color? = null,
        strokeWidthPx: Float? = null,
        lineType: String? = null,
    ): Boolean = updateSelected { overlay ->
        val resolvedType = lineType ?: overlay.line.lineType
        overlay.copy(
            line = overlay.line.copy(
                color = color ?: overlay.line.color,
                strokeWidthPx = strokeWidthPx ?: overlay.line.strokeWidthPx,
                dashed = when (resolvedType) {
                    "dashed" -> true
                    "solid" -> false
                    else -> overlay.line.dashed
                },
                lineType = resolvedType,
            ),
        )
    }

    fun moveSelectedToTop(): Boolean = reorderSelected(top = true)
    fun moveSelectedToBottom(): Boolean = reorderSelected(top = false)

    /** Reverts the last committed overlay mutation and exits the current gesture. */
    fun undo(): Boolean {
        if (!canUndo) return false
        redoStack.addLast(undoStack.removeLast())
        restoreHistory(undoStack.last())
        return true
    }

    /** Reapplies the last reverted overlay mutation. */
    fun redo(): Boolean {
        if (!canRedo) return false
        val restored = redoStack.removeLast()
        undoStack.addLast(restored)
        restoreHistory(restored)
        return true
    }

    fun setContinuous(continuous: Boolean) {
        val drawing = snapshot.state as? DrawingState.Drawing
        snapshot = if (!continuous && drawing != null) {
            snapshot.copy(
                overlays = snapshot.overlays.filterNot { it.id == drawing.overlayId },
                state = DrawingState.Prepared,
                pointer = null,
                continuous = false,
            )
        } else {
            snapshot.copy(continuous = continuous)
        }
    }

    fun setMagnetMode(mode: DrawingMagnetMode) {
        snapshot = snapshot.copy(magnetMode = mode)
    }

    fun snap(
        position: Offset,
        space: DrawingCoordinateSpace,
        candles: List<KlineCandle>,
        minDistancePx: Float = 10f,
    ): DrawingPoint? {
        val raw = space.unproject(position) ?: return null
        if (snapshot.magnetMode == DrawingMagnetMode.Normal) return raw
        val index = space.series.indexAtOrBefore(raw.timestampMillis) ?: return raw
        val candle = candles.getOrNull(index) ?: return raw
        val candleX = space.viewport.xForIndex(space.plotRect.right, index.toDouble())
        var nearestValue = candle.open
        var nearestDistance = Float.MAX_VALUE
        repeat(4) { candidateIndex ->
            val value = when (candidateIndex) {
                0 -> candle.open
                1 -> candle.high
                2 -> candle.low
                else -> candle.close
            }
            val distance = (Offset(candleX, space.valueToY(value)) - position).getDistance()
            if (distance < nearestDistance) {
                nearestValue = value
                nearestDistance = distance
            }
        }
        val nearest = DrawingPoint(candle.timestampMillis, nearestValue)
        return when (snapshot.magnetMode) {
            DrawingMagnetMode.Normal -> raw
            DrawingMagnetMode.Strong -> nearest
            DrawingMagnetMode.Weak -> nearest.takeIf { nearestDistance <= minDistancePx } ?: raw
        }
    }

    fun persist() {
        if (snapshot.symbol.isNotBlank()) {
            store.save(snapshot.symbol, snapshot.overlays.filter(DrawingOverlay::isComplete))
        }
    }

    private fun replace(
        overlay: DrawingOverlay,
        state: DrawingState,
        pointer: Offset? = snapshot.pointer,
        currentIndex: Int = overlayIndex(overlay.id),
    ) {
        if (currentIndex < 0) return
        val previous = snapshot.overlays[currentIndex]
        val overlays = if (previous.zIndex == overlay.zIndex) {
            DrawingOverlayReplacementList.replace(snapshot.overlays, currentIndex, overlay)
        } else {
            snapshot.overlays.toMutableList().apply {
                this[currentIndex] = overlay
                sortBy(DrawingOverlay::zIndex)
            }
        }
        snapshot = snapshot.copy(
            overlays = overlays,
            state = state,
            pointer = pointer,
        )
    }

    private fun reorderSelected(top: Boolean): Boolean {
        val editing = snapshot.state as? DrawingState.Editing ?: return false
        val overlay = snapshot.overlays[overlayIndex(editing.overlayId)]
        val targetZ = if (top) {
            (snapshot.overlays.maxOfOrNull(DrawingOverlay::zIndex) ?: 0) + 1
        } else {
            (snapshot.overlays.minOfOrNull(DrawingOverlay::zIndex) ?: 0) - 1
        }
        replace(overlay.copy(zIndex = targetZ), editing)
        persist()
        recordHistory()
        return true
    }

    private fun selectedOverlay(): DrawingOverlay? {
        val id = (snapshot.state as? DrawingState.Editing)?.overlayId ?: return null
        val index = overlayIndex(id)
        return snapshot.overlays.getOrNull(index)
    }

    private fun overlayIndex(id: String): Int {
        if (
            cachedOverlayId == id &&
            cachedOverlayIndex in snapshot.overlays.indices &&
            snapshot.overlays[cachedOverlayIndex].id == id
        ) {
            return cachedOverlayIndex
        }
        val index = snapshot.overlays.indexOfFirst { it.id == id }
        cachedOverlayId = id
        cachedOverlayIndex = index
        return index
    }

    private fun completeOverlays(): List<DrawingOverlay> = snapshot.overlays.filter(DrawingOverlay::isComplete)

    private fun resetHistory(overlays: List<DrawingOverlay>) {
        undoStack.clear()
        redoStack.clear()
        undoStack.addLast(overlays.filter(DrawingOverlay::isComplete))
    }

    private fun recordHistory() {
        val current = completeOverlays()
        if (undoStack.lastOrNull() != current) {
            undoStack.addLast(current)
            redoStack.clear()
        }
    }

    private fun restoreHistory(overlays: List<DrawingOverlay>) {
        snapshot = snapshot.copy(
            overlays = overlays,
            state = DrawingState.Prepared,
            pointer = null,
        )
        persist()
    }
}
