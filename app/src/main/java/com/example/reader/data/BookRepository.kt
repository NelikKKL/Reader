package com.example.reader.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class BookRepository(private val context: Context) {
    private val dir = File(context.filesDir, "books").apply { mkdirs() }
    private val index = File(context.filesDir, "library.json")
    private var list: MutableList<Book> = readIndex()

    @Synchronized
    fun all(): List<Book> = list.toList()

    /** Fast path: binary cache. Slow path (old imports): parse once and create the cache. */
    fun load(book: Book): List<Chapter> {
        val cache = File(dir, "${book.id}.cache")
        if (cache.exists()) {
            try {
                return ChapterCache.read(cache)
            } catch (e: Exception) {
                cache.delete()
            }
        }
        val chapters = BookParser.splitLong(BookParser.parse(File(book.filePath), book.format).chapters)
        try {
            ChapterCache.write(cache, chapters)
        } catch (e: Exception) {
            cache.delete()
        }
        return chapters
    }

    fun import(uri: Uri): Book? {
        val name = displayName(uri) ?: return null
        val format = detectFormat(name) ?: return null
        val id = UUID.randomUUID().toString()
        val file = File(dir, "$id.$format")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { i -> file.outputStream().use { i.copyTo(it) } }
        } catch (e: Exception) {
            file.delete()
            return null
        }
        val parsed = try {
            BookParser.parse(file, format)
        } catch (e: Throwable) {
            null
        }
        if (parsed == null || parsed.chapters.isEmpty()) {
            file.delete()
            return null
        }
        val chapters = BookParser.splitLong(parsed.chapters)
        try {
            ChapterCache.write(File(dir, "$id.cache"), chapters)
        } catch (e: Exception) {
            File(dir, "$id.cache").delete()
        }
        val cover = parsed.cover?.let { saveCover(id, it) }
        val book = Book(
            id = id,
            title = parsed.title.ifBlank { name.substringBeforeLast('.').removeSuffix(".fb2") },
            author = parsed.author,
            format = format,
            filePath = file.absolutePath,
            coverPath = cover,
            chapter = 0,
            offset = 0,
            addedAt = System.currentTimeMillis()
        )
        synchronized(this) {
            list.add(0, book)
            persist()
        }
        return book
    }

    /** Copies a user font into app storage. Returns (path, display name) or null if the file is not a font. */
    fun importFont(uri: Uri): Pair<String, String>? {
        val name = displayName(uri) ?: "font"
        val fontsDir = File(context.filesDir, "fonts").apply { mkdirs() }
        val file = File(fontsDir, "custom_${System.currentTimeMillis()}.ttf")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { i -> file.outputStream().use { i.copyTo(it) } }
        } catch (e: Exception) {
            file.delete()
            return null
        }
        val ok = try {
            Typeface.Builder(file).build() != null
        } catch (e: Exception) {
            false
        }
        if (!ok) {
            file.delete()
            return null
        }
        fontsDir.listFiles()?.filter { it != file }?.forEach { it.delete() }
        return file.absolutePath to name.substringBeforeLast('.')
    }

    @Synchronized
    fun delete(book: Book) {
        list.removeAll { it.id == book.id }
        File(book.filePath).delete()
        File(dir, "${book.id}.cache").delete()
        book.coverPath?.let { File(it).delete() }
        persist()
    }

    @Synchronized
    fun updateProgress(id: String, chapter: Int, offset: Int, progress: Float) {
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) {
            list[i] = list[i].copy(chapter = chapter, offset = offset, progress = progress)
            persist()
        }
    }

    private fun detectFormat(name: String): String? {
        val n = name.lowercase()
        return when {
            n.endsWith(".fb2.zip") || n.endsWith(".zip") -> "fb2zip"
            n.endsWith(".fb2") -> "fb2"
            n.endsWith(".epub") -> "epub"
            n.endsWith(".txt") -> "txt"
            else -> null
        }
    }

    private fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: uri.lastPathSegment
    } catch (e: Exception) {
        uri.lastPathSegment
    }

    private fun saveCover(id: String, bytes: ByteArray): String? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 480) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return null
            val f = File(dir, "$id.jpg")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            f.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun persist() {
        val arr = JSONArray()
        list.forEach { b ->
            arr.put(JSONObject().apply {
                put("id", b.id); put("title", b.title); put("author", b.author)
                put("format", b.format); put("filePath", b.filePath)
                put("coverPath", b.coverPath ?: ""); put("chapter", b.chapter)
                put("offset", b.offset); put("addedAt", b.addedAt)
                put("progress", b.progress.toDouble())
            })
        }
        index.writeText(arr.toString())
    }

    private fun readIndex(): MutableList<Book> = try {
        val arr = JSONArray(index.readText())
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Book(
                id = o.getString("id"), title = o.getString("title"), author = o.optString("author"),
                format = o.getString("format"), filePath = o.getString("filePath"),
                coverPath = o.optString("coverPath").ifEmpty { null },
                chapter = o.optInt("chapter"), offset = o.optInt("offset"), addedAt = o.optLong("addedAt"),
                progress = o.optDouble("progress", 0.0).toFloat()
            )
        }.filter { File(it.filePath).exists() }.toMutableList()
    } catch (e: Exception) {
        mutableListOf()
    }
}
