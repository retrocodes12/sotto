package com.sotto.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import com.sotto.Wire
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sotto.LogEntry
import com.sotto.MainViewModel
import com.sotto.Modem
import kotlinx.coroutines.delay
import kotlin.math.log10

@Composable
fun ConversationScreen(vm: MainViewModel) {
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.ensureListening()
        while (true) { vm.refreshMediaVolume(); delay(1000) }
    }

    val chat = vm.openChat
    BackHandler(enabled = chat != null) { vm.openChat(null) }
    val shown = vm.log.filter { it.peer == chat }

    // Scaffold's own inset padding already covers the navigation bar, and imePadding covers it
    // again once the keyboard is up, which left a dead band above the keyboard. Take the insets
    // once, here, from safeDrawing -- which is the system bars, the cutout and the IME together.
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).safeDrawingPadding()) {
            Header(vm, onSettings = { showSettings = true })
            if (vm.chatPeers.isNotEmpty()) ChatChips(vm)
            if (shown.isEmpty()) {
                if (chat == null) EmptyState(Modifier.weight(1f), vm.nearby.mapNotNull { vm.identity.nameFor(it) })
                else PrivateEmpty(Modifier.weight(1f), vm.identity.nameFor(chat) ?: "", vm.hasKeyFor(chat))
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(shown, key = { it.id }) { MessageTile(it, if (chat == null) vm.identity.nameFor(it.senderId) else null, it.via?.let { v -> vm.identity.nameFor(v) }) }
                }
            }
            vm.status?.let { Note(it, error = true) }
            vm.update?.let { u -> UpdateNudge(u.version, onOpen = { showSettings = true }) }
            if (vm.mediaVolume < 0.7f) VolumeNudge(vm)
            ComposeBar(vm)
        }
    }
    if (showSettings) SettingsSheet(vm, onDismiss = { showSettings = false })
    vm.reach?.let { ReachSheet(vm, it) }
}

@Composable
private fun Header(vm: MainViewModel, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            val chat = vm.openChat
            if (chat == null) {
                Text("sotto", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.onBackground)
                StatusLine(vm)
            } else {
                Text(vm.identity.nameFor(chat) ?: "", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onBackground)
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (vm.hasKeyFor(chat)) "private · their key ${vm.fingerprintOf(chat)} · yours ${vm.myFingerprint}" else "getting ${vm.identity.nameFor(chat)}'s key by sound…",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (vm.pendingKeys.containsKey(chat)) {
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Their key changed. Compare fingerprints out loud first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.acceptNewKey(chat) }) { Text("Accept", color = MaterialTheme.colorScheme.onBackground) }
                        TextButton(onClick = { vm.rejectNewKey(chat) }) { Text("Ignore", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        IconButton(onClick = { vm.startReach() }, enabled = !vm.busy, modifier = Modifier.padding(top = 6.dp)) {
            Icon(Icons.Outlined.Radar, contentDescription = "How's my reach?", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onSettings, modifier = Modifier.padding(top = 6.dp)) {
            Icon(Icons.Outlined.Tune, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusLine(vm: MainViewModel) {
    val active = vm.captureSource != null && vm.wantListening
    val db = if (vm.micLevel > 0f) 20f * log10(vm.micLevel) else -96f
    // Quantised to twentieths before it becomes an animation target. Feeding it the raw level
    // twelve times a second restarted the animation twelve times a second, so the whole app
    // never reached an idle frame while the meter was on screen.
    val target = if (active) (((db + 60f) / 60f).coerceIn(0f, 1f) * 20f).toInt() / 20f else 0f
    val level by animateFloatAsState(targetValue = target, label = "level")
    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (active) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline))
        Spacer(Modifier.width(8.dp))
        Text(vm.statusLine, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.width(56.dp).height(2.dp).background(MaterialTheme.colorScheme.outline)) {
            Box(Modifier.fillMaxWidth(level).height(2.dp).background(MaterialTheme.colorScheme.onSurfaceVariant))
        }
    }
}

@Composable
private fun ChatChips(vm: MainViewModel) {
    val near = vm.nearby.toSet()
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Room", selected = vm.openChat == null, badge = vm.roomUnread, dot = false) { vm.openChat(null) }
        for (id in vm.chatPeers) {
            Chip(vm.identity.nameFor(id) ?: "", selected = vm.openChat == id, badge = vm.unread[id] ?: 0, dot = id in near) { vm.openChat(id) }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, badge: Int, dot: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) { Box(Modifier.size(6.dp).clip(CircleShape).background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)); Spacer(Modifier.width(6.dp)) }
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
        if (badge > 0) {
            Spacer(Modifier.width(6.dp))
            Box(Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primary).padding(horizontal = 6.dp, vertical = 1.dp)) {
                Text("$badge", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun PrivateEmpty(modifier: Modifier, name: String, hasKey: Boolean) {
    Column(modifier.fillMaxWidth().padding(horizontal = 40.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(12.dp))
        Text(if (hasKey) "Private with $name." else "Swapping keys with $name.", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            if (hasKey) "Messages here are encrypted to $name's phone. Other phones, even ones repeating them, cannot read them."
            else "The two phones exchange keys by sound once, about six seconds each way. Sending unlocks when $name's key has arrived.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier, nearby: List<String>) {
    Column(modifier.fillMaxWidth().padding(horizontal = 40.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (nearby.isEmpty()) "Nobody nearby yet." else "${nearby.joinToString(", ")} ${if (nearby.size == 1) "is" else "are"} nearby.", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            if (nearby.isEmpty()) "Open Sotto on the other phone too. Phones find each other by sound within a minute or so. What you send plays as a sound too high to hear."
            else "Say something. It plays as a sound too high to hear, and shows up on their phone.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MessageTile(e: LogEntry, sender: String?, via: String?) {
    if (e.kind == LogEntry.Kind.INFO) {
        Note(e.progress?.let { "${e.text}, $it" } ?: e.text)
        return
    }
    val out = e.kind == LogEntry.Kind.TX
    val tile = if (out) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val ink = if (out) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(
        topStart = 18.dp, topEnd = 18.dp,
        bottomStart = if (out) 18.dp else 6.dp, bottomEnd = if (out) 6.dp else 18.dp,
    )
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (out) Alignment.End else Alignment.Start) {
        if (!out && sender != null) {
            Text(sender, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 3.dp))
        }
        Surface(color = tile, shape = shape, modifier = Modifier.widthIn(max = 300.dp)) {
            Column(Modifier.padding(if (e.image != null) 6.dp else 12.dp)) {
                e.image?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(), contentDescription = "photo", contentScale = ContentScale.Fit,
                        modifier = Modifier.width(240.dp).aspectRatio(bmp.width.toFloat() / bmp.height.coerceAtLeast(1)).clip(RoundedCornerShape(14.dp)),
                    )
                }
                if (e.card != null) {
                    CardBody(e.card, ink)
                } else if (e.text.isNotEmpty()) {
                    Text(e.text, style = MaterialTheme.typography.bodyLarge, color = ink,
                        modifier = Modifier.padding(if (e.image != null) 8.dp else 0.dp))
                }
                e.fraction?.let { f ->
                    val p by animateFloatAsState(targetValue = f, label = "progress")
                    LinearProgressIndicator(
                        progress = { p }, modifier = Modifier.fillMaxWidth().padding(horizontal = if (e.image != null) 6.dp else 0.dp, vertical = 8.dp).height(3.dp),
                        color = ink, trackColor = ink.copy(alpha = 0.18f),
                    )
                }
            }
        }
        val caption = buildString {
            append(e.time.substring(0, 5))
            if (e.peer != null) append(" · private")
            if (e.kind == LogEntry.Kind.TX && e.peer != null && e.deliveredAfter == null) append(if (e.delivered) " · delivered" else " · sent")
            append(carryCaption(e))
            via?.let { append(" · via "); append(it) }
            e.progress?.let { append(" · "); append(it) } ?: run {
                if (e.bytes > 0) { append(" · "); append(if (e.bytes >= 1000) "%.1f KB".format(e.bytes / 1000f) else "${e.bytes} B") }
                if (e.protocol.startsWith("ggwave")) { append(" · "); append(e.protocol) }
            }
        }
        Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp, start = 4.dp, end = 4.dp))
    }
}

@Composable
private fun Note(text: String, error: Boolean = false) {
    Text(
        text, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
    )
}

@Composable
private fun UpdateNudge(version: String, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Sotto $version is out. Both phones should run the same version.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text("Update", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun VolumeNudge(vm: MainViewModel) {
    Row(
        Modifier.fillMaxWidth().clickable { vm.showVolumePanel() }.padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Media volume is ${(vm.mediaVolume * 100).toInt()}%. Louder reaches farther.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
        )
        Text("Turn up", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun ComposeBar(vm: MainViewModel) {
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.sendPhoto(uri) }
    val over = vm.draftBytes > MainViewModel.MAX_BYTES
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)) {
        val secs = vm.draftSeconds
        if (secs != null) {
            Text(
                if (over) "${vm.draftBytes} / ${MainViewModel.MAX_BYTES} bytes, too long"
                else if (vm.draftBytes > 80) "${vm.draftBytes} / ${MainViewModel.MAX_BYTES} bytes · about ${secs.toInt().coerceAtLeast(1)} s"
                else "about ${secs.toInt().coerceAtLeast(1)} s of sound",
                style = MaterialTheme.typography.bodySmall,
                color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End).padding(end = 64.dp, bottom = 4.dp),
            )
        }
        var menu by remember { mutableStateOf(false) }
        var dialog by remember { mutableStateOf(0) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconButton(onClick = { menu = true }, enabled = !vm.busy, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Add, contentDescription = "Share", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Photo") }, leadingIcon = { Icon(Icons.Outlined.Image, null) }, onClick = { menu = false; pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                    DropdownMenuItem(text = { Text("Link") }, onClick = { menu = false; dialog = Wire.CARD_LINK })
                    DropdownMenuItem(text = { Text("Wi-Fi network") }, onClick = { menu = false; dialog = Wire.CARD_WIFI })
                    DropdownMenuItem(text = { Text("Contact") }, onClick = { menu = false; dialog = Wire.CARD_CONTACT })
                }
            }
            if (dialog != 0) CardDialog(kind = dialog, onDismiss = { dialog = 0 }, onSend = { f -> vm.sendCard(dialog, f); dialog = 0 })
            TextField(
                value = vm.draft, onValueChange = { vm.draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(vm.openChat?.let { "Private to ${vm.identity.nameFor(it)}" } ?: "Message", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                textStyle = MaterialTheme.typography.bodyLarge,
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
            Spacer(Modifier.width(8.dp))
            val can = vm.canSend
            Box(
                Modifier.size(48.dp).clip(CircleShape)
                    .background(if (can) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = can) { vm.send() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.Send, contentDescription = "Send",
                    tint = if (can) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun CardDialog(kind: Int, onDismiss: () -> Unit, onSend: (List<String>) -> Unit) {
    val labels = when (kind) {
        Wire.CARD_LINK -> listOf("Link")
        Wire.CARD_WIFI -> listOf("Network name", "Password")
        else -> listOf("Name", "Phone", "Email")
    }
    val values = remember { labels.map { mutableStateOf("") } }
    val ok = values[0].value.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        title = { Text(when (kind) { Wire.CARD_LINK -> "Share a link"; Wire.CARD_WIFI -> "Share Wi-Fi"; else -> "Share a contact" }, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                labels.forEachIndexed { i, l ->
                    OutlinedTextField(value = values[i].value, onValueChange = { values[i].value = it }, label = { Text(l) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                }
                Text("Goes out on Near, at arm's length, in about a second. Anyone within reach can hear it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onSend(values.map { it.value }) }, enabled = ok) { Text("Send", color = MaterialTheme.colorScheme.onBackground) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
    )
}

@Composable
private fun CardBody(card: LogEntry.Card, ink: Color) {
    val context = LocalContext.current
    val f = card.fields
    when (card.kind) {
        Wire.CARD_LINK -> {
            val url = f.getOrElse(0) { "" }
            Text(url, style = MaterialTheme.typography.bodyLarge, color = ink)
            ActionLine("Open", ink) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(if (url.contains("://")) url else "https://$url"))) } }
        }
        Wire.CARD_WIFI -> {
            Text("Wi-Fi  ${f.getOrElse(0) { "" }}", style = MaterialTheme.typography.bodyLarge, color = ink)
            if (f.getOrElse(1) { "" }.isNotEmpty()) Text("password  ${f[1]}", style = MaterialTheme.typography.bodyMedium, color = ink.copy(alpha = 0.8f))
            Row {
                if (f.getOrElse(1) { "" }.isNotEmpty()) ActionLine("Copy password", ink) {
                    val clip = ClipData.newPlainText("Wi-Fi password", f[1])
                    // Otherwise Android 13 and later show the password in the clipboard preview
                    // and let the keyboard keep it in its history.
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        clip.description.extras = android.os.PersistableBundle().apply {
                            putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                        }
                    }
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                }
                Spacer(Modifier.width(16.dp))
                ActionLine("Wi-Fi settings", ink) { runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } }
            }
        }
        else -> {
            Text(f.getOrElse(0) { "" }, style = MaterialTheme.typography.bodyLarge, color = ink)
            f.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ink.copy(alpha = 0.8f)) }
            f.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ink.copy(alpha = 0.8f)) }
            ActionLine("Save contact", ink) {
                runCatching {
                    context.startActivity(Intent(ContactsContract.Intents.Insert.ACTION).apply {
                        type = ContactsContract.RawContacts.CONTENT_TYPE
                        putExtra(ContactsContract.Intents.Insert.NAME, f.getOrElse(0) { "" })
                        f.getOrNull(1)?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
                        f.getOrNull(2)?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
                    })
                }
            }
        }
    }
}

@Composable
private fun ActionLine(label: String, ink: Color, onClick: () -> Unit) {
    // clickable before padding, so the padding is inside the target instead of a dead margin
    // around it, and a floor of 48dp: this was a ~20dp strip of text.
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = ink)
    }
}

/**
 * What a message can honestly say about its journey. The sender only ever learns what it
 * handed on itself, what the network's own count came back as, and — for a private message —
 * that it arrived, because the receipt travelled back the same way.
 */
private fun carryCaption(e: LogEntry): String {
    if (e.kind == LogEntry.Kind.RX) return if (e.carriedHops > 0) " · carried, ${e.carriedHops} ${if (e.carriedHops == 1) "hop" else "hops"}" else ""
    if (e.kind != LogEntry.Kind.TX || e.bundleSeq == null) return ""
    e.deliveredAfter?.let { (hops, minutes) ->
        return " · delivered after $hops ${if (hops == 1) "phone" else "phones"}, ${humanMinutes(minutes)}"
    }
    if (e.reach > e.handed && e.reach > 1) return " · reached about ${e.reach} phones"
    if (e.handed > 0) return " · handed to ${e.handed} ${if (e.handed == 1) "phone" else "phones"}"
    return " · on its way"
}

private fun humanMinutes(m: Int): String = when {
    m < 1 -> "under a minute"
    m < 60 -> "$m min"
    m < 60 * 24 -> "${m / 60} h ${m % 60} min"
    else -> "${m / (60 * 24)} d"
}
