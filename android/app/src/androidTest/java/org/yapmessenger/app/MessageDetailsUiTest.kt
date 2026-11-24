package org.yapmessenger.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageDetailsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun liveBluetoothAcknowledgementDoesNotRenderARecipientReceipt() {
        val session = ChatSession { 1000L }
        session.draft = "Real message"
        session.beginSend()
        session.onBluetoothUpdate("Message delivered to the other phone.")
        compose.setContent { YapTheme { YapApp(session, send = {}, discover = {}) } }
        compose.onNodeWithText("Nearby Yap phone").performScrollTo().performClick()
        compose.onNodeWithText("Real message").performScrollTo().performClick()
        compose.onNodeWithText("Recipient receipt not confirmed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Recipient acknowledged receipt").assertDoesNotExist()
        compose.onNodeWithText("Queued").assertDoesNotExist()
    }

    @Test fun previewCannotSendOrClearLiveHistory() {
        val session = ChatSession()
        session.onBluetoothUpdate("Message received: Keep this")
        var sends = 0
        var discover = 0
        compose.setContent { YapTheme { YapApp(session, send = { sends++ }, discover = { discover++ }) } }
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Preview the full design").performScrollTo().performClick()
        compose.onNodeWithText("Ravi").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Data & storage").performScrollTo().performClick()
        compose.onNodeWithText("Clear this session").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(0, sends)
            assertEquals(0, discover)
            assertEquals("Keep this", session.messages.single().body)
        }
    }
}
