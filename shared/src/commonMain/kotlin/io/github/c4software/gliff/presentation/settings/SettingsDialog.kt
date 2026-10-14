package io.github.c4software.gliff.presentation.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.c4software.gliff.domain.machine.Machine
import io.github.c4software.gliff.domain.settings.Settings
import io.github.c4software.gliff.presentation.theme.Strings

/** What applies to every machine: the scale, how touch behaves, the web mirror. */
@Composable
fun SettingsDialog(
    settings: Settings,
    /** Where the web mirror answers; null hides its options, where there is no mirror. */
    mirrorUrls: List<String>?,
    strings: Strings,
    /** The platform has desktop windows, which a session can leave for the whole display. */
    desktop: Boolean = false,
    onChange: (Settings) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.options) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(strings.resolutionTitle, style = MaterialTheme.typography.titleSmall)
                    Text(
                        strings.resolutionExplain,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ResolutionPicker(settings.forcedWidth, settings.forcedHeight, inherit = false, strings) { width, height ->
                        onChange(settings.copy(forcedWidth = width, forcedHeight = height))
                    }
                }
                Toggle(strings.keyboardZoom, strings.keyboardZoomExplain, settings.keyboardZoom) {
                    onChange(settings.copy(keyboardZoom = it))
                }
                Toggle(strings.directTouch, strings.directTouchExplain, settings.directTouch) {
                    onChange(settings.copy(directTouch = it))
                }
                Toggle(strings.clipboard, strings.clipboardExplain, settings.clipboard) {
                    onChange(settings.copy(clipboard = it))
                }
                Toggle(strings.keepAwake, strings.keepAwakeExplain, settings.keepAwake) {
                    onChange(settings.copy(keepAwake = it))
                }
                if (desktop) {
                    Toggle(strings.desktopFullScreen, strings.desktopFullScreenExplain, settings.desktopFullScreen) {
                        onChange(settings.copy(desktopFullScreen = it))
                    }
                }
                if (mirrorUrls != null) {
                    Toggle(strings.httpMirror, strings.httpMirrorExplain, settings.httpMirror) {
                        onChange(settings.copy(httpMirror = it))
                    }
                }
                if (mirrorUrls != null && settings.httpMirror) {
                    Toggle(strings.mirrorControl, strings.mirrorControlExplain, settings.mirrorControl) {
                        onChange(settings.copy(mirrorControl = it))
                    }
                    Toggle(strings.mirrorDark, strings.mirrorDarkExplain, settings.mirrorDark) {
                        onChange(settings.copy(mirrorDark = it))
                    }
                    SelectionContainer {
                        Text(
                            mirrorUrls.joinToString("\n").ifEmpty { strings.httpMirrorNoNetwork },
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(strings.close) } },
    )
}

/**
 * A resolution: the window's own, one of the usual ones, or any typed by
 * hand. `inherit` adds the choice of leaving it to the options, for a machine.
 */
@Composable
internal fun ResolutionPicker(
    width: Int,
    height: Int,
    inherit: Boolean,
    strings: Strings,
    onChange: (width: Int, height: Int) -> Unit,
) {
    var custom by remember {
        mutableStateOf(width > 0 && height > 0 && Settings.RESOLUTIONS.none { it.first == width && it.second == height })
    }
    var typedWidth by remember { mutableStateOf(if (custom) width.toString() else "") }
    var typedHeight by remember { mutableStateOf(if (custom) height.toString() else "") }
    // Kept as it was until both sides make a resolution.
    fun typed() {
        val w = typedWidth.toIntOrNull()?.takeIf { it in Settings.SIDES } ?: return
        val h = typedHeight.toIntOrNull()?.takeIf { it in Settings.SIDES } ?: return
        onChange(w, h)
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val choices = listOfNotNull(
            if (inherit) strings.resolutionInherit to (Machine.INHERIT to Machine.INHERIT) else null,
            strings.scaleAuto to (0 to 0),
        ) + Settings.RESOLUTIONS.map { "${it.first} × ${it.second}" to it }
        for ((label, size) in choices) {
            FilterChip(
                !custom && size == (width to height),
                onClick = {
                    custom = false
                    onChange(size.first, size.second)
                },
                label = { Text(label) },
            )
        }
        FilterChip(
            custom,
            onClick = {
                custom = true
                typed()
            },
            label = { Text(strings.resolutionCustom) },
        )
    }
    if (custom) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (side in 0..1) {
                val text = if (side == 0) typedWidth else typedHeight
                OutlinedTextField(
                    value = text,
                    onValueChange = { new ->
                        val digits = new.filter(Char::isDigit).take(4)
                        if (side == 0) typedWidth = digits else typedHeight = digits
                        typed()
                    },
                    label = { Text(if (side == 0) strings.width else strings.height) },
                    singleLine = true,
                    isError = text.isNotEmpty() && text.toIntOrNull() !in Settings.SIDES,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                if (side == 0) Text("×")
            }
        }
    }
}

@Composable
private fun Toggle(title: String, explain: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(explain, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = onChange)
    }
}
