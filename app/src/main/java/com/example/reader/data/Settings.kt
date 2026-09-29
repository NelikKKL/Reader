package com.example.reader.data

import android.content.SharedPreferences

enum class ThemeMode { SYSTEM, DARK, LIGHT }
enum class ColorSource { SYSTEM, WALLPAPER, DEFAULT }
enum class PageAnim { SLIDE, CURL, NONE }
enum class FontChoice { SANS, SERIF, MONO, CONDENSED, CUSTOM }
enum class LibraryView { GRID, COMPACT, LIST }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val colorSource: ColorSource = ColorSource.SYSTEM,
    val amoled: Boolean = false,
    val font: FontChoice = FontChoice.SERIF,
    val customFontPath: String? = null,
    val customFontName: String? = null,
    val fontSize: Float = 19f,
    val lineSpacing: Float = 1.3f,
    val margin: Int = 24,
    val bold: Boolean = false,
    val pageAnim: PageAnim = PageAnim.SLIDE,
    val libraryView: LibraryView = LibraryView.GRID
)

class SettingsStore(private val prefs: SharedPreferences) {
    private inline fun <reified T : Enum<T>> enumOf(name: String?, def: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: def

    fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            themeMode = enumOf(prefs.getString("theme_mode", null), d.themeMode),
            colorSource = enumOf(prefs.getString("color_source", null), d.colorSource),
            amoled = prefs.getBoolean("amoled", d.amoled),
            font = enumOf(prefs.getString("font_family", null), d.font),
            customFontPath = prefs.getString("custom_font_path", null),
            customFontName = prefs.getString("custom_font_name", null),
            fontSize = prefs.getFloat("font_size", d.fontSize),
            lineSpacing = prefs.getFloat("line_spacing", d.lineSpacing),
            margin = prefs.getInt("margin", d.margin),
            bold = prefs.getBoolean("bold", d.bold),
            pageAnim = enumOf(prefs.getString("page_anim", null), d.pageAnim),
            libraryView = enumOf(prefs.getString("library_view", null), d.libraryView)
        )
    }

    fun save(s: AppSettings) {
        prefs.edit()
            .putString("theme_mode", s.themeMode.name)
            .putString("color_source", s.colorSource.name)
            .putBoolean("amoled", s.amoled)
            .putString("font_family", s.font.name)
            .putString("custom_font_path", s.customFontPath)
            .putString("custom_font_name", s.customFontName)
            .putFloat("font_size", s.fontSize)
            .putFloat("line_spacing", s.lineSpacing)
            .putInt("margin", s.margin)
            .putBoolean("bold", s.bold)
            .putString("page_anim", s.pageAnim.name)
            .putString("library_view", s.libraryView.name)
            .apply()
    }
}
