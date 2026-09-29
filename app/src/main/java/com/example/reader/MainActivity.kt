package com.example.reader

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.reader.ui.LibraryScreen
import com.example.reader.ui.ReaderScreen
import com.example.reader.ui.ReaderTheme
import com.example.reader.ui.SettingsScreen
import com.example.reader.ui.isDark

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: MainViewModel = viewModel()
            val settings by vm.settings.collectAsState()
            val dark = isDark(settings)
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            ReaderTheme(settings) { ReaderApp(vm) }
        }
    }
}

@Composable
fun ReaderApp(vm: MainViewModel) {
    val open by vm.openBook.collectAsState()
    val chapters by vm.chapters.collectAsState()
    val books by vm.books.collectAsState()
    val importing by vm.importing.collectAsState()
    val failed by vm.failed.collectAsState()
    val settings by vm.settings.collectAsState()
    val showSettings by vm.showSettings.collectAsState()

    val book = open
    val list = chapters

    if (book == null) {
        if (showSettings) {
            SettingsScreen(
                settings = settings,
                onChange = vm::update,
                onImportFont = vm::importFont,
                onBack = { vm.showSettings.value = false }
            )
        } else {
            LibraryScreen(
                books = books,
                importing = importing,
                failed = failed,
                onImport = vm::importBooks,
                onOpen = vm::open,
                onDelete = vm::delete,
                onFailedShown = vm::clearFailed,
                onOpenSettings = { vm.showSettings.value = true },
                view = settings.libraryView,
                onViewChange = { v -> vm.update { it.copy(libraryView = v) } }
            )
        }
    } else if (list == null) {
        BackHandler { vm.close() }
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) { CircularProgressIndicator() }
    } else {
        ReaderScreen(
            book = book,
            chapters = list,
            settings = settings,
            onSettings = vm::update,
            onImportFont = vm::importFont,
            onProgress = { c, o, p -> vm.saveProgress(book.id, c, o, p) },
            onBack = vm::close
        )
    }
}
