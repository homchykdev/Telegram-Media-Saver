package com.example.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Awaits [Call.execute] as a *cancellable* suspension.
 *
 * `Call.execute()` blocks the calling thread and cannot be interrupted, so cancelling
 * the coroutine that wrapped it left the socket running to completion: the worker
 * kept downloading, kept uploading, and only noticed the cancellation afterwards.
 * That is what let a "replaced" download finish its upload anyway and post a second
 * copy of the video into the group.
 *
 * Going through the callback API lets cancellation reach [Call.cancel], which really
 * does tear the connection down. That is what makes `cancelUniqueWork` and
 * `ExistingWorkPolicy.REPLACE` able to stop a transfer that is already in flight.
 */
suspend fun Call.executeAwait(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            // suspendCancellableCoroutine cancels promptly: if the coroutine was
            // already cancelled, resume() is discarded and nobody would ever close
            // this body, leaking the connection instead of returning it to the pool.
            if (continuation.isCancelled) {
                response.close()
            } else {
                continuation.resume(response)
            }
        }
    })
}