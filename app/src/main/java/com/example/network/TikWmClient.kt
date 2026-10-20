package com.example.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import android.net.Uri
import java.io.IOException

sealed class TikWmResult {
    abstract val title: String
    abstract val coverUrl: String?
    data class Video(val videoUrl: String, val fallbackVideoUrl: String?, override val title: String, override val coverUrl: String?) : TikWmResult()
    data class Carousel(val imageUrls: List<String>, override val title: String, override val coverUrl: String?) : TikWmResult()
}

class TikWmClient(private val client: OkHttpClient) {

    @Throws(IOException::class)
    suspend fun fetchMedia(url: String): TikWmResult {
        var attempts = 0
        var lastException: Exception? = null
        // Built through Uri.Builder rather than by interpolation: LinkDetector
        // deliberately keeps `&` and `#` in the matched link, so a shared
        // `?sender_device=pc&...` was being split at the `&` and the tail was handed
        // to TikWM as a bogus parameter, while everything after a `#` was dropped
        // entirely.
        val requestUrl = Uri.Builder()
            .scheme("https")
            .authority("www.tikwm.com")
            .path("/api/")
            .appendQueryParameter("url", url)
            .appendQueryParameter("hd", "1")
            .build()
        val request = Request.Builder()
            .url(requestUrl.toString())
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        while (attempts < 3) {
            attempts++
            try {
                awaitRateLimitWindow()
                client.newCall(request).executeAwait().use { response ->
                    // 4xx from this API means the link itself is unusable; retrying it
                    // three times only adds load to a service that is already limited.
                    if (response.code in 400..499) {
                        throw PermanentTikWmException("Ошибка сервера TikWM: ${response.code}")
                    }
                    if (!response.isSuccessful) throw IOException("Ошибка сервера TikWM: ${response.code}")
                    
                    val bodyString = response.body?.string() ?: throw IOException("Пустой ответ от TikWM API")
                    val json = JSONObject(bodyString)
                    val code = json.optInt("code", -1)
                    
                    if (code != 0) {
                        val msg = json.optString("msg", "Неизвестная ошибка API")
                        if (msg.contains("limit", ignoreCase = true) || msg.contains("request", ignoreCase = true) || msg.contains("frequent", ignoreCase = true)) {
                            throw IOException("RateLimit: $msg")
                        }
                        throw IOException("TikWM ошибка: $msg")
                    }

                    val data = json.getJSONObject("data")
                    val title = data.optString("title", "TikTok Media")

                    var coverUrl = data.optString("cover", "").trim()
                    if (coverUrl.isEmpty() || coverUrl == "null") {
                        coverUrl = ""
                    }

                    // Check if it's a photo carousel
                    val imagesArray = data.optJSONArray("images")
                    if (imagesArray != null && imagesArray.length() > 0) {
                        val imageUrls = ArrayList<String>()
                        for (i in 0 until imagesArray.length()) {
                            imageUrls.add(imagesArray.getString(i))
                        }
                        val finalCover = if (coverUrl.isEmpty()) imageUrls.firstOrNull() else coverUrl
                        return TikWmResult.Carousel(imageUrls, title, finalCover)
                    }

                    // Otherwise, it's a single video
                    val hdplay = usableUrl(data, "hdplay")
                    val play = usableUrl(data, "play")
                    val videoUrl = hdplay.ifEmpty { play }
                    if (videoUrl.isEmpty()) {
                        throw IOException("TikWM не вернул ссылку на видео для этого ролика")
                    }
                    val fallbackVideoUrl = if (hdplay.isNotEmpty() && play.isNotEmpty() && hdplay != videoUrl) play else null

                    return TikWmResult.Video(videoUrl, fallbackVideoUrl, title, if (coverUrl.isEmpty()) null else coverUrl)
                }
            } catch (e: PermanentTikWmException) {
                throw IOException(e.message ?: "Ошибка TikWM")
            } catch (e: IOException) {
                lastException = e
                // If it's a fatal error not related to rate limit or connection, just throw
                if (e.message?.startsWith("TikWM ошибка:") == true) {
                    throw e
                }
                // Sleep before retry (longer wait for rate limits). Suspending
                // rather than Thread.sleep keeps the backoff cancellable, so a
                // replaced download stops waiting instead of burning through it.
                if (attempts < 3) {
                    val waitTime = if (e.message?.startsWith("RateLimit:") == true) 2000L else 1000L
                    delay(waitTime)
                }
            }
        }
        throw IOException("Не удалось получить данные после 3 попыток: ${lastException?.message}")
    }

    /**
     * Serialises access to this service's global quota.
     *
     * tikwm allows one request per second per IP. The per-worker retry loop was not
     * enough on its own: two downloads started together each burned all three
     * attempts against the shared budget, and every attempt turned into another
     * rate-limit error — which is exactly the burst of "Free Api Limit" lines the
     * production history showed. A mutex plus a minimum gap between calls keeps the
     * app inside the budget no matter how many workers are running.
     */
    private suspend fun awaitRateLimitWindow() {
        rateLimitMutex.withLock {
            val now = System.currentTimeMillis()
            val waitFor = lastRequestAt + MIN_REQUEST_GAP_MS - now
            if (waitFor > 0) delay(waitFor)
            lastRequestAt = System.currentTimeMillis()
        }
    }

    private class PermanentTikWmException(message: String) : IOException(message)

    /**
     * This API returns the literal string `"null"` for a field it has no value for,
     * and `Request.Builder().url("null")` then throws IllegalArgumentException rather
     * than IOException — which would escape the retry loop and surface to the user as
     * an incomprehensible message about URL schemes.
     */
    private fun usableUrl(data: JSONObject, key: String): String {
        val value = data.optString(key, "").trim()
        return if (value.isEmpty() || value == "null") "" else value
    }

    private companion object {
        val rateLimitMutex = Mutex()
        const val MIN_REQUEST_GAP_MS = 1_200L

        @Volatile
        var lastRequestAt = 0L
    }
}
