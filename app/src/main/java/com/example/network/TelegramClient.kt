package com.example.network

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

class TelegramClient(private val client: OkHttpClient) {

    @Throws(IOException::class)
    fun testConnection(token: String, groupId: String): Boolean {
        // Test if the bot token is valid
        val getMeRequest = Request.Builder()
            .url("https://api.telegram.org/bot$token/getMe")
            .build()
        client.newCall(getMeRequest).execute().use { response ->
            if (!response.isSuccessful) return false
        }
        // Test if the group is accessible to the bot
        val getChatRequest = Request.Builder()
            .url("https://api.telegram.org/bot$token/getChat?chat_id=$groupId")
            .build()
        client.newCall(getChatRequest).execute().use { response ->
            return response.isSuccessful
        }
    }

    @Throws(IOException::class)
    fun sendMessage(token: String, groupId: String, text: String, replyToMessageId: Long? = null): Long {
        val requestBodyBuilder = FormBody.Builder()
            .add("chat_id", groupId)
            .add("text", text)
            .add("parse_mode", "HTML")
            
        if (replyToMessageId != null) {
            requestBodyBuilder.add("reply_to_message_id", replyToMessageId.toString())
        }

        val request = Request.Builder()
            .url("https://api.telegram.org/bot$token/sendMessage")
            .post(requestBodyBuilder.build())
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Не удалось отправить сообщение в Telegram: ${response.code}")
            val jsonResponse = JSONObject(response.body?.string() ?: "")
            val result = jsonResponse.getJSONObject("result")
            return result.getLong("message_id")
        }
    }

    @Throws(IOException::class)
    fun sendVideo(token: String, groupId: String, file: File, caption: String?): String {
        val requestBodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", groupId)
        
        if (caption != null) {
            requestBodyBuilder.addFormDataPart("caption", caption)
        }
        
        requestBodyBuilder.addFormDataPart("parse_mode", "HTML")
            .addFormDataPart(
                "video", 
                file.name, 
                file.asRequestBody("video/mp4".toMediaType())
            )

        val request = Request.Builder()
            .url("https://api.telegram.org/bot$token/sendVideo")
            .post(requestBodyBuilder.build())
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Не удалось отправить видео в Telegram: ${response.code}")
            val jsonResponse = JSONObject(response.body?.string() ?: "")
            val result = jsonResponse.getJSONObject("result")
            val messageId = result.getLong("message_id")
            return formatMessageLink(groupId, messageId)
        }
    }

    @Throws(IOException::class)
    fun sendMediaGroup(token: String, groupId: String, files: List<File>, caption: String?, replyToMessageId: Long? = null): Long {
        val mediaArray = JSONArray()
        val requestBodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", groupId)
            
        if (replyToMessageId != null) {
            requestBodyBuilder.addFormDataPart("reply_to_message_id", replyToMessageId.toString())
        }

        files.forEachIndexed { index, file ->
            val attachName = "file_$index"
            val mediaObj = JSONObject().apply {
                put("type", "photo")
                put("media", "attach://$attachName")
                if (index == 0 && caption != null) {
                    put("caption", caption)
                    put("parse_mode", "HTML")
                }
            }
            mediaArray.put(mediaObj)
            requestBodyBuilder.addFormDataPart(
                attachName,
                file.name,
                file.asRequestBody("image/jpeg".toMediaType())
            )
        }

        requestBodyBuilder.addFormDataPart("media", mediaArray.toString())

        val request = Request.Builder()
            .url("https://api.telegram.org/bot$token/sendMediaGroup")
            .post(requestBodyBuilder.build())
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Не удалось отправить альбом в Telegram: ${response.code}")
            val jsonResponse = JSONObject(response.body?.string() ?: "")
            val results = jsonResponse.getJSONArray("result")
            val firstMessageId = results.getJSONObject(0).getLong("message_id")
            return firstMessageId
        }
    }

    fun formatMessageLink(groupId: String, messageId: Long): String {
        val cleanId = if (groupId.startsWith("-100")) {
            groupId.substring(4)
        } else {
            groupId.replace("-", "")
        }
        return "https://t.me/c/$cleanId/$messageId"
    }
}
