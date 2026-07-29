# Kanvas — Jetpack Compose 原生 K 线图与蜡烛图组件

[![Android verification](https://github.com/zhumeng1582/Kanvas/actions/workflows/android.yml/badge.svg)](https://github.com/zhumeng1582/Kanvas/actions/workflows/android.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![minSdk 24](https://img.shields.io/badge/minSdk-24-brightgreen.svg)](https://developer.android.com/about/versions/nougat)

Kanvas 是使用 Kotlin 和 Jetpack Compose 构建的 Android 原生 K 线图、蜡烛图组件，
无需 WebView。它支持实时行情、技术指标、画线工具、十字光标提示、订单标记、多窗格，
以及可扩展的指标与渲染器插件。

[English](README.md) | [简体中文](README.zh-CN.md)

参阅[代码架构说明](ARCHITECTURE.zh-CN.md)，了解模块边界、运行时数据流和后续重构建议；
高频行情接入请阅读[实时 K 线、指标刷新与画面稳定](docs/realtime-updates.zh-CN.md)，
示例指标弹窗与参数编辑请阅读[指标选择与参数设置](docs/indicator-settings.zh-CN.md)。

下载可直接安装的 [Kanvas Example APK v0.1.0](docs/downloads/kanvas-example-0.1.0.apk)。
该 Release 包使用 Android debug 证书签名，仅用于演示和测试；正式分发请使用自己的
生产签名证书。

<p align="center">
  <img src="docs/images/kanvas-example.png" alt="Kanvas Compose 图表，包含订单标记、MA 和成交量" width="300" />
  <img src="docs/images/kanvas-indicator-picker.png" alt="Kanvas 主图与副图指标选择" width="300" />
  <img src="docs/images/kanvas-indicator-editor.png" alt="Kanvas RSI 参数、线宽和颜色编辑" width="300" />
</p>

<p align="center">
  <img src="docs/images/kanvas-theme-light-red-up.png" alt="Kanvas 浅色主题与红涨绿跌配色" width="300" />
  <img src="docs/images/kanvas-theme-dark-settings.png" alt="Kanvas 深色主题指标设置" width="300" />
</p>

## 目录

- [快速开始](#快速开始)
- [主题与图表样式](#主题与图表样式)
- [原生水印](#原生水印)
- [布局与交互](#布局与交互)
- [数据加载与实时更新](#数据加载与实时更新)
- [订单标记](#订单标记)
- [绘图与持久化](#绘图与持久化)
- [Compose 原生指标插件](#compose-原生指标插件)
- [副图布局与自定义指标](#副图布局与自定义指标)
- [发布](#发布)

目前可用的模块包括：

- `kanvas-core`：新数据在前的 K 线序列、按时间戳合并、右边界视口计算、LRU
  控制器/缓存、加载事件、不可变指标快照，以及指标激活/保留生命周期状态。
- `kanvas-compose`：Compose Canvas/图表 API，以及 Kotlin 原生指标插件
  SPI、渲染器、有状态生命周期运行时和 Android MA/成交量示例插件。
- `kanvas-drawing`：基于时间戳/数值的持久化覆盖物、绘图工具 SPI、绘制/编辑状态机、
  磁吸模式、可拖动工具栏和 Android 放大镜集成。
- `example`：使用 BTC-USDT 固定数据的交互示例，包含 Android 示例 MA 和成交量插件、
  显示/隐藏控制、深浅主题、红涨/绿涨配色切换、平移/缩放、十字光标和加载更多事件。

内置示例覆盖 MA、EMA、BOLL、SAR、AVL、SuperTrend、MACD、KDJ、RSI、OBV、WR、
StochRSI、成交量、Candle 和 Time；应用也可以通过插件 API 添加自己的指标与渲染器。

## 快速开始

从源码工程接入时，业务 App 通常只需要依赖 `kanvas-compose`；它会传递依赖
`kanvas-core` 和 `kanvas-drawing`：

```kotlin
dependencies {
    implementation(project(":kanvas-compose"))
}
```

运行仓库自带示例：

```bash
./gradlew :example:installDebug
adb shell am start -n com.zhumeng.kanvas.example/.MainActivity
```

库要求 Android `minSdk 24`、Java 17，并使用 Jetpack Compose。

只展示数据或低频整体替换时，可以直接传入 K 线。默认顺序为最新数据在前；接口返回正序时
指定 `OldestFirst`：

```kotlin
KanvasChart(
    candles = candles,
    order = KanvasCandleOrder.OldestFirst,
    spec = KlineSpec("BTC-USDT", KlineInterval.hours(1), precision = 2),
    modifier = Modifier.fillMaxSize(),
)
```

实时行情项目使用一个高层状态即可。它统一持有 Controller、视口、加载状态、画线、指标计算、
Renderer 生命周期及资源清理：

```kotlin
val chartState = rememberKanvasChartState()
val spec = remember {
    KlineSpec("BTC-USDT", KlineInterval.minutes(15), precision = 2)
}

LaunchedEffect(spec) {
    chartState.setMarket(
        spec = spec,
        candles = marketRepository.initialCandles(spec),
    )
}

KanvasChart(
    state = chartState,
    modifier = Modifier.fillMaxSize(),
)
```

实时更新和导航直接调用状态方法：

```kotlin
chartState.updateLatest(candle)
chartState.moveToLatest()
chartState.moveTo(timestampMillis)
```

`KanvasChartState` 是推荐给应用开发者的标准 API。原有接收 `KlineUiState` 的
`KanvasChart`、`KlineController`、Registry 和 Renderer 参数继续作为高级 API 保留，
需要完全掌控运行时边界的项目仍可直接使用。

`KlineCandle` 至少需要时间戳和 OHLC；`volume`、`turnover` 与 `confirmed` 可按数据源填充。
所有时间戳均使用毫秒。

## 主题与图表样式

`KlineChartStyle` 管理图表颜色，`KlineChartRenderConfig` 管理尺寸、手势、网格、K 线、
loading 和覆盖物行为。二者职责分离，适合从应用主题统一生成：

```kotlin
val darkStyle = KlineChartStyle(
    background = Color(0xFF0B1220),
    gridLine = Color(0xFF1F2A3A),
    bullish = Color(0xFF19C3A3),
    bearish = Color(0xFFFF5C69),
    ticksTextColor = Color(0xFF9EADBF),
    textColor = Color(0xFFE6EDF5),
    indicatorLines = listOf(
        Color(0xFFFFC857),
        Color(0xFFB388FF),
        Color(0xFF4DD0E1),
    ),
)

val renderConfig = KlineChartRenderConfig(
    gesture = KlineGestureConfig(
        panSensitivity = 1f,
        autoLoadMore = true,
    ),
    loading = KlineLoadingRenderConfig(
        sizePx = 26f,
        strokeWidthPx = 4f,
    ),
)

KanvasChart(
    state = chartState,
    config = KanvasChartConfig(
        style = darkStyle,
        render = renderConfig,
        chartType = KlineChartType.Bar(KlineBarStyle.UpHollow),
    ),
)
```

图表类型可在运行时切换：

- `KlineChartType.Bar`：`AllSolid`、`AllHollow`、`UpHollow`、`DownHollow`、`Ohlc`。
- `KlineChartType.Line`：普通分时线或 `UpDown` 涨跌双色线，并支持区域渐变。

主题对象应使用 `remember` 或由稳定的应用 Theme 提供，避免每次重组都创建新配置。

示例首次进入时跟随系统深浅主题，也提供手动切换按钮；相邻的涨跌色按钮可在“红涨绿跌”与
“绿涨红跌”之间切换。K 线实体和影线、成交量柱、最新价等语义元素统一读取
`KlineChartStyle.bullish` / `bearish`；示例还将同一组颜色映射到
`KlineOrderMarkerRenderConfig`，让 Buy 跟随上涨色、Sell 跟随下跌色。宿主只需维护一组
统一的涨跌语义色，无需逐个修改 renderer。

## 原生水印

通过 `KanvasChartConfig.watermark` 可以直接在图表 Canvas 内绘制不拦截触摸的原生水印，
不需要额外包裹 Overlay，也不会替换内置 Loading：

```kotlin
KanvasChart(
    state = chartState,
    config = KanvasChartConfig(
        watermark = KanvasWatermarkConfig(
            content = KanvasWatermarkContent.Text(
                value = "ACME EXCHANGE",
                color = darkStyle.textColor,
            ),
            target = KanvasWatermarkTarget.MainPane,
            placement = KanvasWatermarkPlacement.Tiled,
            layer = KanvasWatermarkLayer.BehindContent,
            alpha = 0.08f,
            rotationDegrees = -20f,
        ),
    ),
)
```

图片 Logo 使用 `KanvasWatermarkContent.Image(bitmap)`；未指定高度时会保持图片宽高比。
水印范围支持全图、主图或 `SubPane(id)` 指定副图。`BehindContent` 位于网格和行情内容下方，
`AboveContent` 位于 K 线、指标及画线之上，但仍低于 Loading Overlay。运行时将配置改为
`null` 即可关闭水印。

## 布局与交互

`KlinePaneRenderConfig(mode = KlineLayoutMode.Adapt)` 会通过图表自定义的 Compose
`Layout` 请求计算后的高度，并受父级约束限制。`onLayoutChange` 还会发布物理窗格几何信息和
`requiredHeightPx`，供需要协调周边 UI 的宿主使用。

当最新 K 线不在屏幕中时，最新价标签可以点击。标准状态会自动回到最新位置，并清除待处理的
加载更多状态。若双击只需要通知业务而不自动复位，可设置
`KanvasChartConfig.resetToLatestOnDoubleTap=false`，并通过 `KanvasChartCallbacks` 观察事件。
当周期长于一秒时，屏幕内最新 K 线的价格标签下方还会显示 Kanvas 风格的实时倒计时。
原生 `KlineCountdownRenderConfig` 控制可见性，以及当前支持的背景、文字、边框尺寸；
宿主需要直接构造该配置；当前仓库不提供外部 Candle 记录到 Compose 配置的自动转换器。
分时图会应用与 Kanvas 兼容的区域渐变。普通线条向窗格底部填充；涨跌线会精确地在与最新价
基线相交的位置拆分，并分别使用看涨/看跌渐变。宿主通过
`KlineCandleGradientConfiguration` 直接配置对齐方式、色标位置、平铺模式、动态透明度和
显式颜色列表。

触摸平移默认使用 `KlineGestureConfig.panSensitivity = 1.2f`，因此横向移动距离和松手后的
惯性距离会比物理 1:1 跟随增加 20%。如需精确跟随可设为 `1f`，也可以在宿主渲染配置中覆盖。

常用交互由 `KlineGestureConfig` 控制，包括长按 Cross、惯性平移、双指缩放、键盘快捷键、
纵向缩放和自动加载更多。标准入口默认双击复位：

```kotlin
KanvasChart(
    state = chartState,
    callbacks = KanvasChartCallbacks(
        onDoubleTap = { analytics.track("chart_double_tap") },
    ),
)
```

## 数据加载与实时更新

Kanvas 将数据操作明确区分为三类：

| 场景 | API | 行为 |
| --- | --- | --- |
| 首次市场加载 | `setMarket(spec, candles)` | 选择市场、替换序列并回到初始视口 |
| 完整刷新 | `setData(candles)` | 替换当前市场的整个序列 |
| 行情推送 | `updateLatest(candle)` | 更新同时间戳最新 K 线，或插入一根更新的 K 线 |
| 历史分页 | `completeLoadMore(requestId, candles, hasMoreOlder)` | 将严格更旧的一页追加到历史侧，并保持当前视口锚点 |

当 `renderConfig.gesture.autoLoadMore` 为 `true` 时，
`KlineLoadingState.InitLoading` 和 `LoadingMore` 会在主窗格中显示加载动画。
宿主通过 `KlineLoadingRenderConfig` 直接配置尺寸、线宽和颜色；
`LoadMore` 保持静默，与 Kanvas 的预取状态一致。

图表会在惯性开始前根据预计落点触发加载：进入提前加载阈值时使用静默
`LoadMore`，预计撞到历史边界时升级为显示动画的 `LoadingMore`。
加载更多使用请求令牌。收集 `KlineEvent.LoadMore`，通过
`completeLoadMore(requestId, incoming, hasMoreOlder)` 或
`failLoadMore(requestId, message)` 结束请求。过期的完成回调会被拒绝，
`hasMoreOlder=false` 会阻止重复请求。

推荐在一个与 Controller 生命周期一致的协程中收集加载事件。每个事件都包含请求令牌和明确的
`beforeTimestampMillis` 游标：

```kotlin
LaunchedEffect(chartState, spec) {
    chartState.events.collect { event ->
        if (event !is KlineEvent.LoadMore || event.spec.key != spec.key) return@collect

        runCatching {
            marketRepository.loadOlder(
                spec = event.spec,
                beforeTimestampMillis = event.beforeTimestampMillis,
            )
        }.onSuccess { page ->
            val normalized = page.candles
                .sortedByDescending(KlineCandle::timestampMillis)
                .distinctBy(KlineCandle::timestampMillis)
                .filter { it.timestampMillis < event.beforeTimestampMillis }

            chartState.completeLoadMore(
                requestId = event.requestId,
                candles = normalized,
                hasMoreOlder = page.hasMoreOlder,
            )
        }.onFailure { error ->
            chartState.failLoadMore(
                requestId = event.requestId,
                message = error.message ?: "load older candles failed",
            )
        }
    }
}
```

同一请求从静默 `LoadMore` 升级到可见 `LoadingMore` 时不会产生第二个数据事件。可见 loading
期间图表会隔离新的触摸手势，避免上一页尚未结束时连续翻页。请求完成后，下一次滑动才会生成
新的 requestId。业务层不要绕过令牌直接拼接数据。

Core 不排序、去重或合并交易所数据。宿主必须提供严格按时间倒序、时间戳不重复的
K 线，并使用明确的数据操作：

```kotlin
chartState.setMarket(spec, initialCandles)
chartState.updateLatest(realtimeCandle)
chartState.completeLoadMore(requestId, strictlyOlderCandles)
```

对于 300ms 等高频更新，推荐让最新蜡烛实时变化、指标仅在新 K 线出现时刷新，并对
同一根最新蜡烛的自动价格范围做平滑处理：

```kotlin
IndicatorRuntimeCoordinator(
    controller = controller,
    registry = indicatorRegistry,
    scope = scope,
    refreshPolicy = KlineIndicatorRefreshPolicy.OnCandleBoundary,
)

val renderConfig = KlineChartRenderConfig(
    latestCandleRangeSmoothFactor = 0.18f,
)
```

完整的时间戳边界、`confirmed`、强制刷新和参数说明见
[实时 K 线、指标刷新与画面稳定](docs/realtime-updates.zh-CN.md)。

`completeLoadMore` 只把新页追加到旧数据尾部；第一页必须严格早于当前 oldest。
交易所分页的闭区间重复、正序返回和中间历史修正都应由数据接口适配层处理。
加载事件同时提供语义明确的 `beforeTimestampMillis` 游标。

## 订单标记

订单以 K 线时间戳作为锚点传给图表。Kanvas 在最低价下方绘制方形买入标记 `B`，
在最高价上方绘制方形卖出标记 `S`，并用三角箭头指向对应 K 线；平移、缩放和加载
历史分页后，标记仍会跟随对应 K 线。

```kotlin
val orders = listOf(
    KlineOrderMarker(candleTimestampMillis, KlineOrderSide.Buy),
    KlineOrderMarker(olderCandleTimestampMillis, KlineOrderSide.Sell),
)

KanvasChart(
    state = chartState,
    orderMarkers = orders,
    config = KanvasChartConfig(
        orderMarkers = KlineOrderMarkerRenderConfig(
            buyColor = Color(0xFF16A085),
            sellColor = Color(0xFFE05A5A),
        ),
    ),
)
```

`timestampMillis` 必须与目标 K 线的时间戳一致。同一根 K 线上的多个同向订单会自动堆叠，
可视范围外的订单不会参与绘制。

## 绘图与持久化

创建 `DrawingController`，将它传给图表，然后从应用 UI 启动内置工具：

```kotlin
val drawing = chartState.drawingController

KanvasChart(state = chartState)

Button(onClick = { drawing.prepare(DrawingTypeDescriptor.TwoPointLine) }) {
    Text("Draw line")
}
```

内置工具包括线段、射线、无限直线、水平线、垂直线、矩形和斐波那契回撤，顺序列表可从
`DrawingTypeDescriptor.BuiltIns` 获取。`DrawingController` 还提供 `undo()`、`redo()`、
`canUndo` 和 `canRedo`，便于宿主构建完整工具面板。Example 展示了可在图表范围内拖动的
分组工具选择器和选中态上下文工具栏。

绘制/编辑的指针输入优先级高于 Cross、窗格大小调整、纵向缩放、双指缩放和平移。
点位只持久化时间戳和值；每一帧都会根据当前序列和视口进行投影。
`DrawingToolRegistry` 支持注册自定义工具。`DrawingToolbar` 承载应用自定义操作，
并发布限制在有效范围内的可拖动位置。`DrawingMagnifierConfig` 控制 Android 平台放大镜。
绘图状态由宿主通过 `DrawingController` 管理，可使用应用自己的 Android 存储方案持久化。
竞品分析和 Kanvas 的分阶段方案见
[K 线绘图竞品调研与 Kanvas 方案](docs/drawing-research.zh-CN.md)。

## Compose 原生指标插件

Kanvas 附带的 Kotlin 指标均通过公开插件 API 实现，业务可以直接使用，也可以作为自定义指标模板：

| 指标 | 插件 | 默认位置 |
| --- | --- | --- |
| MA | `KlineMovingAverageIndicatorPlugin` | 主图 |
| EMA | `KlineEmaIndicatorPlugin` / `KlineEmaTripleIndicatorPlugin` | 主图 |
| BOLL | `KlineBollIndicatorPlugin` | 主图 |
| Volume | `KlineVolumeIndicatorPlugin` | `volume` 副图 |
| MACD | `KlineMacdIndicatorPlugin` | `macd` 副图 |
| KDJ | `KlineKdjIndicatorPlugin` | `kdj` 副图 |
| RSI | `KlineRsiIndicatorPlugin` | `rsi` 副图 |
| SAR | `KlineSarIndicatorPlugin` | 主图 |
| AVL | `KlineAvlIndicatorPlugin` | 主图 |
| SUPER | `KlineSuperTrendIndicatorPlugin` | 主图 |
| OBV | `KlineObvIndicatorPlugin` | `obv` 副图 |
| WR | `KlineWrIndicatorPlugin` | `wr` 副图 |
| StochRSI | `KlineStochasticRsiIndicatorPlugin` | `stoch_rsi` 副图 |

内置实现遵循常见交易软件约定：RSI 和 StochRSI 使用 Wilder 平滑；MACD 柱值为
`2 × (DIF - DEA)`；KDJ 的 K、D 平滑周期独立；SuperTrend 使用 Wilder ATR；BOLL
计算列顺序固定为 `boll_upper`、`boll_mid`、`boll_lower`，Top Tips 显示为
UPPER、MID、LOWER；Example 编辑器使用 UP、MB、DN 标签。SAR 会根据最早两根有效 K 线
确定初始趋势。

RSI 和 OBV 使用各自的类型安全配置。RSI 可同时绘制多个周期及上下参考线；OBV 可选配
真实计算的 MA/EMA 覆盖线：

```kotlin
val rsiConfig = KlineRsiIndicatorConfig(
    periods = listOf(6, 14, 24),
    upper = 70.0,
    lower = 30.0,
)
val obvConfig = KlineObvIndicatorConfig(maPeriod = 7, emaPeriod = 7)
```

`KlineIndicatorLineStyle.visible` 控制单条输出是否绘制；隐藏线不会进入 Top Tips，也不会影响
指标窗格的纵轴范围。主图指标是否参与 K 线价格纵轴仍由
`KlineChartRenderConfig.includeMainIndicatorsInValueRange` 统一控制。

新指标从 Kotlin 类型安全配置开始，不接收无类型 JSON 或字符串式键值。
插件会生成 Core 定义/计算器，以及可选的渲染器或有状态工厂。
`bind(config)` 会将配置写入 `IndicatorDefinition.configuration`，因此结构性配置变化会使
计算结果失效，并通过 `onUpdate` 传递到保留的有状态渲染器。

```kotlin
val ma = remember { KlineMovingAverageIndicatorPlugin(id = "ma", label = "MA") }
val volume = remember { KlineVolumeIndicatorPlugin(id = "volume", label = "Volume") }
val catalog = remember {
    KlineIndicatorPluginCatalog.of(
        ma.bind(KlineMovingAverageIndicatorConfig(periods = listOf(7, 25))),
        volume.bind(KlineVolumeIndicatorConfig()),
    )
}
val chartState = rememberKanvasChartState(
    indicatorCatalog = catalog,
    activeIndicatorKeys = catalog.definitions.map { it.key },
)

KanvasChart(
    state = chartState,
)
```

无需接触 Registry 或计算协调器，即可更新强类型参数、显示状态和副图顺序：

```kotlin
chartState.indicators.update(
    ma,
    KlineMovingAverageIndicatorConfig(periods = listOf(10, 30)),
)
chartState.indicators.toggle(volume.key)
chartState.indicators.moveSubIndicator(volume.key, index = 0)
```

`rememberKanvasChartState` 负责持有并关闭计算协调器和有状态 Renderer。绑定的渲染器/工厂
只能处理各自 Core `(kind, id)`，因此通配实现无法抢占其他插件或兜底渲染器。
默认最多同时激活四个副指标；超出容量时按激活顺序执行 FIFO。需要其他数量时，向
`rememberKanvasChartState` 传入 `subIndicatorCapacity`。底层
`rememberKlineIndicatorPluginChartRuntime` 和 `IndicatorRuntimeCoordinator` 继续作为高级 API。

`IndicatorRuntimeCoordinator` 会在 UI 线程之外执行计算，替代结果等待中保留上一份成功输出，
仅在计算失败时清空输出，并使用计算时对应的控制器修订号、注册表令牌/代次标记每个成功快照。
`KanvasChart` 会在绘制前再次校验这些标记，因此不会显示与更新状态竞争的旧结果。
通过 `coordinator.error` 观察失败，并针对暂时性错误调用 `retry()`。
每一对控制器/注册表只能创建一个协调器；symbol 或 interval 键变化时，它会调用
`notifySpecChanged(oldSpec)`。宿主如果有意不使用协调器，则必须在切换这些键时自行调用该
注册表方法。计算器是同步宿主代码，必须及时结束；无限阻塞的计算器无法被强制取消。

## 副图布局与自定义指标

指标放置位置由配置中的 `IndicatorPlacement` 决定：

- `IndicatorPlacement.Main`：与 K 线共享主图。
- `IndicatorPlacement.Sub("macd")`：创建或复用 ID 为 `macd` 的副图。
- 多个指标使用相同 `paneId` 时共享一个副图，并按 `zIndex` 确定绘制顺序。

副图高度、最小高度和内边距由宿主统一控制：

```kotlin
val macd = remember { KlineMacdIndicatorPlugin(id = "macd") }
val rsi = remember { KlineRsiIndicatorPlugin(id = "rsi") }
val catalog = remember {
    KlineIndicatorPluginCatalog.of(macd.bind(), rsi.bind())
}

KanvasChart(
    state = chartState,
    config = KanvasChartConfig(
        panes = KlinePaneRenderConfig(
            mode = KlineLayoutMode.Fixed,
            subPanes = listOf(
                KlineSubPaneRenderConfig(
                    id = "macd",
                    preferredHeight = 100.dp,
                    minHeight = 64.dp,
                    padding = KlinePanePadding(topPx = 12f, bottomPx = 4f),
                ),
                KlineSubPaneRenderConfig(
                    id = "rsi",
                    preferredHeight = 80.dp,
                    minHeight = 56.dp,
                ),
            ),
        ),
    ),
)
```

自定义指标通常由三部分组成：

1. 实现 `KlineIndicatorPluginConfig` 的不可变 `data class`。
2. 实现 `KlineIndicatorPlugin<C>`，生成稳定的 `IndicatorKey`、`IndicatorDefinition` 和
   `IndicatorCalculator`。
3. 提供 `KlineIndicatorRenderer`；纯数值折线可以复用 `KlineComputedLineIndicatorRenderer`，
   柱状图、区域图或业务标记可实现自己的 Canvas renderer。

计算器输入和输出都按“最新 K 线在前”排列。输出的每个 `IndicatorColumn` 长度必须和
`series.size` 完全一致，不能在计算器中持有或修改输入序列。完整实现可以参考
[`KlineExampleIndicatorPlugins.kt`](kanvas-compose/src/main/kotlin/com/zhumeng/kanvas/KlineExampleIndicatorPlugins.kt)。

`KlineIndicatorRenderer` 是 Kotlin Canvas SPI。默认注册表会把 Android 示例 `Volume`
计算器中唯一的 `volume` 列渲染为柱状图，然后为非空的 Computed 输出使用通用折线。
Direct/External 渲染器可在 `output == null` 时绘制，并可选择提供
`visibleValueRange`，因此业务标记不依赖预计算流程。如果宿主渲染器应负责某个声明，
请将其放在默认渲染器之前。下面属于高级接入，刻意使用底层图表重载：

```kotlin
val rendererRegistry = remember {
    KlineIndicatorRendererRegistry(
        listOf(orderMarkerRenderer) + KlineIndicatorRendererRegistry.Default.renderers(),
    )
}

KanvasChart(
    state = state,
    onViewportChange = controller::updateViewport,
    indicatorSnapshot = output,
    indicatorRegistrySnapshot = registrySnapshot,
    indicatorRendererRegistry = rendererRegistry,
    onUnsupportedIndicators = { definitions -> reportUnsupported(definitions) },
)
```

没有匹配渲染器的激活声明会通过 `onUnsupportedIndicators` 报告，且不会分配空窗格。
Computed 快照缺失或过期时，只有渲染器实现了 `supportsPending(definition)`（或存在匹配的
有状态工厂），才会保留其窗格；旧输出永远不会被传给渲染器。
`supports`/`supportsPending` 和有状态工厂的 `supports` 都必须是纯粹、无异常的匹配器，
因为图表可能会在组合和命中测试期间调用它们。

渲染器还可以实现 `KlineIndicatorTopTipsRenderer`，用于绘制指标自身的顶部标签。
`prepareTopTips` 在普通绘制时接收最新 K 线，
在 Cross 状态下接收解析后的 Cross 选中项。主指标 Tips 按绘制顺序准备，每个非空的
`claimedHeightPx` 都会将下一个 Tips 矩形向下移动；副指标则各自接收自己的窗格矩形。
准备好的值会在同一个 Canvas 帧中原样传给 `drawTopTips`。该钩子与图表级
`KlineCrossTooltipProvider` 相互独立：它用于指标自身标签，而不是共享的 Cross 提示卡片。

窗格规划会保留注册表的副指标 FIFO。默认情况下，`IndicatorPlacement.Sub()` 为每个键创建
一个窗格；使用相同的非默认 `paneId` 是 Android 共享窗格扩展的显式用法。
为默认 placement 添加具名 `KlineSubPaneRenderConfig` 时，请使用
`IndicatorPlacement.Sub().resolvedPaneId(key)`；其中字面量 `"default"` 仅是哨兵值。
`main` 和 `time` 不能作为具名副窗格 ID。除非显式的 `KlineSubPaneRenderConfig(id)`
覆盖，否则共享窗格自身的几何信息会以确定性方式合并。
`IndicatorLayoutHint` 携带逻辑 `height`、`minHeight` 及可选的自身 `padding`。
K 线主体、Main `COMBINE` 和 `ALONE` 项会在同一个 `zIndex` 序列中渲染：
相同 z-index 时先绘制 K 线，再绘制 Kotlin 指标。`ALONE` 在主绘制区域内使用自己底部对齐的矩形。

底层 `KlineIndicatorRenderer` Canvas SPI 是无状态的。如果 Kotlin 实现需要持有异步业务
状态，请添加按键区分的工厂和
生命周期宿主；上述无状态兜底渲染器仍然完全受支持：

```kotlin
val rendererRegistry = remember {
    KlineIndicatorRendererRegistry(
        renderers = KlineIndicatorRendererRegistry.Default.renderers(),
        statefulFactories = listOf(orderRendererFactory),
    )
}
val rendererHost = rememberKlineIndicatorRendererLifecycleHost(rendererRegistry)

KanvasChart(
    // ... controller state and registry snapshot ...
    indicatorRendererRegistry = rendererRegistry,
    indicatorRendererLifecycleHost = rendererHost,
)
```

`KlineStatefulIndicatorRendererFactory` 会在任何 Computed 输出出现前匹配定义，并为每个
注册表/键创建一个渲染器。它的可选渲染器回调对应 Kanvas 的
init/update/attach/detach/spec-change/dispose 契约。生命周期宿主根据注册表快照
（而非有损事件流）进行协调，永远不会暴露来自旧挂载/代次/spec 转换的实例，并为异步渲染器
状态提供线程安全的 `invalidate()` 回调。生命周期回调失败时，该实例会被销毁，并回退到
无状态渲染器或不支持通道，直到声明/工厂发生变化。

原生 `KlineIndicatorOverlayRenderer`、`KlineIndicatorCrossRenderer` 和
`KlineIndicatorTapHandler` 由 `KanvasChart` 分发。只有非零尺寸窗格会收到钩子。
主窗格覆盖物遵循 `renderConfig.indicatorOverlay.allowOutsideMainRect`；副窗格覆盖物会
自行决定裁剪方式。Cross 在内置 Cross/Time 标签之后运行，随后依次处理物理副窗格和主窗格
z-order。确认点击时，会先让可见且可操作的 Cross 提示行尝试消费事件，再访问屏外价格目标、
主窗格项目和物理副窗格；第一个返回 `true` 的处理器会阻止持久 Cross 状态切换。
点击处理器负责自己的命中测试，因此覆盖物目标可以超出窗格。

### 原生 Cross Tooltip

默认 Cross 卡片现在与 Kanvas 的 Time/O/H/L/C/Chg/%Chg/Range/Amount/Turnover
字段一致。Canvas 绘制和行命中测试共用一个纯双列测量/布局结果，在当前 Cross 会话中保留最大
内容宽度，并遵循 Kanvas 的外层主窗格锚点和绘制区域原点限制。
未换行标签宽于绘制区域，或卡片高于较矮窗格时，仍可能有意溢出，以保持和 Kanvas 一致。
默认 `10.sp` 文字同样会遵循 Android 字体缩放；显示密度或字体缩放变化时，其物理宽度缓存会
重置。原生逻辑像素配置 `tooltipHitTestMarginPx` 用于控制可操作行的命中扩展范围。

可以使用 Kotlin 原生 Provider 提供业务标签、样式或可操作行：

```kotlin
KanvasChart(
    // ... chart state ...
    crossTooltipProvider = KlineCrossTooltipProvider { context ->
        context.candle?.let { candle ->
            listOf(
                KlineCrossTooltipItem("Close", candle.close.toString()),
                KlineCrossTooltipItem(
                    label = "Open order",
                    value = "Details",
                    onClick = { openOrderFor(candle.timestampMillis) },
                ),
            )
        } ?: emptyList()
    },
)
```

Provider 会在 UI 路径上用于绘制和命中测试，因此必须保持确定、快速且无副作用；
只有某一项的 `onClick` 会在确认命中该行后执行。指标 Top Tips 使用独立的
准备/测量/定位/绘制流程。

运行当前检查：

```text
./gradlew apiCheck test lintRelease assembleRelease :example:assembleDebug
./gradlew :kanvas-compose:connectedDebugAndroidTest
./gradlew publishAllPublicationsToWorkspaceRepository verifyPublishedPomScopes
```

修改库的公开 API 后，先审查变更，再运行 `./gradlew apiDump` 更新各模块的 `api/*.api`
基线。`verifyPublishedPomScopes` 用于防止公开签名依赖被错误发布为 Maven runtime 依赖。

## 发布

每个库模块都会通过 `publishAllPublicationsToWorkspaceRepository`，将 release AAR/JAR、
Gradle 元数据、POM、源码和 Dokka 生成的 Javadoc 发布到 `build/maven-repository`。
上传到 Maven Central 时使用 Sonatype Portal OSSRH Staging API，并读取
`MAVEN_CENTRAL_USERNAME`、`MAVEN_CENTRAL_PASSWORD`、`SIGNING_KEY` 和
`SIGNING_PASSWORD`（或对应的 Gradle 属性）。签名密钥只加载到内存中；本地构建不需要
release 密钥。完成签名上传后，Portal 部署的最终确认由发布操作人员执行。
