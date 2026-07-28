package com.zhumeng.kanvas.example

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhumeng.kanvas.KlineIndicatorLineStyle
import com.zhumeng.kanvas.KlineMovingAverageIndicatorConfig
import com.zhumeng.kanvas.KlineSinglePeriodIndicatorConfig
import com.zhumeng.kanvas.KlineTriplePeriodIndicatorConfig
import com.zhumeng.kanvas.KlineStyledIndicatorConfig
import com.zhumeng.kanvas.KlineSarIndicatorConfig
import com.zhumeng.kanvas.KlineRsiIndicatorConfig
import com.zhumeng.kanvas.KlineObvIndicatorConfig
import com.zhumeng.kanvas.KlineSuperTrendIndicatorConfig
import com.zhumeng.kanvas.KlineStochasticRsiIndicatorConfig
import com.zhumeng.kanvas.core.IndicatorDefinition
import com.zhumeng.kanvas.core.IndicatorKey
import com.zhumeng.kanvas.core.IndicatorRegistrySnapshot

internal data class IndicatorEditorValue(
    val id: String,
    val values: List<Double>,
    val lineStyles: List<KlineIndicatorLineStyle>,
    val styleValues: List<Double?> = emptyList(),
    val styleEnabled: List<Boolean> = emptyList(),
)

private enum class IndicatorGroup { Main, Sub }

private data class IndicatorUiSpec(
    val id: String,
    val name: String,
    val description: String,
    val group: IndicatorGroup,
    val supported: Boolean,
)

private sealed interface IndicatorSheetPage {
    data object Selection : IndicatorSheetPage
    data object Settings : IndicatorSheetPage
    data class Editor(val spec: IndicatorUiSpec) : IndicatorSheetPage
}

private val indicatorSpecs = listOf(
    IndicatorUiSpec("sample_ma", "MA", "移动平均线", IndicatorGroup.Main, true),
    IndicatorUiSpec("compose_ema", "EMA", "指数移动平均线", IndicatorGroup.Main, true),
    IndicatorUiSpec("compose_boll", "BOLL", "布林线", IndicatorGroup.Main, true),
    IndicatorUiSpec("compose_sar", "SAR", "抛物线转向指标", IndicatorGroup.Main, true),
    IndicatorUiSpec("compose_avl", "AVL", "均价线", IndicatorGroup.Main, true),
    IndicatorUiSpec("compose_super", "SUPER", "超级趋势", IndicatorGroup.Main, true),
    IndicatorUiSpec("sample_volume", "VOL", "成交量", IndicatorGroup.Sub, true),
    IndicatorUiSpec("compose_macd", "MACD", "指数平滑异同移动平均线", IndicatorGroup.Sub, true),
    IndicatorUiSpec("compose_rsi", "RSI", "相对强弱指标", IndicatorGroup.Sub, true),
    IndicatorUiSpec("compose_kdj", "KDJ", "随机震荡指标", IndicatorGroup.Sub, true),
    IndicatorUiSpec("compose_obv", "OBV", "能量潮", IndicatorGroup.Sub, true),
    IndicatorUiSpec("compose_wr", "WR", "威廉指标", IndicatorGroup.Sub, true),
    IndicatorUiSpec("compose_stoch_rsi", "StochRSI", "随机相对强弱指标", IndicatorGroup.Sub, true),
)

private val indicatorPalette = listOf(
    Color(0xFFFFC21A), Color(0xFFE83CB5), Color(0xFF8B62C9), Color(0xFF38D866),
    Color(0xFFB81458), Color(0xFF27B887), Color(0xFF74D9C4), Color(0xFF8998DB),
    Color(0xFFCBDC55), Color(0xFFE86E47), Color(0xFF4859F1), Color(0xFFF33E5D),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IndicatorSettingsSheet(
    registrySnapshot: IndicatorRegistrySnapshot,
    onToggle: (IndicatorKey) -> Unit,
    onApply: (IndicatorEditorValue) -> Unit,
    onDismiss: () -> Unit,
) {
    var page by remember { mutableStateOf<IndicatorSheetPage>(IndicatorSheetPage.Selection) }
    val definitions = registrySnapshot.registeredDefinitions().associateBy { it.key.id }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        scrimColor = Color.Black.copy(alpha = 0.58f),
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 8.dp, bottom = 2.dp)
                    .size(width = 48.dp, height = 4.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp)),
            )
        },
    ) {
        when (val current = page) {
            IndicatorSheetPage.Selection -> IndicatorSelectionPage(
                registrySnapshot = registrySnapshot,
                definitions = definitions,
                onToggle = onToggle,
                onSettings = { page = IndicatorSheetPage.Settings },
                onDismiss = onDismiss,
            )
            IndicatorSheetPage.Settings -> IndicatorSettingsListPage(
                definitions = definitions,
                onEdit = { page = IndicatorSheetPage.Editor(it) },
            )
            is IndicatorSheetPage.Editor -> IndicatorEditorPage(
                spec = current.spec,
                definition = definitions[current.spec.id],
                onApply = {
                    onApply(it)
                    page = IndicatorSheetPage.Settings
                },
            )
        }
    }
}

@Composable
private fun IndicatorSelectionPage(
    registrySnapshot: IndicatorRegistrySnapshot,
    definitions: Map<String, IndicatorDefinition>,
    onToggle: (IndicatorKey) -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("指标设置", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("完成", color = MaterialTheme.colorScheme.primary) }
        }
        Spacer(Modifier.height(14.dp))
        IndicatorChipSection(
            title = "主图指标",
            specs = indicatorSpecs.filter { it.group == IndicatorGroup.Main },
            registrySnapshot = registrySnapshot,
            definitions = definitions,
            onToggle = onToggle,
        )
        Spacer(Modifier.height(20.dp))
        IndicatorChipSection(
            title = "副图指标",
            specs = indicatorSpecs.filter { it.group == IndicatorGroup.Sub },
            registrySnapshot = registrySnapshot,
            definitions = definitions,
            onToggle = onToggle,
        )
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSettings)
                .padding(vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("指标参数设置", fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 32.sp)
        }
    }
}

@Composable
private fun IndicatorChipSection(
    title: String,
    specs: List<IndicatorUiSpec>,
    registrySnapshot: IndicatorRegistrySnapshot,
    definitions: Map<String, IndicatorDefinition>,
    onToggle: (IndicatorKey) -> Unit,
) {
    Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
    Spacer(Modifier.height(10.dp))
    specs.chunked(4).forEachIndexed { index, rowSpecs ->
        if (index > 0) Spacer(Modifier.height(9.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            rowSpecs.forEach { spec ->
                val definition = definitions[spec.id]
                val active = definition != null && registrySnapshot.isActive(definition.key)
                IndicatorChip(
                    spec = spec,
                    active = active,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (definition != null && spec.supported) {
                            onToggle(definition.key)
                        }
                    },
                )
            }
            repeat(4 - rowSpecs.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun IndicatorChip(spec: IndicatorUiSpec, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val borderColor = if (active) colors.onSurface else colors.outlineVariant
    Box(
        modifier
            .height(43.dp)
            .border(if (active) 2.dp else 1.dp, borderColor, RoundedCornerShape(9.dp))
            .clickable(enabled = spec.supported, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(spec.name, color = if (spec.supported) colors.onSurface else colors.onSurfaceVariant.copy(alpha = 0.5f), fontSize = 15.sp)
        if (active) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(20.dp)
                    .background(colors.onSurface, RoundedCornerShape(bottomStart = 9.dp, topEnd = 7.dp)),
                contentAlignment = Alignment.Center,
            ) { Text("✓", color = colors.surface, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun IndicatorSettingsListPage(
    definitions: Map<String, IndicatorDefinition>,
    onEdit: (IndicatorUiSpec) -> Unit,
) {
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.84f)) {
        SheetHeader(title = "指标设置")
        LazyColumn(Modifier.fillMaxWidth()) {
            IndicatorGroup.entries.forEach { group ->
                item {
                    Text(
                        if (group == IndicatorGroup.Main) "主图指标" else "副图指标",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 16.sp,
                    )
                }
                items(indicatorSpecs.filter { it.group == group }) { spec ->
                    val enabled = spec.supported && definitions.containsKey(spec.id)
                    val configurable = enabled && spec.id != "sample_volume"
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = configurable) { onEdit(spec) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(spec.name, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), fontSize = 16.sp)
                            Text(spec.description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        val trailing = when {
                            configurable -> "›"
                            enabled -> "跟随涨跌色"
                            else -> "即将支持"
                        }
                        Text(trailing, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = if (configurable) 30.sp else 12.sp)
                    }
                }
                item { Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant)) }
            }
        }
    }
}

private data class EditableIndicatorLine(
    val enabled: Boolean,
    val value: String,
    val widthPx: Float,
    val color: Color,
)

private data class IndicatorEditorSchema(
    val parameterLabels: List<String> = emptyList(),
    val styleLabels: List<String>,
    val styleValues: List<String> = List(styleLabels.size) { "" },
    val defaultEnabled: List<Boolean> = List(styleLabels.size) { true },
    val styleValueColumn: Boolean = false,
    val calculationStyleValueUntil: Int = Int.MAX_VALUE,
    val allowDisable: Boolean = false,
    val checkboxFrom: Int = 0,
    val checkboxUntil: Int = Int.MAX_VALUE,
    val showWidth: Boolean = true,
    val colorOnlyFrom: Int = Int.MAX_VALUE,
    val trailingValues: List<String> = emptyList(),
    val styleColors: List<Color> = emptyList(),
)

@Composable
private fun IndicatorEditorPage(
    spec: IndicatorUiSpec,
    definition: IndicatorDefinition?,
    onApply: (IndicatorEditorValue) -> Unit,
) {
    val schema = remember(spec.id) { editorSchema(spec.id) }
    val initialParameters = remember(spec.id, definition) { editorParameters(spec, definition, schema) }
    val initialLines = remember(spec.id, definition) { editorStyleLines(spec, definition, schema) }
    var parameters by remember(spec.id, definition) { mutableStateOf(initialParameters) }
    var lines by remember(spec.id, definition) { mutableStateOf(initialLines) }
    var expandedWidthIndex by remember { mutableStateOf<Int?>(null) }
    var expandedColorIndex by remember { mutableStateOf<Int?>(null) }
    var validationError by remember { mutableStateOf<String?>(null) }
    val sheetHeightFraction = (0.30f + schema.parameterLabels.size * 0.045f + schema.styleLabels.size * 0.045f)
        .coerceIn(0.42f, 0.78f)
    Column(Modifier.fillMaxWidth().fillMaxHeight(sheetHeightFraction)) {
        SheetHeader(title = "${spec.name} - ${spec.description}")
        LazyColumn(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            if (schema.parameterLabels.isNotEmpty()) {
                items(schema.parameterLabels.indices.toList()) { index ->
                    StandaloneParameterRow(
                        label = schema.parameterLabels[index],
                        value = parameters[index],
                        onValueChange = { value ->
                            if (value.isValidParameterInput()) {
                                parameters = parameters.replace(index, value)
                                validationError = null
                            }
                        },
                    )
                }
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 14.dp)
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                }
            }
            item {
                IndicatorStyleHeader(showValue = schema.styleValueColumn, showWidth = schema.showWidth)
            }
            items(lines.indices.toList()) { index ->
                val line = lines[index]
                IndicatorEditorRow(
                    label = schema.styleLabels[index],
                    line = line,
                    allowDisable = schema.allowDisable,
                    showCheckbox = index in schema.checkboxFrom until schema.checkboxUntil,
                    hasValue = schema.styleValueColumn && line.value.isNotEmpty(),
                    reserveValue = schema.styleValueColumn,
                    showWidth = schema.showWidth && index < schema.colorOnlyFrom,
                    reserveWidth = schema.showWidth,
                    onEnabledChange = { enabled ->
                        lines = lines.replace(index, line.copy(enabled = enabled))
                        validationError = null
                    },
                    onValueChange = { value ->
                        if (value.isValidParameterInput()) {
                            lines = lines.replace(index, line.copy(value = value))
                            validationError = null
                        }
                    },
                    onWidthClick = {
                        expandedWidthIndex = if (expandedWidthIndex == index) null else index
                        expandedColorIndex = null
                    },
                    onColorClick = {
                        expandedColorIndex = if (expandedColorIndex == index) null else index
                        expandedWidthIndex = null
                    },
                )
                if (expandedWidthIndex == index) {
                    WidthPicker(selected = line.widthPx) { width ->
                        lines = lines.replace(index, line.copy(widthPx = width))
                        expandedWidthIndex = null
                    }
                }
                if (expandedColorIndex == index) {
                    ColorPicker(selected = line.color) { color ->
                        lines = lines.replace(index, line.copy(color = color))
                        expandedColorIndex = null
                    }
                }
            }
        }
        validationError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    parameters = editorParameters(spec, null, schema)
                    lines = editorStyleLines(spec, null, schema)
                    validationError = null
                },
                modifier = Modifier.weight(1f).height(50.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                shape = RoundedCornerShape(8.dp),
            ) { Text("重置", fontSize = 18.sp, fontWeight = FontWeight.Medium) }
            Button(
                onClick = {
                    val enabled = lines.filter(EditableIndicatorLine::enabled)
                    val calculationStyleValues = lines.mapIndexedNotNull { index, line ->
                        line.value.takeIf { line.enabled && index < schema.calculationStyleValueUntil && it.isNotEmpty() }
                    }
                    val rawValues = parameters +
                        (if (schema.styleValueColumn) calculationStyleValues else emptyList()) +
                        schema.trailingValues
                    val values = rawValues.mapNotNull(String::toDoubleOrNull)
                    val requiresValue = rawValues.isNotEmpty()
                    val requiresIntegers = spec.id !in setOf("compose_sar", "compose_super", "compose_avl", "compose_obv")
                    validationError = when {
                        enabled.isEmpty() -> "请至少启用一组参数"
                        spec.id == "compose_rsi" && lines.take(3).none(EditableIndicatorLine::enabled) -> "请至少启用一条 RSI"
                        requiresValue && (values.size != rawValues.size || values.any { !it.isFinite() || it <= 0.0 }) -> "参数值必须大于 0"
                        requiresIntegers && values.any { it % 1.0 != 0.0 } -> "周期参数必须是正整数"
                        spec.id == "compose_super" && values.firstOrNull()?.rem(1.0) != 0.0 -> "ATR 周期必须是正整数"
                        values.distinct().size != values.size && spec.id in setOf("sample_ma", "compose_ema") -> "周期参数不能重复"
                        spec.id == "compose_sar" && values.size == 2 && values[1] < values[0] -> "最大加速因子不能小于步长"
                        spec.id == "compose_macd" && values.size == 3 && values[1] <= values[0] -> "慢线周期必须大于快线周期"
                        else -> null
                    }
                    if (validationError == null) {
                        onApply(
                            IndicatorEditorValue(
                                id = spec.id,
                                values = values,
                                lineStyles = if (spec.id in setOf("sample_ma", "compose_ema")) {
                                    enabled.map { KlineIndicatorLineStyle(it.color, it.widthPx) }
                                } else {
                                    lines.map { KlineIndicatorLineStyle(it.color, it.widthPx, visible = it.enabled) }
                                },
                                styleValues = lines.map { it.value.toDoubleOrNull() },
                                styleEnabled = lines.map(EditableIndicatorLine::enabled),
                            ),
                        )
                    }
                },
                modifier = Modifier.weight(1f).height(50.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                shape = RoundedCornerShape(8.dp),
            ) { Text("确认", fontSize = 18.sp, fontWeight = FontWeight.Medium) }
        }
    }
}

@Composable
private fun SheetHeader(title: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(2.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StandaloneParameterRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
        CompactNumberField(
            value = value,
            enabled = true,
            onValueChange = onValueChange,
            modifier = Modifier.width(126.dp),
        )
    }
}

@Composable
private fun IndicatorStyleHeader(showValue: Boolean, showWidth: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("指标参数", Modifier.weight(1.55f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
        if (showValue) {
            Text("参数值", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
            Spacer(Modifier.width(8.dp))
        }
        if (showWidth) {
            Text("线宽", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
            Spacer(Modifier.width(8.dp))
        }
        Text("颜色", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
    }
}

@Composable
private fun IndicatorEditorRow(
    label: String,
    line: EditableIndicatorLine,
    allowDisable: Boolean,
    showCheckbox: Boolean,
    hasValue: Boolean,
    reserveValue: Boolean,
    showWidth: Boolean,
    reserveWidth: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onValueChange: (String) -> Unit,
    onWidthClick: () -> Unit,
    onColorClick: () -> Unit,
) {
    val contentAlpha = if (line.enabled) 1f else 0.4f
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1.55f).clickable(enabled = allowDisable && showCheckbox) { onEnabledChange(!line.enabled) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showCheckbox) {
                CompactCheckbox(
                    checked = line.enabled,
                    enabled = allowDisable,
                )
                Spacer(Modifier.width(5.dp))
            }
            Text(label, fontSize = 16.sp)
        }
        if (reserveValue) {
            if (hasValue) {
                CompactNumberField(
                    value = line.value,
                    enabled = line.enabled,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f).height(42.dp))
            }
        }
        if (showWidth) {
            Spacer(Modifier.width(8.dp))
            Surface(
                modifier = Modifier.weight(1f).height(42.dp).clickable(enabled = line.enabled, onClick = onWidthClick),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(6.dp),
            ) {
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IndicatorWidthPreview(
                        widthPx = line.widthPx,
                        color = line.color.copy(alpha = contentAlpha),
                        modifier = Modifier.size(width = 28.dp, height = 18.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha), fontSize = 12.sp)
                }
            }
        } else if (reserveWidth) {
            Spacer(Modifier.width(8.dp))
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.width(8.dp))
        Surface(
            modifier = Modifier.weight(1f).height(42.dp).clickable(enabled = line.enabled, onClick = onColorClick),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(6.dp),
        ) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(17.dp).background(line.color.copy(alpha = contentAlpha), RoundedCornerShape(3.dp)))
                Spacer(Modifier.weight(1f))
                Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun CompactCheckbox(checked: Boolean, enabled: Boolean) {
    val colors = MaterialTheme.colorScheme
    val borderColor = if (checked) colors.onSurface else colors.outline
    Box(
        Modifier
            .size(22.dp)
            .border(1.5.dp, borderColor, RoundedCornerShape(4.dp))
            .background(
                if (checked) colors.onSurface else Color.Transparent,
                RoundedCornerShape(4.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Text("✓", color = colors.surface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CompactNumberField(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(42.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(6.dp),
    ) {
        Box(Modifier.padding(horizontal = 9.dp), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    fontSize = 17.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun WidthPicker(selected: Float, onSelect: (Float) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 160.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(1.25f, 2f, 3f, 4f).forEach { width ->
            Surface(
                modifier = Modifier.size(width = 48.dp, height = 40.dp).clickable { onSelect(width) },
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = if (selected == width) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                shape = RoundedCornerShape(6.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    IndicatorWidthPreview(
                        widthPx = width,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(width = 29.dp, height = 15.dp),
                    )
                    Text(formatLineWidth(width), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun IndicatorWidthPreview(
    widthPx: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val inset = 3.dp.toPx()
        drawLine(
            color = color,
            start = Offset(inset, size.height - inset),
            end = Offset(size.width - inset, inset),
            strokeWidth = widthPx.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

private fun formatLineWidth(widthPx: Float): String =
    if (widthPx % 1f == 0f) "${widthPx.toInt()}px" else "${widthPx}px"

@Composable
private fun ColorPicker(selected: Color, onSelect: (Color) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(start = 100.dp, bottom = 5.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 5.dp,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            indicatorPalette.chunked(6).forEach { colors ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    colors.forEach { color ->
                        Box(
                            Modifier
                                .size(32.dp)
                                .border(if (selected == color) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(5.dp))
                                .padding(3.dp)
                                .background(color, RoundedCornerShape(4.dp))
                                .clickable { onSelect(color) },
                        )
                    }
                }
            }
        }
    }
}

private fun editorSchema(id: String): IndicatorEditorSchema = when (id) {
    "sample_ma" -> IndicatorEditorSchema(
        styleLabels = List(10) { "MA${it + 1}" },
        styleValues = listOf("7", "25", "99") + List(7) { "120" },
        defaultEnabled = List(3) { true } + List(7) { false },
        styleValueColumn = true,
        calculationStyleValueUntil = 3,
        allowDisable = true,
    )
    "compose_ema" -> IndicatorEditorSchema(
        styleLabels = List(10) { "EMA${it + 1}" },
        styleValues = listOf("5", "10", "20") + List(7) { "60" },
        defaultEnabled = List(3) { true } + List(7) { false },
        styleValueColumn = true,
        calculationStyleValueUntil = 0,
        allowDisable = true,
    )
    "compose_boll" -> IndicatorEditorSchema(
        parameterLabels = listOf("计算周期", "带宽"),
        styleLabels = listOf("UP", "MB", "DN"),
        allowDisable = true,
        trailingValues = listOf("1"),
    )
    "compose_sar" -> IndicatorEditorSchema(
        parameterLabels = listOf("开始", "最大"),
        styleLabels = listOf("SAR"),
        checkboxFrom = Int.MAX_VALUE,
        showWidth = false,
    )
    "compose_avl" -> IndicatorEditorSchema(
        styleLabels = listOf("AVL"),
        checkboxFrom = Int.MAX_VALUE,
    )
    "compose_super" -> IndicatorEditorSchema(
        parameterLabels = listOf("ATR 周期", "乘数"),
        styleLabels = listOf("上升趋势", "下降趋势", "上升趋势背景颜色", "下降趋势背景颜色"),
        allowDisable = true,
        checkboxFrom = 2,
        showWidth = true,
        colorOnlyFrom = 2,
        styleColors = listOf(Color(0xFF27B887), Color(0xFFF33E5D), Color(0xFF8ADCC7), Color(0xFFF193A6)),
    )
    "compose_macd" -> IndicatorEditorSchema(
        parameterLabels = listOf("快线周期", "慢线周期", "信号周期"),
        styleLabels = listOf("DIF", "DEA", "MACD"),
        allowDisable = true,
    )
    "compose_kdj" -> IndicatorEditorSchema(
        parameterLabels = listOf("计算周期", "移动平均周期1", "移动平均周期2"),
        styleLabels = listOf("K", "D", "J"),
        allowDisable = true,
    )
    "compose_rsi" -> IndicatorEditorSchema(
        styleLabels = listOf("RSI1", "RSI2", "RSI3", "Upper", "Lower"),
        styleValues = listOf("6", "14", "24", "70", "30"),
        defaultEnabled = listOf(true, false, false, true, true),
        styleValueColumn = true,
        allowDisable = true,
        styleColors = listOf(
            Color(0xFFFFC21A), Color(0xFFE83CB5), Color(0xFF8B62C9),
            Color(0xFF9B9B9B), Color(0xFF9B9B9B),
        ),
    )
    "compose_obv" -> IndicatorEditorSchema(
        styleLabels = listOf("OBV", "MA", "EMA"),
        styleValues = listOf("", "7", "7"),
        defaultEnabled = listOf(true, false, false),
        styleValueColumn = true,
        allowDisable = true,
        checkboxFrom = 1,
        styleColors = listOf(Color(0xFFFFC21A), Color(0xFF8B62C9), Color(0xFF4859F1)),
    )
    "compose_wr" -> IndicatorEditorSchema(
        styleLabels = listOf("WR"),
        styleValues = listOf("14"),
        styleValueColumn = true,
        checkboxFrom = Int.MAX_VALUE,
    )
    "compose_stoch_rsi" -> IndicatorEditorSchema(
        parameterLabels = listOf("RSI 周期", "Stoch 周期", "平滑 K", "平滑 D"),
        styleLabels = listOf("K%", "D%"),
        allowDisable = true,
        styleColors = listOf(Color(0xFFFFC21A), Color(0xFF8B62C9)),
    )
    else -> IndicatorEditorSchema(styleLabels = listOf(id))
}

private fun editorParameters(
    spec: IndicatorUiSpec,
    definition: IndicatorDefinition?,
    schema: IndicatorEditorSchema,
): List<String> {
    val configured = when (val config = definition?.configuration) {
        is KlineTriplePeriodIndicatorConfig -> if (spec.id == "compose_boll") {
            listOf(config.firstPeriod.toString(), "${config.secondPeriod}.0", config.thirdPeriod.toString())
        } else {
            listOf(config.firstPeriod, config.secondPeriod, config.thirdPeriod).map(Int::toString)
        }
        is KlineSarIndicatorConfig -> listOf(formatParameter(config.step), formatParameter(config.maximum))
        is KlineSuperTrendIndicatorConfig -> listOf(config.atrPeriod.toString(), formatParameter(config.multiplier))
        is KlineStochasticRsiIndicatorConfig ->
            listOf(config.rsiPeriod, config.stochasticPeriod, config.kPeriod, config.dPeriod).map(Int::toString)
        else -> emptyList()
    }
    val defaults = when (spec.id) {
        "compose_boll" -> listOf("20", "2.0")
        "compose_sar" -> listOf("0.02", "0.2")
        "compose_super" -> listOf("10", "3.0")
        "compose_macd" -> listOf("12", "26", "9")
        "compose_kdj" -> listOf("9", "3", "3")
        "compose_stoch_rsi" -> listOf("14", "14", "3", "3")
        else -> emptyList()
    }
    return List(schema.parameterLabels.size) { index ->
        configured.getOrNull(index) ?: defaults.getOrNull(index).orEmpty()
    }
}

private fun editorStyleLines(
    spec: IndicatorUiSpec,
    definition: IndicatorDefinition?,
    schema: IndicatorEditorSchema,
): List<EditableIndicatorLine> {
    val configuredStyles = when (val config = definition?.configuration) {
        is KlineMovingAverageIndicatorConfig -> config.lineStyles
        is KlineSinglePeriodIndicatorConfig -> config.lineStyles
        is KlineTriplePeriodIndicatorConfig -> config.lineStyles
        is KlineSarIndicatorConfig -> config.lineStyles
        is KlineSuperTrendIndicatorConfig -> config.lineStyles
        is KlineRsiIndicatorConfig -> List(schema.styleLabels.size) { index ->
            (when {
                index < config.periods.size -> config.lineStyles.getOrNull(index)
                index == 3 -> config.lineStyles.getOrNull(config.periods.size)
                index == 4 -> config.lineStyles.getOrNull(config.periods.size + 1)
                else -> null
            }) ?: KlineIndicatorLineStyle(
                color = schema.styleColors.getOrNull(index),
                visible = schema.defaultEnabled.getOrElse(index) { true },
            )
        }
        is KlineObvIndicatorConfig -> List(3) { index ->
            when (index) {
                0 -> config.lineStyles.getOrNull(0)
                1 -> config.maPeriod?.let { config.lineStyles.getOrNull(1) }
                else -> config.emaPeriod?.let { config.lineStyles.getOrNull(if (config.maPeriod != null) 2 else 1) }
            } ?: KlineIndicatorLineStyle(
                color = schema.styleColors.getOrNull(index),
                visible = index == 0,
            )
        }
        is KlineStochasticRsiIndicatorConfig -> config.lineStyles
        is KlineStyledIndicatorConfig -> config.lineStyles
        else -> emptyList()
    }
    val configuredStyleValues = when (val config = definition?.configuration) {
        is KlineMovingAverageIndicatorConfig -> config.periods.map(Int::toString)
        is KlineSinglePeriodIndicatorConfig -> listOf(config.period.toString())
        is KlineRsiIndicatorConfig -> List(schema.styleLabels.size) { index ->
            when {
                index < config.periods.size -> config.periods[index].toString()
                index == 3 -> formatParameter(config.upper)
                index == 4 -> formatParameter(config.lower)
                else -> schema.styleValues.getOrNull(index).orEmpty()
            }
        }
        is KlineObvIndicatorConfig -> listOf(
            "",
            config.maPeriod?.toString() ?: schema.styleValues.getOrNull(1).orEmpty(),
            config.emaPeriod?.toString() ?: schema.styleValues.getOrNull(2).orEmpty(),
        )
        else -> emptyList()
    }
    return List(schema.styleLabels.size) { index ->
        val enabled = if (definition == null) {
            schema.defaultEnabled.getOrElse(index) { true }
        } else when (spec.id) {
            "sample_ma", "compose_ema" -> index < configuredStyleValues.size
            else -> configuredStyles.getOrNull(index)?.visible ?: schema.defaultEnabled.getOrElse(index) { true }
        }
        val value = configuredStyleValues.getOrNull(index) ?: schema.styleValues.getOrNull(index).orEmpty()
        val style = configuredStyles.getOrNull(index)
        EditableIndicatorLine(
            enabled = enabled,
            value = value,
            widthPx = style?.widthPx ?: 1.25f,
            color = style?.color ?: schema.styleColors.getOrNull(index) ?: indicatorPalette[index % indicatorPalette.size],
        )
    }
}

private fun String.isValidParameterInput(): Boolean =
    length <= 7 && count { it == '.' } <= 1 && all { it.isDigit() || it == '.' }

private fun formatParameter(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

private fun <T> List<T>.replace(index: Int, value: T): List<T> = toMutableList().also { it[index] = value }
