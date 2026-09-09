# Kanvas 性能验证与扩展指南

## 120Hz 验收口径

120Hz 的刷新周期为 `1000 / 120 = 8.33ms`。屏幕处于 120Hz、应用请求
120fps，或平均帧率接近 120，都不等价于交互全程流畅。本仓库采用以下保守验收门槛：

- 固定 Release/Benchmark 构建、设备、系统版本、刷新率、温度和数据规模；
- 连续交互窗口的 `frameDurationCpuMs` P95/P99 不超过 8.33ms；
- `frameOverrunMs` P95/P99 不大于 0，且 Perfetto 中没有稳定的隔帧提交；
- 没有周期性 GC、位图上传或 GPU/RSS 峰值增长；
- 在目标设备矩阵上重复验证，而不是只接受单机单轮结果。

其中 CPU P95/P99 ≤8.33ms 是本仓库额外要求的处理余量，**不是判断实际 FPS 的充分或必要条件**。
`frameDurationCpuMs` 跨越 UI 与 RenderThread，系统通过流水线和 VSync 偏移调度；这台手机的
Expected FrameTimeline 渲染预算中位数为 13.667ms。实际掉帧/延迟应结合 `frameOverrunMs`、
FrameTimeline 的呈现状态与 SurfaceFlinger 呈现间隔判断。此前将 8.33ms 直接称作该 CPU 指标的
系统预算不够准确，这里明确修正；CI 数值门槛保持不变。参见
[FrameTimingMetric](https://developer.android.com/reference/androidx/benchmark/macro/FrameTimingMetric)、
[Android VSync 流水线](https://source.android.com/docs/core/graphics/implement-vsync?hl=en)与
[Perfetto FrameTimeline](https://perfetto.dev/docs/data-sources/frametimeline)。

`KlineChartRenderConfig.preferredFrameRate` 默认为 `120f`，用于向系统提交刷新率偏好；设为
`0f` 可清除偏好。它只是平台提示，系统仍可根据屏幕能力、电量和温控改变实际刷新率，不能替代
Macrobenchmark/Perfetto 验收。参见 Android 官方的
[Compose preferredFrameRate](https://developer.android.com/reference/kotlin/androidx/compose/ui/preferredFrameRate.modifier)
与[自适应刷新率说明](https://developer.android.com/develop/ui/views/animations/adaptive-refresh-rate)。

## 2026-09-08 第三阶段真机复测（当前候选版本）

**缩放 GPU 内存明显下降，平移/缩放大部分呈现间隔接近 120Hz；整体严格门槛仍为 FAIL。**
Cross 仍有实际截止时间超限，不能宣布全场景稳定 120fps。最终候选版本在同一台
Xiaomi 23127PN0CC / Android 15 上完成 Cross、Pan、Pinch 各 8 轮，保持 10 万根 K 线、
MA7/25 + Volume、300ms 行情更新、3 秒稳定时间及 `CompilationMode.Partial()`。
三个场景开始时实际刷新率均为 120Hz；充电中，边界电量 84–87%、电池温度 32.4–32.8°C。
未锁定 CPU 频率或屏蔽基准错误，基准报告的温控等待时间均为 0。

本轮保留的改动：

- 仅在双指缩放时，将内置主图计算线每条 Path 的分段上限从 8 改为 64，减少变化中的小路径缓存压力。
  手势结束或取消后恢复 8；历史图层缓存键包含该模式，防止松手后沿用缩放路径。
  自定义 renderer、ALONE 和已有预构建线沿用原通道。
- 价格格式化增加复用缓冲区的定点快速通道，临界舍入和大数仍交给原来的 `DecimalFormat`。
  JVM 与 Android 对照测试覆盖多精度、负零、舍入临界值和不同数量级。
- 等半径文字背景改用 `drawRoundRect`，非对称圆角仍使用 Path；像素对照测试覆盖透明填充、边框、
  椭圆角和超大圆角。重新生成适配当前代码的库 Baseline Profile。

最终 APK SHA-256 为 `397ff16e8df6a4bea53f368e13bc6742bdcb06ff850c1014613db48a86bb2753`，
大小 1,523,465 字节，内含 4,802 字节的编译后 `baseline.prof`。库原始 Profile 共 4,859 条规则、
681,294 字节，SHA-256 为 `11e29d0ae11a3443b923970a25fa343910145eada2f57956c01f1fd22e5ce3a0`。
公共 API 检查通过，无新增公共 API 或迁移要求。

原始 Macrobenchmark JSON（ms；门槛仍读取完整原始结果，未改变验收口径）：

| 场景 | CPU P95 | CPU P99 | Overrun P95 | Overrun P99 | 严格门槛 |
| --- | ---: | ---: | ---: | ---: | --- |
| 平移 | 17.101 | 21.052 | 7.483 | 10.894 | FAIL |
| 双指缩放 | 17.326 | 21.160 | 6.642 | 10.291 | FAIL |
| 十字线 | 21.833 | 24.935 | 11.200 | 15.359 | FAIL |

24 轮共 19,472 个未过滤帧样本的 CPU/Overrun 数值逐帧与原始 JSON 对齐。按第一阶段解释的
相同匹配规则，仅保留 `measureBlock` 内实际交互帧，共 15,648 帧：

| 实际交互窗口 | 有效绘制帧 | CPU P95 | CPU P99 | Overrun P95 | Overrun P99 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 平移 | 7924 | 8.665 | 15.114 | -2.715 | 10.188 |
| 双指缩放 | 7525 | 10.289 | 11.490 | -1.519 | -0.243 |
| 十字线 | 199 | 22.886 | 26.728 | 12.438 | 16.888 |

缩放的交互 Overrun P99 从第二阶段的 +0.243ms 降至 -0.243ms，但 CPU 余量门槛仍失败。
平移 P95 接近第二阶段，P99 则从 11.356ms 升至 15.114ms，不能称作全面改善；Cross 也未改善。
本轮与第二阶段不是随机交叉 A/B，电量、温度、Profile 与运行顺序不同，小幅差值不应归因于单项改动。

| 场景 | 第二阶段 GPU 峰值中位数 | 本轮 GPU 峰值中位数 | 本轮 GPU 最大峰值 | 本轮 Heap 峰值中位数 |
| --- | ---: | ---: | ---: | ---: |
| 平移 | 126.5MiB | 122.1MiB | 173.1MiB | 86.5MiB |
| 双指缩放 | 495.6MiB | 63.3MiB | 64.0MiB | 87.2MiB |
| 十字线 | 78.1MiB | 78.4MiB | 78.5MiB | 65.6MiB |

这里的中位数是 8 轮各自最大采样值的中位数，不是平均常驻内存。缩放下降约 **87.2%**，
但路径纹理上传仍存在；降低峰值不等于解决所有 GPU 开销，也不构成内存泄漏或页面退出释放的验证。

另将交互窗口的应用 Surface 帧按 `display_frame_token` 关联 SurfaceFlinger 实际呈现：

| 场景 | Surface 帧 | Late Present | 呈现间隔 P50 / P95 / P99 |
| --- | ---: | ---: | --- |
| 平移 | 7954 | 319（4.01%） | 8.302 / 8.334 / 8.576ms |
| 双指缩放 | 7577 | 797（10.52%） | 8.302 / 8.334 / 33.331ms |
| 十字线 | 273 | 208（76.19%） | 吸附后按需出帧，不换算 FPS |

这些间隔包含脚本手势之间的停顿，尤其缩放 P99 不能全部解释为连续交互掉帧。
`Late Present` 包括 Buffer Stuffing 导致的延迟，**不能直接称为掉帧率**；平移与缩放已呈现大量
约 8.3ms 间隔，但这不证明所有帧都及时。FrameTimeline 中 Cross/Pan/Pinch 分别有 73/4/32 个
应用帧缺少结束时刻，因此其呈现状态统计与上面的完整时长匹配样本不同。所有 Surface 帧均关联到
SurfaceFlinger 呈现事件。底层驱动仍有负时间戳导入警告，局部切片只用于定位；原始帧指标已独立校验。

最终候选的第 2 轮 Cross 中，`Kanvas.interaction.draw` 平均 3.490ms、最大 7.415ms；
1200×1882 背景图层仍栅格化 13 次，平均 2.285ms；159×261 纹理上传 16 次。
这些是继续优化提示绘制和行情触发背景重绘的依据，局部切片不能相加代替整帧时长。
Pinch 第 2 轮确认使用 `Kanvas.indicator.pinch.paths`，路径准备平均 0.029ms，但 RenderThread
`flush commands` 平均仍为 2.728ms，并有持续大纹理上传。
24 轮模式检查确认该 trace 在每轮 Pinch 交互窗口出现，在全部 Cross/Pan 窗口均为 0 次。

本轮还比较过全场景 64 段路径、描边转填充轮廓、仅历史图层离屏缓存。
全场景 64 段版本另完成 24 轮：平移 GPU 峰值中位数升至 284.8MiB、交互 CPU P95 为 10.727ms，
因此撤回全局应用，仅保留缩放模式；其余两个方案也未保留，不能把试验版本的最好值写成最终结果。

验证：`test apiCheck lintRelease` 与 Benchmark 构建通过，JVM 共 424 次测试执行、0 失败；
模拟器 Android 回归 17 项通过，包含缩放期间 MA 可见、松手后像素位置恢复、随后 Cross 正常显示，
以及格式化和背景像素对照；Python 门槛测试 6 项通过。功能回归来自模拟器，性能结果来自真机。
严格性能检查退出码为 1。手机最低刷新率恢复为原来的未设置状态，最高刷新率保留原值 120。
最终 APK、24 轮 trace/JSON 与分析脚本保存在本机 `/private/tmp/kanvas-120hz-audit/`，
最终版本前缀为 `adaptive_profiled`，严格检查输入目录为 `adaptive-final-results`；未纳入版本控制。
本轮试验、正式复测及 Profile 共 83 个手机端文件已与本地备份核对 SHA-256，再清除手机上的测试副本。
完整设备矩阵和实际业务插件仍需验收。

## 2026-09-08 第二阶段真机复测（历史候选版本）

**结论：仍未通过严格 120Hz；这是待继续优化的候选版本，不能按性能达标发布。**
同一台 Xiaomi 23127PN0CC 上，Pan、Pinch、Cross 各完成 8 轮；10 万根 K 线、
MA7/25 + Volume、300ms 行情更新、3 秒稳定时间、`CompilationMode.Partial()` 和手势脚本均保留。
场景开始时实际刷新率为 120Hz；充电中，边界采样电量 40–43%、电池温度 33.4–34.2°C。
没有屏蔽基准错误或锁定 CPU 频率。

本轮增加了以下改动：

- 修复主图发布绘制几何时对 Snapshot 计数器的自订阅，避免一次行情更新安排额外主图绘制；
  交互层显式观察几何发布信号，保证新行情到达后提示框继续显示。
- 默认内置绘制在 Cross 激活时缓存背景的离屏栅格，Pan/Pinch 仍回放矢量指令。
  自定义主图、非内置指标、已有绘图覆盖物和 Tips 预布局模式不走此通道。
  此改动有显存和首次栅格化成本，不能视为免费优化。
- 示例 Release/Benchmark 开启 R8 与资源压缩，并从真机重新生成库 Baseline Profile。
  单独的 `baselineProfile` 构建关闭 R8 用于生成原始签名；性能测试只使用优化后的 `benchmark`。
  参见 [Android R8 配置](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization)。
- 指标提示文字复用线程本地 Formatter，保持原有舍入和负零语义；增加更细的提示绘制 trace。

最终 APK SHA-256 为 `bec5fe80ebc4f6661366f0aa50e7deab9350d9e9085d50b976e77bb867e650e9`，
大小 1,523,465 字节，内含 4,806 字节的编译后 `baseline.prof`。这轮同时改变了构建优化与 Profile，
不能把与第一阶段的差值单独归因于某项绘制修复，也不是随机交叉 A/B 实验。

原始 Macrobenchmark JSON（ms）：

| 场景 | CPU P95 | CPU P99 | Overrun P95 | Overrun P99 | 严格门槛 |
| --- | ---: | ---: | ---: | ---: | --- |
| 平移 | 16.659 | 21.282 | 6.879 | 10.879 | FAIL |
| 双指缩放 | 17.622 | 21.586 | 7.003 | 11.004 | FAIL |
| 十字线 | 21.645 | 24.568 | 11.033 | 14.686 | FAIL |

按下一节解释的相同 FrameTimeline 匹配规则，24 轮共 19,348 个未过滤样本的 CPU/Overrun
数值全部与原始 JSON 对齐。仅保留 `measureBlock` 内的实际交互帧后：

| 实际交互窗口 | 有效绘制帧 | CPU P95 | CPU P99 | Overrun P95 | Overrun P99 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 平移 | 7795 | 8.719 | 11.356 | -2.185 | 2.660 |
| 双指缩放 | 7442 | 10.516 | 11.709 | -1.135 | 0.243 |
| 十字线 | 188 | 22.399 | 25.681 | 11.629 | 15.645 |

Cross 的交互 CPU P95/P99 从第一阶段的 23.982/29.877ms 降至 22.399/25.681ms；
Pan 的 P95 略降，P99 反而上升。此前单轮诊断中的 Cross P95 15.820ms 没有在正式汇总中复现。
因此不能用单轮最好值宣称达标；CLI 已拒绝少于 8 轮的结果。

剩余问题及代价：

- Cross 第 2 轮 `Kanvas.interaction.draw` 平均 3.642ms、最大 8.580ms；RenderThread
  `Drawing` 平均 6.421ms、最大 15.846ms。背景仍会随行情更新重新栅格化，需要进一步拆开
  静态历史、实时价格和提示文字的更新。第 1 轮还出现约 52ms 的 RenderThread 帧及 shader 编译；
  局部切片与已匹配帧指标的样本集合不同，不能据汇总 P99 忽略首次使用长帧。
- 每轮最大 GPU 内存的中位数，Cross/Pan/Pinch 为 **78.1/126.5/495.6MiB**，第一阶段为
  59.9/131.8/245.0MiB。尤其缩放的 GPU 峰值明显变差；需要核查缓存淘汰和退出页面后的释放。
  多项改动同时存在，尚不能定位单一原因，也不能仅凭该峰值断言内存泄漏。
- 原始 JSON 与交互窗口口径仍都失败；这只能说明当前实现未达标，不能证明 Compose 或该设备
  存在无法达到 120Hz 的硬性上限。其他设备和实际业务插件仍需单独验收。

验证：全量 `test`、`apiCheck`、`lintRelease`、Benchmark 构建通过；Compose 模拟器 UI 14 项通过，
涵盖绘制隔离、单次行情更新和提示框可见性；Python 门槛测试 6 项通过。历史分页测试曾暴露首次
市场/指标初始化与断言的竞争，现先等待初始化对齐再验证分页，未修改该路径的生产逻辑。
性能门槛退出码为 1。手机的 `min_refresh_rate` 已恢复为原来的未设置状态，`peak_refresh_rate`
保留原值 120。APK、24 轮 trace/JSON 和逐帧校验脚本存于本机
`/private/tmp/kanvas-120hz-audit/`（本轮前缀 `r8_profiled`），未纳入版本控制。

## 2026-09-08 第一阶段真机复测（R8 关闭）

**结论：修复后仍未达到严格 120Hz，不能据此放行。** 本次以 `fa1920f` 为修改前版本，
比较第一阶段的渲染优化；设备为 Xiaomi 23127PN0CC（Android 15，OS2.0.215.0.VNCCNXM），
1200×2670，SurfaceFlinger 实测 120Hz。使用非 debuggable 的 Benchmark 构建，未开启 R8 压缩，
`CompilationMode.Partial()`；10 万根 K 线、MA7/25 + Volume、300ms 行情更新、加载后稳定 3 秒。
修改前 Cross 8 次；修改后 Pan、Pinch、Cross 各 8 次。没有屏蔽低电量或其他基准错误；
修改后测试处于充电状态，电池温度在场景边界采样为 31.2–33.9°C，未锁定 CPU 频率。

原始 Macrobenchmark JSON 的修改后结果（单位均为 ms）：

| 场景 | CPU P95 | CPU P99 | Overrun P95 | Overrun P99 | 严格门槛 |
| --- | ---: | ---: | ---: | ---: | --- |
| 平移 | 16.489 | 22.690 | 6.710 | 12.587 | FAIL |
| 双指缩放 | 17.590 | 22.959 | 7.718 | 13.216 | FAIL |
| 十字线 | 23.666 | 25.889 | 14.381 | 17.449 | FAIL |

这台设备的 trace 包含 `measureBlock` 之前约 30 秒的行情刷新帧，而 AndroidX Benchmark 1.4.1
的 `FrameTimingQuery` 会统计整段 trace。因此另按其相同的 UI/RenderThread/FrameTimeline
匹配规则提取帧，再限制 UI 帧起点位于 `measureBlock` 内。过滤前逐帧 CPU/Overrun 数值与每轮
原始 JSON 一致；过滤后汇总 8 轮，避免把准备期间的帧混称为连续交互帧：

| 实际交互窗口 | 有效绘制帧 | CPU P95 | CPU P99 | Overrun P95 | Overrun P99 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 平移 | 7791 | 9.028 | 10.034 | -0.512 | 2.453 |
| 双指缩放 | 7429 | 11.036 | 12.192 | -0.598 | 0.657 |
| 十字线 | 202 | 23.982 | 29.877 | 16.087 | 19.865 |

两种口径都失败。十字线吸附到同一根 K 线时不需要持续出帧，因此这里的帧数不能直接换算 FPS。
修改前 Cross 原始 CPU P95/P99 为 24.549/26.780ms，交互窗口为 26.433/34.064ms；
修改后有部分改善，但 CPU 余量与实际 Deadline 仍未达标，不能称为稳定 120fps。上述并非随机交叉 A/B 实验，
电量、温度和运行顺序不同，也不应把小幅变化都归因于代码。

本次已修复的具体问题：

- 主图历史指标缓存比较仍包含水平偏移，导致本可通过 translation 复用的历史层失效。
  现在仅在历史主图通道允许忽略 X 偏移，缩放、Y 范围、样式、输出和数据一致性检查仍然保留。
- 内置主图 MA 等计算线在历史/最新段拆分之前生成了完整 Path，随后又丢弃重建。
  现在跳过这些预构建工作，自定义主图、ALONE 和副图路径保持原有行为。
- 主图与交互 Canvas 缺少独立绘制记录边界。现在使用独立 `graphicsLayer`，减少 Cross 引起的主图重绘。
  绘制阶段和图层的关系参见 [Compose phases](https://developer.android.com/develop/ui/compose/phases?hl=en)。
- CI 原来解析一位小数的展示文本，可能将 8.34ms 错判为 8.3ms。现改为原始 JSON 严格验收；
  刷新率检查也只匹配当前 `mActiveRenderFrameRate`，不再把 `supportedModes` 列出 120Hz 当作实际已启用。

仍需优化的重点：

- **Cross 的 UI 绘制和 RenderThread 回放。** 修改后第 2 轮交互窗口中，`Kanvas.interaction.draw`
  平均 4.825ms、最大 9.418ms；RenderThread `Drawing` 平均 7.026ms、最大 15.728ms，且仍有
  重复纹理上传。减少指标 Path 工作尚不足以解决这部分成本，需继续拆分提示内容、行情更新和图层回放。
- **缩放需要持续重录。** 第 2 轮窗口中约 942 次绘制对应 887 次历史主图/时间轴重录；
  平移同轮约 982 次绘制只需 140 次历史主图重录。水平缓存不能覆盖缩放与 Y 范围变化，需继续减少
  坐标轴、价格标记和图形提交开销。这里是单轮定位证据，不是所有设备的平均值。
- **GPU 内存余量。** 8 轮最大 GPU 内存的中位数，Cross/Pan/Pinch 约为 59.9/131.8/245.0MiB。
  应同时约束图层与路径缓存峰值；不能仅靠增加缓存换帧耗。该采样不构成内存泄漏判断。

部分底层事件存在 Perfetto 时间戳导入警告；局部切片用于定位，帧统计已逐帧与原始 JSON 校验。

验证：`./gradlew test apiCheck lintRelease :example:assembleBenchmark` 通过；Compose 模拟器 UI
回归 12 项通过（含 Cross 绘制隔离、自定义 renderer 状态更新）；Python 门槛测试 5 项通过。
真机 UI 回归受小米后台 Activity 启动限制，功能回归改在模拟器执行；性能测量全部来自真机。
严格性能检查退出码为 1（预期的 FAIL）。本次原始 JSON、trace、APK 和窗口分析脚本保存在本机
`/private/tmp/kanvas-120hz-audit/`，未纳入版本控制。刷新率测试设置已恢复。

这些结果只覆盖一台高端机和默认指标组合；完整设备矩阵及实际业务插件仍待验收。

## 2026-08-21 历史基线

以下是 2026-08-21 的验证样本，不是跨设备承诺：Xiaomi 23127PN0CC、Android 15、
1200×2670，SurfaceFlinger 确认 120Hz；10 万根 K 线；Benchmark/Release 目标；
`CompilationMode.Partial()`；页面加载完成后稳定 3 秒；每个场景 3 次独立迭代。示例同时保留
300ms 一次的最新 K 线更新，以覆盖真实行情压力。

| 场景 | CPU P50 | CPU P90 | CPU P95 | CPU P99 | Overrun P50/P90/P95/P99 | 帧数中位数 | GPU/Heap 峰值中位数 |
| --- | ---: | ---: | ---: | ---: | --- | ---: | ---: |
| 平移 | 8.31ms | 15.80ms | 16.81ms | 19.32ms | -2.83 / 7.86 / 10.70 / 10.92ms | 1006 | 70.7 / 57.4MiB |
| 双指缩放 | 9.11ms | 11.82ms | 16.24ms | 18.89ms | -2.47 / 0.23 / 4.97 / 7.54ms | 986 | 95.3 / 67.9MiB |
| 长按 Cross 连续拖动 | 15.10ms | 18.90ms | 19.70ms | 21.50ms | 3.80 / 7.50 / 8.40 / 11.20ms | 163 | 65.4 / 50.4MiB |

结论：该历史版本在 10 万根数据下可以完成平移/缩放，但**未通过仓库严格门槛**。
CPU 时长不能直接取倒数换算 FPS；此前将 Cross 描述为 50～66fps 缺少呈现时序依据，予以撤回。
正值 Overrun 表明存在超过系统截止时间的帧，应结合 FrameTimeline 检查，不能把 P50 或屏幕
刷新率写成“稳定 120fps”。最新候选版本以本文最近一轮结果为准。

## 120Hz 专用渲染栈

已落地的热路径优化包括：可视区/指标结果缓存、蜡烛与成交量原生 Canvas 批绘、预构建 Path、
平移优先复用 GraphicsLayer 并使用水平 overscan、网格/坐标轴/时间轴拆层、原生简单文本批绘、
VSync 合并视口提交、可复用的价格格式器，以及覆盖平移/缩放/Cross 的 AAR Baseline Profile。

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
- 横向拖动和惯性滚动仍按当前可视窗口逐帧计算 Y 范围；手势或惯性期间，仅当 viewport 未变化
  且确认只是替换最新一根 K 线时，才隔离 300ms 实时 tick。加载更多、新周期和整批快照会立即
  更新 Y 范围；
- 主图指标分成内置 latest-aware 快速通道、`ImmutableInputs` 自定义缓存通道和 `Never` 动态通道。
  单个动态 renderer 不再使 candle 和所有内置指标退回逐帧绘制。

旧表是改造前的可追溯基线，不能代表新渲染栈已经通过 120Hz。只有下面的严格门槛在完整目标设备
矩阵上通过后，才能把状态改为“严格满足”。

同一台设备对拆层后版本执行正式 8 次 Cross 专项复测（90 个 MOVE、每次间隔 8ms）得到：CPU
P50/P90/P95/P99 为 `17.7/24.4/25.7/28.1ms`，Overrun P50/P90/P95/P99 为
`6.7/13.2/14.7/17.5ms`；帧数中位数 87，GPU/Java Heap 峰值中位数约
`59.6/64.3MiB`。因此该次历史复测仍是**严格门槛失败**。已完成的拆层解决了实时数据对历史
display list 的错误失效，也降低了 Tooltip 的 UI 线程工作，但尚未把整场景 RenderThread 回放和行情
更新长尾压入 8.33ms。

## P95/P99 与 Overrun CI 门槛

设备矩阵和阈值位于 `benchmark/thresholds/120hz-device-matrix.json`。120Hz profile 对 Pan、Pinch、
Cross 三个 10 万根场景统一要求：CPU P95/P99 ≤ 8.33ms，Frame Overrun P95/P99 ≤ 0ms。任何场景
缺失或少于 8 次迭代也会失败，避免测试崩溃、单轮诊断或被过滤后误报通过。

本地检查 Macrobenchmark 结果：

```bash
python3 tools/check_macrobenchmark_thresholds.py \
  --results benchmark/build/outputs/connected_android_test_additional_output/benchmark \
  --profile strict-120hz
```

验收优先读取 `*-benchmarkData.json` 中未舍入的 `sampledMetrics`。Studio/XML 展示摘要通常
只有一位小数，会把 `8.34ms` 显示为 `8.3ms`，不适合严格的 `8.33ms` 边界判断。保留旧文本
解析函数用于诊断，但命令行严格验收只接受原始 JSON，也不会用旧文本补齐缺失场景。

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
`kanvas.benchmark.iterations` 参数仅用于缩短本地诊断；低于 8 次的结果不能用于严格验收。

只生成 Baseline Profile：

```bash
./gradlew :benchmark:connectedBaselineProfileAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.zhumeng.kanvas.benchmark.KanvasBaselineProfileGenerator
```

`benchmark` 使用开启 R8 和资源压缩的 Release 配置。`baselineProfile` 构建关闭 R8，
用于保留库的原始方法签名；不要把该构建的帧耗作为优化后 Release 的验收结果。
参见 [Android Baseline Profile 生成要求](https://developer.android.com/topic/performance/baselineprofiles/overview)。
生成结果位于 `benchmark/build/outputs/connected_android_test_additional_output/baselineProfile/`。
库的 Profile 只保留 `com.zhumeng.kanvas` 热规则并排除示例 App；将生成的
`KanvasBaselineProfileGenerator_generate-baseline-prof.txt` 更新到
`kanvas-compose/src/main/baseline-prof.txt` 后重新构建，才能让发布包包含新规则。
发布前还应执行：

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
- 大历史页优先调用 `completeLoadMorePrepared`；准备成功时会在后台完成指标计算，
  并以快照身份做 O(1) 原子提交。有限重试仍无法对齐实时数据时，该方法返回
  `IgnoredStalePreparedData` 且不发布历史页。
- 指标配置必须具有结构相等语义；集合字段应在构造时复制，避免外部修改破坏缓存键。
- 自定义指标、主图 renderer、Tooltip provider 和绘图工具都运行在交互/绘制相关路径，不应执行 I/O 或长任务。
