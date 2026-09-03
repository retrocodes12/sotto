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
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
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

            Caps("You")
            var nameDraft by remember { mutableStateOf(vm.identity.name) }
            val focus = LocalFocusManager.current
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = nameDraft, onValueChange = { if (it.toByteArray().size <= 24) nameDraft = it },
                    modifier = Modifier.weight(1f), singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium, shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { vm.setName(nameDraft); focus.clearFocus() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
                Spacer(Modifier.width(12.dp))
                Text(vm.identity.tag, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (nameDraft.trim().isNotEmpty() && nameDraft.trim() != vm.identity.name) {
                TextButton(onClick = { vm.setName(nameDraft); focus.clearFocus() }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text("Save name", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(
                "Others see your name over your messages. The tag never changes. Phones announce themselves when opened and chirp three bytes when they have been quiet for a minute; anyone heard in the last three minutes counts as nearby. Tap a name above the messages for a private chat: the phones swap keys by sound once, and nobody else can read what follows.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
            )
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.sayHello() }, enabled = !vm.busy && vm.identity.name.isNotEmpty()) { Text("Say hello", color = MaterialTheme.colorScheme.onBackground) }
            }
            val people = vm.identity.contacts.entries.sortedByDescending { it.value.lastHeard }
            if (people.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                val near = vm.nearby.toSet()
                val far = vm.farther.toSet()
                Caps("Heard · ${near.size} nearby" + if (far.isNotEmpty()) " · ${far.size} farther" else "")
                for ((id, c) in people) {
                    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.width(8.dp).height(8.dp).clip(CircleShape).background(
                                when (id) { in near -> MaterialTheme.colorScheme.primary; in far -> MaterialTheme.colorScheme.onSurfaceVariant; else -> MaterialTheme.colorScheme.outline }
                            )
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(c.name.ifEmpty { "someone" }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(com.sotto.IdentityStore.tagOf(id), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Text(ago(c.lastHeard), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Rule()

            SwitchRow(
                "Repeat for others",
                if (vm.relayForOthers) "Messages this phone hears are played again once, so phones out of the sender's reach still get them." else "This phone keeps what it hears to itself.",
                vm.relayForOthers,
            ) { vm.relayForOthers = it }
            Rule()

            SwitchRow("Listening", if (vm.captureSource != null) "Mic on, ${vm.captureSource}" else "Off. Nothing arrives while off.", vm.wantListening) { vm.setListening(it) }
            Spacer(Modifier.height(14.dp))
            SwitchRow(
                "Keep listening in the background",
                if (vm.listenInBackground) "Screen off or another app in front, messages still arrive. A quiet notification shows while the mic is open." else "Listening stops when Sotto leaves the screen.",
                vm.listenInBackground,
            ) { vm.keepListeningInBackground(it) }
            Rule()

            SwitchRow(
                "Choose protocol for me",
                if (vm.autoProtocol) "${if (vm.silentText) "Ultrasound" else "Fast"} for messages, Near for photos" else "Everything on ${Modem.protocolName(vm.protocolId)}",
                vm.autoProtocol,
            ) { vm.autoProtocol = it }
            if (vm.autoProtocol) {
                Spacer(Modifier.height(14.dp))
                SwitchRow(
                    "Silent messages",
                    if (vm.silentText) "18–19.5 kHz, above hearing. About 2 m in a room; 5 bytes take 2 s, 20 bytes 4 s." else "Audible 2–8 kHz band. Farther and faster, but everyone hears the chirp.",
                    vm.silentText,
                ) { vm.silentText = it }
                Text(
                    "Photos always use Near, which is audible: four tones at once, at arm's length.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                )
            } else {
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

            Caps("History")
            var confirmClear by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${vm.log.count { it.kind != com.sotto.LogEntry.Kind.INFO }} messages kept on this phone. Nothing is stored anywhere else.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = { if (confirmClear) { vm.clearHistory(); confirmClear = false } else confirmClear = true }) {
                    Text(if (confirmClear) "Really clear" else "Clear", color = if (confirmClear) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground)
                }
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

private fun ago(t: Long): String {
    val s = (System.currentTimeMillis() - t) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} h ago"
        else -> "${s / 86400} d ago"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReachSheet(vm: MainViewModel, r: MainViewModel.Reach) {
    ModalBottomSheet(onDismissRequest = { vm.dismissReach() }, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Reach", style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                if (r.running) "Probe ${r.probesSent} of ${MainViewModel.REACH_PROBES}. Every phone that hears one answers with how loudly it arrived."
                else if (r.heard.isEmpty()) "Nobody answered. Either no one is listening, or you are out of reach for ${if (vm.silentText) "silent" else "audible"} messages."
                else "Done. Each bar is one phone, from how loudly the probes arrived in both directions.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            for ((id, snrs) in r.heard.entries.sortedByDescending { it.value.sorted()[it.value.size / 2] }) {
                val (bar, verdict) = vm.reachVerdict(snrs)
                Text(vm.identity.nameFor(id) ?: "", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth(bar).height(6.dp).background(MaterialTheme.colorScheme.primary))
                }
                Spacer(Modifier.height(6.dp))
                Text("${snrs.size / 2} of ${r.probesSent} probes answered, $verdict", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
            }
            if (!r.running) {
                val weak = r.heard.values.any { vm.reachVerdict(it).first < 0.45f } || r.heard.isEmpty()
                Row {
                    if (weak && vm.silentText && vm.autoProtocol) {
                        Button(
                            onClick = { vm.silentText = false; vm.dismissReach() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                        ) { Text("Switch to audible") }
                        Spacer(Modifier.width(12.dp))
                    }
                    OutlinedButton(onClick = { vm.dismissReach() }) { Text("Close", color = MaterialTheme.colorScheme.onBackground) }
                }
            }
        }
    }
}
