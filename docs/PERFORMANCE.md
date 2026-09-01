# Kanvas performance validation and extension guide

## Strict 120 Hz acceptance

A 120 Hz display allows only `1000 / 120 = 8.33ms` per frame. A 120 Hz display,
an application request for 120 fps, or an average near 120 fps does not prove
that interaction consistently meets that budget. Strict acceptance requires:

- fixed Release/Benchmark builds, devices, OS versions, refresh rates,
  temperatures, and data sizes;
- `frameDurationCpuMs` P95 and P99 at or below 8.33 ms throughout interaction;
- `frameOverrunMs` P95 and P99 at or below 0 ms, with no persistent alternate-
  frame submission in Perfetto;
- no periodic GC, bitmap upload, or growing GPU/RSS peak;
- repeated results across the target device matrix.

`KlineChartRenderConfig.preferredFrameRate` defaults to `120f` and only submits
a platform preference. `0f` clears it. Display capability, battery policy, and
thermal control still determine the actual rate, so the preference cannot
replace Macrobenchmark or Perfetto evidence. See Android's documentation for
[Compose preferredFrameRate](https://developer.android.com/reference/kotlin/androidx/compose/ui/preferredFrameRate.modifier)
and [adaptive refresh rate](https://developer.android.com/develop/ui/views/animations/adaptive-refresh-rate).

## Device baseline

The following sample was recorded on 2026-08-21. It is not a cross-device
promise: Xiaomi 23127PN0CC, Android 15, 1200×2670, SurfaceFlinger-confirmed
120 Hz; 100,000 candles; Benchmark/Release target; `CompilationMode.Partial()`;
three seconds of stabilization; three independent iterations per scenario.
The fixture retained a 300 ms latest-candle update cadence.

| Scenario | CPU P50 | CPU P90 | CPU P95 | CPU P99 | Overrun P50/P90/P95/P99 | Median frames | Median GPU/heap peak |
| --- | ---: | ---: | ---: | ---: | --- | ---: | ---: |
| Pan | 8.31 ms | 15.80 ms | 16.81 ms | 19.32 ms | -2.83 / 7.86 / 10.70 / 10.92 ms | 1006 | 70.7 / 57.4 MiB |
| Pinch | 9.11 ms | 11.82 ms | 16.24 ms | 18.89 ms | -2.47 / 0.23 / 4.97 / 7.54 ms | 986 | 95.3 / 67.9 MiB |
| Continuous crosshair drag | 15.10 ms | 18.90 ms | 19.70 ms | 21.50 ms | 3.80 / 7.50 / 8.40 / 11.20 ms | 163 | 65.4 / 50.4 MiB |

The current version remains usable and reasonably smooth with 100,000 candles,
but it **does not strictly meet 120 Hz**. Pan only approaches 8.33 ms at the
median, pinch already exceeds it at the median, and continuous crosshair drag
is in roughly the 50–66 fps frame-time range. P95/P99 and overrun, rather than
display rate or P50, determine acceptance.

## 120 Hz rendering stack

Implemented hot-path work includes visible-range and indicator caches, native
Canvas batching for candles and volume, prebuilt paths, horizontal-overscan
`GraphicsLayer` reuse during pan, split grid/axis/time layers, simple native
text batching, VSync-coalesced viewport commits, allocation-free price
formatting, and AAR Baseline Profiles for pan, pinch, and uninterrupted
crosshair drag.

The stack was subsequently split further:

- Historical candle and built-in calculation layers omit index 0. A latest-bar
  layer records one candle, while line/MA patches keep only the index 0–1
  segment, producing a patch RenderNode about one or two candle steps wide.
- Core provides conservative latest-only history checks for `KlineSeries` and
  `IndicatorOutput`. Cache reuse occurs only when calculation storage was
  actually reused; third-party rebuilt output invalidates safely.
- Crosshair lines, point, price label, tooltip, and time label use separate
  layers. Lines and point translate during MOVE; labels re-record only when
  their price bucket, candle, or content changes. Tight bounds and
  `ModulateAlpha` avoid unnecessary offscreen composition.
- Pan and fling continue to calculate the visible-window Y range each frame.
  A 300 ms tick is isolated only when the viewport did not change and the
  update is proven to replace the latest candle. History, a new period, and a
  full snapshot update the range immediately.
- Main indicators use a built-in latest-aware path, an `ImmutableInputs` custom
  cache path, or a `Never` dynamic path. One dynamic renderer no longer forces
  candles and every built-in indicator back to per-frame drawing.

The earlier table is a traceable pre-split baseline; it does not prove that the
new stack passes 120 Hz. A later eight-iteration crosshair retest on the same
device, with 90 MOVE events spaced 8 ms apart, measured CPU P50/P90/P95/P99 of
`17.7/24.4/25.7/28.1ms` and overrun P50/P90/P95/P99 of
`6.7/13.2/14.7/17.5ms`, with 87 median frames and about `59.6/64.3MiB` median
GPU/Java-heap peaks. The strict gate therefore still fails on that device.

## P95/P99 and overrun CI gate

The matrix and thresholds are stored in
`benchmark/thresholds/120hz-device-matrix.json`. The strict profile requires
CPU P95/P99 ≤ 8.33 ms and frame-overrun P95/P99 ≤ 0 ms for the pan, pinch, and
crosshair 100,000-candle scenarios. A missing scenario also fails the check.

Check local Macrobenchmark output with:

```bash
python3 tools/check_macrobenchmark_thresholds.py \
  --results benchmark/build/outputs/androidTest-results/connected/benchmark \
  --profile strict-120hz
```

`.github/workflows/performance-120hz.yml` uses three dedicated self-hosted
120 Hz device classes: high-end Qualcomm, mainstream Qualcomm, and mainstream
MediaTek. Add the `performance-120hz` PR label or dispatch the workflow to lock
120 Hz, run eight formal iterations, apply the hard gate, and retain results
and Perfetto traces. Ordinary cloud emulators do not release-gate performance.

An integrating application should still generate an app-level Baseline Profile
that includes startup, feed subscription, and its real plugin set, followed by
thermal-throttling tests. The example application cannot substitute for that
integration acceptance.

## Performance tests and Baseline Profiles

The repository has two performance layers:

- `kanvas-core` `KlinePerformanceSmokeTest` covers 100,000 candles, batch
  indicators, and latest-candle updates to catch complexity regressions.
- `benchmark` measures 10,000-candle cold start and pan, pinch, long-press
  crosshair, Java heap, RSS, and GPU memory with 100,000 candles.
- The Compose and Drawing AARs include Baseline Profiles generated from pan,
  pinch, and continuous crosshair drag. Core is a JVM module, so its hot rules
  ship through the Android AARs.

Run the full benchmark on a connected API 24+ device:

```bash
./gradlew :benchmark:connectedBenchmarkAndroidTest
```

During development, use the instrumentation `class` argument to select
`panOnlyWithHundredThousandCandles`, `pinchOnlyWithHundredThousandCandles`, or
`crossOnlyWithHundredThousandCandles`. Formal records use the eight iterations
defined in code.

Generate only the Baseline Profile with:

```bash
./gradlew :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.zhumeng.kanvas.benchmark.KanvasBaselineProfileGenerator
```

The library profile keeps `com.zhumeng.kanvas` hot rules and excludes the
example application. Before release, also run:

```bash
./gradlew test lintRelease assembleRelease
```

## Custom main-chart rendering

`KlineMainChartRenderer` can replace the built-in bar/line renderer for
Heikin-Ashi, Renko, or domain-specific price presentations:

```kotlin
object CustomPriceRenderer : KlineMainChartRenderer {
    override val cachePolicy = KlineMainChartCachePolicy.ImmutableInputs

    override fun drawChart(scope: DrawScope, context: KlineMainChartDrawContext) = with(scope) {
        for (index in context.paintRange.startInclusive until context.paintRange.endExclusive) {
            val candle = context.state.series[index]
            drawCircle(
                color = context.style.line,
                radius = 2f * context.densityScale,
                center = Offset(context.xForIndex(index.toDouble()), context.yForValue(candle.close)),
            )
        }
    }
}

KanvasChart(
    state = chartState,
    mainChartRenderer = CustomPriceRenderer,
)
```

Custom drawing runs on the UI draw path and must be fast, deterministic, and
non-blocking. Use `ImmutableInputs` only when output depends entirely on the
immutable context and renderer fields; retain `Never` when observing external
mutable drawing state.

A custom indicator renderer may similarly use
`KlineIndicatorCachePolicy.ImmutableInputs`. A `Never` renderer stays in its
own dynamic path instead of invalidating built-in candle/calculation caches.
Overlay, crosshair, top-tip, and click callbacks remain live regardless of the
draw cache policy.

## Hot-path constraints

- Sort, deduplicate, and reconcile overlapping pages before creating a
  `KlineSeries`.
- Prefer `completeLoadMorePrepared` for large history pages. A successfully
  prepared page calculates indicators off-thread and commits by snapshot
  identity in O(1). If bounded retries cannot align with realtime data, it
  returns `IgnoredStalePreparedData` without publishing the page.
- Indicator configuration must have structural equality. Copy collection
  inputs at construction so external mutation cannot corrupt cache keys.
- Custom indicators, renderers, tooltip providers, and drawing tools execute on
  interaction or draw-related paths and must not perform I/O or long work.
