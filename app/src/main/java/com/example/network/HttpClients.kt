package com.example.network

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The process-wide OkHttp client.
 *
 * Previously each `DownloadWorker` built its own client, so every download created a
 * fresh dispatcher, thread pool and connection pool and left idle sockets pinned for
 * the 5 minute keep-alive. Sharing one client reuses connections across runs.
 */
object HttpClients {

    val shared: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            // Generous on purpose: this bounds how long the app waits for Telegram to
            // answer *after* the upload body has been delivered, which is precisely
            // the window in which duplicate uploads used to be produced.
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS) // ~50 MB uploads need headroom
            .retryOnConnectionFailure(true)
            .build()
    }
}