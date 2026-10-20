package com.example.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

private const val TAG = "CustomSounds"

/**
 * Storage for user-supplied alert sounds.
 *
 * Files live in the app's private `files/sounds` directory, one per import, so they
 * survive the original `content://` grant being revoked and stay readable from a
 * worker with no ContentResolver in scope.
 */
object CustomSounds {

    private const val DIR_NAME = "sounds"

    /** Enough for any sane notification sound; keeps a bad pick from filling storage. */
    const val MAX_BYTES = 10L * 1024 * 1024

    fun dir(context: Context): File = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    fun file(context: Context, fileName: String): File? {
        // Defend against a stored value that is really a path: only ever hand back
        // something that really is inside the sounds directory.
        if (fileName.isBlank() || fileName.contains('/') || fileName.contains('\\')) return null
        val candidate = File(dir(context), fileName)
        return candidate.takeIf { it.isFile }
    }

    /** Copies [source] into app storage. Returns the stored file name, or throws. */
    fun import(context: Context, source: Uri): String {
        val resolver = context.contentResolver
        val displayName = queryDisplayName(resolver, source)
        val extension = (displayName ?: "").substringAfterLast('.', "")
            .takeIf { it.isNotBlank() && it.length <= 5 && it.all { c -> c.isLetterOrDigit() } }
            ?: "audio"

        val target = File(dir(context), "${UUID.randomUUID()}.$extension")
        try {
            resolver.openInputStream(source).use { input ->
                requireNotNull(input) { "Не удалось открыть выбранный файл" }
                target.outputStream().use { output -> copyBounded(input, output) }
            }
        } catch (e: TooLargeException) {
            target.delete()
            throw IOException("Файл слишком большой (максимум ${MAX_BYTES / 1024 / 1024} МБ)", e)
        } catch (e: Exception) {
            target.delete()
            throw IOException("Не удалось сохранить звук: ${e.message}", e)
        }

        if (target.length() == 0L) {
            target.delete()
            throw IOException("Выбранный файл пустой")
        }
        return target.name
    }

    /**
     * Copies with the limit enforced *while* writing.
     *
     * Checking the size afterwards would mean the whole file lands on disk first: a
     * file manager that hands over a multi-gigabyte video labelled as audio would fill
     * the device (or hit ENOSPC) before the limit was ever consulted.
     */
    private fun copyBounded(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > MAX_BYTES) throw TooLargeException()
            output.write(buffer, 0, read)
        }
    }

    private class TooLargeException : IOException()

    /** Best-effort human-readable name; falls back to the file name. */
    fun displayName(context: Context, fileName: String): String {
        val stem = fileName.substringBeforeLast('.', fileName)
        return stem.takeIf { it.isNotBlank() } ?: fileName
    }

    fun delete(context: Context, fileName: String) {
        file(context, fileName)?.delete()
    }

    /** Removes imports that are no longer referenced by any stored preference. */
    fun pruneOrphans(context: Context, referenced: Set<String>) {
        dir(context).listFiles()?.forEach { file ->
            if (file.name !in referenced && file.isFile) {
                if (!file.delete()) Log.w(TAG, "Не удалось удалить звук ${file.name}")
            }
        }
    }

    private fun queryDisplayName(
        resolver: android.content.ContentResolver,
        uri: Uri
    ): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    /** Suffixes of every sound currently imported, for pruning and settings display. */
    fun listFileNames(context: Context): List<String> =
        dir(context).listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty()
}