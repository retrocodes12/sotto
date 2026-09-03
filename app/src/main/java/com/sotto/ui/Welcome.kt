package com.sotto.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
                "Type on one phone and it plays a sound too high for people to hear. Another phone running Sotto hears it " +
                    "and shows the words. No internet, no account, nothing to pair. Photos work too, at arm's length.",
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

/** Asked once, right after the microphone: the name other phones will show over your messages. */
@Composable
fun NameScreen(tag: String, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val ok = name.trim().isNotEmpty()
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 28.dp, vertical = 40.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Column {
            Spacer(Modifier.height(48.dp))
            Text("sotto", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(24.dp))
            Text("What should others call you?", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(16.dp))
            TextField(
                value = name, onValueChange = { if (it.toByteArray().size <= 24) name = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Your name", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                textStyle = MaterialTheme.typography.titleLarge,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (ok) onDone(name) }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Your phone's tag is $tag. It stays the same, so two people with the same name are still told apart. " +
                    "Anyone in earshot running Sotto can hear your name; it is a label, not a login.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            onClick = { onDone(name) }, enabled = ok, modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
        ) { Text("Continue", style = MaterialTheme.typography.labelLarge) }
    }
}
