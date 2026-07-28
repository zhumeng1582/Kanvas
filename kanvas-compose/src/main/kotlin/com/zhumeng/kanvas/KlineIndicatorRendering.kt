/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.zhumeng.kanvas.core.IndexRange
import com.zhumeng.kanvas.core.IndicatorDefinition
import com.zhumeng.kanvas.core.IndicatorKind
import com.zhumeng.kanvas.core.IndicatorKey
import com.zhumeng.kanvas.core.IndicatorOutput
import com.zhumeng.kanvas.core.IndicatorPaintMode
import com.zhumeng.kanvas.core.IndicatorPlacement
import com.zhumeng.kanvas.core.IndicatorRegistrySnapshot
import com.zhumeng.kanvas.core.IndicatorRuntimeSnapshot
import com.zhumeng.kanvas.core.KlineUiState
import com.zhumeng.kanvas.core.KlineCandle
import com.zhumeng.kanvas.core.KlineViewport
import com.zhumeng.kanvas.core.Volume
import com.zhumeng.kanvas.core.Macd
import com.zhumeng.kanvas.core.SuperTrend

/**
 * Renderer SPI for native Kotlin indicators.
 *
 * Core persists renderer-neutral indicator keys and configuration. A host
 * registers a renderer for each implementation it wants to display. Returning
 * `false` from [supports]
 * leaves a declaration active in Core while omitting its Canvas pane, which
 * preserves the raw serialized record without drawing an empty chart area.
 */
interface KlineIndicatorRenderer {
    /**
     * Pure, deterministic matcher for this declaration/output pair. The chart
     * may call it during composition and pointer hit-testing, so it must not
     * mutate state, perform I/O, or throw.
     */
    fun supports(
        definition: IndicatorDefinition,
        output: IndicatorOutput?,
    ): Boolean

    /**
     * Declares that this renderer owns an attached Computed declaration before
     * its first output arrives. The default asks [supports] with null;
     * output-dependent renderers should override
     * this method so their pane does not disappear while pending.
     */
    fun supportsPending(definition: IndicatorDefinition): Boolean = supports(definition, output = null)

    fun draw(
        scope: DrawScope,
        context: KlineIndicatorDrawContext,
    )

    /**
     * Optional visible numeric range owned by this renderer.
     *
     * Direct and External indicators commonly have no [IndicatorOutput], so
     * they cannot otherwise contribute to their main-combine, alone, or sub
     * pane scale. Return an unpadded raw range; the chart normalizes it with
     * the candle/output ranges before drawing. `null` means this renderer has
     * no numeric-axis contribution. The chart may evaluate this repeatedly
     * during composition, drawing, and pointer routing; implementations must
     * therefore be pure, deterministic, non-blocking, and non-throwing.
     */
    fun visibleValueRange(context: KlineIndicatorRangeContext): KlineIndicatorValueRange? = null
}

/**
 * Optional renderer capability for drawing an indicator overlay.
 *
 * The chart invokes this only for an indicator whose resolved pane has a
 * non-zero drawable rect. A sub-pane overlay runs after that pane's ordinary
 * painting and is intentionally not clipped; clip to
 * `context.indicator.pane.plotRect` inside the renderer when needed. A main
 * overlay follows [KlineIndicatorOverlayRenderConfig]: it either runs clipped
 * before the other panes or, by default, after them and may draw outside the
 * main rect. It is deliberately a separate capability so a basic line/bar
 * renderer does not accidentally inherit an overlay phase. Like ordinary
 * renderer drawing, this callback must not block or throw; a future chart-wide
 * error boundary will be an explicit API rather than silently swallowing it.
 */
interface KlineIndicatorOverlayRenderer {
    fun drawOverlay(
        scope: DrawScope,
        context: KlineIndicatorOverlayDrawContext,
    )
}

/** Canvas data plus the full frame geometry supplied to an overlay renderer. */
data class KlineIndicatorOverlayDrawContext(
    val indicator: KlineIndicatorDrawContext,
    val layout: KlineLayout,
    val policy: KlineIndicatorOverlayRenderConfig,
)

/** Immutable Cross selection shared with optional indicator cross/tap hooks. */
data class KlineIndicatorCrosshairContext(
    /** Pointer location clamped to the main plot before candle snapping. */
    val rawPosition: Offset,
    /** Actual cross-line position after candle snapping. */
    val position: Offset,
    val candleIndex: Int?,
    val candle: KlineCandle?,
    /** The older neighbour (`index + 1`) used by tooltip calculations. */
    val previousCandle: KlineCandle?,
    /**
     * Original Canvas pointer, retained for sub-pane cross/value interactions.
     * It is trailing so the previous five-field shape can remain available.
     */
    val inputPosition: Offset,
) {
    /**
     * Binary- and source-compatible constructor for the earlier Cross context
     * shape, before the original pointer was exposed to sub-pane renderers.
     */
    constructor(
        rawPosition: Offset,
        position: Offset,
        candleIndex: Int?,
        candle: KlineCandle?,
        previousCandle: KlineCandle?,
    ) : this(
        rawPosition = rawPosition,
        position = position,
        candleIndex = candleIndex,
        candle = candle,
        previousCandle = previousCandle,
        inputPosition = rawPosition,
    )
}

/**
 * Optional renderer capability for drawing crosshair content.
 *
 * The built-in Cross and Time label paint first; the chart then calls native
 * sub panes in physical layout order and main items in their established
 * z-order. [KlineIndicatorCrosshairContext.inputPosition] remains the
 * un-clamped pointer so a sub-pane renderer can safely use
 * [KlineIndicatorCrossDrawContext.valueAtPointer]. A blank-area Cross can
 * have no selected candle and is still delivered to this callback. This
 * callback runs in the Canvas draw path and must not block or throw.
 */
interface KlineIndicatorCrossRenderer {
    fun drawCross(
        scope: DrawScope,
        context: KlineIndicatorCrossDrawContext,
    )
}

/** Per-indicator Canvas data plus the currently selected Cross candle. */
data class KlineIndicatorCrossDrawContext(
    val indicator: KlineIndicatorDrawContext,
    val crosshair: KlineIndicatorCrosshairContext,
    val layout: KlineLayout,
    /** Null when the pointer lies outside this indicator's plot rect. */
    val valueAtPointer: Double?,
)

/** Optional renderer capability for consuming chart taps. */
interface KlineIndicatorTapHandler {
    /**
     * Called synchronously from the Chart pointer route. The off-view latest
     * price target gets first refusal, then main items run in z-order, followed
     * by sub panes in physical layout order. Return true to consume the tap and
     * prevent Cross from toggling. The chart deliberately does not impose a
     * pane-bound hit test because an indicator may own an overlay target; a
     * handler that should be local must test [KlineIndicatorTapContext.position]
     * against `context.indicator.pane.plotRect` itself. Stateful renderers can
     * mutate their business state and request a redraw through lifecycle
     * `invalidate()`; do not suspend, block, or throw here.
     */
    fun onTap(context: KlineIndicatorTapContext): Boolean
}

/** Hit-test input supplied to [KlineIndicatorTapHandler]. */
data class KlineIndicatorTapContext(
    val position: Offset,
    val indicator: KlineIndicatorDrawContext,
    val layout: KlineLayout,
    /** Null when Cross is inactive. */
    val crosshair: KlineIndicatorCrosshairContext?,
)

/** Resolves one renderer for an active indicator declaration. */
interface KlineIndicatorRendererResolver {
    fun resolve(
        definition: IndicatorDefinition,
        output: IndicatorOutput?,
    ): KlineIndicatorRenderer?

    /**
     * Contextual form used by the chart when a registry owns declaration
     * residency. Ordinary registries do not need that context; lifecycle
     * hosts use it to avoid returning an instance that belongs to an older
     * registry mount or generation during the composition before SideEffect
     * has reconciled it.
     */
    fun resolve(
        definition: IndicatorDefinition,
        output: IndicatorOutput?,
        registry: IndicatorRegistrySnapshot?,
    ): KlineIndicatorRenderer? = resolve(definition, output)

    /**
     * Full chart context for lifecycle-aware resolvers. The default keeps the
     * stable two-argument SPI sufficient for stateless renderers.
     */
    fun resolve(
        definition: IndicatorDefinition,
        output: IndicatorOutput?,
        state: KlineUiState,
        registry: IndicatorRegistrySnapshot?,
    ): KlineIndicatorRenderer? = resolve(definition, output, registry)
}

/**
 * Factory for a per-key stateful renderer instance.
 *
 * Unlike [KlineIndicatorRenderer.supports], this match deliberately receives
 * no calculated output: a pane can be attached before a computed result
 * exists, and the factory must be able to preserve that pane.
 */
interface KlineStatefulIndicatorRendererFactory {
    /**
     * Pure, deterministic declaration matcher. It can run during composition
     * to reserve a pending pane, therefore it must not mutate state, perform
     * I/O, or throw.
     */
    fun supports(definition: IndicatorDefinition): Boolean

    fun create(definition: IndicatorDefinition): KlineStatefulIndicatorRenderer
}

/** Context common to stateful renderer init/attach/detach callbacks. */
data class KlineIndicatorLifecycleContext(
    val definition: IndicatorDefinition,
    val state: KlineUiState,
    val registry: IndicatorRegistrySnapshot,
    /** Thread-safe request for another Compose draw after external state changes. */
    val invalidate: () -> Unit,
)

/** Optional callback context after a renderer definition is updated. */
data class KlineIndicatorUpdateContext(
    val previous: IndicatorDefinition,
    val current: IndicatorDefinition,
    val state: KlineUiState,
    val registry: IndicatorRegistrySnapshot,
    val invalidate: () -> Unit,
)

/** Optional callback context after the chart symbol or interval changes. */
data class KlineIndicatorSpecChangeContext(
    val oldSpec: com.zhumeng.kanvas.core.KlineSpec?,
    val newSpec: com.zhumeng.kanvas.core.KlineSpec?,
    val definition: IndicatorDefinition,
    val state: KlineUiState,
    val registry: IndicatorRegistrySnapshot,
    val invalidate: () -> Unit,
)

/** Context supplied when a stateful renderer is permanently discarded. */
data class KlineIndicatorDisposeContext(
    val definition: IndicatorDefinition,
    /** Null only when the entire Compose host is disposed before its first reconciliation. */
    val state: KlineUiState?,
    val registry: IndicatorRegistrySnapshot?,
    val invalidate: () -> Unit,
)

/**
 * Optional instance lifecycle layered on top of the stable drawing SPI.
 *
 * The lifecycle host guarantees `onInit` at most once per instance and
 * `onDispose` at most once. `onAttach`/`onDetach` mirror registry active ↔
 * retained transitions. Callbacks run outside `DrawScope`; asynchronous
 * renderer work should call [KlineIndicatorLifecycleContext.invalidate] when
 * new state needs a frame.
 */
interface KlineStatefulIndicatorRenderer : KlineIndicatorRenderer {
    fun onInit(context: KlineIndicatorLifecycleContext) = Unit

    fun onAttach(context: KlineIndicatorLifecycleContext) = Unit

    fun onUpdate(context: KlineIndicatorUpdateContext) = Unit

    fun onSpecChanged(context: KlineIndicatorSpecChangeContext) = Unit

    fun onDetach(context: KlineIndicatorLifecycleContext) = Unit

    fun onDispose(context: KlineIndicatorDisposeContext) = Unit
}

/** Unpadded finite data range reported by a [KlineIndicatorRenderer]. */
data class KlineIndicatorValueRange(
    val minimum: Double,
    val maximum: Double,
) {
    init {
        require(minimum.isFinite() && maximum.isFinite()) { "Indicator value range must be finite" }
        require(minimum <= maximum) { "Indicator value range minimum must not exceed maximum" }
    }
}

/** Immutable range query input supplied before a renderer is asked to draw. */
data class KlineIndicatorRangeContext(
    val state: KlineUiState,
    val definition: IndicatorDefinition,
    /** Null means the current runtime snapshot is absent or stale. */
    val output: IndicatorOutput?,
    val paintRange: IndexRange,
    val viewport: KlineViewport,
)

/** Immutable draw input given to one [KlineIndicatorRenderer]. */
data class KlineIndicatorDrawContext(
    val state: KlineUiState,
    val definition: IndicatorDefinition,
    /** Null is valid for Direct/External Kotlin renderers. */
    val output: IndicatorOutput?,
    val pane: KlinePaneLayout,
    val paintRange: IndexRange,
    val valueRange: KlineValueRange,
    val viewport: KlineViewport,
    val style: KlineChartStyle,
    /** First theme line color reserved for this item. */
    val colorIndex: Int,
    /** Compose density at the Canvas boundary. */
    val densityScale: Float,
) {
    /** X center of a newest-first candle/indicator sample. */
    fun xForIndex(index: Double): Float =
        viewport.xForIndex(pane.plotRect.right, index) - viewport.candleHalfStepPx

    /** Maps an indicator value into this item's local pane range. */
    fun yFor(value: Double): Float =
        pane.plotRect.bottom - ((value - valueRange.minimum) / valueRange.span).toFloat() * pane.plotRect.height
}

/** Ordered renderer registry; the first matching renderer owns an indicator item. */
class KlineIndicatorRendererRegistry(
    renderers: Iterable<KlineIndicatorRenderer> = Default.renderers(),
    statefulFactories: Iterable<KlineStatefulIndicatorRendererFactory> = emptyList(),
) : KlineIndicatorRendererResolver {
    private val rendererStorage = renderers.toList()
    private val statefulFactoryStorage = statefulFactories.toList()

    override fun resolve(
        definition: IndicatorDefinition,
        output: IndicatorOutput?,
    ): KlineIndicatorRenderer? = rendererStorage.firstOrNull { renderer ->
        if (output == null) renderer.supportsPending(definition) else renderer.supports(definition, output)
    }

    fun renderers(): List<KlineIndicatorRenderer> = rendererStorage.toList()

    /**
     * First matching per-key factory. A lifecycle host gives a matching
     * factory priority over stateless [renderers]; the two ordered lists are
     * intentionally independent.
     */
    fun resolveStatefulFactory(definition: IndicatorDefinition): KlineStatefulIndicatorRendererFactory? =
        statefulFactoryStorage.firstOrNull { factory -> factory.supports(definition) }

    fun statefulFactories(): List<KlineStatefulIndicatorRendererFactory> = statefulFactoryStorage.toList()

    companion object {
        /** Default renderers for computed lines and volume columns. */
        val Default: KlineIndicatorRendererRegistry = KlineIndicatorRendererRegistry(
            renderers = listOf(KlineVolumeIndicatorRenderer, KlineComputedLineIndicatorRenderer),
        )
    }
}

/** Default renderer for the Android example `Volume` output column. */
object KlineVolumeIndicatorRenderer : KlineIndicatorRenderer {
    override fun supports(definition: IndicatorDefinition, output: IndicatorOutput?): Boolean =
        definition.calculator === Volume &&
            // An attached Computed indicator owns its pane before its first
            // calculation finishes. Keep the Volume renderer selected during
            // that pending interval, but remain strict once columns exist so
            // a multi-column Volume-derived output falls through to lines.
            (output == null || output.columns().singleOrNull()?.name == Volume.ColumnName)

    override fun draw(scope: DrawScope, context: KlineIndicatorDrawContext) {
        val column = context.output?.column(Volume.ColumnName) ?: return
        scope.drawVolumeBars(
            candles = context.state.series.candles,
            valuesColumn = column,
            range = context.paintRange,
            plotRect = context.pane.plotRect,
            values = context.valueRange,
            viewport = context.viewport,
            style = context.style,
        )
    }
}

/** Default renderer for non-volume computed columns, including Android MA examples. */
object KlineComputedLineIndicatorRenderer : KlineIndicatorRenderer, KlineIndicatorTopTipsRenderer {
    override fun supports(definition: IndicatorDefinition, output: IndicatorOutput?): Boolean =
        // Keep an attached computed renderer and its pane geometry while a
        // calculation is pending. An empty/null output
        // simply makes draw() a no-op for this frame.
        definition.key.kind == IndicatorKind.COMPUTED

    override fun visibleValueRange(context: KlineIndicatorRangeContext): KlineIndicatorValueRange? {
        val config = context.definition.configuration as? KlineRsiIndicatorConfig ?: return null
        return KlineIndicatorValueRange(config.lower, config.upper)
    }

    override fun draw(scope: DrawScope, context: KlineIndicatorDrawContext) {
        val output = context.output ?: return
        val configuredStyles = when (val config = context.definition.configuration) {
            is KlineMovingAverageIndicatorConfig -> config.lineStyles
            is KlineSinglePeriodIndicatorConfig -> config.lineStyles
            is KlineTriplePeriodIndicatorConfig -> config.lineStyles
            is KlineStyledIndicatorConfig -> config.lineStyles
            is KlineSarIndicatorConfig -> config.lineStyles
            is KlineSuperTrendIndicatorConfig -> config.lineStyles
            is KlineRsiIndicatorConfig -> config.lineStyles
            is KlineObvIndicatorConfig -> config.lineStyles
            is KlineStochasticRsiIndicatorConfig -> config.lineStyles
            else -> emptyList()
        }
        if (context.definition.calculator is Macd) {
            val histogram = output.column("histogram")
            val histogramVisible = configuredStyles.getOrNull(2)?.visible != false
            val zeroY = context.yFor(0.0)
            for (index in if (histogramVisible) context.paintRange.startInclusive until context.paintRange.endExclusive else IntRange.EMPTY) {
                val value = histogram?.get(index) ?: continue
                if (!value.isFinite()) continue
                val x = context.xForIndex(index.toDouble())
                val half = (context.viewport.candleHalfStepPx * 0.72f).coerceAtLeast(1f)
                scope.drawRect(
                    color = if (value >= 0.0) context.style.bullish else context.style.bearish,
                    topLeft = androidx.compose.ui.geometry.Offset(x - half, minOf(zeroY, context.yFor(value))),
                    size = androidx.compose.ui.geometry.Size(half * 2f, kotlin.math.abs(context.yFor(value) - zeroY).coerceAtLeast(1f)),
                )
            }
        }
        if (context.definition.calculator is SuperTrend) {
            val up = output.column(SuperTrend.UpColumn)
            val down = output.column(SuperTrend.DownColumn)
            for (index in context.paintRange.startInclusive until context.paintRange.endExclusive) {
                val upValue = up?.get(index) ?: Double.NaN
                val downValue = down?.get(index) ?: Double.NaN
                val isUp = upValue.isFinite()
                val value = if (isUp) upValue else downValue
                if (!value.isFinite()) continue
                val backgroundStyle = configuredStyles.getOrNull(if (isUp) 2 else 3) ?: continue
                if (!backgroundStyle.visible) continue
                val color = backgroundStyle.color ?: continue
                val x = context.xForIndex(index.toDouble())
                val half = context.viewport.candleHalfStepPx.coerceAtLeast(1f)
                val closeY = context.yFor(context.state.series[index].close)
                val trendY = context.yFor(value)
                scope.drawRect(
                    color = color.copy(alpha = color.alpha.coerceAtMost(0.28f)),
                    topLeft = androidx.compose.ui.geometry.Offset(x - half, minOf(closeY, trendY)),
                    size = androidx.compose.ui.geometry.Size(half * 2f, kotlin.math.abs(closeY - trendY).coerceAtLeast(1f)),
                )
            }
        }
        (context.definition.configuration as? KlineRsiIndicatorConfig)?.let { config ->
            val thresholdStyles = listOf(
                config.upper to configuredStyles.getOrNull(config.periods.size),
                config.lower to configuredStyles.getOrNull(config.periods.size + 1),
            )
            thresholdStyles.forEach { (value, lineStyle) ->
                if (lineStyle?.visible == false) return@forEach
                val color = lineStyle?.color ?: context.style.gridLine
                val y = context.yFor(value)
                scope.drawLine(
                    color = color,
                    start = androidx.compose.ui.geometry.Offset(context.pane.plotRect.left, y),
                    end = androidx.compose.ui.geometry.Offset(context.pane.plotRect.right, y),
                    strokeWidth = (lineStyle?.widthPx ?: 1f) * context.densityScale,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 5f)),
                )
            }
        }
        val excludedColumns = buildSet {
            if (context.definition.calculator is Macd) add("histogram")
            if (context.definition.calculator is SuperTrend) add(SuperTrend.ColumnName)
        }
        scope.drawIndicatorLines(
            outputs = listOf(output),
            range = context.paintRange,
            plotRect = context.pane.plotRect,
            values = context.valueRange,
            viewport = context.viewport,
            style = context.style,
            colorOffset = context.colorIndex,
            lineStyles = configuredStyles,
            excludedColumns = excludedColumns,
        )
    }

    override fun prepareTopTips(context: KlineIndicatorTopTipsPrepareContext): KlineIndicatorTopTipsPrepared? {
        val output = context.indicator.output ?: return null
        val index = when (val selection = context.selection) {
            is KlineIndicatorTopTipsSelection.Latest -> 0
            is KlineIndicatorTopTipsSelection.Cross -> selection.crosshair.candleIndex ?: return null
        }
        if (index !in 0 until output.seriesSize) return null
        val values = output.columns().mapNotNull { column -> column[index].takeIf(Double::isFinite) }
       if (values.isEmpty()) return null
       return object : KlineIndicatorTopTipsPrepared { override val claimedHeightPx: Float = (16f + 4f) * context.indicator.densityScale }
   }

    override fun drawTopTips(scope: DrawScope, context: KlineIndicatorTopTipsDrawContext) {
        val output = context.indicator.output ?: return
        val index = when (val selection = context.selection) {
            is KlineIndicatorTopTipsSelection.Latest -> 0
            is KlineIndicatorTopTipsSelection.Cross -> selection.crosshair.candleIndex ?: return
        }
        val parts = output.columns().mapIndexedNotNull { columnIndex, column ->
            if (context.indicator.definition.calculator is SuperTrend && column.name == SuperTrend.ColumnName) {
                return@mapIndexedNotNull null
            }
            val configuredStyles = when (val config = context.indicator.definition.configuration) {
                is KlineMovingAverageIndicatorConfig -> config.lineStyles
                is KlineSinglePeriodIndicatorConfig -> config.lineStyles
                is KlineTriplePeriodIndicatorConfig -> config.lineStyles
                is KlineStyledIndicatorConfig -> config.lineStyles
                is KlineSarIndicatorConfig -> config.lineStyles
                is KlineSuperTrendIndicatorConfig -> config.lineStyles
                is KlineRsiIndicatorConfig -> config.lineStyles
                is KlineObvIndicatorConfig -> config.lineStyles
                is KlineStochasticRsiIndicatorConfig -> config.lineStyles
                else -> emptyList()
            }
            if (configuredStyles.getOrNull(columnIndex)?.visible == false) return@mapIndexedNotNull null
            val value = column[index].takeIf(Double::isFinite) ?: return@mapIndexedNotNull null
            val displayName = when {
                column.name.startsWith("ema_") -> "EMA(${column.name.removePrefix("ema_")})"
                column.name.startsWith("ma_") -> "MA(${column.name.removePrefix("ma_")})"
                column.name == "boll_mid" -> "MID"
                column.name == "boll_upper" -> "UPPER"
                column.name == "boll_lower" -> "LOWER"
                else -> column.name.uppercase()
            }
            val fallbackColors = context.indicator.style.indicatorLines.ifEmpty { listOf(context.indicator.style.line) }
            val color = configuredStyles.getOrNull(columnIndex)?.color
                ?: fallbackColors[(context.indicator.colorIndex + columnIndex) % fallbackColors.size]
            "$displayName: ${"%.2f".format(java.util.Locale.US, value)}" to color
        }
       val tipPadding = 4f * context.indicator.densityScale
       var x = context.placement.geometry.tipsRect.left + tipPadding
       val y = context.placement.geometry.tipsRect.top + tipPadding
       parts.forEach { (text, color) ->
           val layout = context.textMeasurer.measure(text, TextStyle(color = color, fontSize = 10.sp))
           scope.drawText(layout, topLeft = androidx.compose.ui.geometry.Offset(x, y))
           x += layout.size.width + 8f * context.indicator.densityScale
       }
   }
}

/** Point renderer for Parabolic SAR; scoped by the native plugin binding. */
object KlineSarIndicatorRenderer : KlineIndicatorRenderer {
    override fun supports(definition: IndicatorDefinition, output: IndicatorOutput?): Boolean =
        definition.calculator is com.zhumeng.kanvas.core.ParabolicSar

    override fun draw(scope: DrawScope, context: KlineIndicatorDrawContext) {
        val column = context.output?.column(com.zhumeng.kanvas.core.ParabolicSar.ColumnName) ?: return
        val configured = (context.definition.configuration as? KlineSarIndicatorConfig)?.lineStyles?.firstOrNull()
        val colors = context.style.indicatorLines.ifEmpty { listOf(context.style.line) }
        val color = configured?.color ?: colors[context.colorIndex % colors.size]
        val radius = ((configured?.widthPx ?: 1.25f) * 1.65f * context.densityScale).coerceAtLeast(1.5f)
        for (index in context.paintRange.startInclusive until context.paintRange.endExclusive) {
            val value = column[index]
            if (!value.isFinite()) continue
            scope.drawCircle(
                color = color,
                radius = radius,
                center = androidx.compose.ui.geometry.Offset(context.xForIndex(index.toDouble()), context.yFor(value)),
                style = Stroke(width = (configured?.widthPx ?: 1.25f) * context.densityScale),
            )
        }
    }
}

/** One active declaration that has a renderer and can participate in Canvas layout. */
internal data class KlineIndicatorRenderItem(
    val definition: IndicatorDefinition,
    val output: IndicatorOutput?,
    val renderer: KlineIndicatorRenderer,
    val declarationOrder: Int,
    val colorIndex: Int = 0,
)

/** Geometry owned by one resolved sub-pane. */
internal data class KlineIndicatorSubPaneSpec(
    val id: String,
    /** One hint per member; null fields retain each member's host fallback. */
    val layoutHints: List<com.zhumeng.kanvas.core.IndicatorLayoutHint>,
)

/**
 * Renderer-ready layout plan. Main combine items share the Candle range;
 * main alone items receive their own bottom-aligned internal rect later;
 * sub-pane order follows registry FIFO when a lifecycle snapshot is supplied.
 */
internal data class KlineIndicatorPanePlan(
    /** One z-index ordered main sequence; combine/alone must not form paint passes. */
    val mainPaintOrder: List<KlineIndicatorRenderItem>,
    val subByPane: LinkedHashMap<String, List<KlineIndicatorRenderItem>>,
    /**
     * Per-member hints rather than one eagerly merged value. `null` in an
     * [com.zhumeng.kanvas.core.IndicatorLayoutHint] means inherit
     * the host fallback, which cannot be represented by treating it as zero.
     */
    val subPaneLayoutHints: LinkedHashMap<String, List<com.zhumeng.kanvas.core.IndicatorLayoutHint>>,
    val unsupportedDefinitions: List<IndicatorDefinition>,
) {
    val mainCombine: List<KlineIndicatorRenderItem> =
        mainPaintOrder.filter { it.definition.paintMode == IndicatorPaintMode.COMBINE }

    val mainAlone: List<KlineIndicatorRenderItem> =
        mainPaintOrder.filter { it.definition.paintMode == IndicatorPaintMode.ALONE }

    val mainCombineOutputs: List<IndicatorOutput> =
        mainCombine.mapNotNull(KlineIndicatorRenderItem::output)

    val subPaneSpecs: List<KlineIndicatorSubPaneSpec> =
        subByPane.keys.map { id ->
            KlineIndicatorSubPaneSpec(id = id, layoutHints = checkNotNull(subPaneLayoutHints[id]))
        }

    companion object {
        val Empty: KlineIndicatorPanePlan = KlineIndicatorPanePlan(
            mainPaintOrder = emptyList(),
            subByPane = linkedMapOf(),
            subPaneLayoutHints = linkedMapOf(),
            unsupportedDefinitions = emptyList(),
        )
    }
}

internal fun IndicatorRuntimeSnapshot?.resolveIndicatorPanePlan(
    state: KlineUiState,
    registry: IndicatorRegistrySnapshot?,
    renderers: KlineIndicatorRendererResolver,
): KlineIndicatorPanePlan {
    // A registry is the authoritative declaration/lifecycle source. Direct
    // and External renderers must remain drawable while a computed snapshot is
    // absent or stale; only output data is gated by freshness.
    val freshSnapshot = this?.takeIf { snapshot ->
        if (registry != null) {
            snapshot.matches(state, registry)
        } else {
            snapshot.matches(state)
        }
    }
    val definitions = when {
        registry != null -> {
            // Keep the main z-index tie-break deterministic while retaining
            // Preserve FIFO activation order for ordinary sub panes.
            registry.activeMainDefinitions() + registry.activeSubDefinitions()
        }

        freshSnapshot != null -> freshSnapshot.definitions()
        else -> emptyList()
    }

    data class OrderedItem(
        val item: KlineIndicatorRenderItem,
        val zIndex: Int,
    )

    val main = mutableListOf<OrderedItem>()
    val sub = linkedMapOf<String, MutableList<OrderedItem>>()
    val subPaneHints = linkedMapOf<String, MutableList<com.zhumeng.kanvas.core.IndicatorLayoutHint>>()
    val unsupported = mutableListOf<IndicatorDefinition>()
    definitions.forEachIndexed { declarationOrder, definition ->
        val output = freshSnapshot?.output(definition.key)
        val renderer = renderers.resolve(definition, output, state, registry)
        if (renderer == null) {
            // A missing output only makes a renderer pending when it can
            // match the declaration without columns (supportsPending or a
            // stateful factory). Otherwise this is an unsupported Android
            // renderer and should be surfaced immediately instead of making
            // a pane appear later when its first result arrives.
            unsupported += definition
            return@forEachIndexed
        }
        val item = KlineIndicatorRenderItem(
            definition = definition,
            output = output,
            renderer = renderer,
            declarationOrder = declarationOrder,
        )
        val ordered = OrderedItem(item = item, zIndex = definition.zIndex)
        when (val placement = definition.placement) {
            IndicatorPlacement.Main -> main += ordered

            is IndicatorPlacement.Sub -> {
                val paneId = placement.resolvedPaneId(definition.key)
                sub.getOrPut(paneId) { mutableListOf() } += ordered
                // Ordinary sub indicators are isolated by default. A repeated
                // non-default pane id explicitly creates a shared pane. Preserve every
                // member hint so null can still inherit the host fallback
                // during layout resolution.
                subPaneHints.getOrPut(paneId) { mutableListOf() } += definition.layoutHint
            }
        }
    }
    val mainOrder = compareBy<OrderedItem>({ it.zIndex }, { it.item.declarationOrder })
    fun assignColorIndexes(items: List<OrderedItem>): List<KlineIndicatorRenderItem> {
        var nextColor = 0
        return items.map { ordered ->
            ordered.item.copy(colorIndex = nextColor).also { item ->
                nextColor += maxOf(1, item.output?.columns()?.size ?: 0)
            }
        }
    }
    return KlineIndicatorPanePlan(
        mainPaintOrder = assignColorIndexes(main.sortedWith(mainOrder)),
        subByPane = LinkedHashMap(
            sub.mapValues { (_, items) ->
                // A default sub pane normally contains one item; an explicit
                // shared paneId may contain multiple items.
                assignColorIndexes(items.sortedWith(mainOrder))
            },
        ),
        subPaneLayoutHints = LinkedHashMap(
            subPaneHints.mapValues { (_, hints) -> hints.toList() },
        ),
        unsupportedDefinitions = unsupported.toList(),
    )
}

/**
 * Returns the actual Canvas pane id allocated for this sub placement/key.
 *
 * A default [IndicatorPlacement.Sub] is intentionally isolated per indicator,
 * so its `default` sentinel expands to this stable id. Use the result when
 * adding a named [KlineSubPaneRenderConfig] override instead of relying on a
 * private string convention. A non-default id is returned unchanged and can
 * therefore intentionally share a pane with another declaration.
 */
fun IndicatorPlacement.Sub.resolvedPaneId(key: IndicatorKey): String =
    if (paneId == IndicatorPlacement.DefaultPaneId) {
        "sub:${key.kind.name.lowercase()}:${key.id}"
    } else {
        paneId
    }

/** Queries renderer-owned numeric contributions without exposing draw internals to layout code. */
internal fun List<KlineIndicatorRenderItem>.rendererValueRanges(
    state: KlineUiState,
    paintRange: IndexRange,
    viewport: KlineViewport,
): List<KlineIndicatorValueRange> = mapNotNull { item ->
    item.renderer.visibleValueRange(
        KlineIndicatorRangeContext(
            state = state,
            definition = item.definition,
            output = item.output,
            paintRange = paintRange,
            viewport = viewport,
        ),
    )
}

/**
 * Splits the already-sorted main indicator sequence around Candle's own
 * z-index. Equal z-index items are deliberately painted after Candle, which
 * gives the built-in chart a deterministic first-registration tie-break.
 */
internal data class KlineMainPaintOrder(
    val beforeCandle: List<KlineIndicatorRenderItem>,
    val afterCandle: List<KlineIndicatorRenderItem>,
)

internal fun resolveMainPaintOrder(
    indicators: List<KlineIndicatorRenderItem>,
    candleZIndex: Int,
): KlineMainPaintOrder {
    val split = indicators.indexOfFirst { item -> item.definition.zIndex >= candleZIndex }
    return if (split < 0) {
        KlineMainPaintOrder(beforeCandle = indicators, afterCandle = emptyList())
    } else {
        KlineMainPaintOrder(
            beforeCandle = indicators.take(split),
            afterCandle = indicators.drop(split),
        )
    }
}
