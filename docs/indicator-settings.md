# Indicator selection and settings

The example app has a three-level indicator workflow: a bottom-sheet picker,
a settings list, and a per-indicator editor. Main and sub indicators both
support multiple selections.

MA, EMA, BOLL, SAR, AVL, SUPER, VOL, MACD, RSI, KDJ, OBV, WR, and StochRSI are
functional. Volume follows the chart's bullish/bearish colors.

The following values are initial presets in the example editor, not necessarily
the plugin constructor defaults. For example, `KlineRsiIndicatorPlugin().bind()`
calculates only period `6` by default, while the OBV plugin enables neither MA
nor EMA until configured.

| Indicator | Example editor preset | Notes |
| --- | --- | --- |
| SAR | step `0.02`, maximum `0.2` | Reversal points on the main pane |
| AVL | no period | Cumulative turnover / volume; falls back to `close * volume` |
| SUPER | ATR `10`, multiplier `3` | Wilder ATR; separate trend lines/backgrounds |
| RSI | RSI1 `6` enabled; RSI2 `14` and RSI3 `24` disabled; upper `70`, lower `30` | Wilder RSI with independently visible references |
| OBV | optional MA/EMA inputs prefilled with `7`, disabled by default | Cumulative signed volume with real overlays |
| WR | period `14` | Range `-100..0` |
| StochRSI | RSI `14`, stochastic `14`, K `3`, D `3` | K and D outputs |

Most calculators support incremental replacement of the latest candle.
StochRSI currently performs a full recalculation to keep Wilder RSI and rolling
windows consistent. For 300ms quotes,
`KlineIndicatorRefreshPolicy.OnCandleBoundary` keeps indicator geometry stable
until the next candle begins.

Computed plugins accept optional per-output styles:

```kotlin
KlineMovingAverageIndicatorConfig(
    periods = listOf(7, 25, 99),
    lineStyles = listOf(
        KlineIndicatorLineStyle(Color.Yellow, widthPx = 1.25f),
        KlineIndicatorLineStyle(Color.Magenta, widthPx = 2f),
    ),
)
```

Rebind the typed configuration and call `indicatorRegistry.upsert(definition)`
to apply periods, colors, and widths without replacing the indicator identity.
`KlineIndicatorLineStyle.visible` controls an individual output. Hidden outputs
are excluded from drawing, Top Tips, and pane range calculation. Whole-indicator
visibility continues to use `show(key)` and `hide(key)`.
