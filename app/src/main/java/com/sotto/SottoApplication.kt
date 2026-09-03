package com.sotto

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras

/**
 * Holds the one [MainViewModel] for the life of the process, so the audio engine inside it
 * keeps listening when the activity is gone and the foreground service is what keeps the
 * process alive.
 */
class SottoApplication : Application(), ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
    override val viewModelStore = ViewModelStore()

    // Compose's viewModel() asks the owner how to build the model; an AndroidViewModel needs
    // the Application, which the plain default factory cannot supply.
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = ViewModelProvider.AndroidViewModelFactory.getInstance(this)
    override val defaultViewModelCreationExtras: CreationExtras
        get() = MutableCreationExtras().apply { set(ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY, this@SottoApplication) }

    val engine: MainViewModel
        get() = ViewModelProvider.create(this, defaultViewModelProviderFactory, defaultViewModelCreationExtras)[MainViewModel::class.java]

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_LISTENING, "Listening", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while Sotto keeps the microphone open in the background"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Messages that arrive while Sotto is not on screen"
        })
    }

    companion object {
        const val CHANNEL_LISTENING = "listening"
        const val CHANNEL_MESSAGES = "messages"
    }
}
