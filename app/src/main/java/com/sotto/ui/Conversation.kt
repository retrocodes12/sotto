package com.sotto.ui

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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
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
    val view = LocalView.current
    DisposableEffect(vm.wantListening) {
        view.keepScreenOn = vm.wantListening
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Header(vm, onSettings = { showSettings = true })
            if (vm.log.isEmpty()) {
                EmptyState(Modifier.weight(1f))
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(vm.log, key = { it.id }) { MessageTile(it) }
                }
            }
            vm.status?.let { Note(it, error = true) }
            if (vm.mediaVolume < 0.7f) VolumeNudge(vm)
            ComposeBar(vm)
        }
    }
    if (showSettings) SettingsSheet(vm, onDismiss = { showSettings = false })
}

@Composable
private fun Header(vm: MainViewModel, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text("sotto", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.onBackground)
            StatusLine(vm)
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
    val level by animateFloatAsState(targetValue = if (active) ((db + 60f) / 60f).coerceIn(0f, 1f) else 0f, label = "level")
    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline))
        Spacer(Modifier.width(8.dp))
        Text(vm.statusLine, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.width(56.dp).height(2.dp).background(MaterialTheme.colorScheme.outline)) {
            Box(Modifier.fillMaxWidth(level).height(2.dp).background(MaterialTheme.colorScheme.onSurfaceVariant))
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 40.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Nothing yet.", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Text(
            "Open Sotto on the other phone too. Anything you send plays as sound, and anything it hears shows up here.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MessageTile(e: LogEntry) {
    if (e.kind == LogEntry.Kind.INFO) { Note(e.text); return }
    val out = e.kind == LogEntry.Kind.TX
    val tile = if (out) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val ink = if (out) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(
        topStart = 18.dp, topEnd = 18.dp,
        bottomStart = if (out) 18.dp else 6.dp, bottomEnd = if (out) 6.dp else 18.dp,
    )
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (out) Alignment.End else Alignment.Start) {
        Surface(color = tile, shape = shape, modifier = Modifier.widthIn(max = 300.dp)) {
            Column(Modifier.padding(if (e.image != null) 6.dp else 12.dp)) {
                e.image?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(), contentDescription = "photo", contentScale = ContentScale.Fit,
                        modifier = Modifier.width(240.dp).aspectRatio(bmp.width.toFloat() / bmp.height.coerceAtLeast(1)).clip(RoundedCornerShape(14.dp)),
                    )
                }
                if (e.text.isNotEmpty()) {
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
        if (vm.draftBytes > 80) {
            Text(
                "${vm.draftBytes} / ${MainViewModel.MAX_BYTES} bytes",
                style = MaterialTheme.typography.bodySmall,
                color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End).padding(end = 64.dp, bottom = 4.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !vm.busy,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Outlined.Image, contentDescription = "Send a photo", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextField(
                value = vm.draft, onValueChange = { vm.draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message", color = MaterialTheme.colorScheme.onSurfaceVariant) },
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
