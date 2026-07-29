package com.zhumeng.kanvas.example

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.zhumeng.kanvas.KanvasChart
import com.zhumeng.kanvas.KanvasChartConfig
import com.zhumeng.kanvas.KanvasWatermarkConfig
import com.zhumeng.kanvas.KanvasWatermarkContent
import com.zhumeng.kanvas.KlineChartStyle
import com.zhumeng.kanvas.KlineChartType
import com.zhumeng.kanvas.KlineIndicatorPluginCatalog
import com.zhumeng.kanvas.KlineMovingAverageIndicatorConfig
import com.zhumeng.kanvas.KlineMovingAverageIndicatorPlugin
import com.zhumeng.kanvas.KlinePaneRenderConfig
import com.zhumeng.kanvas.KlineChartRenderConfig
import com.zhumeng.kanvas.KlineSubPaneRenderConfig
import com.zhumeng.kanvas.KlineVolumeIndicatorConfig
import com.zhumeng.kanvas.KlineVolumeIndicatorPlugin
import com.zhumeng.kanvas.KlineEmaTripleIndicatorPlugin
import com.zhumeng.kanvas.KlineBollIndicatorPlugin
import com.zhumeng.kanvas.KlineMacdIndicatorPlugin
import com.zhumeng.kanvas.KlineOrderMarker
import com.zhumeng.kanvas.KlineOrderMarkerRenderConfig
import com.zhumeng.kanvas.KlineOrderSide
import com.zhumeng.kanvas.KlineKdjIndicatorPlugin
import com.zhumeng.kanvas.KlineRsiIndicatorPlugin
import com.zhumeng.kanvas.KlineSinglePeriodIndicatorConfig
import com.zhumeng.kanvas.KlineTriplePeriodIndicatorConfig
import com.zhumeng.kanvas.KlineSarIndicatorPlugin
import com.zhumeng.kanvas.KlineSarIndicatorConfig
import com.zhumeng.kanvas.KlineRsiIndicatorConfig
import com.zhumeng.kanvas.KlineObvIndicatorConfig
import com.zhumeng.kanvas.KlineAvlIndicatorPlugin
import com.zhumeng.kanvas.KlineStyledIndicatorConfig
import com.zhumeng.kanvas.KlineSuperTrendIndicatorPlugin
import com.zhumeng.kanvas.KlineSuperTrendIndicatorConfig
import com.zhumeng.kanvas.KlineObvIndicatorPlugin
import com.zhumeng.kanvas.KlineWrIndicatorPlugin
import com.zhumeng.kanvas.KlineStochasticRsiIndicatorPlugin
import com.zhumeng.kanvas.KlineStochasticRsiIndicatorConfig
import com.zhumeng.kanvas.bind
import com.zhumeng.kanvas.rememberKanvasChartState
import com.zhumeng.kanvas.core.IndicatorPlacement
import com.zhumeng.kanvas.core.KlineCandle
import com.zhumeng.kanvas.core.KlineComputeMode
import com.zhumeng.kanvas.core.KlineEvent
import com.zhumeng.kanvas.core.KlineInterval
import com.zhumeng.kanvas.core.KlineSpec
import com.zhumeng.kanvas.drawing.DrawingController
import com.zhumeng.kanvas.drawing.DrawingMagnetMode
import com.zhumeng.kanvas.drawing.DrawingState
import com.zhumeng.kanvas.drawing.DrawingToolbar
import com.zhumeng.kanvas.drawing.DrawingToolbarState
import com.zhumeng.kanvas.drawing.DrawingTypeDescriptor
import com.zhumeng.kanvas.drawing.rememberDrawingToolbarState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val systemDark = isSystemInDarkTheme()
            var darkTheme by rememberSaveable { mutableStateOf(systemDark) }
            SampleTheme(darkTheme = darkTheme) {
                ReferenceSample(
                    darkTheme = darkTheme,
                    onToggleTheme = { darkTheme = !darkTheme },
                )
            }
        }
    }
}

@Composable
private fun ReferenceSample(
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    var riseRed by rememberSaveable { mutableStateOf(false) }
    val chartStyle = remember(darkTheme, riseRed) {
        if (darkTheme) darkKlineChartStyle(riseRed) else lightKlineChartStyle(riseRed)
    }
    val orderMarkerConfig = remember(chartStyle) {
        KlineOrderMarkerRenderConfig(
            buyColor = chartStyle.bullish,
            sellColor = chartStyle.bearish,
        )
    }
    val density = LocalDensity.current
    val renderConfig = remember {
        KlineChartRenderConfig(
            includeMainIndicatorsInValueRange = false,
            latestCandleRangeSmoothFactor = 0.18f,
        )
    }
    var showDrawingTools by rememberSaveable { mutableStateOf(false) }
    val movingAveragePlugin = remember {
        KlineMovingAverageIndicatorPlugin(
            id = "sample_ma",
            label = "MA (Android example)",
        )
    }
    val volumePlugin = remember {
        KlineVolumeIndicatorPlugin(
            id = "sample_volume",
            label = "Volume (Android example)",
        )
    }
    val emaPlugin = remember { KlineEmaTripleIndicatorPlugin() }
    val bollPlugin = remember { KlineBollIndicatorPlugin() }
    val macdPlugin = remember { KlineMacdIndicatorPlugin() }
    val kdjPlugin = remember { KlineKdjIndicatorPlugin() }
    val rsiPlugin = remember { KlineRsiIndicatorPlugin() }
    val sarPlugin = remember { KlineSarIndicatorPlugin() }
    val avlPlugin = remember { KlineAvlIndicatorPlugin() }
    val superPlugin = remember { KlineSuperTrendIndicatorPlugin() }
    val obvPlugin = remember { KlineObvIndicatorPlugin() }
    val wrPlugin = remember { KlineWrIndicatorPlugin() }
    val stochRsiPlugin = remember { KlineStochasticRsiIndicatorPlugin() }
    val indicatorCatalog = remember(
        movingAveragePlugin,
        volumePlugin,
        emaPlugin,
        bollPlugin,
        macdPlugin,
        kdjPlugin,
        rsiPlugin,
        sarPlugin,
        avlPlugin,
        superPlugin,
        obvPlugin,
        wrPlugin,
        stochRsiPlugin,
    ) {
        KlineIndicatorPluginCatalog.of(
            movingAveragePlugin.bind(
                KlineMovingAverageIndicatorConfig(
                    periods = listOf(7, 25),
                    placement = IndicatorPlacement.Main,
                ),
            ),
            volumePlugin.bind(
                KlineVolumeIndicatorConfig(
                    placement = IndicatorPlacement.Sub("volume"),
                ),
            ),
            emaPlugin.bind(),
            bollPlugin.bind(),
            macdPlugin.bind(),
            kdjPlugin.bind(),
            rsiPlugin.bind(),
            sarPlugin.bind(),
            avlPlugin.bind(),
            superPlugin.bind(),
            obvPlugin.bind(),
            wrPlugin.bind(),
            stochRsiPlugin.bind(),
        )
    }
    val indicatorDefinitions = remember(indicatorCatalog) { indicatorCatalog.definitions }
    val activeIndicatorKeys = remember(indicatorDefinitions) { indicatorDefinitions.take(2).map { it.key } }
    var computeMode by remember { mutableStateOf(KlineComputeMode.Fast) }
    var chartType by remember { mutableStateOf<KlineChartType>(KlineChartType.Bar()) }
    val chartState = rememberKanvasChartState(
        config = KanvasChartConfig(render = renderConfig),
        indicatorCatalog = indicatorCatalog,
        activeIndicatorKeys = activeIndicatorKeys,
        computeMode = computeMode,
    )
    val state by chartState.state.collectAsState()
    val indicatorRegistrySnapshot by chartState.indicators.state.collectAsState()
    val drawingController = chartState.drawingController
    var selectedTimeframe by remember { mutableStateOf("1h") }
    var showIndicatorSheet by remember { mutableStateOf(false) }
    var historicalPage by remember { mutableStateOf(0) }
    val candleIntervalMillis = when (selectedTimeframe) {
        "1m" -> 60_000L
        "15m" -> 15L * 60_000L
        "4h" -> 4L * HourMillis
        "1d" -> 24L * HourMillis
        else -> HourMillis
    }
    // Align the fixture's latest Candle timestamp to the current interval
    // boundary so the countdown remains manually testable for every
    // timeframe including sub-hour ones.
    val fixtureLatestMillis = remember(candleIntervalMillis) {
        val now = System.currentTimeMillis()
        now - now.mod(candleIntervalMillis)
    }
    val sampleOrderMarkers = remember(fixtureLatestMillis, candleIntervalMillis) {
        listOf(
            KlineOrderMarker(fixtureLatestMillis - 6 * candleIntervalMillis, KlineOrderSide.Buy),
            KlineOrderMarker(fixtureLatestMillis - 13 * candleIntervalMillis, KlineOrderSide.Sell),
            KlineOrderMarker(fixtureLatestMillis - 21 * candleIntervalMillis, KlineOrderSide.Buy),
            KlineOrderMarker(fixtureLatestMillis - 30 * candleIntervalMillis, KlineOrderSide.Sell),
            KlineOrderMarker(fixtureLatestMillis - 38 * candleIntervalMillis, KlineOrderSide.Buy),
        )
    }
    val spec = remember(selectedTimeframe) {
        KlineSpec(
            symbol = "BTC-USDT",
            interval = when (selectedTimeframe) {
                "1m" -> KlineInterval.minutes(1)
                "15m" -> KlineInterval.minutes(15)
                "4h" -> KlineInterval.hours(4)
                "1d" -> KlineInterval.days(1)
                else -> KlineInterval.hours(1)
            },
            precision = 2,
            label = "Compose reference fixture",
        )
    }

    LaunchedEffect(chartState, spec, fixtureLatestMillis, candleIntervalMillis) {
        chartState.setMarket(
            spec = spec,
            candles = sampleCandles(
                count = 240,
                latestHourMillis = fixtureLatestMillis,
                candleIntervalMillis = candleIntervalMillis,
            ),
        )
        val realtimeRandom = Random(SampleRandomSeed xor spec.key.hashCode())
        while (true) {
            delay(RealtimeUpdateMillis)
            val current = chartState.state.value
            if (current.spec?.key != spec.key) continue
            val latest = current.series.latest ?: continue
            val now = System.currentTimeMillis()
            val currentBucketMillis = now - now.mod(candleIntervalMillis)
            if (currentBucketMillis > latest.timestampMillis) {
                // Close the previous interval before prepending the next one.
                chartState.updateLatest(latest.copy(confirmed = true))
                chartState.updateLatest(
                    nextRealtimeCandle(
                        previous = latest,
                        timestampMillis = currentBucketMillis,
                        random = realtimeRandom,
                        newInterval = true,
                    ),
                )
            } else {
                chartState.updateLatest(
                    nextRealtimeCandle(
                        previous = latest,
                        timestampMillis = latest.timestampMillis,
                        random = realtimeRandom,
                        newInterval = false,
                    ),
                )
            }
        }
    }
    LaunchedEffect(chartState, spec, candleIntervalMillis) {
        chartState.events.collect { event ->
            if (event is KlineEvent.LoadMore) {
                // Simulate network latency so silent prefetch and boundary
                // loading behavior remain manually testable.
                delay(1_500)
                historicalPage += 1
                chartState.completeLoadMore(
                    requestId = event.requestId,
                    candles = sampleCandles(
                        count = 120,
                        startHoursAgo = 240 + (historicalPage - 1) * 120,
                        latestHourMillis = fixtureLatestMillis,
                        candleIntervalMillis = candleIntervalMillis,
                    ),
                )
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .statusBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Kanvas Reference",
                color = colorScheme.onBackground,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "${state.series.size} candles · 300ms live · MA + Volume are Android examples · $computeMode compute · tap/long-press Cross · double-tap/reset · pinch/axis-drag to scale",
                color = colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf("1m", "15m", "1h", "4h", "1d").forEach { timeframe ->
                    Text(
                        text = timeframe,
                        modifier = Modifier
                            .clickable { selectedTimeframe = timeframe }
                            .padding(vertical = 6.dp),
                        color = if (selectedTimeframe == timeframe) colorScheme.primary else colorScheme.onSurfaceVariant,
                        fontWeight = if (selectedTimeframe == timeframe) FontWeight.Bold else FontWeight.Normal,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                SampleToolbarAction(
                    label = "指标",
                    onClick = { showIndicatorSheet = true },
                )
                {
                    IndicatorTuneIcon(color = colorScheme.onSurfaceVariant)
                }
                SampleToolbarAction(
                    label = "绘图",
                    onClick = { showDrawingTools = !showDrawingTools },
                ) {
                    DrawingToolsIcon(color = if (showDrawingTools) colorScheme.primary else colorScheme.onSurfaceVariant)
                }
                SampleToolbarAction(
                    label = if (darkTheme) "浅色" else "深色",
                    onClick = onToggleTheme,
                )
                {
                    ThemeModeIcon(
                        showLightMode = darkTheme,
                        color = colorScheme.onSurfaceVariant,
                        background = colorScheme.surfaceVariant,
                    )
                }
                Text(
                    text = if (riseRed) "红涨" else "绿涨",
                    modifier = Modifier
                        .clickable { riseRed = !riseRed }
                        .padding(horizontal = 7.dp, vertical = 8.dp),
                    color = chartStyle.bullish,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        if (showIndicatorSheet) {
            IndicatorSettingsSheet(
                registrySnapshot = indicatorRegistrySnapshot,
                onToggle = chartState.indicators::toggle,
                onApply = { value ->
                    val updated = when (value.id) {
                        "sample_ma" -> movingAveragePlugin.bind(
                            KlineMovingAverageIndicatorConfig(
                                periods = value.values.map(Double::toInt),
                                lineStyles = value.lineStyles,
                                placement = IndicatorPlacement.Main,
                            ),
                        ).definition
                        "compose_ema" -> emaPlugin.bind(
                            KlineMovingAverageIndicatorConfig(
                                periods = value.values.map(Double::toInt),
                                lineStyles = value.lineStyles,
                                placement = IndicatorPlacement.Main,
                            ),
                        ).definition
                        "compose_boll" -> bollPlugin.bind(
                            KlineTriplePeriodIndicatorConfig(
                                firstPeriod = value.values[0].toInt(),
                                secondPeriod = value.values[1].toInt(),
                                thirdPeriod = value.values[2].toInt(),
                                placement = IndicatorPlacement.Main,
                                lineStyles = value.lineStyles,
                            ),
                        ).definition
                        "compose_macd" -> macdPlugin.bind(
                            KlineTriplePeriodIndicatorConfig(
                                firstPeriod = value.values[0].toInt(),
                                secondPeriod = value.values[1].toInt(),
                                thirdPeriod = value.values[2].toInt(),
                                placement = IndicatorPlacement.Sub("macd"),
                                lineStyles = value.lineStyles,
                            ),
                        ).definition
                        "compose_kdj" -> kdjPlugin.bind(
                            KlineTriplePeriodIndicatorConfig(
                                firstPeriod = value.values[0].toInt(),
                                secondPeriod = value.values[1].toInt(),
                                thirdPeriod = value.values[2].toInt(),
                                placement = IndicatorPlacement.Sub("kdj"),
                                lineStyles = value.lineStyles,
                            ),
                        ).definition
                        "compose_rsi" -> rsiPlugin.bind(
                            KlineRsiIndicatorConfig(
                                periods = value.styleValues.take(3).mapIndexedNotNull { index, period ->
                                    period?.toInt()?.takeIf { value.styleEnabled.getOrElse(index) { false } }
                                },
                                upper = value.styleValues.getOrNull(3) ?: 70.0,
                                lower = value.styleValues.getOrNull(4) ?: 30.0,
                                lineStyles = value.lineStyles.filterIndexed { index, _ ->
                                    index < 3 && value.styleEnabled.getOrElse(index) { false }
                                } + value.lineStyles.drop(3).take(2),
                            ),
                        ).definition
                        "compose_sar" -> sarPlugin.bind(
                            KlineSarIndicatorConfig(
                                step = value.values[0],
                                maximum = value.values[1],
                                lineStyles = value.lineStyles,
                            ),
                        ).definition
                        "compose_avl" -> avlPlugin.bind(
                            KlineStyledIndicatorConfig(
                                placement = IndicatorPlacement.Main,
                                lineStyles = value.lineStyles,
                            ),
                        ).definition
                        "compose_super" -> superPlugin.bind(
                            KlineSuperTrendIndicatorConfig(
                                atrPeriod = value.values[0].toInt(),
                                multiplier = value.values[1],
                                lineStyles = value.lineStyles,
                            ),
                        ).definition
                        "compose_obv" -> obvPlugin.bind(
                            KlineObvIndicatorConfig(
                                maPeriod = value.styleValues.getOrNull(1)?.toInt()
                                    ?.takeIf { value.styleEnabled.getOrElse(1) { false } },
                                emaPeriod = value.styleValues.getOrNull(2)?.toInt()
                                    ?.takeIf { value.styleEnabled.getOrElse(2) { false } },
                                lineStyles = buildList {
                                    add(value.lineStyles[0])
                                    if (value.styleEnabled.getOrElse(1) { false }) add(value.lineStyles[1])
                                    if (value.styleEnabled.getOrElse(2) { false }) add(value.lineStyles[2])
                                },
                            ),
                        ).definition
                        "compose_wr" -> wrPlugin.bind(
                            KlineSinglePeriodIndicatorConfig(
                                period = value.values[0].toInt(),
                                placement = IndicatorPlacement.Sub("wr"),
                                lineStyles = value.lineStyles,
                            ),
                        ).definition
                        "compose_stoch_rsi" -> stochRsiPlugin.bind(
                            KlineStochasticRsiIndicatorConfig(
                                rsiPeriod = value.values[0].toInt(),
                                stochasticPeriod = value.values[1].toInt(),
                                kPeriod = value.values[2].toInt(),
                                dPeriod = value.values[3].toInt(),
                                lineStyles = value.lineStyles,
                            ),
                        ).definition
                        else -> null
                    }
                    updated?.let { definition ->
                        chartState.indicators.updateDefinition(definition.key) { definition }
                    }
                },
                onDismiss = { showIndicatorSheet = false },
            )
        }

        var chartContainerSize by remember { mutableStateOf(IntSize.Zero) }
        val drawingPickerState = rememberDrawingToolbarState(
            initialPosition = with(density) { Offset(8.dp.toPx(), 92.dp.toPx()) },
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onSizeChanged { chartContainerSize = it },
        ) {
            KanvasChart(
                state = chartState,
                config = KanvasChartConfig(
                    chartType = chartType,
                    style = chartStyle,
                    render = renderConfig,
                    orderMarkers = orderMarkerConfig,
                    panes = KlinePaneRenderConfig(
                        subPanes = listOf(KlineSubPaneRenderConfig("volume", preferredHeight = 96.dp)),
                    ),
                    watermark = KanvasWatermarkConfig(
                        content = KanvasWatermarkContent.Text(
                            value = "KANVAS",
                            color = chartStyle.textColor,
                        ),
                        alpha = 0.06f,
                    ),
                ),
                orderMarkers = sampleOrderMarkers,
                modifier = Modifier.fillMaxSize(),
            )
            if (showDrawingTools) {
                DrawingToolPicker(
                    controller = drawingController,
                    containerSize = chartContainerSize,
                    state = drawingPickerState,
                    onToolSelected = { showDrawingTools = false },
                    onDismiss = { showDrawingTools = false },
                )
            }
            DrawingToolbar(
                controller = drawingController,
                containerSize = chartContainerSize,
                state = rememberDrawingToolbarState(initialPosition = Offset(40f, 16f)),
            ) { draw ->
                DrawingContextToolbar(draw)
            }
        }

        IndicatorTextBar(
            definitions = indicatorDefinitions,
            isActive = indicatorRegistrySnapshot::isActive,
            onToggle = chartState.indicators::toggle,
        )
    }
}

@Composable
private fun IndicatorTextBar(
    definitions: List<com.zhumeng.kanvas.core.IndicatorDefinition>,
    isActive: (com.zhumeng.kanvas.core.IndicatorKey) -> Boolean,
    onToggle: (com.zhumeng.kanvas.core.IndicatorKey) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val mainDefinitions = definitions.filter { it.placement == IndicatorPlacement.Main }
    val subDefinitions = definitions.filter { it.placement != IndicatorPlacement.Main }
    val orderedDefinitions = mainDefinitions + subDefinitions

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colorScheme.background)
            .navigationBarsPadding()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        orderedDefinitions.forEachIndexed { index, definition ->
            if (index == mainDefinitions.size && mainDefinitions.isNotEmpty() && subDefinitions.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 6.dp, vertical = 13.dp)
                        .width(1.dp)
                        .height(18.dp)
                        .background(colorScheme.outlineVariant),
                )
            }
            val active = isActive(definition.key)
            val label = when (definition.key.id) {
                "sample_ma", "compose_ma" -> "MA"
                "sample_volume", "compose_volume" -> "VOL"
                "compose_ema" -> "EMA"
                "compose_boll" -> "BOLL"
                "compose_macd" -> "MACD"
                "compose_kdj" -> "KDJ"
                "compose_rsi" -> "RSI"
                else -> definition.key.label.substringBefore(" (")
            }
            Text(
                text = label,
                modifier = Modifier
                    .defaultMinSize(minWidth = 58.dp, minHeight = 44.dp)
                    .clickable { onToggle(definition.key) }
                    .padding(horizontal = 10.dp, vertical = 11.dp),
                color = if (active) colorScheme.primary else colorScheme.onSurfaceVariant,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun DrawingToolPicker(
    controller: DrawingController,
    containerSize: IntSize,
    state: DrawingToolbarState,
    onToolSelected: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var pickerSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(containerSize, pickerSize) {
        state.moveTo(state.position, containerSize, pickerSize, keepFullyVisible = true)
    }
    val groups = listOf(
        "趋势线" to listOf(
            DrawingTypeDescriptor.TwoPointLine to "线段",
            DrawingTypeDescriptor.RayLine to "射线",
            DrawingTypeDescriptor.StraightLine to "直线",
        ),
        "价格与时间" to listOf(
            DrawingTypeDescriptor.HorizontalLine to "水平线",
            DrawingTypeDescriptor.VerticalLine to "垂直线",
        ),
        "形状与测量" to listOf(
            DrawingTypeDescriptor.Rectangle to "矩形",
            DrawingTypeDescriptor.FibonacciRetracement to "斐波那契回撤",
        ),
    )
    Surface(
        modifier = modifier
            .width(174.dp)
            .offset { IntOffset(state.position.x.roundToInt(), state.position.y.roundToInt()) }
            .onSizeChanged { pickerSize = it },
        shape = RoundedCornerShape(14.dp),
        color = colors.surface.copy(alpha = 0.97f),
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "拖动绘图工具" }
                    .pointerInput(state, containerSize, pickerSize) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            state.moveTo(
                                position = state.position + dragAmount,
                                containerSize = containerSize,
                                toolbarSize = pickerSize,
                                keepFullyVisible = true,
                            )
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("绘图工具  ⋮⋮", color = colors.onSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                Text("关闭", modifier = Modifier.clickable(onClick = onDismiss).padding(4.dp), color = colors.primary, style = MaterialTheme.typography.labelSmall)
            }
            groups.forEach { (group, tools) ->
                Text(
                    group,
                    modifier = Modifier.padding(start = 12.dp, top = 7.dp, bottom = 2.dp),
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                tools.forEach { (type, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = controller.snapshot.symbol.isNotBlank()) {
                                controller.prepare(type)
                                onToolSelected()
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DrawingTypeGlyph(type, colors.primary)
                        Text(label, color = colors.onSurface, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DrawingAction("撤销", controller.canUndo, Modifier.weight(1f)) { controller.undo() }
                DrawingAction("重做", controller.canRedo, Modifier.weight(1f)) { controller.redo() }
                DrawingAction("清空", controller.snapshot.overlays.isNotEmpty(), Modifier.weight(1f)) { controller.removeAll() }
            }
        }
    }
}

@Composable
private fun DrawingContextToolbar(controller: DrawingController) {
    val snapshot = controller.snapshot
    val editing = snapshot.state as? DrawingState.Editing
    val overlay = editing?.let { state -> snapshot.overlays.firstOrNull { it.id == state.overlayId } }
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.width(320.dp),
        shape = RoundedCornerShape(12.dp),
        color = colors.surface.copy(alpha = 0.96f),
        tonalElevation = 7.dp,
        shadowElevation = 7.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .semantics { contentDescription = "拖动绘图操作栏" }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "⋮⋮",
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 5.dp, end = 5.dp, bottom = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val magnetLabel = when (snapshot.magnetMode) {
                    DrawingMagnetMode.Normal -> "磁吸关"
                    DrawingMagnetMode.Weak -> "弱磁吸"
                    DrawingMagnetMode.Strong -> "强磁吸"
                }
                DrawingAction(magnetLabel) {
                    controller.setMagnetMode(snapshot.magnetMode.next())
                }
                DrawingAction(if (snapshot.continuous) "连续开" else "连续关") {
                    controller.setContinuous(!snapshot.continuous)
                }
                DrawingAction("撤销", controller.canUndo) { controller.undo() }
                DrawingAction("重做", controller.canRedo) { controller.redo() }
                if (overlay != null) {
                    DrawingAction("颜色") {
                        val palette = listOf(Color(0xFF4C8DFF), Color(0xFFF4B740), Color(0xFFE05A5A), Color(0xFF16A085), Color.White)
                        val index = palette.indexOf(overlay.line.color).let { if (it < 0) 0 else it }
                        controller.setSelectedLineStyle(color = palette[(index + 1) % palette.size])
                    }
                    DrawingAction("${overlay.line.strokeWidthPx.toInt().coerceAtLeast(1)}px") {
                        val next = when {
                            overlay.line.strokeWidthPx < 2f -> 2f
                            overlay.line.strokeWidthPx < 3f -> 3f
                            else -> 1f
                        }
                        controller.setSelectedLineStyle(strokeWidthPx = next)
                    }
                    DrawingAction(if (overlay.line.dashed) "虚线" else "实线") {
                        controller.setSelectedLineStyle(lineType = if (overlay.line.dashed) "solid" else "dashed")
                    }
                    DrawingAction(if (overlay.locked) "已锁定" else "锁定") {
                        controller.setSelectedLocked(!overlay.locked)
                    }
                    DrawingAction("置顶", !controller.isSelectedOnTop()) { controller.moveSelectedToTop() }
                    DrawingAction("删除") { controller.removeSelected() }
                }
                DrawingAction(if (snapshot.state is DrawingState.Drawing) "取消" else "完成") {
                    if (snapshot.state is DrawingState.Drawing) controller.cancel() else controller.finishEditing()
                }
            }
        }
    }
}

@Composable
private fun DrawingAction(
    label: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minWidth = 44.dp, minHeight = 34.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Box(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(label, maxLines = 1, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun DrawingTypeGlyph(type: DrawingTypeDescriptor, color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val stroke = 1.7.dp.toPx()
        when (type) {
            DrawingTypeDescriptor.HorizontalLine -> drawLine(color, Offset(1f, center.y), Offset(size.width - 1f, center.y), stroke)
            DrawingTypeDescriptor.VerticalLine -> drawLine(color, Offset(center.x, 1f), Offset(center.x, size.height - 1f), stroke)
            DrawingTypeDescriptor.Rectangle -> drawRect(color, Offset(2f, 3f), androidx.compose.ui.geometry.Size(size.width - 4f, size.height - 6f), style = Stroke(stroke))
            DrawingTypeDescriptor.FibonacciRetracement -> listOf(3f, 7f, 11f, 15f, 19f).forEach { y -> drawLine(color, Offset(1f, y), Offset(size.width - 1f, y), stroke) }
            DrawingTypeDescriptor.RayLine -> {
                drawLine(color, Offset(2f, size.height - 3f), Offset(size.width, 2f), stroke)
                drawCircle(color, 2.2f, Offset(2f, size.height - 3f))
            }
            else -> drawLine(color, Offset(2f, size.height - 3f), Offset(size.width - 2f, 3f), stroke)
        }
    }
}

@Composable
private fun DrawingToolsIcon(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val stroke = 1.8.dp.toPx()
        drawLine(color, Offset(2f, size.height - 3f), Offset(size.width - 2f, 3f), stroke, cap = StrokeCap.Round)
        drawCircle(color, 2.3.dp.toPx(), Offset(2.5f, size.height - 3.5f), style = Stroke(stroke))
        drawCircle(color, 2.3.dp.toPx(), Offset(size.width - 2.5f, 3.5f), style = Stroke(stroke))
    }
}

@Composable
private fun SampleToolbarAction(
    label: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.defaultMinSize(minWidth = 64.dp, minHeight = 40.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun IndicatorTuneIcon(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val strokeWidth = 1.8.dp.toPx()
        val knobRadius = 2.2.dp.toPx()
        val lineStart = 1.5.dp.toPx()
        val lineEnd = 16.5.dp.toPx()
        val rows = listOf(
            4.dp.toPx() to 6.dp.toPx(),
            9.dp.toPx() to 12.dp.toPx(),
            14.dp.toPx() to 8.dp.toPx(),
        )
        rows.forEach { (y, knobX) ->
            drawLine(
                color = color,
                start = Offset(lineStart, y),
                end = Offset(lineEnd, y),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            drawCircle(color = color, radius = knobRadius, center = Offset(knobX, y))
        }
    }
}

@Composable
private fun ThemeModeIcon(
    showLightMode: Boolean,
    color: Color,
    background: Color,
) {
    Canvas(Modifier.size(18.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val strokeWidth = 1.7.dp.toPx()
        if (showLightMode) {
            val innerRadius = 3.2.dp.toPx()
            val rayInner = 5.6.dp.toPx()
            val rayOuter = 7.7.dp.toPx()
            drawCircle(color = color, radius = innerRadius, center = center, style = Stroke(strokeWidth))
            listOf(
                Offset(0f, -1f), Offset(0f, 1f), Offset(-1f, 0f), Offset(1f, 0f),
                Offset(-0.707f, -0.707f), Offset(0.707f, -0.707f),
                Offset(-0.707f, 0.707f), Offset(0.707f, 0.707f),
            ).forEach { direction ->
                drawLine(
                    color = color,
                    start = center + direction * rayInner,
                    end = center + direction * rayOuter,
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
        } else {
            drawCircle(
                color = color,
                radius = 6.2.dp.toPx(),
                center = center,
                style = Stroke(strokeWidth),
            )
            drawCircle(
                color = background,
                radius = 5.4.dp.toPx(),
                center = center + Offset(3.2.dp.toPx(), -2.4.dp.toPx()),
            )
        }
    }
}

private fun sampleCandles(
    count: Int,
    startHoursAgo: Int = 0,
    latestHourMillis: Long,
    candleIntervalMillis: Long = HourMillis,
): List<KlineCandle> {
    require(count >= 0)
    require(startHoursAgo >= 0)
    if (count == 0) return emptyList()

    // Rebuild the same deterministic random walk for every page. Using an
    // absolute hoursAgo index keeps separately loaded pages continuous while
    // avoiding the visibly periodic sine-wave fixture used previously.
    val seed = SampleRandomSeed xor candleIntervalMillis.hashCode()
    val oldestHoursAgo = startHoursAgo + count - 1
    val closes = DoubleArray(oldestHoursAgo + 2)
    closes[0] = 69_000.0
    val pathRandom = Random(seed)
    var momentum = 0.0
    for (hoursAgo in 1..closes.lastIndex) {
        val impulse = pathRandom.nextDouble(-260.0, 260.0)
        val occasionalShock = if (pathRandom.nextDouble() < 0.07) {
            pathRandom.nextDouble(-520.0, 520.0)
        } else {
            0.0
        }
        momentum = momentum * 0.58 + impulse * 0.72 + occasionalShock
        val meanReversion = (69_000.0 - closes[hoursAgo - 1]) * 0.018
        closes[hoursAgo] = (closes[hoursAgo - 1] - momentum + meanReversion)
            .coerceIn(58_000.0, 80_000.0)
    }

    return List(count) { index ->
        val hoursAgo = startHoursAgo + index
        val candleRandom = Random(seed xor (hoursAgo * SampleCandleSeedStep))
        val open = closes[hoursAgo + 1]
        val close = closes[hoursAgo]
        val body = abs(close - open)
        val high = max(open, close) + candleRandom.nextDouble(45.0, 260.0)
        val low = min(open, close) - candleRandom.nextDouble(45.0, 260.0)
        val volume = 160.0 + candleRandom.nextDouble(80.0, 760.0) + body * 1.35
        KlineCandle(
            timestampMillis = latestHourMillis - hoursAgo * candleIntervalMillis,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume,
            turnover = volume * (open + close) / 2.0,
            confirmed = hoursAgo != 0,
        )
    }
}

private fun nextRealtimeCandle(
    previous: KlineCandle,
    timestampMillis: Long,
    random: Random,
    newInterval: Boolean,
): KlineCandle {
    val open = if (newInterval) previous.close else previous.open
    val priceDelta = random.nextDouble(-32.0, 32.0)
    val close = (previous.close + priceDelta).coerceAtLeast(0.01)
    val volumeDelta = random.nextDouble(2.0, 18.0)
    val highPadding = if (newInterval) random.nextDouble(2.0, 18.0) else 0.0
    val lowPadding = if (newInterval) random.nextDouble(2.0, 18.0) else 0.0
    val high = if (newInterval) {
        max(open, close) + highPadding
    } else {
        max(previous.high, close)
    }
    val low = if (newInterval) {
        min(open, close) - lowPadding
    } else {
        min(previous.low, close)
    }
    val volume = if (newInterval) volumeDelta else previous.volume + volumeDelta
    val turnoverDelta = volumeDelta * (previous.close + close) / 2.0
    return KlineCandle(
        timestampMillis = timestampMillis,
        open = open,
        high = high,
        low = low,
        close = close,
        volume = volume,
        turnover = if (newInterval) turnoverDelta else (previous.turnover ?: 0.0) + turnoverDelta,
        confirmed = false,
    )
}

private const val HourMillis: Long = 3_600_000L
private const val RealtimeUpdateMillis: Long = 300L
private const val SampleRandomSeed: Int = 0x4B414E56
private const val SampleCandleSeedStep: Int = -1_640_531_527

private val SampleDarkColors = darkColorScheme(
    primary = Color(0xFFF2D38B),
    onPrimary = Color(0xFF2B2108),
    secondary = Color(0xFF4DB6FF),
    tertiary = Color(0xFFB388FF),
    background = Color(0xFF101722),
    onBackground = Color(0xFFF2F5F9),
    surface = Color(0xFF182231),
    onSurface = Color(0xFFF2F5F9),
    surfaceVariant = Color(0xFF253244),
    onSurfaceVariant = Color(0xFFB9C6D5),
    outline = Color(0xFF718197),
    outlineVariant = Color(0xFF3A4658),
    error = Color(0xFFFF6B6B),
)

private val SampleLightColors = lightColorScheme(
    primary = Color(0xFF9A7000),
    onPrimary = Color.White,
    secondary = Color(0xFF256DA8),
    tertiary = Color(0xFF7251B5),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF17202C),
    surface = Color.White,
    onSurface = Color(0xFF17202C),
    surfaceVariant = Color(0xFFEDF1F6),
    onSurfaceVariant = Color(0xFF5C6878),
    outline = Color(0xFF7A8798),
    outlineVariant = Color(0xFFD8DEE8),
    error = Color(0xFFBA1A1A),
)

private fun darkKlineChartStyle(riseRed: Boolean): KlineChartStyle {
    val bullish = if (riseRed) Color(0xFFFF6B6B) else Color(0xFF2EC4B6)
    val bearish = if (riseRed) Color(0xFF2EC4B6) else Color(0xFFFF6B6B)
    return KlineChartStyle(
        bullish = bullish,
        bearish = bearish,
        volumeBullish = bullish.copy(alpha = 0.60f),
        volumeBearish = bearish.copy(alpha = 0.60f),
    )
}

private fun lightKlineChartStyle(riseRed: Boolean): KlineChartStyle {
    val bullish = if (riseRed) Color(0xFFE05252) else Color(0xFF0B9F91)
    val bearish = if (riseRed) Color(0xFF0B9F91) else Color(0xFFE05252)
    return KlineChartStyle(
        background = Color(0xFFF7F9FC),
        gridLine = Color(0xFFDDE3EC),
        bullish = bullish,
        bearish = bearish,
        line = Color(0xFF2478B8),
        crosshair = Color(0xFF718096),
        drawTool = Color(0xFF356FD2),
        markLine = Color(0xFF2478B8),
        latestPriceBackground = Color(0xFF2478B8),
        lastPriceBackground = Color(0xFFF0F3F8),
        crossTextBackground = Color(0xFFFFFFFF),
        ticksTextColor = Color(0xFF5C6878),
        textColor = Color(0xFF263241),
        lastPriceTextColor = Color(0xFF263241),
        crossTextColor = Color(0xFF263241),
        tooltipTextColor = Color(0xFF263241),
        tooltipBackground = Color(0xF2FFFFFF),
        countdownBackground = Color(0xFFE3E9F1),
        indicatorLines = listOf(
            Color(0xFFD99A00),
            Color(0xFF8055C7),
            Color(0xFF008BA3),
            Color(0xFFD64F64),
        ),
        volumeBullish = bullish.copy(alpha = 0.60f),
        volumeBearish = bearish.copy(alpha = 0.60f),
    )
}

@Composable
private fun SampleTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(
        colorScheme = if (darkTheme) SampleDarkColors else SampleLightColors,
        content = content,
    )
}
