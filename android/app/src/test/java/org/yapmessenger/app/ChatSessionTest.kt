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
            assertNull(session.lastAttempt)
        }
        session.draft = "😀".repeat(5)
        assertEquals(session.draft, session.beginSend())
    }

    @Test fun acknowledgementRetainsAttemptTimeAndPreservesAnEditedDraft() {
        var clock = 1000L
        val session = ChatSession { clock }
        session.draft = "Hi from A"
        assertEquals("Hi from A", session.beginSend())
        assertEquals(1000L, session.lastAttempt?.createdAt)
        session.draft = "Next message"
        assertNull(session.beginSend())
        clock = 2000L
        session.onBluetoothUpdate("Message delivered to the other phone.")
        assertEquals("Next message", session.draft)
        assertNull(session.pendingText)
        val sent = session.messages.single()
        assertTrue(sent.mine)
        assertEquals("Hi from A", sent.body)
        assertEquals("Bluetooth acknowledged", sent.state)
        assertEquals(1000L, sent.createdAt)
        assertEquals(2000L, sent.acknowledgedAt)
        assertNull(sent.recipientReceiptAt)
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
        val session = ChatSession { 3000L }
        session.draft = "Hi from A"
        session.beginSend()
        for (update in listOf("Looking for another phone running Yap...",
            "Found 1 Yap device(s). Connecting...", "Connected. Sending message...",
            "Yap is visible to nearby phones.", "Message received: Hi from B")) {
            session.onBluetoothUpdate(update)
            assertEquals("Hi from A", session.pendingText)
        }
        val received = session.messages.single()
        assertFalse(received.mine)
        assertEquals("Hi from B", received.body)
        assertEquals(3000L, received.createdAt)
        assertNull(received.recipientReceiptAt)
    }

    @Test fun transportFailuresReleaseTheComposerWithoutLosingTheDraft() {
        val failures = listOf(
            "Bluetooth isn't supported.", "Please turn on Bluetooth.",
            "BLE scanning isn't available.", "Yap discovery failed (error 2).",
            "No nearby Yap phone found. Try again.", "Connection timed out. Try sending again.",
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
            assertEquals(failure, session.lastAttempt?.failureReason)
            assertNull(session.lastAttempt?.recipientReceiptAt)
            assertEquals("Try again", session.draft)
            assertEquals("Try again", session.beginSend())
        }
    }

    @Test fun stoppingRetainsTheUnconfirmedAttemptAndDraft() {
        val session = ChatSession()
        session.draft = "Keep me"
        session.beginSend()
        session.onStopped()
        assertNull(session.pendingText)
        assertEquals("Keep me", session.draft)
        assertTrue(session.messages.isEmpty())
        assertNotNull(session.lastAttempt?.failureReason)
        assertNull(session.lastAttempt?.acknowledgedAt)
    }

    @Test fun peerConnectionExistsOnlyAfterObservedCallbacks() {
        val session = ChatSession()
        assertEquals(PeerObservation.NONE, session.peer)
        session.draft = "Hello"
        session.beginSend()
        assertEquals(PeerObservation.NONE, session.peer)
        session.onBluetoothUpdate("Found 1 Yap device(s). Connecting...")
        assertEquals(PeerObservation.CONNECTING, session.peer)
        session.onBluetoothUpdate("Connected. Sending message...")
        assertEquals(PeerObservation.CONNECTED, session.peer)
        session.onBluetoothUpdate("Message delivered to the other phone.")
        assertEquals(PeerObservation.RECENT, session.peer)
    }

    @Test fun advertisingAndUnreadCountsFollowLocalEvents() {
        val session = ChatSession()
        session.onBluetoothUpdate("Yap is visible to nearby phones.")
        assertTrue(session.advertising)
        session.onBluetoothUpdate("Message received: failed is text, not a failure")
        assertEquals(1, session.unreadCount)
        assertEquals(PeerObservation.RECENT, session.peer)
        session.markRead()
        assertEquals(0, session.unreadCount)
        session.onStopped()
        assertFalse(session.advertising)
    }

    @Test fun clearHistoryCannotDiscardAnActiveSend() {
        val session = ChatSession()
        session.onBluetoothUpdate("Message received: Hello")
        session.draft = "Reply"
        session.beginSend()
        session.clearHistory()
        assertEquals(1, session.messages.size)
        assertEquals("Reply", session.pendingText)
        session.onStopped()
        session.clearHistory()
        assertTrue(session.messages.isEmpty())
        assertNull(session.lastAttempt)
    }
}
