package com.sotto

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * Holds the one [MainViewModel] for the life of the process, so the audio engine inside it
 * keeps listening when the activity is gone and the foreground service is what keeps the
 * process alive.
 */
class SottoApplication : Application(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    val engine: MainViewModel
        get() = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(this))[MainViewModel::class.java]

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
