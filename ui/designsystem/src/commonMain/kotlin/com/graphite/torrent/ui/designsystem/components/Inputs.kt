package com.graphite.torrent.ui.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.tooling.preview.Preview
import com.graphite.torrent.ui.designsystem.icons.GraphiteIcons
import com.graphite.torrent.ui.designsystem.theme.GraphiteTheme

/** Outlined text field with 12dp corners and Graphite colours. */
@Composable
fun GraphiteTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    password: Boolean = false,
    enabled: Boolean = true,
    minLines: Int = 1,
) {
    val colors = GraphiteTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        label = label?.let { { Text(it, style = GraphiteTheme.typography.caption) } },
        placeholder = placeholder?.let { { Text(it, style = GraphiteTheme.typography.body, color = colors.textDisabled) } },
        leadingIcon = leadingIcon?.let { { Icon(it, null, tint = colors.textSecondary, modifier = Modifier.size(GraphiteTheme.dimens.icon)) } },
        trailingIcon = trailing,
        singleLine = singleLine,
        minLines = minLines,
        isError = isError,
        supportingText = supportingText?.let {
            { Text(it, style = GraphiteTheme.typography.caption, color = if (isError) colors.errorText else colors.textSecondary) }
        },
        textStyle = GraphiteTheme.typography.body.copy(color = colors.textPrimary),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
        shape = GraphiteTheme.shapes.control,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colors.textPrimary,
            unfocusedTextColor = colors.textPrimary,
            disabledTextColor = colors.textDisabled,
            focusedContainerColor = colors.surface1,
            unfocusedContainerColor = colors.surface1,
            disabledContainerColor = colors.surface1,
            cursorColor = colors.accentBright,
            focusedBorderColor = colors.accent,
            unfocusedBorderColor = colors.border,
            disabledBorderColor = colors.border,
            errorBorderColor = colors.error,
            focusedLabelColor = colors.textSecondary,
            unfocusedLabelColor = colors.textSecondary,
            errorLabelColor = colors.errorText,
            errorCursorColor = colors.errorText,
        ),
    )
}

/** Search field used above lists and in settings. */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    clearLabel: String,
    modifier: Modifier = Modifier,
) {
    GraphiteTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = placeholder,
        leadingIcon = GraphiteIcons.Search,
        imeAction = ImeAction.Search,
        trailing = if (query.isNotEmpty()) {
            { GraphiteIconButton(GraphiteIcons.X, clearLabel, { onQueryChange("") }, size = 40.dp) }
        } else {
            null
        },
    )
}

@Composable
fun GraphiteSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = GraphiteTheme.colors
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = colors.onAccent,
            checkedTrackColor = colors.accent,
            checkedBorderColor = colors.accent,
            uncheckedThumbColor = colors.textSecondary,
            uncheckedTrackColor = colors.surface2,
            uncheckedBorderColor = colors.borderStrong,
            disabledCheckedTrackColor = colors.surface3,
            disabledUncheckedTrackColor = colors.surface1,
        ),
    )
}

@Composable
fun GraphiteCheckbox(state: ToggleableState, onClick: (() -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = GraphiteTheme.colors
    TriStateCheckbox(
        state = state,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = CheckboxDefaults.colors(
            checkedColor = colors.accent,
            uncheckedColor = colors.borderStrong,
            checkmarkColor = colors.onAccent,
            disabledCheckedColor = colors.textDisabled,
            disabledUncheckedColor = colors.border,
        ),
    )
}

@Composable
fun GraphiteSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val colors = GraphiteTheme.colors
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        colors = SliderDefaults.colors(
            thumbColor = colors.accentBright,
            activeTrackColor = colors.accent,
            inactiveTrackColor = colors.border,
            activeTickColor = colors.onAccent,
            inactiveTickColor = colors.borderStrong,
        ),
    )
}

/**
 * One setting: title, short explanation and a trailing control. The whole row is the touch target
 * for switches, so the setting can be toggled anywhere on the row.
 */
@Composable
fun SettingRow(
    title: String,
    description: String?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = GraphiteTheme.colors
    val clickModifier = if (onClick != null) {
        Modifier.graphiteClickable(GraphiteTheme.shapes.control, enabled = enabled, baseColor = colors.surface1, onClick = onClick)
    } else {
        Modifier
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = GraphiteTheme.dimens.minTouchTarget)
            .then(clickModifier)
            .padding(horizontal = GraphiteTheme.spacing.sm, vertical = GraphiteTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = colors.textSecondary, modifier = Modifier.size(GraphiteTheme.dimens.icon))
            Spacer(Modifier.width(GraphiteTheme.spacing.sm))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = GraphiteTheme.typography.body, color = if (enabled) colors.textPrimary else colors.textDisabled)
            if (!description.isNullOrBlank()) {
                Text(description, style = GraphiteTheme.typography.caption, color = colors.textSecondary)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(GraphiteTheme.spacing.sm))
            trailing()
        }
    }
}

/** Setting row with a switch; the whole row toggles. */
@Composable
fun SwitchSettingRow(
    title: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = GraphiteTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = GraphiteTheme.dimens.minTouchTarget)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = GraphiteTheme.spacing.sm, vertical = GraphiteTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = colors.textSecondary, modifier = Modifier.size(GraphiteTheme.dimens.icon))
            Spacer(Modifier.width(GraphiteTheme.spacing.sm))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = GraphiteTheme.typography.body, color = if (enabled) colors.textPrimary else colors.textDisabled)
            if (!description.isNullOrBlank()) Text(description, style = GraphiteTheme.typography.caption, color = colors.textSecondary)
        }
        Spacer(Modifier.width(GraphiteTheme.spacing.sm))
        GraphiteSwitch(checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Group of settings inside a card with a header. */
@Composable
fun SettingsGroup(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth()) {
        SectionHeader(title, Modifier.padding(horizontal = GraphiteTheme.spacing.xs))
        GraphiteCard(contentPadding = GraphiteTheme.spacing.xxs) { content() }
    }
}

/** A key/value line used in the "Overview" tab and statistics. */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier, monospace: Boolean = true) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) { stateDescription = value },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = GraphiteTheme.typography.body, color = GraphiteTheme.colors.textSecondary, modifier = Modifier.weight(0.45f))
        Spacer(Modifier.width(GraphiteTheme.spacing.sm))
        Text(
            value,
            style = if (monospace) GraphiteTheme.typography.numberBody else GraphiteTheme.typography.body,
            color = GraphiteTheme.colors.textPrimary,
            modifier = Modifier.weight(0.55f),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Preview
@Composable
private fun InputsPreview() {
    GraphiteTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            var text by remember { mutableStateOf("magnet:?xt=urn:btih:…") }
            GraphiteTextField(text, { text = it }, label = "Ссылка", leadingIcon = GraphiteIcons.Link)
            SearchField("", {}, "Поиск", "Очистить")
            SettingsGroup("Сеть") {
                var on by remember { mutableStateOf(true) }
                SwitchSettingRow("DHT", "Поиск пиров без трекера", on, { on = it })
                SettingRow("Порт", "Выбирается автоматически", onClick = {}) { Text("51413", style = GraphiteTheme.typography.numberBody) }
            }
            GraphiteSlider(0.4f, {})
            GraphiteCheckbox(ToggleableState.Indeterminate, {})
            InfoRow("Хэш", "c9e15763f722f23e98a29decdfae341b98d53056")
        }
    }
}
