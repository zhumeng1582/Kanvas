# 指标选择与参数设置

示例 App 提供三层指标设置交互：

1. 图表页底部弹窗选择主图、副图指标；主图和副图指标均可多选。
2. “指标参数设置”进入指标列表，展示名称、中文说明和支持状态。
3. 点击已支持指标进入参数编辑，可修改周期、单线颜色和线宽，并支持重置与校验。

当前可用指标为 MA、EMA、BOLL、SAR、AVL、SUPER、VOL、MACD、RSI、KDJ、OBV、WR 和
StochRSI。VOL 使用图表涨跌色，因此不提供独立线参数。

Example 参数编辑器的初始预设如下。这些预设不等同于插件构造函数的默认配置：例如
`KlineRsiIndicatorPlugin().bind()` 默认只计算周期 `6`，而 OBV 插件默认不启用 MA/EMA
覆盖线。

| 指标 | Example 编辑器预设 | 计算/显示说明 |
| --- | --- | --- |
| SAR | 步长 `0.02`，最大加速因子 `0.2` | 主图以转向点显示 |
| AVL | 无周期参数 | 累计成交额 / 累计成交量；缺少成交额时使用 `close * volume` |
| SUPER | ATR 周期 `10`，倍数 `3` | Wilder ATR；上涨/下跌线和背景可独立设置 |
| RSI | RSI1 `6` 默认开启；RSI2 `14`、RSI3 `24` 默认关闭；上轨 `70`、下轨 `30` | Wilder RSI；各曲线和参考线可独立显隐 |
| OBV | MA/EMA 输入预填周期 `7`，默认关闭 | 累计成交量，可选真实 MA/EMA 覆盖线 |
| WR | 周期 `14` | 输出范围 `-100..0` |
| StochRSI | RSI `14`，随机周期 `14`，K `3`，D `3` | 输出 K、D 两条线 |

多数计算器支持仅覆盖最新一根 K 线的增量更新；StochRSI 为保证 Wilder RSI 与滚动窗口一致，
当前采用全量重算。实时价格更新时推荐使用 `KlineIndicatorRefreshPolicy.OnCandleBoundary`，
只更新蜡烛和价格；新 K 线形成时再提交指标结果，避免指标线随 300ms 行情抖动。

## 配置更新

线样式使用 `KlineIndicatorLineStyle`：

```kotlin
val config = KlineMovingAverageIndicatorConfig(
    periods = listOf(7, 25, 99),
    lineStyles = listOf(
        KlineIndicatorLineStyle(Color(0xFFFFC21A), widthPx = 1.25f),
        KlineIndicatorLineStyle(Color(0xFFE83CB5), widthPx = 2f),
        KlineIndicatorLineStyle(Color(0xFF8B62C9), widthPx = 3f),
    ),
)
indicatorRegistry.upsert(maPlugin.bind(config).definition)
```

`KlineIndicatorLineStyle.visible` 控制单条输出；关闭的曲线不会绘制、不会进入 Top Tips，
也不会影响该窗格的纵轴范围。`upsert` 保持指标身份不变，参数或线样式改变后会触发对应
定义更新。整个指标的显示状态通过 `indicatorRegistry.show(key)` 和 `hide(key)` 控制。

示例界面实现位于 `example/.../IndicatorSettingsSheet.kt`，业务 App 可以替换视觉层，继续复用
`IndicatorRegistry` 和类型安全的插件配置。
