package com.sotto.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sotto.BuildConfig
import com.sotto.MainViewModel
import com.sotto.Modem
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(vm: MainViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Settings", style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(20.dp))

            SwitchRow("Listening", if (vm.captureSource != null) "Mic on, ${vm.captureSource}" else "Off. Nothing arrives while off.", vm.wantListening) { vm.setListening(it) }
            Rule()

            SwitchRow("Choose protocol for me", if (vm.autoProtocol) "Fast for text, Near for photos" else "Everything on ${Modem.protocolName(vm.protocolId)}", vm.autoProtocol) { vm.autoProtocol = it }
            if (!vm.autoProtocol) {
                Spacer(Modifier.height(8.dp))
                ProtocolPicker(vm)
            }
            Rule()

            Caps("Loudness")
            val capped = vm.effectiveTxVolume != vm.txVolume
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = vm.txVolume.toFloat(), onValueChange = { vm.txVolume = it.roundToInt() }, valueRange = 5f..100f,
                    enabled = !vm.busy, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Text("${vm.txVolume}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(40.dp))
            }
            Text(
                if (capped) "ggwave protocols use ${vm.effectiveTxVolume}; their six-tone sum clips above that." else "Full is right for range. Turn it down only if it distorts.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Rule()

            Caps("Range test")
            Text(
                "Sends a fixed 20-byte message ten times, two seconds apart. The other phone counts what arrived, so you can score a spot or a protocol in one number.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (vm.burstSending) {
                    OutlinedButton(onClick = { vm.cancelBurst() }) { Text("Stop, ${vm.burstSent} of ${MainViewModel.BURST_COUNT} sent") }
                } else {
                    Button(
                        onClick = { vm.startBurst() }, enabled = !vm.busy,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onBackground, contentColor = MaterialTheme.colorScheme.background),
                    ) { Text("Send burst") }
                }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(if (vm.burstExpected == 0) "—" else "${vm.burstReceived} / ${vm.burstExpected}", style = MaterialTheme.typography.titleLarge)
                    Text("received here", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { vm.resetBurstCounter() }) { Text("Reset") }
            }
            Rule()

            Caps("Version")
            val u = vm.update
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (u != null) "Sotto ${u.version} is available" else "Sotto ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
                    val sub = when {
                        vm.updateProgress in 0..99 -> "Downloading, ${vm.updateProgress}%"
                        u != null && vm.updateProgress == 100 -> "Downloaded. Android will ask to confirm the install."
                        u != null && u.notes.isNotEmpty() -> u.notes
                        u != null -> "You have ${BuildConfig.VERSION_NAME}."
                        vm.updateChecking -> "Checking…"
                        else -> vm.updateNote ?: "Updates come straight from the project's releases."
                    }
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (u != null) vm.updateNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
                Spacer(Modifier.width(12.dp))
                if (u != null) {
                    Button(
                        onClick = { vm.installUpdate() }, enabled = vm.updateProgress !in 0..99,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                    ) { Text(if (vm.updateProgress == 100) "Install" else "Update") }
                } else {
                    OutlinedButton(onClick = { vm.checkForUpdates(manual = true) }, enabled = !vm.updateChecking) {
                        Text("Check", color = MaterialTheme.colorScheme.onBackground)
                    }
                }
            }
            Text(
                "Messages travel as sound between the phones' speakers and microphones. No server, no account, nothing leaves the room.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp),
            )
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/retrocodes12/sotto"))) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text("Source and how it works", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary, checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant, uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}

@Composable
private fun ProtocolPicker(vm: MainViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth(), enabled = !vm.busy) {
            Text(Modem.protocolName(vm.protocolId), modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onBackground)
            Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Modem.protocols.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.name, fontWeight = if (p.id == vm.protocolId) FontWeight.SemiBold else null) },
                    onClick = { vm.protocolId = p.id; open = false },
                )
            }
        }
    }
}

@Composable
private fun Caps(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun Rule() {
    HorizontalDivider(Modifier.padding(vertical = 18.dp), color = MaterialTheme.colorScheme.outline)
}
