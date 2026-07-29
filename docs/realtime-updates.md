# Realtime candles, indicator refresh, and range stability

Realtime delivery cadence and candle interval are different concepts. A feed may update every 300ms while all updates inside a 1m/15m/1h interval retain the same candle timestamp. Call `updateLatest` with that timestamp; insert a newer timestamp only when the interval boundary is crossed.

## Indicator policy

`IndicatorRuntimeCoordinator` supports two policies:

- `KlineIndicatorRefreshPolicy.EveryTick` recalculates after every candle revision.
- `KlineIndicatorRefreshPolicy.OnCandleBoundary` keeps indicator values stable while the open candle is replaced and recalculates when a new candle is inserted.

The standard API selects this policy when creating state:

```kotlin
val chartState = rememberKanvasChartState(
    indicatorCatalog = catalog,
    indicatorRefreshPolicy = KlineIndicatorRefreshPolicy.OnCandleBoundary,
)
```

Advanced integrations that own Controller and Registry separately can still
construct `IndicatorRuntimeCoordinator` directly.

The retained output is rebound to the current controller revision, so renderers continue to receive a valid snapshot. A new timestamp/series size, spec or registry change, first load, full replacement, or explicit `retry()` still calculates fresh output.

Changing only `confirmed` on the same timestamp does not count as a boundary. Call `retry()` if confirmation itself must refresh indicators.

## Main-range smoothing

The newest candle can still change the automatic Y range when its high or low expands. Smooth only same-candle realtime range changes with:

```kotlin
val renderConfig = KlineChartRenderConfig(
    latestCandleRangeSmoothFactor = 0.18f,
)
```

The allowed range is `0.1f..1f`; `1f` preserves immediate range updates. Pan, zoom, full replacement, and a newly inserted candle are not treated as same-candle ticks.

For a 300ms feed, the recommended combination is `OnCandleBoundary` plus a range factor around `0.15f..0.25f`. Use exchange timestamps for interval boundaries; the local delay is only a UI consumption cadence.
