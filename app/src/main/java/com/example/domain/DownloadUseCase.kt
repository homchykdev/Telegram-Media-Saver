package com.example.domain

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

class DownloadUseCase(
    private val context: Context,
    private val client: OkHttpClient
) {
    interface ProgressListener {
        fun onProgress(percentage: Float)
    }

    @Throws(IOException::class)
    fun downloadFile(url: String, suffix: String, listener: ProgressListener? = null): File {
        val uniqueDir = File(context.cacheDir, "tiktok_dl/${UUID.randomUUID().hexString()}")
        if (!uniqueDir.exists()) uniqueDir.mkdirs()

        val destinationFile = File(uniqueDir, "media_$suffix")
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Не удалось загрузить медиа по ссылке: ${response.code}")
            val body = response.body ?: throw IOException("Тело ответа пустое")
            val contentLength = body.contentLength()

            body.byteStream().use { inputStream ->
                FileOutputStream(destinationFile).use { outputStream ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = 0L

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        if (contentLength > 0 && listener != null) {
                            listener.onProgress(totalBytesRead.toFloat() / contentLength)
                        }
                    }
                }
            }
        }
        return destinationFile
    }

    fun clearTempDir(includeThumbs: Boolean = false) {
        val cacheFolder = File(context.cacheDir, "tiktok_dl")
        if (cacheFolder.exists()) {
            if (includeThumbs) {
                cacheFolder.deleteRecursively()
            } else {
                cacheFolder.listFiles()?.forEach { file ->
                    if (file.name != "thumbs") {
                        file.deleteRecursively()
                    }
                }
            }
        }
    }

    fun saveThumbnail(url: String): String? {
        try {
            val thumbsDir = File(context.cacheDir, "tiktok_dl/thumbs")
            if (!thumbsDir.exists()) thumbsDir.mkdirs()

            val filename = "thumb_${UUID.randomUUID().hexString()}.jpg"
            val thumbFile = File(thumbsDir, filename)

            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                body.byteStream().use { inputStream ->
                    // Load into Bitmap, scale it down, then compress & write to disk
                    val options = android.graphics.BitmapFactory.Options().apply {
                        inJustDecodeBounds = false
                    }
                    val originalBitmap = android.graphics.BitmapFactory.decodeStream(inputStream, null, options)
                    if (originalBitmap != null) {
                        // Scale it to max 200px width/height to keep it very small and compact!
                        val maxSize = 200
                        val width = originalBitmap.width
                        val height = originalBitmap.height
                        val ratio = width.toFloat() / height.toFloat()
                        val (newWidth, newHeight) = if (width > height) {
                            Pair(maxSize, (maxSize / ratio).toInt())
                        } else {
                            Pair((maxSize * ratio).toInt(), maxSize)
                        }
                        val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(originalBitmap, newWidth, newHeight, true)
                        FileOutputStream(thumbFile).use { out ->
                            scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, out)
                        }
                        originalBitmap.recycle()
                        if (scaledBitmap != originalBitmap) {
                            scaledBitmap.recycle()
                        }
                        return thumbFile.absolutePath
                    }
                }
            }
            return null
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun getCacheSize(): Long {
        val cacheFolder = File(context.cacheDir, "tiktok_dl")
        if (!cacheFolder.exists()) return 0L
        return getFolderSize(cacheFolder)
    }

    private fun getFolderSize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        var size = 0L
        file.listFiles()?.forEach {
            size += getFolderSize(it)
        }
        return size
    }

    fun performAutoCleanup(maxSizeMb: Int, expirationDays: Int) {
        val cacheFolder = File(context.cacheDir, "tiktok_dl")
        if (!cacheFolder.exists()) return

        val expirationTime = System.currentTimeMillis() - (expirationDays * 24L * 60L * 60L * 1000L)

        // 1. Delete files older than expirationDays
        deleteExpiredFiles(cacheFolder, expirationTime)

        // 2. Check total size and delete oldest files if over limit
        val maxSizeBytes = maxSizeMb * 1024L * 1024L
        val currentSize = getFolderSize(cacheFolder)
        if (currentSize > maxSizeBytes) {
            val allFiles = mutableListOf<File>()
            getAllFilesRecursively(cacheFolder, allFiles)
            // Sort by lastModified, oldest first
            allFiles.sortBy { it.lastModified() }

            var tempSize = currentSize
            for (file in allFiles) {
                if (tempSize <= maxSizeBytes) break
                val fileSize = file.length()
                if (file.delete()) {
                    tempSize -= fileSize
                }
            }
        }

        // Clean up empty directories
        deleteEmptyDirectories(cacheFolder)
    }

    private fun deleteExpiredFiles(file: File, expirationTime: Long) {
        if (file.isFile) {
            if (file.lastModified() < expirationTime) {
                file.delete()
            }
        } else {
            file.listFiles()?.forEach { deleteExpiredFiles(it, expirationTime) }
        }
    }

    private fun getAllFilesRecursively(file: File, list: MutableList<File>) {
        if (file.isFile) {
            list.add(file)
        } else {
            file.listFiles()?.forEach { getAllFilesRecursively(it, list) }
        }
    }

    private fun deleteEmptyDirectories(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteEmptyDirectories(it) }
            if (file.listFiles()?.isEmpty() == true && file.name != "tiktok_dl") {
                file.delete()
            }
        }
    }
}

// Helper extension to format UUID
private fun UUID.hexString(): String = this.toString().replace("-", "").take(12)
