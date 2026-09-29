package com.example.reader.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.reader.R
import com.example.reader.data.AppSettings
import com.example.reader.data.ColorSource
import com.example.reader.data.FontChoice
import com.example.reader.data.PageAnim
import com.example.reader.data.ThemeMode
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onImportFont: (Uri) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            SettingsContent(settings, onChange, onImportFont)
        }
    }
}

/** Shared by the settings screen and the in-reader bottom sheet. */
@Composable
fun SettingsContent(
    settings: AppSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onImportFont: (Uri) -> Unit
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportFont(uri)
    }

    Column(Modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.section_appearance))

        FieldLabel(stringResource(R.string.theme))
        ChoiceRow(
            listOf(
                ThemeMode.SYSTEM to stringResource(R.string.theme_system),
                ThemeMode.DARK to stringResource(R.string.theme_dark),
                ThemeMode.LIGHT to stringResource(R.string.theme_light)
            ),
            settings.themeMode
        ) { m -> onChange { it.copy(themeMode = m) } }

        FieldLabel(stringResource(R.string.color_source))
        ChoiceRow(
            listOf(
                ColorSource.SYSTEM to stringResource(R.string.color_system),
                ColorSource.WALLPAPER to stringResource(R.string.color_wallpaper),
                ColorSource.DEFAULT to stringResource(R.string.color_default)
            ),
            settings.colorSource
        ) { c -> onChange { it.copy(colorSource = c) } }

        SwitchRow(stringResource(R.string.amoled), settings.amoled) { v -> onChange { it.copy(amoled = v) } }

        SectionTitle(stringResource(R.string.section_reading))

        FieldLabel(stringResource(R.string.font_family))
        ChoiceRow(
            listOf(
                FontChoice.SANS to stringResource(R.string.font_sans),
                FontChoice.SERIF to stringResource(R.string.font_serif),
                FontChoice.MONO to stringResource(R.string.font_mono),
                FontChoice.CONDENSED to stringResource(R.string.font_condensed),
                FontChoice.CUSTOM to (settings.customFontName ?: stringResource(R.string.font_custom))
            ),
            settings.font
        ) { f ->
            if (f == FontChoice.CUSTOM && settings.customFontPath == null) {
                picker.launch(arrayOf("*/*"))
            } else {
                onChange { it.copy(font = f) }
            }
        }
        TextButton(onClick = { picker.launch(arrayOf("*/*")) }) {
            Text(stringResource(R.string.font_pick))
        }

        SliderRow(
            stringResource(R.string.font_size), settings.fontSize, 12f..34f,
            { it.roundToInt().toString() }
        ) { v -> onChange { it.copy(fontSize = v) } }

        SliderRow(
            stringResource(R.string.line_spacing), settings.lineSpacing, 1.0f..2.0f,
            { "%.1f".format(it) }
        ) { v -> onChange { it.copy(lineSpacing = v) } }

        SliderRow(
            stringResource(R.string.margins), settings.margin.toFloat(), 8f..48f,
            { "${it.roundToInt()} dp" }
        ) { v -> onChange { it.copy(margin = v.roundToInt()) } }

        SwitchRow(stringResource(R.string.bold), settings.bold) { v -> onChange { it.copy(bold = v) } }

        FieldLabel(stringResource(R.string.page_anim))
        ChoiceRow(
            listOf(
                PageAnim.SLIDE to stringResource(R.string.anim_slide),
                PageAnim.CURL to stringResource(R.string.anim_curl),
                PageAnim.NONE to stringResource(R.string.anim_none)
            ),
            settings.pageAnim
        ) { a -> onChange { it.copy(pageAnim = a) } }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp)
    )
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
}

@Composable
private fun <T> ChoiceRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String,
    onCommit: (Float) -> Unit
) {
    var v by remember(value) { mutableFloatStateOf(value) }
    Text("$label: ${display(v)}", modifier = Modifier.padding(top = 12.dp))
    Slider(value = v, onValueChange = { v = it }, onValueChangeFinished = { onCommit(v) }, valueRange = range)
}
