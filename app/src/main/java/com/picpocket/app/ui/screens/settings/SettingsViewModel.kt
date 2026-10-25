package com.picpocket.app.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.picpocket.app.domain.export.PageSize
import com.picpocket.app.domain.scan.QualityTier
import com.picpocket.app.ui.theme.DarkMode
import com.picpocket.app.ui.theme.ThemeManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val searchablePdf: Boolean = true,
    val darkMode: DarkMode = DarkMode.SYSTEM,
    val pageSize: PageSize = PageSize.A4,
    val qualityTier: QualityTier = QualityTier.BEST,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val themeManager: ThemeManager,
) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("settings", 0)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        val savedSize = prefs.getString("page_size", PageSize.A4.name) ?: PageSize.A4.name
        val pageSize = try { PageSize.valueOf(savedSize) } catch (_: Exception) { PageSize.A4 }
        val savedQuality = prefs.getInt("quality_tier", QualityTier.BEST.ordinal)
        val qualityTier = QualityTier.entries.getOrElse(savedQuality) { QualityTier.BEST }
        val config = themeManager.config.value
        _uiState.update {
            it.copy(
                searchablePdf = prefs.getBoolean("searchable_pdf", true),
                pageSize = pageSize,
                qualityTier = qualityTier,
                darkMode = config.darkMode,
            )
        }
    }

    fun toggleSearchablePdf(enabled: Boolean) {
        prefs.edit().putBoolean("searchable_pdf", enabled).apply()
        _uiState.update { it.copy(searchablePdf = enabled) }
    }

    fun setDarkMode(mode: DarkMode) {
        themeManager.setDarkMode(mode)
        _uiState.update { it.copy(darkMode = mode) }
    }

    fun setPageSize(size: PageSize) {
        prefs.edit().putString("page_size", size.name).apply()
        _uiState.update { it.copy(pageSize = size) }
    }

    fun setQualityTier(tier: QualityTier) {
        prefs.edit().putInt("quality_tier", tier.ordinal).apply()
        _uiState.update { it.copy(qualityTier = tier) }
    }


}
