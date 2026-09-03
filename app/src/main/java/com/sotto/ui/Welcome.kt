package com.sotto.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** First run, and the blocking state when the microphone was refused. */
@Composable
fun WelcomeScreen(denied: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 40.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Column {
            Spacer(Modifier.height(48.dp))
            Text("sotto", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(24.dp))
            Text("Messages that travel as sound.", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(16.dp))
            Text(
                "Type on one phone and it plays a short chirp. Another phone running Sotto hears it and shows the words. " +
                    "No internet, no account, nothing to pair. Photos work too, at arm's length.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                if (denied) "The microphone was refused. Sotto cannot hear the other phone without it; nothing is recorded or stored."
                else "Sotto needs the microphone to hear the other phone. Sound is decoded on this phone and never leaves it.",
                style = MaterialTheme.typography.bodyLarge,
                color = if (denied) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column {
            Button(
                onClick = onRequest, modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
            ) { Text(if (denied) "Try again" else "Allow the microphone", style = MaterialTheme.typography.labelLarge) }
            if (denied) {
                TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("Open app settings", color = MaterialTheme.colorScheme.onBackground)
                }
            }
        }
    }
}
