package com.example.network

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

sealed class TikWmResult {
    abstract val title: String
    abstract val coverUrl: String?
    data class Video(val videoUrl: String, val fallbackVideoUrl: String?, override val title: String, override val coverUrl: String?) : TikWmResult()
    data class Carousel(val imageUrls: List<String>, override val title: String, override val coverUrl: String?) : TikWmResult()
}

class TikWmClient(private val client: OkHttpClient) {

    @Throws(IOException::class)
    fun fetchMedia(url: String): TikWmResult {
        var attempts = 0
        var lastException: Exception? = null
        val requestUrl = "https://www.tikwm.com/api/?url=${url}&hd=1"
        val request = Request.Builder()
            .url(requestUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        while (attempts < 3) {
            attempts++
            try {
                client.newCall(request).execute().use { response ->
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
                    val hdplay = data.optString("hdplay", "")
                    val play = data.optString("play", "")
                    val videoUrl = if (hdplay.isNotEmpty()) hdplay else play
                    val fallbackVideoUrl = if (hdplay.isNotEmpty() && play.isNotEmpty() && hdplay != play) play else null
                    
                    return TikWmResult.Video(videoUrl, fallbackVideoUrl, title, if (coverUrl.isEmpty()) null else coverUrl)
                }
            } catch (e: IOException) {
                lastException = e
                // If it's a fatal error not related to rate limit or connection, just throw
                if (e.message?.startsWith("TikWM ошибка:") == true) {
                    throw e
                }
                // Sleep before retry (longer wait for rate limits)
                if (attempts < 3) {
                    val waitTime = if (e.message?.startsWith("RateLimit:") == true) 2000L else 1000L
                    Thread.sleep(waitTime)
                }
            }
        }
        throw IOException("Не удалось получить данные после 3 попыток: ${lastException?.message}")
    }
}
