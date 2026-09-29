package com.example.reader.ui

import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.reader.R
import com.example.reader.data.Book
import com.example.reader.data.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// ------------------------------------------------------------------ pagination

private class Page(val chapter: Int, val startLine: Int, val endLine: Int, val start: Int, val end: Int)

private class Paginated(val layouts: List<StaticLayout>, val paint: TextPaint, val pages: List<Page>)

private enum class Sheet { None, Chapters, Settings }

private class ReaderUi {
    var bars by mutableStateOf(false)
    var sheet by mutableStateOf(Sheet.None)
}

private fun makePaint(sizePx: Float, serif: Boolean) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = sizePx
    typeface = if (serif) Typeface.SERIF else Typeface.SANS_SERIF
}

private fun buildLayout(text: CharSequence, widthPx: Int, paint: TextPaint): StaticLayout =
    StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx)
        .setLineSpacing(0f, 1.3f)
        .setIncludePad(false)
        .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
        .build()

private fun paginate(chapters: List<Chapter>, widthPx: Int, heightPx: Int, sizePx: Float, serif: Boolean): Paginated {
    val paint = makePaint(sizePx, serif)
    val layouts = ArrayList<StaticLayout>()
    val pages = ArrayList<Page>()
    chapters.forEachIndexed { ci, ch ->
        val l = buildLayout(ch.text, widthPx, paint)
        layouts += l
        var line = 0
        while (line < l.lineCount) {
            val top = l.getLineTop(line)
            var end = line + 1
            while (end < l.lineCount && l.getLineBottom(end) - top <= heightPx) end++
            pages += Page(ci, line, end, l.getLineStart(line), l.getLineEnd(end - 1))
            line = end
        }
    }
    return Paginated(layouts, paint, pages)
}

private fun findPage(pages: List<Page>, chapter: Int, offset: Int): Int {
    val i = pages.indexOfFirst { it.chapter == chapter && offset < it.end }
    if (i >= 0) return i
    val j = pages.indexOfLast { it.chapter == chapter }
    return if (j >= 0) j else 0
}

@Composable
private fun chapterLabel(chapters: List<Chapter>, i: Int): String =
    chapters.getOrNull(i)?.title?.ifBlank { null } ?: stringResource(R.string.chapter_n, i + 1)

// ------------------------------------------------------------------ screen

@Composable
fun ReaderScreen(
    book: Book,
    chapters: List<Chapter>,
    fontSize: Float,
    serif: Boolean,
    onFontSize: (Float) -> Unit,
    onSerif: (Boolean) -> Unit,
    onProgress: (chapter: Int, offset: Int) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val ui = remember { ReaderUi() }
    val pos = remember { intArrayOf(book.chapter, book.offset) }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        val pv = WindowInsets.systemBars.asPaddingValues()
        val ld = LocalLayoutDirection.current
        val density = LocalDensity.current
        val areaW = maxWidth - pv.calculateStartPadding(ld) - pv.calculateEndPadding(ld) - 48.dp
        val areaH = maxHeight - pv.calculateTopPadding() - pv.calculateBottomPadding() - 44.dp
        val widthPx = with(density) { areaW.toPx() }.toInt()
        val heightPx = with(density) { areaH.toPx() }.toInt()
        val sizePx = with(density) { fontSize.sp.toPx() }

        val paginated by produceState<Paginated?>(null, chapters, widthPx, heightPx, sizePx, serif) {
            value = withContext(Dispatchers.Default) { paginate(chapters, widthPx, heightPx, sizePx, serif) }
        }
        val pg = paginated
        if (pg == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else {
            key(pg) {
                PagedReader(
                    pg = pg,
                    chapters = chapters,
                    book = book,
                    startChapter = pos[0],
                    startOffset = pos[1],
                    ui = ui,
                    onPosition = { c, o ->
                        pos[0] = c
                        pos[1] = o
                        onProgress(c, o)
                    },
                    onBack = onBack
                )
            }
        }
    }

    if (ui.sheet == Sheet.Settings) {
        SettingsSheet(fontSize, serif, onFontSize, onSerif) { ui.sheet = Sheet.None }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PagedReader(
    pg: Paginated,
    chapters: List<Chapter>,
    book: Book,
    startChapter: Int,
    startOffset: Int,
    ui: ReaderUi,
    onPosition: (Int, Int) -> Unit,
    onBack: () -> Unit
) {
    val pages = pg.pages
    val pager = rememberPagerState(initialPage = findPage(pages, startChapter, startOffset)) { pages.size }
    val scope = rememberCoroutineScope()
    val pv = WindowInsets.systemBars.asPaddingValues()
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()

    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { i ->
            pages.getOrNull(i)?.let { onPosition(it.chapter, it.start) }
        }
    }

    val current = pages[pager.currentPage.coerceIn(0, pages.lastIndex)]
    fun firstPage(chapter: Int) = pages.indexOfFirst { it.chapter == chapter }.coerceAtLeast(0)
    fun turn(delta: Int) {
        val t = pager.currentPage + delta
        if (t in 0..pages.lastIndex) scope.launch { pager.animateScrollToPage(t) }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(pages) {
                    detectTapGestures { o ->
                        val w = size.width
                        when {
                            o.x < w * 0.28f -> { ui.bars = false; turn(-1) }
                            o.x > w * 0.72f -> { ui.bars = false; turn(1) }
                            else -> ui.bars = !ui.bars
                        }
                    }
                }
        ) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { i ->
                Box(Modifier.fillMaxSize().padding(pv)) {
                    Box(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 28.dp)) {
                        PageCanvas(pg, pages[i], textColor)
                    }
                    Text(
                        "${i + 1} / ${pages.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = ui.bars,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it }
        ) {
            TopAppBar(
                title = {
                    Column {
                        Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            chapterLabel(chapters, current.chapter),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { ui.sheet = Sheet.Chapters }) {
                        Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.chapters))
                    }
                    IconButton(onClick = { ui.sheet = Sheet.Settings }) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            )
        }

        AnimatedVisibility(
            visible = ui.bars,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it }
        ) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (pages.size > 1) {
                        var drag by remember { mutableStateOf<Float?>(null) }
                        Slider(
                            value = drag ?: pager.currentPage.toFloat(),
                            onValueChange = { drag = it },
                            onValueChangeFinished = {
                                val t = drag?.roundToInt()
                                drag = null
                                if (t != null) scope.launch { pager.scrollToPage(t) }
                            },
                            valueRange = 0f..(pages.size - 1).toFloat()
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            enabled = pager.currentPage > 0,
                            onClick = {
                                val startOfCurrent = firstPage(current.chapter)
                                val target = if (pager.currentPage > startOfCurrent) startOfCurrent
                                else firstPage(current.chapter - 1)
                                scope.launch { pager.scrollToPage(target) }
                            }
                        ) { Text(stringResource(R.string.prev_chapter)) }
                        Text(
                            "${pager.currentPage + 1} / ${pages.size}",
                            style = MaterialTheme.typography.labelLarge
                        )
                        TextButton(
                            enabled = current.chapter < chapters.lastIndex,
                            onClick = { scope.launch { pager.scrollToPage(firstPage(current.chapter + 1)) } }
                        ) { Text(stringResource(R.string.next_chapter)) }
                    }
                }
            }
        }
    }

    if (ui.sheet == Sheet.Chapters) {
        ModalBottomSheet(onDismissRequest = { ui.sheet = Sheet.None }) {
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = current.chapter)
            LazyColumn(state = listState) {
                itemsIndexed(chapters) { i, _ ->
                    ListItem(
                        headlineContent = {
                            Text(chapterLabel(chapters, i), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = if (i == current.chapter) MaterialTheme.colorScheme.secondaryContainer
                            else Color.Transparent
                        ),
                        modifier = Modifier.clickable {
                            scope.launch { pager.scrollToPage(firstPage(i)) }
                            ui.sheet = Sheet.None
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PageCanvas(pg: Paginated, page: Page, color: Int) {
    Canvas(Modifier.fillMaxSize()) {
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            val layout = pg.layouts[page.chapter]
            val top = layout.getLineTop(page.startLine).toFloat()
            val bottom = layout.getLineBottom(page.endLine - 1).toFloat()
            pg.paint.color = color
            nc.save()
            nc.translate(0f, -top)
            nc.clipRect(0f, top, size.width, bottom)
            layout.draw(nc)
            nc.restore()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    fontSize: Float,
    serif: Boolean,
    onFontSize: (Float) -> Unit,
    onSerif: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleMedium)
            var size by remember { mutableFloatStateOf(fontSize) }
            Text(
                "${stringResource(R.string.font_size)}: ${size.roundToInt()}",
                modifier = Modifier.padding(top = 16.dp)
            )
            Slider(
                value = size,
                onValueChange = { size = it },
                onValueChangeFinished = { onFontSize(size) },
                valueRange = 14f..32f
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.serif))
                Switch(checked = serif, onCheckedChange = onSerif)
            }
        }
    }
}
