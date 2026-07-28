/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas.core

/** One immutable definition/result pair held by an [IndicatorRuntimeSnapshot]. */
internal data class IndicatorRuntimeEntry(
    val definition: IndicatorDefinition,
    val output: IndicatorOutput,
)

/**
 * Immutable result of calculating a set of indicator definitions for a
 * particular newest-first [series].
 */
class IndicatorRuntimeSnapshot internal constructor(
    val series: KlineSeries,
    entries: List<IndicatorRuntimeEntry>,
    /** Non-null only when calculated through [IndicatorRegistry]. */
    val registryGeneration: Long? = null,
    /** Process-local identity of the registry used for [registryGeneration]. */
    val registryToken: Long? = null,
    /**
     * Controller revision that supplied [series], when a coordinator owns this
     * result. Null keeps manually calculated snapshots source-compatible.
     */
    val sourceRevision: Long? = null,
    val computeMode: KlineComputeMode = KlineComputeMode.Fast,
) {
    private val entryStorage: List<IndicatorRuntimeEntry> = entries.toList()
    private val entriesByKey: Map<IndicatorKey, IndicatorRuntimeEntry> =
        entryStorage.associateBy { it.definition.key }

    init {
        require(entriesByKey.size == entryStorage.size) {
            "Indicator runtime snapshot contains duplicate keys"
        }
        require(entryStorage.all { it.output.seriesSize == series.size }) {
            "Indicator runtime snapshot output does not align with its candle series"
        }
    }

    /** Returns the output for [key], if that definition was calculated. */
    fun output(key: IndicatorKey): IndicatorOutput? =
        entriesByKey[key]?.output

    /** Returns a fresh list of immutable outputs in definition order. */
    fun outputs(): List<IndicatorOutput> = entryStorage.map(IndicatorRuntimeEntry::output)

    /** Returns a fresh list of immutable definitions in calculation order. */
    fun definitions(): List<IndicatorDefinition> = entryStorage.map(IndicatorRuntimeEntry::definition)

    internal fun entry(key: IndicatorKey): IndicatorRuntimeEntry? =
        entriesByKey[key]

    /** True when [registry] is a newer snapshot of the same registry instance. */
    internal fun hasRegistryIdentity(registry: IndicatorRegistrySnapshot): Boolean =
        registryToken == registry.registryToken

    /** Prevents a renderer from applying output calculated for another registry or older selection. */
    fun matches(registry: IndicatorRegistrySnapshot): Boolean =
        registryToken == registry.registryToken && registryGeneration == registry.generation

    /**
     * Full render-validity check for coordinator-owned results. A manual
     * runtime result has no [sourceRevision] and remains valid by candle
     * equality plus its registry identity.
     */
    fun matches(state: KlineUiState): Boolean {
        val exactInput = series.candles == state.series.candles &&
            (sourceRevision == null || sourceRevision == state.revision)
        val sameHistoryWithOpenCandleReplacement =
            state.series.differsOnlyAtLatestFrom(series)
        return exactInput || sameHistoryWithOpenCandleReplacement
    }

    fun matches(state: KlineUiState, registry: IndicatorRegistrySnapshot): Boolean =
        matches(registry) && matches(state)

    /** Tags an otherwise immutable calculation with the controller input that created it. */
    internal fun withSourceRevision(revision: Long): IndicatorRuntimeSnapshot =
        if (sourceRevision == revision) this else {
            IndicatorRuntimeSnapshot(
                series = series,
                entries = entryStorage,
                registryGeneration = registryGeneration,
                registryToken = registryToken,
                sourceRevision = revision,
                computeMode = computeMode,
            )
        }

    /**
     * Reuses aligned output for another same-sized candle snapshot. This is
     * used by close-only refresh policies while the newest candle is still
     * open; all historical indexes remain unchanged.
     */
    internal fun rebaseSeries(
        series: KlineSeries,
        sourceRevision: Long,
    ): IndicatorRuntimeSnapshot {
        require(series.size == this.series.size) {
            "Rebased indicator output must keep the same series size"
        }
        return IndicatorRuntimeSnapshot(
            series = series,
            entries = entryStorage,
            registryGeneration = registryGeneration,
            registryToken = registryToken,
            sourceRevision = sourceRevision,
            computeMode = computeMode,
        )
    }

    companion object {
        internal fun empty(
            series: KlineSeries,
            computeMode: KlineComputeMode = KlineComputeMode.Fast,
        ): IndicatorRuntimeSnapshot = IndicatorRuntimeSnapshot(series, emptyList(), computeMode = computeMode)
    }
}

/**
 * Stateless and thread-safe indicator execution engine.
 *
 * The caller supplies a prior snapshot when one exists. This keeps stale-work
 * rejection in the controller's revision layer while allowing each calculator
 * to inspect both old and new candle sequences and, when the definition is
 * unchanged, its previous immutable output. The runtime itself retains no
 * mutable cache and can therefore be used safely from background jobs.
 */
class IndicatorRuntime {
    fun calculate(
        previous: IndicatorRuntimeSnapshot? = null,
        series: KlineSeries,
        definitions: Iterable<IndicatorDefinition>,
        computeMode: KlineComputeMode = KlineComputeMode.Fast,
    ): IndicatorRuntimeSnapshot = calculateInternal(
        previous = previous,
        series = series,
        definitions = definitions,
        registryGeneration = null,
        registryToken = null,
        computeMode = computeMode,
    )

    /** Calculates only attached definitions from [registry], preserving its generation for render validation. */
    fun calculate(
        previous: IndicatorRuntimeSnapshot? = null,
        series: KlineSeries,
        registry: IndicatorRegistrySnapshot,
        computeMode: KlineComputeMode = KlineComputeMode.Fast,
    ): IndicatorRuntimeSnapshot = calculateInternal(
        previous = previous,
        series = series,
        definitions = registry.calculationDefinitions(),
        registryGeneration = registry.generation,
        registryToken = registry.registryToken,
        computeMode = computeMode,
    )

    private fun calculateInternal(
        previous: IndicatorRuntimeSnapshot?,
        series: KlineSeries,
        definitions: Iterable<IndicatorDefinition>,
        registryGeneration: Long?,
        registryToken: Long?,
        computeMode: KlineComputeMode,
    ): IndicatorRuntimeSnapshot {
        val definitionList = definitions.toList()
        require(definitionList.map(IndicatorDefinition::key).distinct().size == definitionList.size) {
            "Indicator definitions must not contain duplicate keys"
        }

        val entries = definitionList.map { definition ->
            val previousEntry = previous?.entry(definition.key)
            val definitionUnchanged = previousEntry?.definition == definition
            val sameCandles =
                previous?.series?.candles == series.candles && previous.computeMode == computeMode
            val output = if (definitionUnchanged && sameCandles) {
                previousEntry.output.withCanonicalKey(definition.key)
            } else {
                calculateFresh(
                    definition,
                    previous,
                    series,
                    previousEntry?.takeIf { definitionUnchanged }?.output,
                    computeMode,
                )
            }
            IndicatorRuntimeEntry(definition, output)
        }
        return IndicatorRuntimeSnapshot(
            series,
            entries,
            registryGeneration,
            registryToken,
            computeMode = computeMode,
        )
    }

    /**
     * Convenience overload for calculators that only need an old candle
     * sequence. It cannot reuse an older output because none was supplied.
     */
    fun calculate(
        previousSeries: KlineSeries?,
        series: KlineSeries,
        definitions: Iterable<IndicatorDefinition>,
        computeMode: KlineComputeMode = KlineComputeMode.Fast,
    ): IndicatorRuntimeSnapshot =
        calculate(
            previous = previousSeries?.let { IndicatorRuntimeSnapshot.empty(it, computeMode) },
            series = series,
            definitions = definitions,
            computeMode = computeMode,
        )

    private fun calculateFresh(
        definition: IndicatorDefinition,
        previous: IndicatorRuntimeSnapshot?,
        series: KlineSeries,
        previousOutput: IndicatorOutput?,
        computeMode: KlineComputeMode,
    ): IndicatorOutput {
        val calculator = definition.calculator
        val output = if (calculator == null) {
            IndicatorOutput.empty(definition.key, series.size)
        } else {
            calculator.calculate(
                IndicatorCalculationInput(
                    definition = definition,
                    previousSeries = previous?.series,
                    series = series,
                    previousOutput = previousOutput,
                    computeMode = computeMode,
                ),
            )
        }
        require(output.key == definition.key) {
            "Indicator calculator for '${definition.key.id}' returned output for '${output.key.id}'"
        }
        require(output.seriesSize == series.size) {
            "Indicator calculator for '${definition.key.id}' returned ${output.seriesSize} samples for ${series.size} candles"
        }
        return output
    }

    /** A display-label update keeps calculation identity but must not leak an old key through output. */
    private fun IndicatorOutput.withCanonicalKey(key: IndicatorKey): IndicatorOutput =
        if (this.key.kind == key.kind && this.key.id == key.id && this.key.label == key.label) {
            this
        } else {
            IndicatorOutput.ofCalculated(
                key = key,
                seriesSize = seriesSize,
                columns = columns(),
                calculationState = calculationState,
            )
        }
}
