package com.example.reader.data

import android.text.Html
import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.nio.charset.Charset
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

object BookParser {
    private val WS = Regex("\\s+")
    private const val INDENT = "\u2003\u2003"

    fun parse(file: File, format: String): ParsedBook = when (format) {
        "fb2" -> file.inputStream().buffered().use { parseFb2(it) }
        "fb2zip" -> ZipInputStream(file.inputStream().buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null && !e.name.lowercase().endsWith(".fb2")) e = zis.nextEntry
            if (e == null) error("fb2 not found in archive")
            parseFb2(zis)
        }
        "epub" -> parseEpub(file)
        else -> parseTxt(file)
    }

    private fun local(n: String) = n.substringAfter(':')

    /** Splits huge chapters so that the first screen can be paginated quickly. */
    fun splitLong(chapters: List<Chapter>, max: Int = 60000, target: Int = 30000): List<Chapter> {
        val out = ArrayList<Chapter>()
        for (ch in chapters) {
            val text = ch.text
            if (text.length <= max) {
                out += ch
                continue
            }
            var start = 0
            var first = true
            while (start < text.length) {
                val end = if (text.length - start <= max) text.length else {
                    val nl = text.indexOf('\n', start + target)
                    if (nl < 0) text.length else nl + 1
                }
                out += Chapter(if (first) ch.title else "", text.substring(start, end).trimEnd('\n'))
                first = false
                start = end
            }
        }
        return out
    }

    // ---------------------------------------------------------------- FB2

    private fun parseFb2(input: InputStream): ParsedBook {
        val p = Xml.newPullParser()
        p.setInput(input, null)

        val stack = ArrayList<String>()
        val text = StringBuilder()
        val para = StringBuilder()
        val chapters = ArrayList<Chapter>()
        val paras = ArrayList<String>()
        val titleLines = ArrayList<String>()
        var chapterTitle = ""
        var bookTitle = ""
        var first = ""
        var middle = ""
        var last = ""
        var inAuthor = false
        var authorDone = false
        var coverId: String? = null
        var cover: ByteArray? = null
        var blockDepth = 0
        var inTitle = false
        var notesDepth = 0

        fun flush() {
            if (paras.isNotEmpty()) {
                val head = if (chapterTitle.isNotEmpty()) chapterTitle + "\n\n" else ""
                chapters += Chapter(chapterTitle, head + paras.joinToString("\n"))
                paras.clear()
            }
            chapterTitle = ""
        }

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (notesDepth > 0) {
                if (ev == XmlPullParser.START_TAG) notesDepth++
                else if (ev == XmlPullParser.END_TAG) notesDepth--
                ev = p.next()
                continue
            }
            when (ev) {
                XmlPullParser.START_TAG -> {
                    val n = local(p.name)
                    if (n == "binary") {
                        val id = p.getAttributeValue(null, "id")
                        val data = p.nextText()
                        if (cover == null && id != null && id == coverId) {
                            cover = runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull()
                        }
                    } else if (n == "body" && p.getAttributeValue(null, "name") == "notes") {
                        notesDepth = 1
                    } else {
                        stack.add(n)
                        when (n) {
                            "author" -> if (stack.contains("title-info") && !authorDone) inAuthor = true
                            "image" -> if (stack.contains("coverpage") && coverId == null) {
                                var href: String? = null
                                for (i in 0 until p.attributeCount) {
                                    if (p.getAttributeName(i).endsWith("href")) href = p.getAttributeValue(i)
                                }
                                coverId = href?.removePrefix("#")
                            }
                            "section" -> flush()
                            "title" -> if (stack.contains("body")) {
                                inTitle = true
                                titleLines.clear()
                            }
                            "p", "v", "subtitle", "text-author" -> if (stack.contains("body")) {
                                blockDepth++
                                if (blockDepth == 1) para.setLength(0)
                            }
                        }
                        text.setLength(0)
                    }
                }
                XmlPullParser.TEXT -> if (blockDepth > 0) para.append(p.text) else text.append(p.text)
                XmlPullParser.END_TAG -> {
                    val n = local(p.name)
                    when (n) {
                        "book-title" -> if (stack.contains("title-info") && bookTitle.isEmpty()) bookTitle = text.toString().trim()
                        "first-name" -> if (inAuthor) first = text.toString().trim()
                        "middle-name" -> if (inAuthor) middle = text.toString().trim()
                        "last-name" -> if (inAuthor) last = text.toString().trim()
                        "author" -> if (inAuthor) {
                            inAuthor = false
                            authorDone = true
                        }
                        "p", "v", "subtitle", "text-author" -> if (blockDepth > 0) {
                            blockDepth--
                            val t = para.toString().replace(WS, " ").trim()
                            if (blockDepth == 0 && t.isNotEmpty()) {
                                if (inTitle) titleLines += t
                                else paras += if (n == "v" || n == "subtitle") t else INDENT + t
                            }
                        }
                        "title" -> if (inTitle) {
                            inTitle = false
                            if (titleLines.isNotEmpty()) chapterTitle = titleLines.joinToString(" ")
                            titleLines.clear()
                        }
                        "section", "body" -> flush()
                    }
                    if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                }
            }
            ev = p.next()
        }
        flush()
        val author = listOf(first, middle, last).filter { it.isNotEmpty() }.joinToString(" ")
        return ParsedBook(bookTitle, author, chapters, cover)
    }

    // ---------------------------------------------------------------- EPUB

    private fun resolve(base: String, href: String): String = try {
        URI(base.replace(" ", "%20"))
            .resolve(URI(href.substringBefore('#').replace(" ", "%20")))
            .path.trimStart('/')
    } catch (e: Exception) {
        href.substringBefore('#')
    }

    private class Item(val href: String, val type: String, val props: String)

    private fun parseEpub(file: File): ParsedBook {
        ZipFile(file).use { zip ->
            fun read(path: String): ByteArray? =
                zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }

            val container = read("META-INF/container.xml")?.toString(Charsets.UTF_8) ?: error("no container.xml")
            val opfPath = Regex("full-path\\s*=\\s*[\"']([^\"']+)[\"']").find(container)?.groupValues?.get(1)
                ?: error("no rootfile")
            val opf = read(opfPath) ?: error("no opf")

            val manifest = LinkedHashMap<String, Item>()
            val spine = ArrayList<String>()
            var title = ""
            var author = ""
            var coverId: String? = null

            val p = Xml.newPullParser()
            p.setInput(ByteArrayInputStream(opf), null)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (local(p.name)) {
                        "title" -> if (title.isEmpty()) title = p.nextText().trim()
                        "creator" -> if (author.isEmpty()) author = p.nextText().trim()
                        "meta" -> if (p.getAttributeValue(null, "name") == "cover") {
                            coverId = p.getAttributeValue(null, "content")
                        }
                        "item" -> {
                            val id = p.getAttributeValue(null, "id")
                            if (id != null) {
                                manifest[id] = Item(
                                    p.getAttributeValue(null, "href") ?: "",
                                    p.getAttributeValue(null, "media-type") ?: "",
                                    p.getAttributeValue(null, "properties") ?: ""
                                )
                            }
                        }
                        "itemref" -> p.getAttributeValue(null, "idref")?.let { spine += it }
                    }
                }
                ev = p.next()
            }

            val coverItem = coverId?.let { manifest[it] }
                ?: manifest.values.firstOrNull { it.props.contains("cover-image") }
            val cover = coverItem?.let { read(resolve(opfPath, it.href)) }

            val chapters = ArrayList<Chapter>()
            for (idref in spine) {
                val item = manifest[idref] ?: continue
                if (!item.type.contains("html")) continue
                val bytes = read(resolve(opfPath, item.href)) ?: continue
                val ch = htmlToChapter(String(bytes, Charsets.UTF_8))
                if (ch.text.isNotBlank()) chapters += ch
            }
            return ParsedBook(title, author, chapters, cover)
        }
    }

    private fun htmlToChapter(html: String): Chapter {
        val opts = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        val heading = Regex("<h[1-3][^>]*>(.*?)</h[1-3]>", opts).find(html)
            ?.groupValues?.get(1)?.let { plain(it) }.orEmpty()
        val body = html.replace(Regex("<head.*?</head>|<style.*?</style>|<script.*?</script>", opts), "")
        val lines = Html.fromHtml(body, Html.FROM_HTML_MODE_LEGACY).toString()
            .replace('\uFFFC', ' ')
            .lines()
            .map { it.replace(WS, " ").trim() }
            .filter { it.isNotEmpty() }
        val text = lines.joinToString("\n") { if (it == heading) it else INDENT + it }
        return Chapter(heading, text)
    }

    private fun plain(h: String) =
        Html.fromHtml(h, Html.FROM_HTML_MODE_LEGACY).toString().replace(WS, " ").trim()

    // ---------------------------------------------------------------- TXT

    private fun parseTxt(file: File): ParsedBook {
        val bytes = file.readBytes()
        var s = String(bytes, Charsets.UTF_8)
        if (s.count { it == '\uFFFD' } > 3) s = String(bytes, Charset.forName("windows-1251"))
        s = s.removePrefix("\uFEFF")
        val chapters = ArrayList<Chapter>()
        val sb = StringBuilder()
        for (line in s.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(INDENT).append(t)
            if (sb.length > 20000) {
                chapters += Chapter("", sb.toString())
                sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) chapters += Chapter("", sb.toString())
        return ParsedBook("", "", chapters, null)
    }
}
