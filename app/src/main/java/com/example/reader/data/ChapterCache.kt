package com.example.reader.data

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Binary cache of parsed chapters: opening a book becomes a plain file read instead of XML/HTML parsing. */
object ChapterCache {
    private const val MAGIC = 0x52443031 // "RD01"

    fun write(file: File, chapters: List<Chapter>) {
        DataOutputStream(BufferedOutputStream(file.outputStream())).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(chapters.size)
            for (ch in chapters) {
                val t = ch.title.toByteArray(Charsets.UTF_8)
                val x = ch.text.toByteArray(Charsets.UTF_8)
                out.writeInt(t.size); out.write(t)
                out.writeInt(x.size); out.write(x)
            }
        }
    }

    fun read(file: File): List<Chapter> {
        DataInputStream(BufferedInputStream(file.inputStream())).use { inp ->
            require(inp.readInt() == MAGIC) { "bad cache" }
            val n = inp.readInt()
            val list = ArrayList<Chapter>(n)
            repeat(n) {
                val t = ByteArray(inp.readInt()).also { inp.readFully(it) }
                val x = ByteArray(inp.readInt()).also { inp.readFully(it) }
                list += Chapter(String(t, Charsets.UTF_8), String(x, Charsets.UTF_8))
            }
            return list
        }
    }
}
