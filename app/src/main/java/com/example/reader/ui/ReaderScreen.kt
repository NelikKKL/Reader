package com.example.reader.ui

import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.reader.R
import com.example.reader.data.AppSettings
import com.example.reader.data.Book
import com.example.reader.data.Chapter
import com.example.reader.data.FontChoice
import com.example.reader.data.PageAnim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ------------------------------------------------------------------ pagination

private class Page(val chapter: Int, val startLine: Int, val endLine: Int, val start: Int, val end: Int)

/** layouts[i] is null for chapters that have not been laid out yet (quick first pass). */
private class Paginated(
    val layouts: List<StaticLayout?>,
    val paint: TextPaint,
    val pages: List<Page>,
    val complete: Boolean
)

private enum class Sheet { None, Chapters, Settings }

private class ReaderUi {
    var bars by mutableStateOf(false)
    var sheet by mutableStateOf(Sheet.None)
}

private fun makeTypeface(s: AppSettings): Typeface {
    val base: Typeface = when (s.font) {
        FontChoice.SANS -> Typeface.SANS_SERIF
        FontChoice.SERIF -> Typeface.SERIF
        FontChoice.MONO -> Typeface.MONOSPACE
        FontChoice.CONDENSED -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        FontChoice.CUSTOM -> s.customFontPath?.let { p ->
            try {
                Typeface.Builder(File(p)).build()
            } catch (e: Exception) {
                null
            }
        } ?: Typeface.SERIF
    }
    return if (s.bold) Typeface.create(base, Typeface.BOLD) else base
}

private fun buildLayout(text: CharSequence, widthPx: Int, paint: TextPaint, spacing: Float): StaticLayout =
    StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx)
        .setLineSpacing(0f, spacing)
        .setIncludePad(false)
        .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
        .build()

private fun paginate(
    chapters: List<Chapter>,
    widthPx: Int,
    heightPx: Int,
    sizePx: Float,
    typeface: Typeface,
    spacing: Float,
    only: Int?
): Paginated {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sizePx
        this.typeface = typeface
    }
    val layouts = ArrayList<StaticLayout?>()
    val pages = ArrayList<Page>()
    chapters.forEachIndexed { ci, ch ->
        if (only != null && ci != only) {
            layouts += null
            return@forEachIndexed
        }
        val l = buildLayout(ch.text, widthPx, paint, spacing)
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
    return Paginated(layouts, paint, pages, only == null)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    book: Book,
    chapters: List<Chapter>,
    settings: AppSettings,
    onSettings: ((AppSettings) -> AppSettings) -> Unit,
    onImportFont: (android.net.Uri) -> Unit,
    onProgress: (chapter: Int, offset: Int, progress: Float) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val ui = remember { ReaderUi() }
    val pos = remember { intArrayOf(book.chapter, book.offset) }
    val cumulative = remember(chapters) {
        LongArray(chapters.size + 1).also { for (i in chapters.indices) it[i + 1] = it[i] + chapters[i].text.length }
    }
    val typeface = remember(settings.font, settings.customFontPath, settings.bold) { makeTypeface(settings) }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        val pv = WindowInsets.systemBars.asPaddingValues()
        val ld = LocalLayoutDirection.current
        val density = LocalDensity.current
        val areaW = maxWidth - pv.calculateLeftPadding(ld) - pv.calculateRightPadding(ld) - (settings.margin * 2).dp
        val areaH = maxHeight - pv.calculateTopPadding() - pv.calculateBottomPadding() - 44.dp
        val widthPx = with(density) { areaW.toPx() }.toInt()
        val heightPx = with(density) { areaH.toPx() }.toInt()
        val sizePx = with(density) { settings.fontSize.sp.toPx() }
        val spacing = settings.lineSpacing

        val paginated by produceState<Paginated?>(
            null, chapters, widthPx, heightPx, sizePx, typeface, spacing
        ) {
            // Quick pass: lay out only the chapter we are opening so the text appears immediately.
            if (value == null && chapters.size > 1) {
                val quick = pos[0].coerceIn(0, chapters.lastIndex)
                value = withContext(Dispatchers.Default) {
                    paginate(chapters, widthPx, heightPx, sizePx, typeface, spacing, quick)
                }
            }
            value = withContext(Dispatchers.Default) {
                paginate(chapters, widthPx, heightPx, sizePx, typeface, spacing, null)
            }
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
                    settings = settings,
                    startChapter = pos[0],
                    startOffset = pos[1],
                    ui = ui,
                    onPosition = { c, o, last ->
                        pos[0] = c
                        pos[1] = o
                        val total = cumulative[chapters.size].coerceAtLeast(1L)
                        val frac = if (last) 1f else ((cumulative[c] + o).toFloat() / total).coerceIn(0f, 1f)
                        onProgress(c, o, frac)
                    },
                    onBack = onBack
                )
            }
        }
    }

    if (ui.sheet == Sheet.Settings) {
        ModalBottomSheet(onDismissRequest = { ui.sheet = Sheet.None }) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp)
            ) {
                SettingsContent(settings, onSettings, onImportFont)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PagedReader(
    pg: Paginated,
    chapters: List<Chapter>,
    book: Book,
    settings: AppSettings,
    startChapter: Int,
    startOffset: Int,
    ui: ReaderUi,
    onPosition: (Int, Int, Boolean) -> Unit,
    onBack: () -> Unit
) {
    val pages = pg.pages
    val pager = rememberPagerState(initialPage = findPage(pages, startChapter, startOffset)) { pages.size }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val ld = LocalLayoutDirection.current
    val pv = WindowInsets.systemBars.asPaddingValues()
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val footerColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val bgColor = MaterialTheme.colorScheme.surface
    val mode = settings.pageAnim
    val margin = settings.margin.dp

    var widthPx by remember { mutableFloatStateOf(0f) }
    var curlForward by remember { mutableStateOf<Boolean?>(null) }
    var curlF by remember { mutableFloatStateOf(0f) }
    var animJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { i ->
            pages.getOrNull(i)?.let { onPosition(it.chapter, it.start, pg.complete && i == pages.lastIndex) }
        }
    }

    val current = pages[pager.currentPage.coerceIn(0, pages.lastIndex)]
    fun firstPage(chapter: Int) = pages.indexOfFirst { it.chapter == chapter }.coerceAtLeast(0)

    // --- page curl -----------------------------------------------------------------------
    fun runCurl(forward: Boolean, from: Float, to: Float, commit: Boolean) {
        animJob?.cancel()
        animJob = scope.launch {
            curlForward = forward
            animate(from, to, animationSpec = tween(320)) { v, _ -> curlF = v }
            if (commit) pager.scrollToPage(pager.currentPage + if (forward) 1 else -1)
            curlForward = null
        }
    }

    fun finishCurl(lastDx: Float) {
        val fwd = curlForward ?: return
        val w = widthPx
        if (w <= 0f) {
            curlForward = null
            return
        }
        val progress = if (fwd) (w - curlF) / w else curlF / w
        val flick = if (fwd) lastDx < -15f else lastDx > 15f
        val complete = progress > 0.3f || (flick && progress > 0.05f)
        val target = if (complete) (if (fwd) 0f else w) else (if (fwd) w else 0f)
        runCurl(fwd, curlF, target, complete)
    }

    fun turn(delta: Int) {
        val t = pager.currentPage + delta
        if (t !in 0..pages.lastIndex) return
        when (mode) {
            PageAnim.SLIDE -> scope.launch { pager.animateScrollToPage(t) }
            PageAnim.NONE -> scope.launch { pager.scrollToPage(t) }
            PageAnim.CURL -> {
                if (curlForward != null) return
                if (widthPx > 0f) {
                    if (delta > 0) runCurl(true, widthPx, 0f, true) else runCurl(false, 0f, widthPx, true)
                } else {
                    scope.launch { pager.scrollToPage(t) }
                }
            }
        }
    }

    val leftPx = with(density) { pv.calculateLeftPadding(ld).toPx() + margin.toPx() }
    val topPx = with(density) { pv.calculateTopPadding().toPx() + 16.dp.toPx() }
    val footerFromBottomPx = with(density) { pv.calculateBottomPadding().toPx() + 9.dp.toPx() }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { widthPx = it.width.toFloat() }
                .pointerInput(pages, mode) {
                    detectTapGestures { o ->
                        val w = size.width
                        when {
                            o.x < w * 0.28f -> { ui.bars = false; turn(-1) }
                            o.x > w * 0.72f -> { ui.bars = false; turn(1) }
                            else -> ui.bars = !ui.bars
                        }
                    }
                }
                .pointerInput(pages, mode) {
                    if (mode == PageAnim.NONE) {
                        var acc = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { acc = 0f },
                            onDragEnd = { if (abs(acc) > size.width * 0.15f) turn(if (acc < 0f) 1 else -1) },
                            onDragCancel = {}
                        ) { change, dx ->
                            change.consume()
                            acc += dx
                        }
                    } else if (mode == PageAnim.CURL) {
                        var last = 0f
                        detectHorizontalDragGestures(
                            onDragStart = {
                                last = 0f
                                animJob?.cancel()
                                animJob = null
                                curlForward = null
                            },
                            onDragEnd = { finishCurl(last) },
                            onDragCancel = { finishCurl(last) }
                        ) { change, dx ->
                            change.consume()
                            last = dx
                            val w = size.width.toFloat()
                            if (curlForward == null) {
                                val fwd = dx < 0f
                                val idx = pager.currentPage
                                if ((fwd && idx >= pages.lastIndex) || (!fwd && idx <= 0)) {
                                    return@detectHorizontalDragGestures
                                }
                                curlForward = fwd
                                curlF = if (fwd) w else 0f
                            }
                            curlF = (curlF + dx).coerceIn(0f, w)
                        }
                    }
                }
        ) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = mode == PageAnim.SLIDE
            ) { i ->
                Box(Modifier.fillMaxSize().padding(pv)) {
                    Box(Modifier.fillMaxSize().padding(start = margin, end = margin, top = 16.dp, bottom = 28.dp)) {
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
            val cf = curlForward
            if (mode == PageAnim.CURL && cf != null) {
                CurlOverlay(
                    pg = pg,
                    pages = pages,
                    index = pager.currentPage,
                    forward = cf,
                    fold = { curlF },
                    textColor = textColor,
                    bg = bgColor,
                    footerColor = footerColor,
                    leftPx = leftPx,
                    topPx = topPx,
                    footerFromBottomPx = footerFromBottomPx
                )
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
                                val t = drag?.let { Math.round(it) }
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
                            enabled = current.chapter < chapters.lastIndex && pg.layouts.getOrNull(current.chapter + 1) != null,
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
                            if (pg.layouts.getOrNull(i) != null) {
                                scope.launch { pager.scrollToPage(firstPage(i)) }
                            }
                            ui.sheet = Sheet.None
                        }
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ drawing

@Composable
private fun PageCanvas(pg: Paginated, page: Page, color: Int) {
    Canvas(Modifier.fillMaxSize()) {
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            val layout = pg.layouts[page.chapter] ?: return@drawIntoCanvas
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

/** Draws one whole page (background, text, page number) at full-screen coordinates. */
private fun DrawScope.drawReaderPage(
    pg: Paginated,
    pages: List<Page>,
    index: Int,
    textColor: Int,
    bg: Color,
    footerPaint: Paint,
    leftPx: Float,
    topPx: Float,
    footerFromBottomPx: Float
) {
    drawRect(bg)
    val page = pages[index]
    drawIntoCanvas { c ->
        val nc = c.nativeCanvas
        val layout = pg.layouts[page.chapter] ?: return@drawIntoCanvas
        val top = layout.getLineTop(page.startLine).toFloat()
        val bottom = layout.getLineBottom(page.endLine - 1).toFloat()
        pg.paint.color = textColor
        nc.save()
        nc.translate(leftPx, topPx - top)
        nc.clipRect(0f, top, size.width, bottom)
        layout.draw(nc)
        nc.restore()
        nc.drawText("${index + 1} / ${pages.size}", size.width / 2f, size.height - footerFromBottomPx, footerPaint)
    }
}

/**
 * Book-like page turn. The fold is a vertical line at x = fold:
 * - the page underneath (next, or the current one when going back) is fully drawn,
 * - the turning page is visible to the left of the fold,
 * - its back side (mirrored, faded paper) lies on top of it from the fold to the left,
 * - gradients imitate the shadow on the paper below and the curvature near the fold.
 */
@Composable
private fun CurlOverlay(
    pg: Paginated,
    pages: List<Page>,
    index: Int,
    forward: Boolean,
    fold: () -> Float,
    textColor: Int,
    bg: Color,
    footerColor: Int,
    leftPx: Float,
    topPx: Float,
    footerFromBottomPx: Float
) {
    val density = LocalDensity.current
    val footerPaint = remember(footerColor, density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = footerColor
            textSize = with(density) { 11.sp.toPx() }
            textAlign = Paint.Align.CENTER
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val topIdx = if (forward) index else index - 1
        val underIdx = if (forward) index + 1 else index
        if (topIdx !in pages.indices || underIdx !in pages.indices) return@Canvas
        val f = fold().coerceIn(0f, w)

        fun page(i: Int) = drawReaderPage(pg, pages, i, textColor, bg, footerPaint, leftPx, topPx, footerFromBottomPx)

        page(underIdx)
        clipRect(left = 0f, top = 0f, right = f, bottom = h) { page(topIdx) }

        val flap = w - f
        if (flap > 1f) {
            val x0 = max(0f, f - flap)

            // shadow cast by the fold onto the page below
            val sw = min(28.dp.toPx(), w - f)
            if (sw > 0f) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent), startX = f, endX = f + sw
                    ),
                    topLeft = Offset(f, 0f),
                    size = Size(sw, h)
                )
            }
            // shadow cast by the flap onto the turning page
            val s2 = min(24.dp.toPx(), x0)
            if (s2 > 0f) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.30f)), startX = x0 - s2, endX = x0
                    ),
                    topLeft = Offset(x0 - s2, 0f),
                    size = Size(s2, h)
                )
            }
            // the back of the page: mirrored, faded paper with curvature shading
            clipRect(left = x0, top = 0f, right = f, bottom = h) {
                scale(scaleX = -1f, scaleY = 1f, pivot = Offset(f, h / 2f)) { page(topIdx) }
                drawRect(bg.copy(alpha = 0.82f))
                drawRect(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Black.copy(alpha = 0.03f), Color.Black.copy(alpha = 0.28f)),
                        startX = x0, endX = f
                    )
                )
            }
        }
    }
}
