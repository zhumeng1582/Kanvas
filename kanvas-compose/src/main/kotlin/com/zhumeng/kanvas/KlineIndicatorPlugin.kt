/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import com.zhumeng.kanvas.core.IndicatorConfiguration
import com.zhumeng.kanvas.core.IndicatorDefinition
import com.zhumeng.kanvas.core.IndicatorKey
import com.zhumeng.kanvas.core.IndicatorParameters
import com.zhumeng.kanvas.core.IndicatorRegistry

/**
 * Immutable, Kotlin-native configuration for one indicator plugin instance.
 *
 * Implement this with a data class whenever possible. The config itself is
 * copied to [IndicatorDefinition.configuration], which makes every structural
 * config change visible to Core calculation identity and stateful renderer
 * `onUpdate` callbacks. [parameters] is optional compact scalar metadata; it
 * is not the source of native type safety. This SPI contains no serialization
 * format dependencies.
 */
interface KlineIndicatorPluginConfig : IndicatorConfiguration {
    val parameters: IndicatorParameters
        get() = IndicatorParameters.Empty
}

/** Convenient config for a plugin with no configurable fields. */
data object KlineEmptyIndicatorPluginConfig : KlineIndicatorPluginConfig {
    override val parameters: IndicatorParameters = IndicatorParameters.Empty
}

/**
 * Compose-native declaration of one Android indicator implementation.
 *
 * A plugin owns one stable Core [key] and maps its strongly typed [C] into a
 * definition/calculator plus optional Canvas renderer or stateful factory.
 * It has no dependency on a persistence format; an adapter may invoke
 * [bind] only after it has decoded an external format into [C].
 */
interface KlineIndicatorPlugin<C : KlineIndicatorPluginConfig> {
    /** `(kind, id)` is stable identity; label is presentation-only. */
    val key: IndicatorKey

    val defaultConfig: C

    /** Must create a definition with the same `(kind, id)` as [key]. */
    fun createDefinition(config: C): IndicatorDefinition

    /**
     * Optional stable stateless Canvas renderer. It must read its typed config
     * from the current definition/context instead of capturing one [bind]
     * invocation, so `IndicatorRegistry.upsert(plugin.bind(newConfig).definition)`
     * can update an already-mounted chart without recreating its registry.
     */
    fun createRenderer(): KlineIndicatorRenderer? = null

    /**
     * Optional stable per-key instance factory. The factory receives the
     * current definition in `create`; a retained instance later receives
     * `onUpdate` when this plugin is rebound with a changed config.
     */
    fun createStatefulRendererFactory(): KlineStatefulIndicatorRendererFactory? = null
}

/**
 * One fully type-checked native plugin instance. Construct it through
 * [KlineIndicatorPlugin.bind] so the config's identity parameters cannot be
 * accidentally omitted from the Core definition.
 */
@ConsistentCopyVisibility
data class KlineIndicatorPluginBinding internal constructor(
    val definition: IndicatorDefinition,
    val renderer: KlineIndicatorRenderer?,
    val statefulRendererFactory: KlineStatefulIndicatorRendererFactory?,
)

/** Binds the plugin's [KlineIndicatorPlugin.defaultConfig]. */
fun <C : KlineIndicatorPluginConfig> KlineIndicatorPlugin<C>.bind(): KlineIndicatorPluginBinding =
    bind(defaultConfig)

/** Binds one type-safe config to its plugin implementation. */
fun <C : KlineIndicatorPluginConfig> KlineIndicatorPlugin<C>.bind(
    config: C,
): KlineIndicatorPluginBinding {
    val created = createDefinition(config)
    require(created.key == key) {
        "Native indicator plugin '${key.id}' returned '${created.key}', expected the same kind/id"
    }
    // Definition equality is how Core and the lifecycle host detect a native
    // config update. Store the typed config itself; parameters are retained as
    // optional scalar metadata for clients that inspect key/value data.
    val definition = created.copy(
        configuration = config,
        parameters = config.parameters,
    )
    return KlineIndicatorPluginBinding(
        definition = definition,
        renderer = createRenderer(),
        statefulRendererFactory = createStatefulRendererFactory(),
    )
}

/** Returns this definition's plugin config with an actionable error on misuse. */
inline fun <reified C : KlineIndicatorPluginConfig> IndicatorDefinition.requirePluginConfig(): C =
    configuration as? C ?: error(
        "Indicator '${key.id}' has ${configuration::class.simpleName ?: "unknown"} configuration; " +
            "${C::class.simpleName ?: "requested"} was required",
    )

/** Type-safe shorthand for a renderer reading its current native config. */
inline fun <reified C : KlineIndicatorPluginConfig> KlineIndicatorDrawContext.requirePluginConfig(): C =
    definition.requirePluginConfig()

/**
 * Immutable native indicator set ready to mount into Core or a Compose chart.
 *
 * Binding renderers/factories are scoped to their definition's `(kind, id)`
 * before entering the ordered fallback registry, so a careless catch-all
 * plugin matcher cannot steal another native plugin's renderer.
 */
class KlineIndicatorPluginCatalog private constructor(
    private val bindingStorage: List<KlineIndicatorPluginBinding>,
) {
    val bindings: List<KlineIndicatorPluginBinding> get() = bindingStorage.toList()

    val definitions: List<IndicatorDefinition>
        get() = bindingStorage.map(KlineIndicatorPluginBinding::definition)

    fun mountIndicatorRegistry(
        activeKeys: Iterable<IndicatorKey> = emptyList(),
        subIndicatorCapacity: Int = IndicatorRegistry.DefaultSubIndicatorCapacity,
    ): IndicatorRegistry = IndicatorRegistry(subIndicatorCapacity).apply {
        mount(definitions, restoredActiveKeys = activeKeys)
    }

    fun createRendererRegistry(
        fallback: KlineIndicatorRendererRegistry = KlineIndicatorRendererRegistry.Default,
    ): KlineIndicatorRendererRegistry = KlineIndicatorRendererRegistry(
        renderers = bindingStorage.mapNotNull { binding ->
            binding.renderer?.let { renderer -> NativeScopedIndicatorRenderer(binding.definition.key, renderer) }
        } + fallback.renderers(),
        statefulFactories = bindingStorage.mapNotNull { binding ->
            binding.statefulRendererFactory?.let { factory ->
                NativeScopedStatefulIndicatorRendererFactory(binding.definition.key, factory)
            }
        } + fallback.statefulFactories(),
    )

    companion object {
        fun of(bindings: Iterable<KlineIndicatorPluginBinding>): KlineIndicatorPluginCatalog {
            val values = bindings.toList()
            require(values.map { it.definition.key }.distinct().size == values.size) {
                "Native indicator plugin bindings must have unique kind/id keys"
            }
            return KlineIndicatorPluginCatalog(values)
        }

        fun of(vararg bindings: KlineIndicatorPluginBinding): KlineIndicatorPluginCatalog = of(bindings.asList())
    }
}

/** Scope wrapper that preserves optional overlay/cross/top-Tips/tap capabilities. */
private class NativeScopedIndicatorRenderer(
    private val key: IndicatorKey,
    private val delegate: KlineIndicatorRenderer,
) : KlineIndicatorRenderer,
    KlineIndicatorOverlayRenderer,
    KlineIndicatorCrossRenderer,
    KlineIndicatorTopTipsRenderer,
    KlineIndicatorTapHandler {
    override fun supports(
        definition: IndicatorDefinition,
        output: com.zhumeng.kanvas.core.IndicatorOutput?,
    ): Boolean = definition.key == key && delegate.supports(definition, output)

    override fun supportsPending(definition: IndicatorDefinition): Boolean =
        definition.key == key && delegate.supportsPending(definition)

    override fun draw(
        scope: androidx.compose.ui.graphics.drawscope.DrawScope,
        context: KlineIndicatorDrawContext,
    ) {
        if (context.definition.key == key) delegate.draw(scope, context)
    }

    override fun visibleValueRange(context: KlineIndicatorRangeContext): KlineIndicatorValueRange? =
        context.takeIf { it.definition.key == key }?.let(delegate::visibleValueRange)

    override fun drawOverlay(
        scope: androidx.compose.ui.graphics.drawscope.DrawScope,
        context: KlineIndicatorOverlayDrawContext,
    ) {
        if (context.indicator.definition.key == key) {
            (delegate as? KlineIndicatorOverlayRenderer)?.drawOverlay(scope, context)
        }
    }

    override fun drawCross(
        scope: androidx.compose.ui.graphics.drawscope.DrawScope,
        context: KlineIndicatorCrossDrawContext,
    ) {
        if (context.indicator.definition.key == key) {
            (delegate as? KlineIndicatorCrossRenderer)?.drawCross(scope, context)
        }
    }

    override fun prepareTopTips(
        context: KlineIndicatorTopTipsPrepareContext,
    ): KlineIndicatorTopTipsPrepared? =
        context.takeIf { it.indicator.definition.key == key }
            ?.let { (delegate as? KlineIndicatorTopTipsRenderer)?.prepareTopTips(it) }

    override fun drawTopTips(
        scope: androidx.compose.ui.graphics.drawscope.DrawScope,
        context: KlineIndicatorTopTipsDrawContext,
    ) {
        if (context.indicator.definition.key == key) {
            (delegate as? KlineIndicatorTopTipsRenderer)?.drawTopTips(scope, context)
        }
    }

    override fun onTap(context: KlineIndicatorTapContext): Boolean =
        context.takeIf { it.indicator.definition.key == key }
            ?.let { (delegate as? KlineIndicatorTapHandler)?.onTap(it) }
            ?: false
}

/** Scope wrapper for a plugin-owned stateful renderer factory. */
private class NativeScopedStatefulIndicatorRendererFactory(
    private val key: IndicatorKey,
    private val delegate: KlineStatefulIndicatorRendererFactory,
) : KlineStatefulIndicatorRendererFactory {
    override fun supports(definition: IndicatorDefinition): Boolean =
        definition.key == key && delegate.supports(definition)

    override fun create(definition: IndicatorDefinition): KlineStatefulIndicatorRenderer {
        require(definition.key == key) {
            "Native stateful factory for '${key.id}' cannot create '${definition.key.id}'"
        }
        return delegate.create(definition)
    }
}
