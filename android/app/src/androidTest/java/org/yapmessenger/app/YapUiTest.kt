package org.yapmessenger.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class YapUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun composerChecksUtf8BytesAndKeyboardSendDoesNotBypassValidation() {
        compose.onNodeWithText("Bluetooth test chat").performClick()
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("😀".repeat(6))
        compose.onNodeWithText("24 / 20 UTF-8 bytes · Shorten your message").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.onNodeWithText("24 / 20 UTF-8 bytes · Shorten your message").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextReplacement("😀".repeat(5))
        compose.onNodeWithText("20 / 20 UTF-8 bytes").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send").assertIsEnabled()
    }

    @Test fun systemBackReturnsThroughControlsAndConversationToChats() {
        compose.onNodeWithText("Bluetooth test chat").performClick()
        compose.onNodeWithContentDescription("Bluetooth controls").performClick()
        compose.onNodeWithText("Connection controls.").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Message").assertExists()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Messages").assertIsDisplayed()
    }

    @Test fun networkAndSettingsRemainReachableAndTruthful() {
        compose.onNodeWithText("Network").performClick()
        compose.onNodeWithText("THIS PHONE · NO LIVE PEER MAP").assertExists()
        compose.onNodeWithText("Make this phone discoverable").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Settings", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Encryption and verified identities are not available in this prototype.").assertExists()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Messages").assertIsDisplayed()
    }
}
