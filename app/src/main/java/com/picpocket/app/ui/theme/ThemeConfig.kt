package com.picpocket.app.ui.theme

enum class DarkMode { SYSTEM, LIGHT, DARK }

data class ThemeConfig(
    val darkMode: DarkMode = DarkMode.SYSTEM,
)
