# K 线绘图竞品调研与 Kanvas 方案

调研时间：2026-07-27。调研范围包括 TradingView、集成 TradingView 图表的交易所产品，以及
开源 KLineChart。目标不是一次性复制全部高级工具，而是确认移动端最重要的绘图闭环。

## 竞品共性

| 能力 | TradingView | 交易所移动端常见实现 | KLineChart | Kanvas 本次方案 |
| --- | --- | --- | --- | --- |
| 工具入口 | 左侧分组工具栏，可收藏常用工具 | 图表顶部入口，展开浮层或底部面板 | 由宿主实现，Overlay API 提供能力 | 顶部“绘图”入口 + 图内分组浮层 |
| 常用工具 | 趋势线、射线、直线、水平/垂直线、形状、斐波那契 | 优先展示趋势线、水平线、矩形、斐波那契 | 内置 16 类 Overlay | 首批内置 7 类高频工具 |
| 绘制辅助 | 磁吸、连续绘制、锁定、隐藏 | 移动端通常保留磁吸与连续绘制 | normal / weak / strong magnet | 普通 / 弱 / 强磁吸，连续绘制 |
| 选中态 | 图形附近或顶部出现上下文工具栏 | 紧凑浮动工具条 | 控制点和轴标签由 Overlay 定义 | 可拖动上下文工具条 |
| 编辑操作 | 颜色、线宽、线型、锁定、层级、复制、删除 | 保留颜色、线宽、锁定、删除 | override / lock / remove | 颜色、线宽、实虚线、锁定、置顶、删除 |
| 容错 | 撤销/重做、对象树管理 | 撤销/重做和清空 | 宿主自行实现 | Controller 原生撤销/重做 |

TradingView 的绘图 API 将“创建图形”和“选择后浮动工具栏”作为独立能力，并支持锁定、隐藏、
保存和恢复图形。KLineChart 的 Overlay 文档则明确提供分步绘制、弱/强磁吸、控制点、轴标签、
锁定和拖动生命周期；其内置工具覆盖水平/垂直线、射线、线段、直线、价格通道、平行线、
斐波那契、画笔和标注。这两套设计都表明：稳定的数据坐标、状态机和上下文编辑能力比堆叠
大量图形更重要。

参考资料：

- [TradingView Advanced Charts：Drawings API](https://www.tradingview.com/charting-library-docs/latest/ui_elements/drawings/drawings-api/)
- [TradingView Advanced Charts：Drawing toolbar](https://www.tradingview.com/charting-library-docs/latest/ui_elements/drawings/drawings-toolbar/)
- [KLineChart：Overlay](https://klinecharts.com/en-US/guide/overlay.html)
- [KLineChart 内置 Overlay 源码](https://github.com/klinecharts/KLineChart/tree/main/src/extension/overlay)

## 本次实现边界

本次优先完成移动端可用闭环：线段、射线、无限直线、水平线、垂直线、矩形和斐波那契回撤；
工具分组选择；绘制预览和控制点；弱/强磁吸；连续绘制；选中后的样式、锁定、层级和删除；
撤销/重做；时间戳/数值持久化坐标。

后续迭代建议按使用频率增加：平行通道、画笔、文本/价格标注、复制、对象列表、单图隐藏，
最后再考虑形态识别和交易测量类工具。这样可以保持 DrawingTool SPI 稳定，避免 UI 和几何能力
相互耦合。
