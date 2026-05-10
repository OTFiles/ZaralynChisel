package com.zaralynchisel.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Manages app-wide user preferences with Material3 theming support.
 */
class PreferenceManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Theme ──────────────────────────────────────────────────────────
    var themeMode: ThemeMode
        get() = ThemeMode.fromValue(prefs.getString(KEY_THEME, ThemeMode.AUTO.value) ?: ThemeMode.AUTO.value)
        set(value) = prefs.edit { putString(KEY_THEME, value.value) }

    var accentColorIndex: Int
        get() = prefs.getInt(KEY_ACCENT_COLOR, 0)
        set(value) = prefs.edit { putInt(KEY_ACCENT_COLOR, value) }

    // ── Performance ────────────────────────────────────────────────────
    var maxMemoryPercent: Int
        get() = prefs.getInt(KEY_MAX_MEMORY, 70)
        set(value) = prefs.edit { putInt(KEY_MAX_MEMORY, value.coerceIn(25, 95)) }

    var renderDistance: Int
        get() = prefs.getInt(KEY_RENDER_DISTANCE, 12)
        set(value) = prefs.edit { putInt(KEY_RENDER_DISTANCE, value.coerceIn(4, 48)) }

    var batchSize: Int
        get() = prefs.getInt(KEY_BATCH_SIZE, 16)
        set(value) = prefs.edit { putInt(KEY_BATCH_SIZE, value.coerceIn(1, 64)) }

    var undoLimit: Int
        get() = prefs.getInt(KEY_UNDO_LIMIT, 50)
        set(value) = prefs.edit { putInt(KEY_UNDO_LIMIT, value.coerceIn(5, 200)) }

    // ── Texture ────────────────────────────────────────────────────────
    var textureCacheSizeMB: Int
        get() = prefs.getInt(KEY_TEXTURE_CACHE_MB, 512)
        set(value) = prefs.edit { putInt(KEY_TEXTURE_CACHE_MB, value.coerceIn(64, 2048)) }

    // ── Recent Worlds ──────────────────────────────────────────────────
    fun getRecentWorlds(): List<String> =
        prefs.getStringSet(KEY_RECENT_WORLDS, emptySet())?.toList() ?: emptyList()

    fun addRecentWorld(path: String) {
        val worlds = getRecentWorlds().toMutableSet()
        worlds.add(path)
        // Keep only last 10
        if (worlds.size > 10) {
            val sorted = worlds.toList().takeLast(10)
            prefs.edit { putStringSet(KEY_RECENT_WORLDS, sorted.toSet()) }
        } else {
            prefs.edit { putStringSet(KEY_RECENT_WORLDS, worlds) }
        }
    }

    fun removeRecentWorld(path: String) {
        val worlds = getRecentWorlds().toMutableSet()
        worlds.remove(path)
        prefs.edit { putStringSet(KEY_RECENT_WORLDS, worlds) }
    }

    // ── Texture Download Mirrors ───────────────────────────────────────
    var textureMirrorUrl: String
        get() = prefs.getString(KEY_TEXTURE_MIRROR, DEFAULT_MIRROR) ?: DEFAULT_MIRROR
        set(value) = prefs.edit { putString(KEY_TEXTURE_MIRROR, value) }

    companion object {
        private const val PREFS_NAME = "zaralyn_chisel_prefs"

        private const val KEY_THEME = "theme"
        private const val KEY_ACCENT_COLOR = "accent_color"
        private const val KEY_MAX_MEMORY = "max_memory"
        private const val KEY_RENDER_DISTANCE = "render_distance"
        private const val KEY_BATCH_SIZE = "batch_size"
        private const val KEY_UNDO_LIMIT = "undo_limit"
        private const val KEY_TEXTURE_CACHE_MB = "texture_cache_mb"
        private const val KEY_RECENT_WORLDS = "recent_worlds"
        private const val KEY_TEXTURE_MIRROR = "texture_mirror"

        private const val DEFAULT_MIRROR = "https://resources.download.minecraft.net"
    }
}

enum class ThemeMode(val value: String) {
    LIGHT("light"),
    DARK("dark"),
    AUTO("auto");

    companion object {
        fun fromValue(value: String): ThemeMode =
            entries.firstOrNull { it.value == value } ?: AUTO
    }
}