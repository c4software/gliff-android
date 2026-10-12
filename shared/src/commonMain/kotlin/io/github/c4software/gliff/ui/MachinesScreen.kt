package io.github.c4software.gliff.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.c4software.gliff.client.Identity
import io.github.c4software.gliff.client.Machine
import io.github.c4software.gliff.client.MachineRepository
import io.github.c4software.gliff.client.Settings
import io.github.c4software.gliff.client.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/** The saved machines: open one, add, edit, remove, and show this device's key. */
@Composable
fun MachinesScreen(
    machines: MachineRepository,
    identity: Identity,
    onOpen: (Machine) -> Unit,
    settings: SettingsRepository,
    /** The web mirror's addresses for a token, when it is on; null where there is no mirror. */
    mirrorUrls: ((token: String) -> List<String>)? = null,
    strings: Strings = rememberStrings(),
) {
    val list by machines.machines.collectAsState()
    val current by settings.settings.collectAsState()
    var showOptions by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Machine?>(null) }
    var adding by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }

    MachinesContent(
        machines = list,
        strings = strings,
        onOpen = onOpen,
        onAdd = { adding = true },
        onEdit = { editing = it },
        onDelete = { machines.remove(it.id) },
        onShowKey = { showKey = true },
        onShowOptions = { showOptions = true },
    )

    if (showOptions) {
        SettingsDialog(
            settings = current,
            mirrorUrls = mirrorUrls?.let { if (current.httpMirror) it(current.mirrorToken) else emptyList() },
            strings = strings,
            onChange = { changed -> settings.update { changed } },
            onDismiss = { showOptions = false },
        )
    }
    if (adding || editing != null) {
        MachineDialog(
            initial = editing,
            strings = strings,
            onDismiss = {
                adding = false
                editing = null
            },
            onSave = {
                machines.save(it)
                adding = false
                editing = null
            },
        )
    }
    if (showKey) {
        val key by produceState<String?>(null) { value = withContext(Dispatchers.IO) { identity.authorizedKey() } }
        KeyDialog(key, strings) { showKey = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MachinesContent(
    machines: List<Machine>,
    strings: Strings,
    onOpen: (Machine) -> Unit,
    onAdd: () -> Unit,
    onEdit: (Machine) -> Unit,
    onDelete: (Machine) -> Unit,
    onShowKey: () -> Unit,
    onShowOptions: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.appName) },
                actions = {
                    TextButton(onClick = onShowOptions) { Text(strings.options) }
                    TextButton(onClick = onShowKey) { Text(strings.sshKey) }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAdd) { Text(strings.addMachine) }
        },
    ) { padding ->
        if (machines.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 480.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(strings.noMachines, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        strings.firstSteps,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start,
                    )
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(machines, key = { it.id }) { machine ->
                    MachineRow(machine, strings, onOpen, onEdit, onDelete)
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun MachineRow(
    machine: Machine,
    strings: Strings,
    onOpen: (Machine) -> Unit,
    onEdit: (Machine) -> Unit,
    onDelete: (Machine) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val mode = when {
        machine.headless -> strings.privateScreen
        machine.output.isBlank() -> strings.mirror
        else -> strings.mirrorOf(machine.output.trim())
    }
    ListItem(
        modifier = Modifier.clickable { onOpen(machine) },
        headlineContent = { Text(machine.address) },
        supportingContent = {
            Text("$mode · " + (machine.hostKey?.let(::shortFingerprint) ?: strings.hostKeyNotSeen))
        },
        trailingContent = {
            Box {
                TextButton(onClick = { menu = true }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(strings.edit) }, onClick = {
                        menu = false
                        onEdit(machine)
                    })
                    DropdownMenuItem(text = { Text(strings.delete) }, onClick = {
                        menu = false
                        onDelete(machine)
                    })
                }
            }
        },
    )
}

/** `SHA256:abcdefgh…wxyz`: enough to recognise a key at a glance. */
internal fun shortFingerprint(fingerprint: String): String {
    val body = fingerprint.substringAfter(':')
    return if (body.length <= 14) fingerprint else "SHA256:${body.take(8)}…${body.takeLast(4)}"
}

@Composable
fun MachineDialog(initial: Machine?, strings: Strings, onDismiss: () -> Unit, onSave: (Machine) -> Unit) {
    var address by remember { mutableStateOf(initial?.address ?: "") }
    var headless by remember { mutableStateOf(initial?.headless ?: true) }
    var output by remember { mutableStateOf(initial?.output ?: "") }
    var serverBin by remember { mutableStateOf(initial?.serverBin ?: Machine.DEFAULT_SERVER_BIN) }
    var showError by remember { mutableStateOf(false) }
    val parsed = Machine.parseAddress(address)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) strings.addMachine else strings.editMachine) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text(strings.address) },
                    placeholder = { Text(strings.addressHint) },
                    singleLine = true,
                    isError = showError && parsed == null,
                    supportingText = if (showError && parsed == null) {
                        { Text(strings.addressError) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(headless, onClick = { headless = true }, label = { Text(strings.privateScreen) })
                    FilterChip(!headless, onClick = { headless = false }, label = { Text(strings.mirror) })
                }
                if (!headless) {
                    OutlinedTextField(
                        value = output,
                        onValueChange = { output = it },
                        label = { Text(strings.outputName) },
                        placeholder = { Text(strings.outputHint) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = serverBin,
                    onValueChange = { serverBin = it },
                    label = { Text(strings.serverPath) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val (user, host, port) = parsed ?: run {
                    showError = true
                    return@TextButton
                }
                val base = initial ?: Machine(user = user, host = host)
                // Another host is another machine: its key has to be seen again.
                val sameHost = base.host == host && base.port == port
                onSave(
                    base.copy(
                        user = user,
                        host = host,
                        port = port,
                        headless = headless,
                        output = output.trim(),
                        serverBin = serverBin.trim().ifEmpty { Machine.DEFAULT_SERVER_BIN },
                        hostKey = if (sameHost) base.hostKey else null,
                    )
                )
            }) { Text(strings.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}

@Composable
fun KeyDialog(key: String?, strings: Strings, onDismiss: () -> Unit) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.keyTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(strings.keyExplain)
                SelectionContainer {
                    Text(
                        key ?: strings.keyLoading,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = key != null, onClick = {
                clipboard.setText(AnnotatedString(key.orEmpty()))
                copied = true
            }) { Text(if (copied) strings.copied else strings.copy) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.close) } },
    )
}

/** What applies to every machine: the scale, how touch behaves, the web mirror. */
@Composable
fun SettingsDialog(
    settings: Settings,
    /** Where the web mirror answers; null hides its options, where there is no mirror. */
    mirrorUrls: List<String>?,
    strings: Strings,
    onChange: (Settings) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.options) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(strings.scaleTitle, style = MaterialTheme.typography.titleSmall)
                    Text(
                        strings.scaleExplain,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(settings.scale == 0f, onClick = { onChange(settings.copy(scale = 0f)) }, label = { Text(strings.scaleAuto) })
                        for (scale in Settings.SCALES) {
                            FilterChip(
                                settings.scale == scale,
                                onClick = { onChange(settings.copy(scale = scale)) },
                                label = { Text("${(scale * 100).toInt()} %") },
                            )
                        }
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
                if (mirrorUrls != null) {
                    Toggle(strings.httpMirror, strings.httpMirrorExplain, settings.httpMirror) {
                        onChange(settings.copy(httpMirror = it))
                    }
                }
                if (mirrorUrls != null && settings.httpMirror) {
                    Toggle(strings.mirrorControl, strings.mirrorControlExplain, settings.mirrorControl) {
                        onChange(settings.copy(mirrorControl = it))
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
