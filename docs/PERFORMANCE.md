# Kanvas performance validation and extension guide

## Strict 120 Hz acceptance

A 120 Hz display refreshes every `1000 / 120 = 8.33ms`. A 120 Hz display,
an application request for 120 fps, or an average near 120 fps does not prove
consistently smooth interaction. This repository uses conservative acceptance gates:

- fixed Release/Benchmark builds, devices, OS versions, refresh rates,
  temperatures, and data sizes;
- `frameDurationCpuMs` P95 and P99 at or below 8.33 ms throughout interaction;
- `frameOverrunMs` P95 and P99 at or below 0 ms, with no persistent alternate-
  frame submission in Perfetto;
- no periodic GC, bitmap upload, or growing GPU/RSS peak;
- repeated results across the target device matrix.

The CPU P95/P99 ≤8.33 ms gate is an extra headroom requirement, **not a necessary or sufficient
test of actual FPS**. `frameDurationCpuMs` spans the UI and RenderThread pipeline; VSync offsets
affect the scheduler's deadline. This device's median Expected FrameTimeline budget is 13.667 ms.
Assess actual lateness with `frameOverrunMs`, FrameTimeline presentation status, and SurfaceFlinger
presentation intervals. Earlier wording equating the refresh period to this CPU metric's system
deadline was imprecise; the numerical CI gates remain unchanged. See
[FrameTimingMetric](https://developer.android.com/reference/androidx/benchmark/macro/FrameTimingMetric),
[Android VSync](https://source.android.com/docs/core/graphics/implement-vsync?hl=en), and
[Perfetto FrameTimeline](https://perfetto.dev/docs/data-sources/frametimeline).

`KlineChartRenderConfig.preferredFrameRate` defaults to `120f` and only submits
a platform preference. `0f` clears it. Display capability, battery policy, and
thermal control still determine the actual rate, so the preference cannot
replace Macrobenchmark or Perfetto evidence. See Android's documentation for
[Compose preferredFrameRate](https://developer.android.com/reference/kotlin/androidx/compose/ui/preferredFrameRate.modifier)
and [adaptive refresh rate](https://developer.android.com/develop/ui/views/animations/adaptive-refresh-rate).

## 2026-09-08 third device audit (current candidate)

**Pinch GPU memory fell substantially and most pan/pinch presentation intervals approach 120 Hz,
but the overall strict gate still FAILS.** Crosshair still exceeds actual deadlines; this is not
a claim of stable 120 fps across scenarios. The final candidate completed eight iterations each
of crosshair, pan, and pinch on the same Xiaomi 23127PN0CC / Android 15, retaining 100,000 candles,
MA7/25 + Volume, 300 ms updates, three seconds of stabilization, and `CompilationMode.Partial()`.
Each scenario started at confirmed 120 Hz. Boundary readings were 84–87% battery and 32.4–32.8°C
while charging. CPU clocks were not locked, benchmark errors were not suppressed, and reported
thermal-throttle sleep was zero.

Retained changes:

- Built-in main combined-indicator paths use up to 64 segments only during pinch, reducing
  pressure from changing small path masks. Stationary/pan paths retain eight segments.
  Gesture completion or cancellation restores that mode, which is part of the history-layer
  cache key. Custom renderers, ALONE indicators, and already-prepared lines retain their paths.
- Price formatting uses a reusable fixed-point fast path, with the original `DecimalFormat`
  handling ambiguous rounding and large values. JVM and Android reference tests cover precision,
  negative zero, rounding boundaries, and varying magnitudes.
- Uniform-radius text backgrounds use `drawRoundRect`; asymmetric corners retain Path rendering.
  Pixel-reference tests cover translucent fill/borders, elliptical corners, and oversized radii.
  The library Baseline Profile was regenerated for this code.

Final APK SHA-256: `397ff16e8df6a4bea53f368e13bc6742bdcb06ff850c1014613db48a86bb2753`.
The APK is 1,523,465 bytes and contains a 4,802-byte compiled `baseline.prof`.
The source profile contains 4,859 rules / 681,294 bytes, SHA-256
`11e29d0ae11a3443b923970a25fa343910145eada2f57956c01f1fd22e5ce3a0`.
API checks pass; there is no new public API or migration requirement.

Raw Macrobenchmark JSON, in milliseconds. The gate still reads the complete original results:

| Scenario | CPU P95 | CPU P99 | Overrun P95 | Overrun P99 | Gate |
| --- | ---: | ---: | ---: | ---: | --- |
| Pan | 17.101 | 21.052 | 7.483 | 10.894 | FAIL |
| Pinch | 17.326 | 21.160 | 6.642 | 10.291 | FAIL |
| Crosshair | 21.833 | 24.935 | 11.200 | 15.359 | FAIL |

All 19,472 unfiltered CPU/overrun samples across 24 iterations matched their original JSON.
Using the same frame association described in the first audit, restricting to `measureBlock`
leaves 15,648 interaction frames:

| Interaction window | Frames | CPU P95 | CPU P99 | Overrun P95 | Overrun P99 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Pan | 7924 | 8.665 | 15.114 | -2.715 | 10.188 |
| Pinch | 7525 | 10.289 | 11.490 | -1.519 | -0.243 |
| Crosshair | 199 | 22.886 | 26.728 | 12.438 | 16.888 |

Pinch interaction overrun P99 changed from +0.243 ms in stage two to -0.243 ms, while its CPU
headroom gate still fails. Pan P95 stayed close to stage two, but CPU P99 increased from
11.356 to 15.114 ms; this is not an improvement in every metric. Crosshair did not improve.
These runs were not randomized A/B trials: charge, temperature, profiles, and execution order
differed, so small timing changes cannot be attributed to one edit.

| Scenario | Stage-two median GPU peak | Current median GPU peak | Current highest GPU peak | Current median heap peak |
| --- | ---: | ---: | ---: | ---: |
| Pan | 126.5 MiB | 122.1 MiB | 173.1 MiB | 86.5 MiB |
| Pinch | 495.6 MiB | 63.3 MiB | 64.0 MiB | 87.2 MiB |
| Crosshair | 78.1 MiB | 78.4 MiB | 78.5 MiB | 65.6 MiB |

These medians aggregate each iteration's maximum sample; they are not average resident memory.
Pinch decreased by about **87.2%**, but path texture uploads remain. A lower peak does not prove
that all GPU overhead is resolved or establish leak-free/page-exit release behavior.

Interaction-window app Surface frames were also joined to SurfaceFlinger presentation events
by `display_frame_token`:

| Scenario | Surface frames | Late Present | Presentation interval P50 / P95 / P99 |
| --- | ---: | ---: | --- |
| Pan | 7954 | 319 (4.01%) | 8.302 / 8.334 / 8.576 ms |
| Pinch | 7577 | 797 (10.52%) | 8.302 / 8.334 / 33.331 ms |
| Crosshair | 273 | 208 (76.19%) | Snapped updates are demand-driven; no FPS conversion |

Intervals include pauses between scripted gestures, particularly affecting pinch P99.
`Late Present` includes Buffer Stuffing latency and **is not a dropped-frame percentage**.
Many pan/pinch intervals are around 8.3 ms, which does not establish timely delivery of every frame.
Crosshair/pan/pinch contain 73/4/32 app frames without an end timestamp, so these presentation
populations differ from the complete-duration matches above. Every Surface frame had a matching
SurfaceFlinger presentation. Driver negative-timestamp import warnings remain; local slices are
diagnostic evidence, while the raw timing samples were independently verified.

Final-candidate crosshair iteration two averaged 3.490 ms in `Kanvas.interaction.draw`, maximum
7.415 ms. The 1200×1882 background layer rasterized 13 times, averaging 2.285 ms; a 159×261
texture uploaded 16 times. Tooltip work and live-update-triggered background rasterization remain
optimization targets; these local slices cannot be summed as whole-frame duration.
Pinch iteration two used `Kanvas.indicator.pinch.paths`, averaging 0.029 ms per preparation,
while RenderThread `flush commands` still averaged 2.728 ms with recurring large texture uploads.
Checks across all 24 iterations found this trace in every pinch interaction window and zero
occurrences in every crosshair/pan window.

Rejected trials included global 64-segment paths, prefilled stroke outlines, and history-only
offscreen caching. The global-path candidate completed another 24 iterations, but pan median GPU
peak rose to 284.8 MiB and interaction CPU P95 to 10.727 ms. It was replaced by pinch-only selection;
the outline and history-only alternatives were also removed. Their best samples are not presented
as final-candidate results.

Validation passed: `test apiCheck lintRelease`, Benchmark assembly, 424 JVM test executions with
zero failures, 17 emulator Android tests, and six Python gate tests. Android coverage includes MA
visibility during pinch, restored pixels after release, subsequent crosshair display, formatter
equivalence, and background pixels. Functional tests ran on the emulator; performance came from
the real device. The strict gate returned 1. Device refresh settings were restored to an unset
minimum and the original peak of 120. Final APKs, 24 traces/JSON, and analysis scripts remain
outside version control at `/private/tmp/kanvas-120hz-audit/`, with prefix `adaptive_profiled`;
`adaptive-final-results` contains only the final gate inputs. All 83 device-side trial, final-run,
and profile files matched their local backups by SHA-256 before their test copies were removed
from the phone. Other device classes and production plugin combinations still require validation.

## 2026-09-08 second device audit (historical candidate)

**Strict 120 Hz still fails. This candidate is not performance-qualified for release.**
All three scenarios completed eight iterations on the same Xiaomi 23127PN0CC at confirmed 120 Hz,
with 100,000 candles, MA7/25 + Volume, 300 ms live updates, three seconds of stabilization,
and `CompilationMode.Partial()`. Boundary samples were 40–43% battery and 33.4–34.2°C while charging.
No benchmark errors were suppressed and CPU clocks were not locked.

This stage removes a draw-geometry snapshot-counter self-subscription and explicitly notifies the
interaction Canvas when geometry becomes available. Built-in Cross backgrounds use offscreen
raster reuse; pan/pinch retain vector replay, with custom renderers, drawings, and Tips preflight
excluded. Indicator tips reuse a Formatter without changing rounding semantics. Release/Benchmark
now enables [R8 optimization](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization)
and resource shrinking, with a regenerated library Baseline Profile. Profile generation uses a
separate unoptimized `baselineProfile` variant.

The tested APK is 1,523,465 bytes and includes a 4,806-byte compiled `baseline.prof`.
SHA-256: `bec5fe80ebc4f6661366f0aa50e7deab9350d9e9085d50b976e77bb867e650e9`.
Build optimization and profiles changed together with drawing code; differences from the first
stage do not isolate any one change and this was not a randomized A/B experiment.

Raw Macrobenchmark JSON, in milliseconds:

| Scenario | CPU P95 | CPU P99 | Overrun P95 | Overrun P99 | Gate |
| --- | ---: | ---: | ---: | ---: | --- |
| Pan | 16.659 | 21.282 | 6.879 | 10.879 | FAIL |
| Pinch | 17.622 | 21.586 | 7.003 | 11.004 | FAIL |
| Crosshair | 21.645 | 24.568 | 11.033 | 14.686 | FAIL |

All 19,348 unfiltered CPU/overrun samples from the 24 traces were checked against raw JSON using
the frame association described below. Restricting UI frame starts to `measureBlock` gives:

| Interaction window | Frames | CPU P95 | CPU P99 | Overrun P95 | Overrun P99 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Pan | 7795 | 8.719 | 11.356 | -2.185 | 2.660 |
| Pinch | 7442 | 10.516 | 11.709 | -1.135 | 0.243 |
| Crosshair | 188 | 22.399 | 25.681 | 11.629 | 15.645 |

Crosshair interaction P95/P99 improved from 23.982/29.877 ms, while pan P99 worsened.
A 15.820 ms crosshair P95 from an earlier single diagnostic run did not reproduce in the formal
aggregate; the CLI now rejects fewer than eight iterations. Both measurement scopes still fail.

Crosshair iteration 1 averaged 3.642 ms in `Kanvas.interaction.draw` and 6.421 ms in RenderThread
`Drawing`, with maxima of 8.580 and 15.846 ms. Live updates still re-rasterize the background;
static history, live prices, and tips need finer separation. Iteration 0 also contained a roughly
52 ms RenderThread frame with shader compilation. These slices and the matched frame metrics
have different sample sets, so the aggregate P99 must not hide first-use stalls.

Median per-iteration peak GPU memory was **78.1/126.5/495.6 MiB** for crosshair/pan/pinch,
versus 59.9/131.8/245.0 MiB in stage one. Pinch memory worsened substantially. Cache eviction and
page-exit release need investigation; concurrent changes prevent attribution to one cause, and
these peaks alone do not establish a leak. Current failure does not establish an inherent
Compose or hardware limit. Other devices and production plugins remain unqualified.

Full JVM tests, API checks, release lint, and Benchmark builds passed, together with 14 emulator
UI tests and six Python gate tests. A pagination test raced initial market/registry reconciliation;
its setup now waits for alignment before testing pagination, without changing production behavior.
The performance gate returned 1. Refresh settings were restored to peak 120 and unset minimum.
Raw APKs, traces, JSON, and validation scripts remain outside version control in
`/private/tmp/kanvas-120hz-audit/`, under the `r8_profiled` prefix for this stage.

## 2026-09-08 first device audit (R8 disabled)

**The first-stage changes still failed strict 120 Hz acceptance.** The baseline was
`fa1920f`; the updated build includes the first-stage rendering fixes. Both used
Xiaomi 23127PN0CC, Android 15 / OS2.0.215.0.VNCCNXM, 1200×2670, confirmed
120 Hz, a non-debuggable Benchmark build without R8 minification,
`CompilationMode.Partial()`, 100,000 candles, MA7/25 + Volume, 300 ms updates,
and three seconds of stabilization. Baseline crosshair and each updated
interaction scenario ran eight iterations without suppressing benchmark errors.

Updated raw Macrobenchmark results, in milliseconds:

| Scenario | CPU P95 | CPU P99 | Overrun P95 | Overrun P99 | Gate |
| --- | ---: | ---: | ---: | ---: | --- |
| Pan | 16.489 | 22.690 | 6.710 | 12.587 | FAIL |
| Pinch | 17.590 | 22.959 | 7.718 | 13.216 | FAIL |
| Crosshair | 23.666 | 25.889 | 14.381 | 17.449 | FAIL |

On this device traces contain about 30 seconds of live-update frames before
`measureBlock`, and AndroidX Benchmark 1.4.1 includes the full trace in its
frame query. A separate analysis matched its frame association and validated
every unfiltered CPU/overrun sample against each iteration's JSON, then kept
frames whose UI start fell inside `measureBlock`. Across eight iterations:

| Interaction window | Frames | CPU P95 | CPU P99 | Overrun P95 | Overrun P99 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Pan | 7791 | 9.028 | 10.034 | -0.512 | 2.453 |
| Pinch | 7429 | 11.036 | 12.192 | -0.598 | 0.657 |
| Crosshair | 202 | 23.982 | 29.877 | 16.087 | 19.865 |

Both interpretations fail. Snapped crosshair positions do not require a new
frame for every MOVE, so the frame counts do not directly imply FPS. Baseline
crosshair CPU P95/P99 was 24.549/26.780 ms for the full trace and
26.433/34.064 ms in the interaction window. Some work was reduced, but this
was not a randomized A/B trial and does not establish stable 120 fps.

Fixes cover historical main-layer invalidation by horizontal offsets,
discarded combined-indicator paths, independent Canvas recording boundaries,
and a raw-JSON CI gate that avoids rounded-summary false passes. CI also checks
the active render rate instead of accepting 120 Hz in supported-mode listings. Crosshair
UI recording and RenderThread replay remain the largest concern; scaling
also re-records history and axis layers. Median maximum GPU memory was about
59.9/131.8/245.0 MiB for crosshair/pan/pinch, so cache growth needs an explicit
budget too. These samples do not establish a memory leak.

JVM tests, API checks, release lint, Benchmark assembly, 12 emulator Compose
UI tests, and five Python gate tests passed. Performance came from the real
device; Xiaomi background Activity restrictions prevented its Compose UI
suite from running. The strict performance gate returned 1 (FAIL). Refresh
settings were restored. Raw artifacts remain outside version control at
`/private/tmp/kanvas-120hz-audit/`. See the
[Chinese audit](PERFORMANCE.zh-CN.md) for trace evidence and
limitations. Other device classes and production plugin combinations still
require validation.

## 2026-08-21 historical baseline

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

That historical version could complete pan and pinch with 100,000 candles,
but **failed this repository's strict gates**. CPU duration cannot be inverted
to infer FPS; the earlier 50–66 fps crosshair claim lacked presentation-timing
evidence and is withdrawn. Positive overrun shows frames exceeding system
deadlines and should be assessed alongside FrameTimeline. Neither display
rate nor P50 establishes stable 120 fps. See the latest audit above for the
current candidate.

## 120 Hz rendering stack

Implemented hot-path work includes visible-range and indicator caches, native
Canvas batching for candles and volume, prebuilt paths, horizontal-overscan
`GraphicsLayer` reuse during pan, split grid/axis/time layers, simple native
text batching, VSync-coalesced viewport commits, reusable price
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
crosshair 100,000-candle scenarios. Missing scenarios or fewer than eight iterations fail the check.

Check local Macrobenchmark output with:

```bash
python3 tools/check_macrobenchmark_thresholds.py \
  --results benchmark/build/outputs/connected_android_test_additional_output/benchmark \
  --profile strict-120hz
```

The gate prefers unrounded `sampledMetrics` from `*-benchmarkData.json`.
Studio/XML summaries commonly round to one decimal place, turning 8.34 ms
into 8.3 ms and potentially passing the 8.33 ms boundary incorrectly. Legacy
text parsing remains available for diagnostics, but the strict CLI gate
requires raw JSON and never fills missing scenarios from older summaries.

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
defined in code. The `kanvas.benchmark.iterations` argument can shorten local diagnostic runs;
results with fewer than eight iterations cannot pass strict acceptance.

Generate only the Baseline Profile with:

```bash
./gradlew :benchmark:connectedBaselineProfileAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.zhumeng.kanvas.benchmark.KanvasBaselineProfileGenerator
```

The `benchmark` variant inherits release R8 optimization and resource shrinking.
The `baselineProfile` variant disables R8 to preserve library method signatures, as required by
the [Android Baseline Profile guidance](https://developer.android.com/topic/performance/baselineprofiles/overview).
Do not use that variant for optimized release performance acceptance. Generated rules are under
`benchmark/build/outputs/connected_android_test_additional_output/baselineProfile/`.
The library profile keeps `com.zhumeng.kanvas` hot rules and excludes the example app. Copy the generated
`KanvasBaselineProfileGenerator_generate-baseline-prof.txt` into `kanvas-compose/src/main/baseline-prof.txt`
and rebuild to package the new rules. Before release, also run:

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
