package dev.duardo.neotransfer.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.designsystem.R
import dev.duardo.neotransfer.designsystem.theme.Neo
import dev.duardo.neotransfer.designsystem.theme.NeoMotion
import dev.duardo.neotransfer.designsystem.theme.NeoRadius
import dev.duardo.neotransfer.designsystem.theme.NeoSpace

/** Persistent search pill at the top of a list. */
@Composable
fun NeoSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    emptyAction: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val scheme = MaterialTheme.colorScheme
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = visualTransformation,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
        cursorBrush = SolidColor(Neo.colors.accentInk),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        decorationBox = { field ->
            Row(
                Modifier
                    .heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .background(Neo.colors.group)
                    .rim(CircleShape)
                    .padding(start = NeoSpace.l, end = NeoSpace.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NeoSpace.m),
            ) {
                Icon(painterResource(R.drawable.ic_search), null, Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) FittedText(placeholder, MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                    field()
                }
                when {
                    query.isNotEmpty() -> NeoIconButton(R.drawable.ic_close, "Borrar búsqueda", { onQueryChange("") }, tint = scheme.onSurfaceVariant)
                    emptyAction != null -> emptyAction()
                    else -> Box(Modifier.size(NeoSpace.m))
                }
            }
        },
    )
}

/**
 * Labeled field: the label sits above the box, never inside its border, and takes the accent while the field has
 * focus. [error] shows only when the value cannot be used and takes the place of [supporting].
 */
@Composable
fun NeoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    error: String? = null,
    supporting: String? = null,
    suffix: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = Neo.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val tone = when {
        error != null -> colors.danger
        focused -> colors.accentInk
        else -> null
    }
    val border by animateColorAsState(tone ?: colors.divider, tween(NeoMotion.short), label = "border")
    val labelColor by animateColorAsState(tone ?: scheme.onSurfaceVariant, tween(NeoMotion.short), label = "label")
    val shape = RoundedCornerShape(NeoRadius.field)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = textStyle.copy(color = scheme.onSurface),
        cursorBrush = SolidColor(colors.accentInk),
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        interactionSource = interaction,
        decorationBox = { field ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, Modifier.padding(start = NeoSpace.xxs), style = MaterialTheme.typography.labelMedium, color = labelColor, maxLines = 1)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clip(shape)
                        .background(colors.group)
                        .border(if (tone != null) 2.dp else 1.dp, border, shape)
                        .padding(start = NeoSpace.l, end = if (trailing != null) NeoSpace.xs else NeoSpace.l),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) { field() }
                    suffix?.let { Text(it, Modifier.padding(start = NeoSpace.xs), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant, maxLines = 1) }
                    trailing?.invoke()
                }
                (error ?: supporting)?.let {
                    Text(it, Modifier.padding(start = NeoSpace.xxs), style = MaterialTheme.typography.bodySmall, color = if (error != null) colors.danger else scheme.onSurfaceVariant)
                }
            }
        },
    )
}

/** Money typed with the system keypad, shown at the size of money: large tabular digits, the currency after them. */
@Composable
fun NeoAmountField(value: String, onValueChange: (String) -> Unit, currency: String, modifier: Modifier = Modifier, label: String = "Importe", error: String? = null) {
    NeoTextField(
        value, onValueChange, label, modifier,
        keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done, error = error, suffix = currency,
        textStyle = MaterialTheme.typography.headlineSmall,
    )
}

/** One of several pills (filters, saved references): rimmed like any raised surface at rest, accent-filled when chosen. */
@Composable
fun NeoChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        modifier = modifier,
        shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Neo.colors.group,
            labelColor = MaterialTheme.colorScheme.onSurface,
            selectedContainerColor = Neo.colors.accentContainer,
            selectedLabelColor = Neo.colors.onAccentContainer,
        ),
        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected, borderColor = Neo.colors.divider, selectedBorderColor = Color.Transparent),
    )
}

/** One choice among a few, all visible: theme, filters. */
@Composable
fun <T> NeoSegmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = {},
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = Neo.colors.accentContainer,
                    activeContentColor = Neo.colors.onAccentContainer,
                    activeBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    inactiveContainerColor = Neo.colors.group,
                    inactiveBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
                label = { Text(label(option), style = MaterialTheme.typography.labelLarge, maxLines = 1) },
            )
        }
    }
}

@Composable
fun NeoSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedTrackColor = Neo.colors.accent,
            checkedThumbColor = Neo.colors.onAccent,
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            uncheckedBorderColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
        ),
    )
}
