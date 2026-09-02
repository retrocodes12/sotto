package com.sotto

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlin.math.log10
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                SottoApp()
            }
        }
    }
}

@Composable
fun SottoApp(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    fun hasMic() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(hasMic()) }
    var deniedOnce by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) deniedOnce = true
    }

    // Re-check after the user comes back from Settings.
    LifecycleResumeEffect(Unit) {
        granted = hasMic()
        onPauseOrDispose { }
    }
    LifecycleStartEffect(Unit) {
        vm.onForeground()
        onStopOrDispose { vm.onBackground() }
    }

    Scaffold { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (granted) {
                MainScreen(vm)
            } else {
                PermissionScreen(
                    denied = deniedOnce,
                    onRequest = { launcher.launch(Manifest.permission.RECORD_AUDIO) },
                    onOpenSettings = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun PermissionScreen(denied: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Sotto", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(16.dp))
        if (denied) {
            Text(
                "Microphone access was denied. Sotto cannot receive messages without it: " +
                    "the other phone's message is sound, and the only way to hear it is the mic.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRequest, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Try again") }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Open app settings") }
        } else {
            Text(
                "Sotto sends messages as sound and hears them with the microphone. " +
                    "Audio is decoded on this phone and never leaves it.",
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRequest, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Allow microphone") }
        }
    }
}

@Composable
private fun MainScreen(vm: MainViewModel) {
    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshMediaVolume()
            delay(1000)
        }
    }
    // Keep the screen (and therefore the app and the mic) alive during range tests.
    val view = LocalView.current
    DisposableEffect(vm.wantListening) {
        view.keepScreenOn = vm.wantListening
        onDispose { view.keepScreenOn = false }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            item { Header() }
            item { ListeningCard(vm) }
            item { Spacer(Modifier.height(12.dp)) }
            item { ProtocolRow(vm) }
            item { Spacer(Modifier.height(12.dp)) }
            item { BurstCard(vm) }
            item { Spacer(Modifier.height(12.dp)) }
            vm.status?.let { item { Text(it, color = MaterialTheme.colorScheme.error); Spacer(Modifier.height(8.dp)) } }
            item {
                Text("Log", style = MaterialTheme.typography.titleMedium)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                if (vm.log.isEmpty()) Text("Nothing yet. Turn on Listening on the other phone and send.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(vm.log) { LogRow(it) }
        }
        if (vm.mediaVolume < LOW_VOLUME) VolumeWarning(vm)
        ComposeRow(vm)
    }
}

@Composable
private fun Header() {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Sotto", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(12.dp))
        Text("data over sound", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ListeningCard(vm: MainViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Listening", style = MaterialTheme.typography.titleMedium)
                    val sub = when {
                        !vm.wantListening -> "Off"
                        vm.captureSource == null -> "Starting…"
                        vm.transmitting -> "Paused while transmitting"
                        else -> "Mic source: ${vm.captureSource}"
                    }
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = vm.wantListening, onCheckedChange = { vm.setListening(it) })
            }
            Spacer(Modifier.height(12.dp))
            LevelMeter(vm.micLevel, active = vm.captureSource != null)
        }
    }
}

@Composable
private fun LevelMeter(rms: Float, active: Boolean) {
    val db = if (rms > 0f) 20f * log10(rms) else -96f
    val target = if (active) ((db + 60f) / 60f).coerceIn(0f, 1f) else 0f
    val level by animateFloatAsState(targetValue = target, label = "level")
    Row(verticalAlignment = Alignment.CenterVertically) {
        LinearProgressIndicator(progress = { level }, modifier = Modifier.weight(1f).height(10.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            if (active) "${db.roundToInt()} dB" else "— dB",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(56.dp),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun ProtocolRow(vm: MainViewModel) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text("Protocol", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().height(52.dp), enabled = !vm.busy) {
                Text(GgWave.protocolName(vm.protocolId), modifier = Modifier.weight(1f))
                Text("▾")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                GgWave.protocols.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p.name, fontWeight = if (p.id == vm.protocolId) FontWeight.Bold else null) },
                        onClick = { vm.protocolId = p.id; open = false },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tx amplitude ${vm.txVolume}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(130.dp))
            Slider(
                value = vm.txVolume.toFloat(),
                onValueChange = { vm.txVolume = it.roundToInt() },
                valueRange = 5f..100f,
                enabled = !vm.busy,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BurstCard(vm: MainViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Test burst", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (vm.burstSending) "Sending ${vm.burstSent} / ${MainViewModel.BURST_COUNT}"
                        else "${MainViewModel.BURST_COUNT} x 20 bytes, 2 s apart",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (vm.burstSending) {
                    OutlinedButton(onClick = { vm.cancelBurst() }, modifier = Modifier.height(48.dp)) { Text("Cancel") }
                } else {
                    Button(onClick = { vm.startBurst() }, enabled = !vm.busy, modifier = Modifier.height(48.dp)) { Text("Send burst") }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Received", style = MaterialTheme.typography.labelLarge)
                    Text(
                        if (vm.burstExpected == 0) "waiting for a burst…"
                        else "${vm.burstReceived} / ${vm.burstExpected}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                TextButton(onClick = { vm.resetBurstCounter() }) { Text("Reset") }
            }
        }
    }
}

@Composable
private fun LogRow(e: LogEntry) {
    val tint = when (e.kind) {
        LogEntry.Kind.RX -> MaterialTheme.colorScheme.primary
        LogEntry.Kind.TX -> MaterialTheme.colorScheme.tertiary
        LogEntry.Kind.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row {
            Text(e.time, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            val label = when (e.kind) {
                LogEntry.Kind.RX -> "IN  · ${e.protocol} · ${e.bytes} B"
                LogEntry.Kind.TX -> "OUT · ${e.protocol} · ${e.bytes} B"
                LogEntry.Kind.INFO -> "INFO"
            }
            Text(label, style = MaterialTheme.typography.bodySmall, color = tint, fontWeight = FontWeight.SemiBold)
        }
        Text(e.text, style = if (e.kind == LogEntry.Kind.INFO) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun VolumeWarning(vm: MainViewModel) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Media volume is ${(vm.mediaVolume * 100).roundToInt()}%. Raise it to 70% or more before sending.",
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { vm.showVolumePanel() }) { Text("Volume") }
    }
}

@Composable
private fun ComposeRow(vm: MainViewModel) {
    val over = vm.draftBytes > MainViewModel.MAX_BYTES
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            OutlinedTextField(
                value = vm.draft,
                onValueChange = { vm.draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                isError = over,
                maxLines = 3,
                supportingText = {
                    Text(
                        "${vm.draftBytes} / ${MainViewModel.MAX_BYTES} bytes",
                        color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = { vm.send() },
                enabled = vm.canSend,
                modifier = Modifier.padding(top = 8.dp).height(56.dp),
            ) {
                Text(if (vm.transmitting) "Sending…" else "Send")
            }
        }
    }
}

private const val LOW_VOLUME = 0.7f
