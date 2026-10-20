package com.example

import android.app.Application
import com.example.data.StartupReconciler

class MediaSaverApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Once per process, and against real WorkManager state — not on every
        // ViewModel construction, where it used to mislabel live downloads.
        StartupReconciler(this).run()
    }
}