package org.yapmessenger.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The composer's byte limit, checked on an encrypted conversation screen (limit 4,096 UTF-8 bytes;
 * the legacy chat's 20-byte limit went with the legacy chat). The screen is shown on its own with a
 * counting send callback, because a real encrypted chat needs a paired contact.
 */
@RunWith(AndroidJUnit4::class)
class ComposerUiTest {
    @get:Rule val compose = createComposeRule()
    private var sent = 0

    private fun show() {
        compose.setContent {
            YapTheme {
                var draft by remember { mutableStateOf("") }
                ConversationScreen(ChatSummary("secure:x", "Friend", "Ready"), emptyList(), preview = false,
                    draft = draft, onDraft = { draft = it }, pending = false, status = "", send = { sent++ },
                    back = {}, controls = {}, group = {}, details = {}, maxBytes = 4096, sendEnabled = true, secure = true)
            }
        }
    }

    @Test fun composerChecksUtf8BytesAndKeyboardSendDoesNotBypassValidation() {
        show()
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        // 1,025 four-byte emoji = 4,100 bytes: over the limit by 4.
        compose.onNode(hasSetTextAction()).performTextInput("😀".repeat(1025))
        compose.onNodeWithText("4100 / 4096 UTF-8 bytes · Shorten your message").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.onNodeWithText("4100 / 4096 UTF-8 bytes · Shorten your message").assertIsDisplayed()
        assertEquals("keyboard send must not bypass validation", 0, sent)
        compose.onNode(hasSetTextAction()).performTextReplacement("😀".repeat(1024))
        compose.onNodeWithText("4096 / 4096 UTF-8 bytes").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send").assertIsEnabled()
        compose.onNode(hasSetTextAction()).performImeAction()
        assertEquals("a valid message does send from the keyboard", 1, sent)
    }
}
