package com.picpocket.app.ui

import androidx.test.core.app.ApplicationProvider
import com.picpocket.app.domain.export.PageSize

import com.picpocket.app.ui.screens.settings.SettingsViewModel
import com.picpocket.app.ui.theme.DarkMode
import com.picpocket.app.ui.theme.ThemeManager
import com.picpocket.app.util.MainCoroutineRule
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private lateinit var viewModel: SettingsViewModel
    private lateinit var themeManager: ThemeManager

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        themeManager = ThemeManager(app)
        viewModel = SettingsViewModel(
            app,
            themeManager,
        )
    }

    @Test
    fun `default state has searchable PDF enabled`() {
        val state = viewModel.uiState.value
        assertTrue(state.searchablePdf)
    }

    @Test
    fun `toggle searchable PDF off`() {
        viewModel.toggleSearchablePdf(false)
        assertFalse(viewModel.uiState.value.searchablePdf)
    }

    @Test
    fun `toggle searchable PDF back on`() {
        viewModel.toggleSearchablePdf(false)
        viewModel.toggleSearchablePdf(true)
        assertTrue(viewModel.uiState.value.searchablePdf)
    }

    @Test
    fun `default dark mode is SYSTEM`() {
        assertEquals(DarkMode.SYSTEM, viewModel.uiState.value.darkMode)
    }

    @Test
    fun `set dark mode to DARK`() {
        viewModel.setDarkMode(DarkMode.DARK)
        assertEquals(DarkMode.DARK, viewModel.uiState.value.darkMode)
    }

    @Test
    fun `set dark mode to LIGHT`() {
        viewModel.setDarkMode(DarkMode.LIGHT)
        assertEquals(DarkMode.LIGHT, viewModel.uiState.value.darkMode)
    }

    @Test
    fun `set dark mode persists in ThemeManager`() {
        viewModel.setDarkMode(DarkMode.DARK)
        assertEquals(DarkMode.DARK, themeManager.config.value.darkMode)
    }

    @Test
    fun `default page size is A4`() {
        assertEquals(PageSize.A4, viewModel.uiState.value.pageSize)
    }

    @Test
    fun `set page size to LETTER`() {
        viewModel.setPageSize(PageSize.LETTER)
        assertEquals(PageSize.LETTER, viewModel.uiState.value.pageSize)
    }

    @Test
    fun `set page size to A5`() {
        viewModel.setPageSize(PageSize.A5)
        assertEquals(PageSize.A5, viewModel.uiState.value.pageSize)
    }
}
