package org.yapmessenger.app

import org.junit.Assert.*
import org.junit.Test

class ChatSessionTest {
    @Test fun validatesUtf8BytesRatherThanCharacters() {
        val session = ChatSession()
        for (invalid in listOf("", "a".repeat(21), "😀".repeat(6))) {
            session.draft = invalid
            assertNull(session.beginSend())
            assertNull(session.pendingText)
            assertEquals(invalid, session.draft)
        }
        session.draft = "😀".repeat(5)
        assertEquals(session.draft, session.beginSend())
    }

    @Test fun acknowledgementAddsTheOriginalMessageAndPreservesAnEditedDraft() {
        val session = ChatSession()
        session.draft = "Hi from A"
        assertEquals("Hi from A", session.beginSend())
        session.draft = "Next message"
        assertNull(session.beginSend())
        session.onBluetoothUpdate("Message delivered to the other phone.")
        assertEquals("Next message", session.draft)
        assertNull(session.pendingText)
        assertEquals(listOf(ChatMessage(true, "Hi from A", "Bluetooth acknowledged")), session.messages)
        assertFalse(session.status.contains("delivered", ignoreCase = true))
        session.onBluetoothUpdate("Message delivered to the other phone.")
        assertEquals(1, session.messages.size)
    }

    @Test fun acknowledgementClearsOnlyTheSentDraft() {
        val session = ChatSession()
        session.draft = "Hi"
        session.beginSend()
        session.onBluetoothUpdate("Message delivered to the other phone.")
        assertEquals("", session.draft)
    }

    @Test fun incomingMessagesAndProgressDoNotCancelAnOutgoingMessage() {
        val session = ChatSession()
        session.draft = "Hi from A"
        session.beginSend()
        for (update in listOf("Looking for another phone running Yap...",
            "Found 1 Yap device(s). Connecting...", "Connected. Sending message...",
            "Yap is visible to nearby phones.", "Message received: Hi from B")) {
            session.onBluetoothUpdate(update)
            assertEquals("Hi from A", session.pendingText)
        }
        assertEquals(listOf(ChatMessage(false, "Hi from B")), session.messages)
    }

    @Test fun transportFailuresReleaseTheComposerWithoutLosingTheDraft() {
        val failures = listOf(
            "Bluetooth isn't supported.", "Please turn on Bluetooth.",
            "BLE scanning isn't available.", "Yap discovery failed (error 2).",
            "No nearby Yap phone found. Try again.",
            "Connection timed out. Try sending again.",
            "Disconnected before send was confirmed (status 133).",
            "Couldn't discover the Yap service.", "Could not find Yap's message service.",
            "Could not start Bluetooth message transfer.", "Message transfer failed (status 1).",
            "Still connecting. Please wait a moment before sending again."
        )
        for (failure in failures) {
            val session = ChatSession()
            session.draft = "Try again"
            session.beginSend()
            session.onBluetoothUpdate(failure)
            assertNull(failure, session.pendingText)
            assertTrue(session.messages.isEmpty())
            assertEquals("Try again", session.draft)
            assertEquals("Try again", session.beginSend())
        }
    }

    @Test fun stoppingReleasesTheComposerAndKeepsAnUnsentDraft() {
        val session = ChatSession()
        session.draft = "Keep me"
        session.beginSend()
        session.onStopped()
        assertNull(session.pendingText)
        assertEquals("Keep me", session.draft)
        assertTrue(session.messages.isEmpty())
    }
}
