package com.example.reader.data

data class Book(
    val id: String,
    val title: String,
    val author: String,
    val format: String,
    val filePath: String,
    val coverPath: String?,
    val chapter: Int,
    val offset: Int,
    val addedAt: Long
)

/** title is empty when the source has no chapter title. */
data class Chapter(val title: String, val text: String)

class ParsedBook(
    val title: String,
    val author: String,
    val chapters: List<Chapter>,
    val cover: ByteArray?
)
