package com.example

import android.app.Application
import com.example.data.StartupReconciler
import com.example.data.warmUpSecureSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MediaSaverApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Once per process, and against real WorkManager state — not on every
        // ViewModel construction, where it used to mislabel live downloads.
        StartupReconciler(this).run()
        // Opening the encrypted settings store costs a Keystore round trip plus a
        // decryption pass. Warm it up off the main thread so the first caller —
        // MainActivity, the share sheet, a settings read during composition — finds
        // the store ready and every Secrets read falls back to a finished object.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { warmUpSecureSettings(this@MediaSaverApp) }
        }
    }
}
