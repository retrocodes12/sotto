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
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sotto.ui.ConversationScreen
import com.sotto.ui.NameScreen
import com.sotto.ui.SottoTheme
import com.sotto.ui.WelcomeScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { SottoTheme { SottoApp() } }
    }
}

@Composable
fun SottoApp(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    fun hasMic() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(hasMic()) }
    var deniedOnce by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) deniedOnce = true
    }

    LifecycleResumeEffect(Unit) {
        granted = hasMic()
        onPauseOrDispose { }
    }
    LifecycleStartEffect(Unit) {
        vm.onForeground()
        onStopOrDispose { vm.onBackground() }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (granted && vm.identity.name.isEmpty()) {
            NameScreen(tag = vm.identity.tag, onDone = { vm.setName(it) })
        } else if (granted) {
            ConversationScreen(vm)
        } else {
            WelcomeScreen(
                denied = deniedOnce,
                onRequest = { launcher.launch(Manifest.permission.RECORD_AUDIO) },
                onOpenSettings = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                },
            )
        }
    }
}
