/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas.core

/**
 * Lifecycle and data-source category for indicator declarations.
 *
 * Direct indicators normally draw directly from candles, computed indicators
 * produce derived columns, and external indicators are fed by application
 * data. The runtime can execute a calculator for any kind, but a computed
 * definition must provide one.
 */
enum class IndicatorKind {
    DIRECT,
    COMPUTED,
    EXTERNAL,
    ;

}

/**
 * Stable identifier for an indicator declaration.
 *
 * Equality intentionally uses [kind] and [id], but not [label]: changing a
 * display label must not allocate another computed
 * slot or lose an existing persisted indicator selection.
 */
class IndicatorKey(
    val kind: IndicatorKind,
    val id: String,
    val label: String = id,
) {
    init {
        require(id.isNotBlank()) { "Indicator key id must not be blank" }
    }

    override fun equals(other: Any?): Boolean =
        other is IndicatorKey && kind == other.kind && id == other.id

    override fun hashCode(): Int = 31 * kind.hashCode() + id.hashCode()

    override fun toString(): String = "${kind.name.lowercase()}:$id:$label"

    companion object {
        fun direct(id: String, label: String = id): IndicatorKey =
            IndicatorKey(IndicatorKind.DIRECT, id, label)

        fun computed(id: String, label: String = id): IndicatorKey =
            IndicatorKey(IndicatorKind.COMPUTED, id, label)

        fun external(id: String, label: String = id): IndicatorKey =
            IndicatorKey(IndicatorKind.EXTERNAL, id, label)
    }
}

/** Where an indicator is laid out. A sub-pane id permits multiple sub charts. */
sealed interface IndicatorPlacement {
    /** Shared main candle pane. */
    data object Main : IndicatorPlacement

    /** Dedicated sub-pane, addressed independently from its visual order. */
    data class Sub(val paneId: String = DefaultPaneId) : IndicatorPlacement {
        init {
            require(paneId.isNotBlank()) { "Sub indicator pane id must not be blank" }
            require(paneId !in SystemPaneIds) {
                "Sub indicator pane id '$paneId' is reserved for a system pane"
            }
        }
    }

    companion object {
        const val DefaultPaneId: String = "default"

        /** Internal layout ids owned by the built-in Main and Time indicators. */
        val SystemPaneIds: Set<String> = setOf("main", "time")
    }
}

/**
 * Geometry mode for an indicator inside a shared pane.
 *
 * A [COMBINE] indicator shares its parent pane's geometry and value range.
 * An [ALONE] indicator owns a separate geometry/value-range pass.  The Core
 * registry preserves this declaration now; Compose's pane planner consumes it
 * when renderer SPI support is attached.
 */
enum class IndicatorPaintMode {
    COMBINE,
    ALONE,
    ;

}

/** Platform-neutral logical-pixel insets owned by an indicator declaration. */
data class IndicatorInsets(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) {
            "Indicator insets must be finite"
        }
        require(left >= 0f && top >= 0f && right >= 0f && bottom >= 0f) {
            "Indicator insets must not be negative"
        }
    }

    companion object {
        val Zero: IndicatorInsets = IndicatorInsets()
    }
}

/**
 * Optional per-indicator geometry for custom height and padding.
 *
 * Values are logical pixels owned by the host. [preferredHeight] is retained as a source-
 * compatible alias for the earlier Compose API; when both are specified they
 * must agree. A null [padding] means that a sub pane may inherit the host's
 * default pane padding, while [IndicatorInsets.Zero] deliberately overrides
 * that fallback.
 */
data class IndicatorLayoutHint(
    val height: Float? = null,
    val padding: IndicatorInsets? = null,
    val preferredHeight: Float? = null,
    val minHeight: Float? = null,
) {
    init {
        require(height == null || (height.isFinite() && height >= 0f)) {
            "Indicator height must be finite and non-negative"
        }
        require(preferredHeight == null || (preferredHeight.isFinite() && preferredHeight >= 0f)) {
            "Indicator preferred height must be finite and non-negative"
        }
        require(minHeight == null || (minHeight.isFinite() && minHeight >= 0f)) {
            "Indicator minimum height must be finite and non-negative"
        }
        require(height == null || preferredHeight == null || height == preferredHeight) {
            "Indicator height and preferred height must agree when both are set"
        }
        val effectiveHeight = height ?: preferredHeight
        require(effectiveHeight == null || minHeight == null || effectiveHeight >= minHeight) {
            "Indicator preferred height must not be smaller than its minimum height"
        }
    }

    /** Effective indicator height after folding the compatibility alias. */
    val resolvedHeight: Float? get() = height ?: preferredHeight
}

/**
 * Immutable, scalar configuration associated with an [IndicatorDefinition].
 *
 * It deliberately does not expose its backing collection. An integration
 * layer may store raw payloads separately; this small typed core
 * value is intended for calculation identity and Kotlin-defined parameters.
 */
class IndicatorParameters private constructor(
    private val entries: List<Entry>,
) {
    data class Entry(val name: String, val value: String) {
        init {
            require(name.isNotBlank()) { "Indicator parameter name must not be blank" }
        }
    }

    val size: Int get() = entries.size

    val isEmpty: Boolean get() = entries.isEmpty()

    operator fun get(name: String): String? = entries.firstOrNull { it.name == name }?.value

    /** Returns an independent list, so callers cannot mutate this value. */
    fun asList(): List<Entry> = entries.toList()

    override fun equals(other: Any?): Boolean =
        other is IndicatorParameters && entries == other.entries

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String = "IndicatorParameters($entries)"

    companion object {
        val Empty: IndicatorParameters = IndicatorParameters(emptyList())

        fun of(values: Map<String, String>): IndicatorParameters =
            of(values.map { (name, value) -> Entry(name, value) })

        fun of(vararg values: Pair<String, String>): IndicatorParameters =
            of(values.map { (name, value) -> Entry(name, value) })

        fun of(entries: Iterable<Entry>): IndicatorParameters {
            val copied = entries.toList()
            require(copied.map(Entry::name).distinct().size == copied.size) {
                "Indicator parameter names must be unique"
            }
            return if (copied.isEmpty()) Empty else IndicatorParameters(copied)
        }
    }
}

/**
 * Immutable Kotlin configuration attached to an [IndicatorDefinition].
 *
 * This is intentionally a small, UI-toolkit-neutral Core marker. Native
 * indicator plugins normally use a Kotlin `data class` here, so
 * structural equality becomes part of definition identity: changing a config
 * causes Core to recalculate and lets a stateful renderer receive `onUpdate`.
 * Implementations must therefore be immutable and implement stable
 * `equals`/`hashCode` semantics.
 */
interface IndicatorConfiguration

/** Default configuration for a declaration that has no typed native settings. */
data object EmptyIndicatorConfiguration : IndicatorConfiguration

/**
 * Immutable declaration of an indicator's identity, layout, lifecycle and
 * pure calculator. Definitions can be safely compared by a runtime to decide
 * whether an older result remains valid.
 */
data class IndicatorDefinition(
    val key: IndicatorKey,
    val placement: IndicatorPlacement = IndicatorPlacement.Main,
    val zIndex: Int = 0,
    /** External declarations auto-activate unless a plugin opts out. */
    val autoActivate: Boolean = key.kind == IndicatorKind.EXTERNAL,
    /** External declarations keep their business state alive by default. */
    val keepAlive: Boolean = key.kind == IndicatorKind.EXTERNAL,
    val paintMode: IndicatorPaintMode = IndicatorPaintMode.COMBINE,
    val layoutHint: IndicatorLayoutHint = IndicatorLayoutHint(),
    val parameters: IndicatorParameters = IndicatorParameters.Empty,
    val calculator: IndicatorCalculator? = null,
    /**
     * Strongly typed Kotlin-native plugin configuration. It participates in
     * definition equality; [parameters] remains a compact scalar
     * metadata form for callers that do not have a typed config.
     */
    val configuration: IndicatorConfiguration = EmptyIndicatorConfiguration,
) {
    init {
        require(key.kind != IndicatorKind.COMPUTED || calculator != null) {
            "Computed indicator '${key.id}' requires an IndicatorCalculator"
        }
    }
}

/** Input supplied to a pure [IndicatorCalculator]. Both series are newest-first. */
data class IndicatorCalculationInput(
    val definition: IndicatorDefinition,
    val previousSeries: KlineSeries?,
    val series: KlineSeries,
    val previousOutput: IndicatorOutput?,
    val computeMode: KlineComputeMode = KlineComputeMode.Fast,
)

/**
 * Pure indicator calculation SPI. Implementations must not mutate either
 * series or retain mutable references to their result buffers.
 */
fun interface IndicatorCalculator {
    fun calculate(input: IndicatorCalculationInput): IndicatorOutput
}
