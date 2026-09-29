package com.example.reader

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.reader.data.AppSettings
import com.example.reader.data.Book
import com.example.reader.data.BookRepository
import com.example.reader.data.Chapter
import com.example.reader.data.FontChoice
import com.example.reader.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = BookRepository(app)
    private val store = SettingsStore(app.getSharedPreferences("reader", Context.MODE_PRIVATE))

    private val _books = MutableStateFlow(repo.all())
    val books: StateFlow<List<Book>> = _books

    val importing = MutableStateFlow(false)
    val failed = MutableStateFlow(false)
    val openBook = MutableStateFlow<Book?>(null)
    val chapters = MutableStateFlow<List<Chapter>?>(null)
    val settings = MutableStateFlow(store.load())
    val showSettings = MutableStateFlow(false)

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

    fun saveProgress(id: String, chapter: Int, offset: Int, progress: Float) {
        viewModelScope.launch(Dispatchers.IO) { repo.updateProgress(id, chapter, offset, progress) }
    }

    fun clearFailed() { failed.value = false }

    fun update(transform: (AppSettings) -> AppSettings) {
        val n = transform(settings.value)
        settings.value = n
        store.save(n)
    }

    fun importFont(uri: Uri) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { repo.importFont(uri) }
            if (r == null) failed.value = true
            else update { it.copy(font = FontChoice.CUSTOM, customFontPath = r.first, customFontName = r.second) }
        }
    }
}
