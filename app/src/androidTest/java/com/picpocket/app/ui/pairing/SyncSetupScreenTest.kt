package com.picpocket.app.ui.pairing

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.picpocket.app.drive.sync.FolderLocator
import com.picpocket.app.drive.sync.SyncSetupCodec
import com.picpocket.app.drive.sync.SyncSetupPayload
import com.picpocket.app.ui.screens.pairing.SetupCodeCard
import com.picpocket.app.ui.screens.pairing.SyncSetupContent
import com.picpocket.app.ui.screens.pairing.SyncSetupUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncSetupScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val code = SyncSetupCodec.encode(
        SyncSetupPayload(
            folder = FolderLocator("com.example.documents", "primary:PicPocketTest", "PicPocketTest"),
            passphrase = "pw",
            passphraseCount = 2,
        ),
    )

    @Test
    fun setupCodeRenders() {
        composeRule.setContent {
            MaterialTheme { SetupCodeCard(code = code, passphraseIncluded = true) }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("setup_code").assertIsDisplayed()
    }

    @Test
    fun configuredDeviceCanShare() {
        composeRule.setContent {
            MaterialTheme {
                SyncSetupContent(
                    state = SyncSetupUiState(
                        localDeviceId = "dev-a",
                        localDeviceName = "Pixel",
                        shareCode = code,
                        passphraseIncluded = true,
                    ),
                    onNavigateBack = {},
                    onPasteChange = {},
                    onApplyPasted = {},
                    onCodeScanned = {},
                    onUnpair = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("share_setup").assertIsDisplayed()
        composeRule.onNodeWithTag("scan_setup").assertIsDisplayed()
    }

    @Test
    fun noFolderDisablesSharing() {
        composeRule.setContent {
            MaterialTheme {
                SyncSetupContent(
                    state = SyncSetupUiState(shareCode = null),
                    onNavigateBack = {},
                    onPasteChange = {},
                    onApplyPasted = {},
                    onCodeScanned = {},
                    onUnpair = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("share_setup").assertIsNotEnabled()
    }

    @Test
    fun pastingACodeInvokesApply() {
        var applied = false
        composeRule.setContent {
            var state by remember { mutableStateOf(SyncSetupUiState(shareCode = null)) }
            MaterialTheme {
                SyncSetupContent(
                    state = state,
                    onNavigateBack = {},
                    onPasteChange = { state = state.copy(pastedCode = it) },
                    onApplyPasted = { applied = true },
                    onCodeScanned = {},
                    onUnpair = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("pasted_code").performTextInput("abc")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("apply_code").performClick()
        composeRule.waitForIdle()

        assertTrue(applied)
    }
}
