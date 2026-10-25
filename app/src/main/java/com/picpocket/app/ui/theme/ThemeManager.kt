package com.picpocket.app.ui.theme

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThemeManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<ThemeConfig> = _config.asStateFlow()

    private fun loadConfig(): ThemeConfig = ThemeConfig(
        darkMode = DarkMode.entries[prefs.getInt("theme_dark_mode", 0)],
    )

    fun setDarkMode(mode: DarkMode) {
        prefs.edit().putInt("theme_dark_mode", mode.ordinal).apply()
        _config.value = loadConfig()
    }
}
