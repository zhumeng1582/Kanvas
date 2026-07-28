/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import androidx.compose.ui.graphics.Color
import com.zhumeng.kanvas.core.IndicatorDefinition
import com.zhumeng.kanvas.core.IndicatorKey
import com.zhumeng.kanvas.core.IndicatorLayoutHint
import com.zhumeng.kanvas.core.IndicatorPaintMode
import com.zhumeng.kanvas.core.IndicatorParameters
import com.zhumeng.kanvas.core.IndicatorPlacement
import com.zhumeng.kanvas.core.MovingAverage
import com.zhumeng.kanvas.core.Volume
import com.zhumeng.kanvas.core.ExponentialMovingAverage
import com.zhumeng.kanvas.core.ExponentialMovingAverages
import com.zhumeng.kanvas.core.BollingerBands
import com.zhumeng.kanvas.core.Macd
import com.zhumeng.kanvas.core.Kdj
import com.zhumeng.kanvas.core.Rsi
import com.zhumeng.kanvas.core.RelativeStrengthIndexes
import com.zhumeng.kanvas.core.ParabolicSar
import com.zhumeng.kanvas.core.AverageValueLine
import com.zhumeng.kanvas.core.SuperTrend
import com.zhumeng.kanvas.core.OnBalanceVolume
import com.zhumeng.kanvas.core.OnBalanceVolumeWithAverages
import com.zhumeng.kanvas.core.WilliamsR
import com.zhumeng.kanvas.core.StochasticRsi

/** Optional per-output visual style used by the bundled computed indicators. */
data class KlineIndicatorLineStyle(
    val color: Color? = null,
    val widthPx: Float = 1.25f,
    val visible: Boolean = true,
) {
    init {
        require(widthPx.isFinite() && widthPx > 0f) { "Indicator line width must be finite and positive" }
    }
}

/**
 * Strongly typed settings for the Android example moving-average plugin.
 *
 * The period collection is copied at construction and only exposed through a
 * defensive copy, so a config safely remains an immutable Core definition
 * payload after it is bound to a chart.
 */
class KlineMovingAverageIndicatorConfig(
    periods: Iterable<Int> = listOf(7, 25),
    val placement: IndicatorPlacement = IndicatorPlacement.Main,
    val zIndex: Int = 0,
    val paintMode: IndicatorPaintMode = IndicatorPaintMode.COMBINE,
    val layoutHint: IndicatorLayoutHint = IndicatorLayoutHint(),
    lineStyles: Iterable<KlineIndicatorLineStyle> = emptyList(),
) : KlineIndicatorPluginConfig {
    private val periodStorage = periods.toList()
    private val lineStyleStorage = lineStyles.toList()

    init {
        require(periodStorage.isNotEmpty()) { "Moving-average periods must not be empty" }
        require(periodStorage.all { it > 0 }) { "Moving-average periods must be positive" }
        require(periodStorage.distinct().size == periodStorage.size) {
            "Moving-average periods must be unique"
        }
    }

    val periods: List<Int> get() = periodStorage.toList()
    val lineStyles: List<KlineIndicatorLineStyle> get() = lineStyleStorage.toList()

    override val parameters: IndicatorParameters = IndicatorParameters.of(
        "periods" to periodStorage.joinToString(","),
        "lineWidths" to lineStyleStorage.joinToString(",") { it.widthPx.toString() },
        "placement" to placement.toString(),
        "zIndex" to zIndex.toString(),
        "paintMode" to paintMode.name,
    )

    override fun equals(other: Any?): Boolean =
        other is KlineMovingAverageIndicatorConfig &&
            periodStorage == other.periodStorage &&
            lineStyleStorage == other.lineStyleStorage &&
            placement == other.placement &&
            zIndex == other.zIndex &&
            paintMode == other.paintMode &&
            layoutHint == other.layoutHint

    override fun hashCode(): Int {
        var result = periodStorage.hashCode()
        result = 31 * result + lineStyleStorage.hashCode()
        result = 31 * result + placement.hashCode()
        result = 31 * result + zIndex
        result = 31 * result + paintMode.hashCode()
        result = 31 * result + layoutHint.hashCode()
        return result
    }

    override fun toString(): String =
        "KlineMovingAverageIndicatorConfig(periods=$periodStorage, lineStyles=$lineStyleStorage, placement=$placement, zIndex=$zIndex, " +
            "paintMode=$paintMode, layoutHint=$layoutHint)"
}

/** Kotlin/Compose-native moving-average plugin. */
class KlineMovingAverageIndicatorPlugin(
    id: String = "compose_ma",
    label: String = "MA",
    override val defaultConfig: KlineMovingAverageIndicatorConfig = KlineMovingAverageIndicatorConfig(),
) : KlineIndicatorPlugin<KlineMovingAverageIndicatorConfig> {
    override val key: IndicatorKey = IndicatorKey.computed(id, label)

    override fun createDefinition(config: KlineMovingAverageIndicatorConfig): IndicatorDefinition =
        IndicatorDefinition(
            key = key,
            placement = config.placement,
            zIndex = config.zIndex,
            paintMode = config.paintMode,
            layoutHint = config.layoutHint,
            calculator = MovingAverage(config.periods),
        )

    override fun createRenderer(): KlineIndicatorRenderer = KlineComputedLineIndicatorRenderer
}

/** Strongly typed settings for the Android example volume plugin. */
data class KlineVolumeIndicatorConfig(
    val placement: IndicatorPlacement = IndicatorPlacement.Sub("volume"),
    val zIndex: Int = 0,
    val paintMode: IndicatorPaintMode = IndicatorPaintMode.COMBINE,
    val layoutHint: IndicatorLayoutHint = IndicatorLayoutHint(),
) : KlineIndicatorPluginConfig {
    override val parameters: IndicatorParameters = IndicatorParameters.of(
        "placement" to placement.toString(),
        "zIndex" to zIndex.toString(),
        "paintMode" to paintMode.name,
    )
}

/** Kotlin/Compose-native volume plugin. */
class KlineVolumeIndicatorPlugin(
    id: String = "compose_volume",
    label: String = "Volume",
    override val defaultConfig: KlineVolumeIndicatorConfig = KlineVolumeIndicatorConfig(),
) : KlineIndicatorPlugin<KlineVolumeIndicatorConfig> {
    override val key: IndicatorKey = IndicatorKey.computed(id, label)

    override fun createDefinition(config: KlineVolumeIndicatorConfig): IndicatorDefinition =
        IndicatorDefinition(
            key = key,
            placement = config.placement,
            zIndex = config.zIndex,
            paintMode = config.paintMode,
            layoutHint = config.layoutHint,
            calculator = Volume,
        )

    override fun createRenderer(): KlineIndicatorRenderer = KlineVolumeIndicatorRenderer
}

data class KlineSinglePeriodIndicatorConfig(
    val period: Int,
    val placement: IndicatorPlacement,
    val zIndex: Int = 0,
    val paintMode: IndicatorPaintMode = IndicatorPaintMode.COMBINE,
    val layoutHint: IndicatorLayoutHint = IndicatorLayoutHint(),
    val lineStyles: List<KlineIndicatorLineStyle> = emptyList(),
) : KlineIndicatorPluginConfig {
    init { require(period > 0) }
    override val parameters: IndicatorParameters = IndicatorParameters.of("period" to period.toString())
}

private class KlineComputedPlugin(
    id: String,
    label: String,
    override val defaultConfig: KlineSinglePeriodIndicatorConfig,
    private val calculatorFactory: (Int) -> com.zhumeng.kanvas.core.IndicatorCalculator,
) : KlineIndicatorPlugin<KlineSinglePeriodIndicatorConfig> {
    override val key: IndicatorKey = IndicatorKey.computed(id, label)
    override fun createDefinition(config: KlineSinglePeriodIndicatorConfig): IndicatorDefinition = IndicatorDefinition(key = key, placement = config.placement, zIndex = config.zIndex, paintMode = config.paintMode, layoutHint = config.layoutHint, calculator = calculatorFactory(config.period))
    override fun createRenderer(): KlineIndicatorRenderer = KlineComputedLineIndicatorRenderer
}

class KlineEmaIndicatorPlugin(id: String = "compose_ema", label: String = "EMA", period: Int = 12) : KlineIndicatorPlugin<KlineSinglePeriodIndicatorConfig> by KlineComputedPlugin(id, label, KlineSinglePeriodIndicatorConfig(period, IndicatorPlacement.Main), ::ExponentialMovingAverage)

class KlineEmaTripleIndicatorPlugin(id: String = "compose_ema", label: String = "EMA") : KlineIndicatorPlugin<KlineMovingAverageIndicatorConfig> {
    override val key: IndicatorKey = IndicatorKey.computed(id, label)
    override val defaultConfig = KlineMovingAverageIndicatorConfig(periods = listOf(5, 10, 20), placement = IndicatorPlacement.Main)
    override fun createDefinition(config: KlineMovingAverageIndicatorConfig): IndicatorDefinition = IndicatorDefinition(key = key, placement = config.placement, zIndex = config.zIndex, paintMode = config.paintMode, layoutHint = config.layoutHint, calculator = ExponentialMovingAverages(config.periods))
    override fun createRenderer(): KlineIndicatorRenderer = KlineComputedLineIndicatorRenderer
}

class KlineRsiIndicatorConfig(
    periods: Iterable<Int> = listOf(6),
    val upper: Double = 70.0,
    val lower: Double = 30.0,
    val placement: IndicatorPlacement = IndicatorPlacement.Sub("rsi"),
    val lineStyles: List<KlineIndicatorLineStyle> = emptyList(),
) : KlineIndicatorPluginConfig {
    private val periodStorage = periods.toList()

    init {
        require(periodStorage.isNotEmpty() && periodStorage.all { it > 0 })
        require(periodStorage.distinct().size == periodStorage.size)
        require(lower.isFinite() && upper.isFinite() && lower < upper)
    }

    val periods: List<Int> get() = periodStorage.toList()
    override val parameters: IndicatorParameters = IndicatorParameters.of(
        "periods" to periodStorage.joinToString(","),
        "upper" to upper.toString(),
        "lower" to lower.toString(),
    )
}

class KlineRsiIndicatorPlugin(
    id: String = "compose_rsi",
    label: String = "RSI",
) : KlineIndicatorPlugin<KlineRsiIndicatorConfig> {
    override val key: IndicatorKey = IndicatorKey.computed(id, label)
    override val defaultConfig: KlineRsiIndicatorConfig = KlineRsiIndicatorConfig()
    override fun createDefinition(config: KlineRsiIndicatorConfig): IndicatorDefinition = IndicatorDefinition(
        key = key,
        placement = config.placement,
        calculator = RelativeStrengthIndexes(config.periods),
    )
    override fun createRenderer(): KlineIndicatorRenderer = KlineComputedLineIndicatorRenderer
}

data class KlineTriplePeriodIndicatorConfig(
    val firstPeriod: Int,
    val secondPeriod: Int,
    val thirdPeriod: Int,
    val placement: IndicatorPlacement,
    val zIndex: Int = 0,
    val paintMode: IndicatorPaintMode = IndicatorPaintMode.COMBINE,
    val layoutHint: IndicatorLayoutHint = IndicatorLayoutHint(),
    val lineStyles: List<KlineIndicatorLineStyle> = emptyList(),
) : KlineIndicatorPluginConfig {
    init { require(firstPeriod > 0 && secondPeriod > 0 && thirdPeriod > 0) }
    override val parameters: IndicatorParameters = IndicatorParameters.of("periods" to "$firstPeriod,$secondPeriod,$thirdPeriod")
}

private class KlineTripleComputedPlugin(
    id: String,
    label: String,
    override val defaultConfig: KlineTriplePeriodIndicatorConfig,
    private val calculatorFactory: (Int, Int, Int) -> com.zhumeng.kanvas.core.IndicatorCalculator,
) : KlineIndicatorPlugin<KlineTriplePeriodIndicatorConfig> {
    override val key: IndicatorKey = IndicatorKey.computed(id, label)
    override fun createDefinition(config: KlineTriplePeriodIndicatorConfig): IndicatorDefinition = IndicatorDefinition(key = key, placement = config.placement, zIndex = config.zIndex, paintMode = config.paintMode, layoutHint = config.layoutHint, calculator = calculatorFactory(config.firstPeriod, config.secondPeriod, config.thirdPeriod))
    override fun createRenderer(): KlineIndicatorRenderer = KlineComputedLineIndicatorRenderer
}

class KlineMacdIndicatorPlugin(id: String = "compose_macd", label: String = "MACD") : KlineIndicatorPlugin<KlineTriplePeriodIndicatorConfig> by KlineTripleComputedPlugin(id, label, KlineTriplePeriodIndicatorConfig(12, 26, 9, IndicatorPlacement.Sub("macd")), ::Macd)

class KlineKdjIndicatorPlugin(id: String = "compose_kdj", label: String = "KDJ") : KlineIndicatorPlugin<KlineTriplePeriodIndicatorConfig> by KlineTripleComputedPlugin(id, label, KlineTriplePeriodIndicatorConfig(9, 3, 3, IndicatorPlacement.Sub("kdj")), { a, b, c -> Kdj(a, b, c) })

class KlineBollIndicatorPlugin(id: String = "compose_boll", label: String = "BOLL") : KlineIndicatorPlugin<KlineTriplePeriodIndicatorConfig> by KlineTripleComputedPlugin(id, label, KlineTriplePeriodIndicatorConfig(20, 2, 0.coerceAtLeast(1), IndicatorPlacement.Main), { a, b, _ -> BollingerBands(a, b.toDouble()) })

data class KlineStyledIndicatorConfig(
    val placement: IndicatorPlacement,
    val zIndex: Int = 0,
    val paintMode: IndicatorPaintMode = IndicatorPaintMode.COMBINE,
    val layoutHint: IndicatorLayoutHint = IndicatorLayoutHint(),
    val lineStyles: List<KlineIndicatorLineStyle> = emptyList(),
) : KlineIndicatorPluginConfig

data class KlineSarIndicatorConfig(
    val step: Double = 0.02,
    val maximum: Double = 0.2,
    val placement: IndicatorPlacement = IndicatorPlacement.Main,
    val zIndex: Int = 0,
    val paintMode: IndicatorPaintMode = IndicatorPaintMode.COMBINE,
    val layoutHint: IndicatorLayoutHint = IndicatorLayoutHint(),
    val lineStyles: List<KlineIndicatorLineStyle> = emptyList(),
) : KlineIndicatorPluginConfig {
    init { ParabolicSar(step, maximum) }
    override val parameters: IndicatorParameters = IndicatorParameters.of(
        "step" to step.toString(),
        "maximum" to maximum.toString(),
    )
}

data class KlineSuperTrendIndicatorConfig(
    val atrPeriod: Int = 10,
    val multiplier: Double = 3.0,
    val placement: IndicatorPlacement = IndicatorPlacement.Main,
    val zIndex: Int = 0,
    val paintMode: IndicatorPaintMode = IndicatorPaintMode.COMBINE,
    val layoutHint: IndicatorLayoutHint = IndicatorLayoutHint(),
    val lineStyles: List<KlineIndicatorLineStyle> = emptyList(),
) : KlineIndicatorPluginConfig {
    init { SuperTrend(atrPeriod, multiplier) }
    override val parameters: IndicatorParameters = IndicatorParameters.of(
        "atrPeriod" to atrPeriod.toString(),
        "multiplier" to multiplier.toString(),
    )
}

data class KlineStochasticRsiIndicatorConfig(
    val rsiPeriod: Int = 14,
    val stochasticPeriod: Int = 14,
    val kPeriod: Int = 3,
    val dPeriod: Int = 3,
    val placement: IndicatorPlacement = IndicatorPlacement.Sub("stoch_rsi"),
    val zIndex: Int = 0,
    val paintMode: IndicatorPaintMode = IndicatorPaintMode.COMBINE,
    val layoutHint: IndicatorLayoutHint = IndicatorLayoutHint(),
    val lineStyles: List<KlineIndicatorLineStyle> = emptyList(),
) : KlineIndicatorPluginConfig {
    init { StochasticRsi(rsiPeriod, stochasticPeriod, kPeriod, dPeriod) }
    override val parameters: IndicatorParameters = IndicatorParameters.of(
        "periods" to "$rsiPeriod,$stochasticPeriod,$kPeriod,$dPeriod",
    )
}

class KlineSarIndicatorPlugin(id: String = "compose_sar", label: String = "SAR") : KlineIndicatorPlugin<KlineSarIndicatorConfig> {
    override val key: IndicatorKey = IndicatorKey.computed(id, label)
    override val defaultConfig: KlineSarIndicatorConfig = KlineSarIndicatorConfig()
    override fun createDefinition(config: KlineSarIndicatorConfig): IndicatorDefinition = IndicatorDefinition(
        key = key,
        placement = config.placement,
        zIndex = config.zIndex,
        paintMode = config.paintMode,
        layoutHint = config.layoutHint,
        calculator = ParabolicSar(config.step, config.maximum),
    )
    override fun createRenderer(): KlineIndicatorRenderer = KlineSarIndicatorRenderer
}

class KlineAvlIndicatorPlugin(id: String = "compose_avl", label: String = "AVL") : KlineIndicatorPlugin<KlineStyledIndicatorConfig> by KlineStyledComputedPlugin(
    id, label, KlineStyledIndicatorConfig(IndicatorPlacement.Main), AverageValueLine,
)

class KlineSuperTrendIndicatorPlugin(id: String = "compose_super", label: String = "SUPER") : KlineIndicatorPlugin<KlineSuperTrendIndicatorConfig> {
    override val key: IndicatorKey = IndicatorKey.computed(id, label)
    override val defaultConfig: KlineSuperTrendIndicatorConfig = KlineSuperTrendIndicatorConfig()
    override fun createDefinition(config: KlineSuperTrendIndicatorConfig): IndicatorDefinition = IndicatorDefinition(
        key = key,
        placement = config.placement,
        zIndex = config.zIndex,
        paintMode = config.paintMode,
        layoutHint = config.layoutHint,
        calculator = SuperTrend(config.atrPeriod, config.multiplier),
    )
    override fun createRenderer(): KlineIndicatorRenderer = KlineComputedLineIndicatorRenderer
}

data class KlineObvIndicatorConfig(
    val maPeriod: Int? = null,
    val emaPeriod: Int? = null,
    val placement: IndicatorPlacement = IndicatorPlacement.Sub("obv"),
    val lineStyles: List<KlineIndicatorLineStyle> = emptyList(),
) : KlineIndicatorPluginConfig {
    init {
        require(maPeriod == null || maPeriod > 0)
        require(emaPeriod == null || emaPeriod > 0)
    }
}

class KlineObvIndicatorPlugin(id: String = "compose_obv", label: String = "OBV") : KlineIndicatorPlugin<KlineObvIndicatorConfig> {
    override val key: IndicatorKey = IndicatorKey.computed(id, label)
    override val defaultConfig: KlineObvIndicatorConfig = KlineObvIndicatorConfig()
    override fun createDefinition(config: KlineObvIndicatorConfig): IndicatorDefinition = IndicatorDefinition(
        key = key,
        placement = config.placement,
        calculator = OnBalanceVolumeWithAverages(config.maPeriod, config.emaPeriod),
    )
    override fun createRenderer(): KlineIndicatorRenderer = KlineComputedLineIndicatorRenderer
}

class KlineWrIndicatorPlugin(id: String = "compose_wr", label: String = "WR", period: Int = 14) : KlineIndicatorPlugin<KlineSinglePeriodIndicatorConfig> by KlineComputedPlugin(
    id, label, KlineSinglePeriodIndicatorConfig(period, IndicatorPlacement.Sub("wr")), ::WilliamsR,
)

class KlineStochasticRsiIndicatorPlugin(id: String = "compose_stoch_rsi", label: String = "StochRSI") : KlineIndicatorPlugin<KlineStochasticRsiIndicatorConfig> {
    override val key: IndicatorKey = IndicatorKey.computed(id, label)
    override val defaultConfig: KlineStochasticRsiIndicatorConfig = KlineStochasticRsiIndicatorConfig()
    override fun createDefinition(config: KlineStochasticRsiIndicatorConfig): IndicatorDefinition = IndicatorDefinition(
        key = key,
        placement = config.placement,
        zIndex = config.zIndex,
        paintMode = config.paintMode,
        layoutHint = config.layoutHint,
        calculator = StochasticRsi(config.rsiPeriod, config.stochasticPeriod, config.kPeriod, config.dPeriod),
    )
    override fun createRenderer(): KlineIndicatorRenderer = KlineComputedLineIndicatorRenderer
}

private class KlineStyledComputedPlugin(
    id: String,
    label: String,
    override val defaultConfig: KlineStyledIndicatorConfig,
    private val calculator: com.zhumeng.kanvas.core.IndicatorCalculator,
) : KlineIndicatorPlugin<KlineStyledIndicatorConfig> {
    override val key: IndicatorKey = IndicatorKey.computed(id, label)
    override fun createDefinition(config: KlineStyledIndicatorConfig): IndicatorDefinition = IndicatorDefinition(
        key = key,
        placement = config.placement,
        zIndex = config.zIndex,
        paintMode = config.paintMode,
        layoutHint = config.layoutHint,
        calculator = calculator,
    )
    override fun createRenderer(): KlineIndicatorRenderer = KlineComputedLineIndicatorRenderer
}
