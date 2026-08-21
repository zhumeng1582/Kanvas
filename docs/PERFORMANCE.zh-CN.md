# Kanvas 性能验证与扩展指南

## 120Hz 验收口径

120Hz 的单帧预算只有 `1000 / 120 = 8.33ms`。屏幕处于 120Hz、应用请求
120fps，或平均帧率接近 120，都不等价于交互全程满足 120Hz。严格验收至少同时要求：

- 固定 Release/Benchmark 构建、设备、系统版本、刷新率、温度和数据规模；
- 连续交互窗口的 `frameDurationCpuMs` P95/P99 不超过 8.33ms；
- `frameOverrunMs` P95/P99 不大于 0，且 Perfetto 中没有稳定的隔帧提交；
- 没有周期性 GC、位图上传或 GPU/RSS 峰值增长；
- 在目标设备矩阵上重复验证，而不是只接受单机单轮结果。

`KlineChartRenderConfig.preferredFrameRate` 默认为 `120f`，用于向系统提交刷新率偏好；设为
`0f` 可清除偏好。它只是平台提示，系统仍可根据屏幕能力、电量和温控改变实际刷新率，不能替代
Macrobenchmark/Perfetto 验收。参见 Android 官方的
[Compose preferredFrameRate](https://developer.android.com/reference/kotlin/androidx/compose/ui/preferredFrameRate.modifier)
与[自适应刷新率说明](https://developer.android.com/develop/ui/views/animations/adaptive-refresh-rate)。

## 实机基线

以下是 2026-08-21 的验证样本，不是跨设备承诺：Xiaomi 23127PN0CC、Android 15、
1200×2670，SurfaceFlinger 确认 120Hz；10 万根 K 线；Benchmark/Release 目标；
`CompilationMode.Partial()`；页面加载完成后稳定 3 秒；每个场景 3 次独立迭代。示例同时保留
300ms 一次的最新 K 线更新，以覆盖真实行情压力。

| 场景 | CPU P50 | CPU P90 | CPU P95 | CPU P99 | Overrun P50/P90/P95/P99 | 帧数中位数 | GPU/Heap 峰值中位数 |
| --- | ---: | ---: | ---: | ---: | --- | ---: | ---: |
| 平移 | 8.31ms | 15.80ms | 16.81ms | 19.32ms | -2.83 / 7.86 / 10.70 / 10.92ms | 1006 | 70.7 / 57.4MiB |
| 双指缩放 | 9.11ms | 11.82ms | 16.24ms | 18.89ms | -2.47 / 0.23 / 4.97 / 7.54ms | 986 | 95.3 / 67.9MiB |
| 长按 Cross 连续拖动 | 15.10ms | 18.90ms | 19.70ms | 21.50ms | 3.80 / 7.50 / 8.40 / 11.20ms | 163 | 65.4 / 50.4MiB |

结论：当前版本已经能在 10 万根数据下保持可用和较顺滑的平移/缩放，但**尚未严格满足
120Hz**。平移只有中位帧接近 8.33ms；缩放的中位帧已经越界；Cross 连续拖动约落在
50～66fps 的帧耗区间。判断应以 P95/P99 和 Overrun 为准，不能把 P50 或屏幕刷新率写成
“稳定 120fps”。

## 120Hz 专用渲染栈

已落地的热路径优化包括：可视区/指标结果缓存、蜡烛与成交量原生 Canvas 批绘、预构建 Path、
平移优先复用 GraphicsLayer 并使用水平 overscan、网格/坐标轴/时间轴拆层、原生简单文本批绘、
VSync 合并视口提交、无分配价格格式化，以及覆盖平移/缩放/Cross 的 AAR Baseline Profile。

上述基线之后，渲染栈已继续拆分：

- 历史 candle/内置计算线不再记录 index 0；最新 Bar 只重录一根，Line/MA patch 只保留 index 0～1
  的连续段，patch RenderNode 的宽度约为 1～2 个 candle step；
- Core 对 `KlineSeries` 和 `IndicatorOutput` 提供保守的 latest-only 历史一致性判断。只有增量计算实际
  复用历史存储时才命中，第三方重建结果会安全失效；
- Cross 的竖线、横线、交点、价格标签、Tooltip/时间标签已拆成独立 GraphicsLayer。线和点在 MOVE
  期间只修改 translation；价格标签仅在格式化价格桶变化时重录；Tooltip/时间标签仅在选中 candle
  或其内容变化时重录。四个高频节点使用紧绘制边界和 `ModulateAlpha`，避免动态 0/1 alpha 触发
  不必要的离屏合成；内置 Tooltip 使用 Android Paint 测量和线程本地定点格式器，自定义 Provider/
  富文本样式仍保留完整 Compose 文本布局；
- 横向拖动和惯性滚动仍按当前可视窗口逐帧计算 Y 范围；仅当 viewport 未变化且确认只是替换最新
  一根 K 线时，才隔离 300ms 实时 tick。加载更多、新周期和整批快照会立即更新 Y 范围；
- 主图指标分成内置 latest-aware 快速通道、`ImmutableInputs` 自定义缓存通道和 `Never` 动态通道。
  单个动态 renderer 不再使 candle 和所有内置指标退回逐帧绘制。

旧表是改造前的可追溯基线，不能代表新渲染栈已经通过 120Hz。只有下面的严格门槛在完整目标设备
矩阵上通过后，才能把状态改为“严格满足”。

同一台设备对拆层后版本执行正式 8 次 Cross 专项复测（90 个 MOVE、每次间隔 8ms）得到：CPU
P50/P90/P95/P99 为 `17.7/24.4/25.7/28.1ms`，Overrun P50/P90/P95/P99 为
`6.7/13.2/14.7/17.5ms`；帧数中位数 87，GPU/Java Heap 峰值中位数约
`59.6/64.3MiB`。因此该设备上的当前状态仍是**严格门槛失败**。已完成的拆层解决了实时数据对历史
display list 的错误失效，也降低了 Tooltip 的 UI 线程工作，但尚未把整场景 RenderThread 回放和行情
更新长尾压入 8.33ms。

## P95/P99 与 Overrun CI 门槛

设备矩阵和阈值位于 `benchmark/thresholds/120hz-device-matrix.json`。120Hz profile 对 Pan、Pinch、
Cross 三个 10 万根场景统一要求：CPU P95/P99 ≤ 8.33ms，Frame Overrun P95/P99 ≤ 0ms。任何场景
缺失也会失败，避免测试崩溃或被过滤后误报通过。

本地检查 Macrobenchmark 结果：

```bash
python3 tools/check_macrobenchmark_thresholds.py \
  --results benchmark/build/outputs/androidTest-results/connected/benchmark \
  --profile strict-120hz
```

`.github/workflows/performance-120hz.yml` 使用三类专用 120Hz 自托管真机 runner：高端高通、主流高通、
主流联发科。给 PR 添加 `performance-120hz` 标签或手动触发 workflow 后，CI 会锁定 120Hz、执行正式
8 次迭代、应用上述硬门槛并保留测试结果与 Perfetto trace。普通云模拟器不参与性能放行。

最终业务 App 仍应生成包含启动、行情订阅和真实插件的应用级 Baseline Profile，并补充温控降频复测；
这属于集成验收，不能由库的示例 App 代替。

## 性能测试与 Baseline Profile

仓库包含两层性能验证：

- `kanvas-core` 的 `KlinePerformanceSmokeTest` 覆盖 10 万根数据、批量指标和实时末根更新，防止算法复杂度回退。
- `benchmark` 在真实 Android 设备上测量 1 万根冷启动，以及 10 万根数据下的平移、缩放、长按 Cross、Java Heap、RSS 和 GPU 内存峰值。
- `kanvas-compose` 与 `kanvas-drawing` 发布的 AAR 携带平移、缩放和不间断 Cross 拖动生成的 Baseline Profile；Core 是 JVM 模块，其热规则随 Android AAR 一起发布。

连接 API 24+ 的设备后运行完整基准：

```bash
./gradlew :benchmark:connectedBenchmarkAndroidTest
```

开发时可通过 instrumentation 的 `class` 参数只运行
`panOnlyWithHundredThousandCandles`、`pinchOnlyWithHundredThousandCandles` 或
`crossOnlyWithHundredThousandCandles`，正式留档使用代码中的 8 次迭代。

只生成 Baseline Profile：

```bash
./gradlew :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.zhumeng.kanvas.benchmark.KanvasBaselineProfileGenerator
```

库的 Profile 只保留 `com.zhumeng.kanvas` 热规则并排除示例 App；发布前还应执行：

```bash
./gradlew test lintRelease assembleRelease
```

## 自定义主图

`KlineMainChartRenderer` 可以替换内置 Bar/Line 绘制，适合 Heikin-Ashi、Renko 或业务自定义价格图：

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

自定义绘制在 UI Draw 路径执行，必须快速、确定且不阻塞。只有输出完全由不可变 context 和 renderer 字段决定时，才使用 `ImmutableInputs`；需要观察外部可变绘制状态时保留默认的 `Never`。

自定义指标渲染器同样可以设置 `KlineIndicatorCachePolicy.ImmutableInputs`，使其普通主图绘制进入独立缓存
通道。保留默认 `Never` 时只执行该 renderer 的动态通道，不会使内置 candle/计算线缓存失效。为了保持
RenderNode 原子顺序，自定义指标仍先按 `zIndex` 分到 candle 下方或上方；同侧动态内容作为该侧最后的
实时 overlay。该策略只覆盖 `draw`；Overlay、Cross、Top Tips 与点击回调仍保持实时执行。

## 热路径约束

- 数据源应在后台完成排序、去重和页重叠处理，再交给 `KlineSeries`。
- 大历史页优先调用 `completeLoadMorePrepared`，它会在后台准备指标，并以快照身份做 O(1) 原子提交。
- 指标配置必须具有结构相等语义；集合字段应在构造时复制，避免外部修改破坏缓存键。
- 自定义指标、主图 renderer、Tooltip provider 和绘图工具都运行在交互/绘制相关路径，不应执行 I/O 或长任务。
