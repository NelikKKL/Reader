package com.example.reader

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.reader.data.Book
import com.example.reader.data.BookRepository
import com.example.reader.data.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = BookRepository(app)
    private val prefs = app.getSharedPreferences("reader", Context.MODE_PRIVATE)

    private val _books = MutableStateFlow(repo.all())
    val books: StateFlow<List<Book>> = _books

    val importing = MutableStateFlow(false)
    val failed = MutableStateFlow(false)
    val openBook = MutableStateFlow<Book?>(null)
    val chapters = MutableStateFlow<List<Chapter>?>(null)
    val fontSize = MutableStateFlow(prefs.getFloat("font", 19f))
    val serif = MutableStateFlow(prefs.getBoolean("serif", true))
    val darkTheme = MutableStateFlow(prefs.getBoolean("dark", true))

    fun importBooks(uris: List<Uri>) {
        viewModelScope.launch {
            importing.value = true
            for (u in uris) {
                val b = withContext(Dispatchers.IO) { repo.import(u) }
                if (b == null) failed.value = true else _books.value = repo.all()
            }
            importing.value = false
        }
    }

    fun delete(book: Book) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.delete(book) }
            _books.value = repo.all()
        }
    }

    fun open(book: Book) {
        openBook.value = book
        chapters.value = null
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { repo.load(book) }.getOrNull() }
            if (openBook.value?.id != book.id) return@launch
            if (r.isNullOrEmpty()) {
                openBook.value = null
                failed.value = true
            } else {
                chapters.value = r
            }
        }
    }

    fun close() {
        openBook.value = null
        chapters.value = null
        _books.value = repo.all()
    }

    fun saveProgress(id: String, chapter: Int, offset: Int) {
        viewModelScope.launch(Dispatchers.IO) { repo.updateProgress(id, chapter, offset) }
    }

    fun clearFailed() { failed.value = false }

    fun setFontSize(v: Float) {
        fontSize.value = v
        prefs.edit().putFloat("font", v).apply()
    }

    fun setDarkTheme(v: Boolean) {
        darkTheme.value = v
        prefs.edit().putBoolean("dark", v).apply()
    }

    fun setSerif(v: Boolean) {
        serif.value = v
        prefs.edit().putBoolean("serif", v).apply()
    }
}
