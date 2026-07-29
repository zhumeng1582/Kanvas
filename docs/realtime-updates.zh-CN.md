# 实时 K 线、指标刷新与画面稳定

本文说明如何以 300ms 等高频节奏更新最新 K 线，同时避免指标线和主图纵轴持续抖动。

## 设计原则

实时行情包含两个不同的时间概念：

- **推送节奏**：例如每 300ms 收到一次最新成交或聚合快照。
- **K 线周期**：例如 1m、15m、1h。同一周期内的所有推送使用相同的 K 线时间戳。

宿主应在同一周期内持续调用 `updateLatest` 替换最新 K 线；进入下一个周期时，再传入更大的时间戳插入新 K 线。不要每 300ms 创建一根新 K 线。

```kotlin
chartState.updateLatest(realtimeCandle)
```

`realtimeCandle` 必须保留当前周期的 `open`，并累计更新 `high`、`low`、`close`、`volume` 和 `turnover`。

## 指标刷新策略

Kanvas 提供两种 `KlineIndicatorRefreshPolicy`：

| 策略 | 同时间戳 Tick | 新 K 线、品种/周期或指标配置变化 |
| --- | --- | --- |
| `EveryTick` | 重算指标 | 重算指标 |
| `OnCandleBoundary` | 保持已有指标值 | 重算指标 |

交易类 App 通常推荐 `OnCandleBoundary`：最新价格和蜡烛继续更新，但 MA、EMA、BOLL、MACD、KDJ、RSI 等指标在当前 K 线内保持稳定。

标准 API 在创建状态时选择策略：

```kotlin
val chartState = rememberKanvasChartState(
    indicatorCatalog = catalog,
    indicatorRefreshPolicy = KlineIndicatorRefreshPolicy.OnCandleBoundary,
)
```

需要分别持有 Controller 和 Registry 的高级接入仍可直接创建
`IndicatorRuntimeCoordinator`。

该策略不会发布过期状态。Kanvas 会把上一份指标输出重新绑定到最新的 Controller revision，使渲染器继续显示同一组指标值，而不是先清空再出现。

以下情况仍会触发计算：

- 最新 K 线时间戳变化或序列长度变化；
- 交易对、周期或指标 Registry generation 变化；
- 调用 `IndicatorRuntimeCoordinator.retry()`；
- 首次加载或完整替换数据。

仅把同时间戳蜡烛的 `confirmed` 从 `false` 改为 `true` 不属于 K 线边界。如果业务必须在确认瞬间计算，可以调用 `retry()`，或使用 `EveryTick`。

## 主图纵轴稳定

即使指标不更新，最新蜡烛突破当前 `high/low` 时也会改变自动价格范围。`latestCandleRangeSmoothFactor` 只对“同一根最新 K 线被替换”的范围变化做插值：

```kotlin
val renderConfig = KlineChartRenderConfig(
    latestCandleRangeSmoothFactor = 0.18f,
)
```

- 取值范围为 `0.1f..1f`；
- `1f` 表示立即应用新范围，保持原行为；
- 数值越小，纵轴移动越平滑；
- 新 K 线、完整数据替换、平移和缩放不会被误判为实时 Tick。

推荐从 `0.15f..0.25f` 开始真机调试。过小会让价格突破后的纵轴跟随显得迟缓。

## 300ms 更新示例

```kotlin
LaunchedEffect(chartState, spec) {
    while (true) {
        delay(300)
        val latest = chartState.state.value.series.latest ?: continue
        val bucket = currentExchangeTimeMillis.toIntervalBucket(intervalMillis)

        if (bucket == latest.timestampMillis) {
            chartState.updateLatest(aggregateCurrentCandle(latest, latestTick))
        } else if (bucket > latest.timestampMillis) {
            chartState.updateLatest(latest.copy(confirmed = true))
            chartState.updateLatest(createNextCandle(bucket, latest.close, latestTick))
        }
    }
}
```

生产环境应使用交易所/服务端提供的时间戳和聚合数据。`delay(300)` 只表示 UI 消费节奏，不应用来推断服务器 K 线边界。页面或订阅停止时应取消该协程。

## 推荐组合

对于 300ms 行情刷新，推荐：

```kotlin
KlineIndicatorRefreshPolicy.OnCandleBoundary
KlineChartRenderConfig(latestCandleRangeSmoothFactor = 0.18f)
```

最终效果是：最新价格、蜡烛和成交量实时变化；指标在当前周期内保持稳定；价格突破时纵轴平滑移动；进入下一根 K 线时指标统一刷新。
