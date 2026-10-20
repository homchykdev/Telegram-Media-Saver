package com.example.network

import kotlinx.coroutines.delay
import okhttp3.FormBody
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

private const val BASE = "https://api.telegram.org"
private const val MAX_ATTEMPTS = 4
private const val MAX_FLOOD_WAIT_MS = 60_000L

class TelegramApiException(
    val code: Int,
    override val message: String,
    val retryAfterSeconds: Int = 0
) : IOException(message)

/**
 * The request body was fully written to the socket but no response arrived, so the
 * media may or may not already be in the group.
 *
 * The Bot API has no idempotency key, so there is no safe way to tell the two cases
 * apart. Replaying the request would put a second copy of the video into the group,
 * so the caller is told the truth instead of being handed a duplicate.
 */
class UploadOutcomeUnknownException(message: String) : IOException(message)

/**
 * Wraps a request body and records whether every byte actually reached the socket.
 *
 * OkHttp's own `RetryAndFollowUpInterceptor` deliberately refuses to replay a
 * request whose body has already gone out (it tracks this as `requestSendStarted`
 * and treats a post-upload timeout as unrecoverable). The retry loop in this file
 * builds a brand new [okhttp3.Call] per attempt and therefore does not inherit that
 * guard — this wrapper reproduces the distinction so the loop can respect it.
 *
 * `isOneShot()` also stops OkHttp from replaying the request behind our back, so
 * there is exactly one place that decides whether a send is retried.
 */
private class TransferAwareBody(private val delegate: RequestBody) : RequestBody() {

    @Volatile
    var bodyFullyWritten: Boolean = false
        private set

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        delegate.writeTo(sink)
        // Only set once the delegate returns cleanly. A partial write leaves the
        // server holding a truncated multipart body, which it rejects outright, so
        // that case is still safe to retry.
        bodyFullyWritten = true
    }
}

/**
 * Minimal hand-rolled Bot API client.
 *
 * Three behaviours matter for reliability:
 *  - **Non-idempotent calls are never blindly replayed.** `sendVideo`,
 *    `sendMediaGroup` and `sendMessage` are retried only when the server has
 *    provably rejected the request (HTTP 429) or when the body never reached the
 *    socket. Previously any `IOException` — including a read timeout *after*
 *    Telegram had already accepted and delivered the video — caused the entire
 *    file to be uploaded again, up to four times over.
 *  - HTTP 5xx is retried only for idempotent reads. A 502/504 from an edge proxy
 *    says nothing about whether the upload landed.
 *  - The error body is read exactly once. `ResponseBody.string()` closes the source,
 *    so reading it twice threw `IllegalStateException: closed` and silently
 *    replaced Telegram's real description with a generic string.
 */
class TelegramClient(
    private val client: OkHttpClient,
    /** Overridable so the retry policy can be exercised against a local server. */
    private val base: String = BASE
) {

    // ------------------------------------------------------------------ checks

    @Throws(IOException::class)
    suspend fun testConnection(token: String, groupId: String): Boolean {
        if (!TOKEN_PATTERN.matches(token.trim())) return false

        executeWithRetry(token, "getMe", idempotent = true) { rb ->
            rb.url("$base/bot${token.trim()}/getMe").get()
        }.close()

        executeWithRetry(token, "getChat", idempotent = true) { rb ->
            rb.url("$base/bot${token.trim()}/getChat?chat_id=${groupId.urlEncoded()}").get()
        }.use { response ->
            if (!response.isSuccessful) {
                throw IOException(describeError(response, "Не удалось проверить доступ к чату"))
            }
        }
        return true
    }

    // ----------------------------------------------------------------- sending

    @Throws(IOException::class)
    suspend fun sendMessage(
        token: String,
        groupId: String,
        text: String,
        parseMode: String? = "HTML",
        replyToMessageId: Long? = null
    ): Long {
        val body = TransferAwareBody(
            FormBody.Builder()
                .add("chat_id", groupId)
                .add("text", text)
                .apply {
                    if (parseMode != null) add("parse_mode", parseMode)
                    if (replyToMessageId != null) add("reply_to_message_id", replyToMessageId.toString())
                }
                .build()
        )

        // Tracked like the media sends: a read timeout after the request went out means
        // Telegram may already have posted the message, and replaying it would put a
        // second copy of the caption in the group.
        return executeWithRetry(token, "sendMessage", idempotent = false, tracker = body) { rb ->
            rb.url("$base/bot${token.trim()}/sendMessage").post(body)
        }.use { response -> parseOk(response).getJSONObject("result").getLong("message_id") }
    }

    @Throws(IOException::class)
    suspend fun sendVideo(token: String, groupId: String, file: File, caption: String?): String {
        val body = TransferAwareBody(
            MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", groupId)
                .apply {
                    if (caption != null) addFormDataPart("caption", caption)
                    addFormDataPart("parse_mode", "HTML")
                    addFormDataPart("video", file.name, file.asRequestBody("video/mp4".toMediaType()))
                }
                .build()
        )

        val messageId = executeWithRetry(token, "sendVideo", idempotent = false, tracker = body) { rb ->
            rb.url("$base/bot${token.trim()}/sendVideo").post(body)
        }.use { response -> parseOk(response).getJSONObject("result").getLong("message_id") }

        return formatMessageLink(groupId, messageId)
    }

    @Throws(IOException::class)
    suspend fun sendMediaGroup(
        token: String,
        groupId: String,
        files: List<File>,
        caption: String?,
        replyToMessageId: Long? = null
    ): Long {
        val media = JSONArray()
        val body = TransferAwareBody(
            MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", groupId)
                .apply {
                    if (replyToMessageId != null) {
                        addFormDataPart("reply_to_message_id", replyToMessageId.toString())
                    }
                    files.forEachIndexed { index, file ->
                        val attachName = "file_$index"
                        media.put(JSONObject().apply {
                            put("type", "photo")
                            put("media", "attach://$attachName")
                            if (index == 0 && caption != null) {
                                put("caption", caption)
                                put("parse_mode", "HTML")
                            }
                        })
                        addFormDataPart(attachName, file.name, file.asRequestBody("image/jpeg".toMediaType()))
                    }
                    addFormDataPart("media", media.toString())
                }
                .build()
        )

        return executeWithRetry(token, "sendMediaGroup", idempotent = false, tracker = body) { rb ->
            rb.url("$base/bot${token.trim()}/sendMediaGroup").post(body)
        }.use { response ->
            parseOk(response).getJSONArray("result").getJSONObject(0).getLong("message_id")
        }
    }

    // --------------------------------------------------------------- internals

    private suspend fun executeWithRetry(
        token: String,
        method: String,
        idempotent: Boolean,
        tracker: TransferAwareBody? = null,
        build: (Request.Builder) -> Request.Builder
    ): Response {
        val url = token.trim()
        var lastError: IOException? = null

        for (attempt in 0 until MAX_ATTEMPTS) {
            val response: Response
            try {
                response = client.newCall(build(Request.Builder()).build()).executeAwait()
            } catch (e: IOException) {
                // Every byte reached the socket, so Telegram may already have posted
                // the video. There is no idempotency key to make a replay safe.
                if (!idempotent && tracker?.bodyFullyWritten == true) {
                    throw UploadOutcomeUnknownException(
                        "Связь с Telegram оборвалась уже после передачи файла. " +
                            "Видео могло попасть в группу — проверьте группу, прежде чем повторять."
                    )
                }
                lastError = e
                if (attempt == MAX_ATTEMPTS - 1) throw e
                delay(backoffMs(attempt))
                continue
            }

            if (response.isSuccessful) return response

            val code = response.code
            // Read the body exactly once — a second `string()` throws on a real socket.
            val raw = runCatching { response.body?.string() }.getOrNull()
            response.close()

            val retryAfter = retryAfterFrom(raw)
            val description = descriptionFrom(raw) ?: "Telegram: ошибка $method (HTTP $code)"
            lastError = TelegramApiException(code, description, retryAfter)

            // 429 is the one status that proves the request was rejected before it
            // was acted on. A 5xx may come from an edge proxy after the upload
            // already landed, so it is only safe to replay for idempotent reads.
            val retryable = code == 429 || (idempotent && code >= 500)
            if (!retryable || attempt == MAX_ATTEMPTS - 1) throw lastError

            delay(if (retryAfter > 0) minOf(retryAfter * 1000L, MAX_FLOOD_WAIT_MS) else backoffMs(attempt))
        }
        throw lastError ?: IOException("Telegram: неизвестная ошибка $method")
    }

    private fun parseOk(response: Response): JSONObject {
        val raw = response.body?.string() ?: throw IOException("Пустой ответ от Telegram")
        val json = JSONObject(raw)
        if (!json.optBoolean("ok", false)) {
            throw TelegramApiException(
                json.optInt("error_code", 0),
                json.optString("description", "Неизвестная ошибка Telegram")
            )
        }
        return json
    }

    private fun retryAfterFrom(raw: String?): Int {
        if (raw.isNullOrBlank()) return 0
        return runCatching {
            JSONObject(raw)
                .optJSONObject("parameters")
                ?.optInt("retry_after", 0)
                ?: 0
        }.getOrDefault(0).coerceAtLeast(0)
    }

    private fun descriptionFrom(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return runCatching { JSONObject(raw).optString("description") }.getOrNull()
    }

    private fun describeError(response: Response, fallback: String): String {
        val raw = runCatching { response.body?.string() }.getOrNull()
        return descriptionFrom(raw)?.takeIf { it.isNotBlank() && it != "null" } ?: fallback
    }

    private fun backoffMs(attempt: Int): Long = 500L shl attempt

    fun formatMessageLink(groupId: String, messageId: Long): String {
        val cleanId = if (groupId.startsWith("-100")) groupId.substring(4) else groupId.replace("-", "")
        return "https://t.me/c/$cleanId/$messageId"
    }

    private fun String.urlEncoded(): String = java.net.URLEncoder.encode(this, "UTF-8")

    private companion object {
        val TOKEN_PATTERN = Regex("""^\d+:[A-Za-z0-9_-]{30,}$""")
    }
}