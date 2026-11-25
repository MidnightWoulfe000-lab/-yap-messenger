package org.yapmessenger.app

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel

/** IDs and timestamps are local UI observations, not protocol IDs or delivery receipts. */
internal data class ChatMessage(
    val mine: Boolean,
    val body: String,
    val state: String = "",
    val id: Long = 0,
    val createdAt: Long = 0,
    val acknowledgedAt: Long? = null,
    val failureReason: String? = null,
    // Populated only by the explicitly labelled design-preview fixtures.
    val fileName: String? = null,
    val fileSize: String? = null,
    val recipientReceiptAt: Long? = null,
    val secure: Boolean = false
)

internal enum class PeerObservation { NONE, CONNECTING, CONNECTED, RECENT }

/** In-memory presentation state. The proven BLE transport and callbacks stay unchanged. */
internal class ChatSession(private val now: () -> Long = { System.currentTimeMillis() }) : ViewModel() {
    var status by mutableStateOf("Ready to connect")
    var draft by mutableStateOf("")
    var pendingText: String? by mutableStateOf(null)
        private set
    var lastAttempt: ChatMessage? by mutableStateOf(null)
        private set
    var peer by mutableStateOf(PeerObservation.NONE)
        private set
    var advertising by mutableStateOf(false)
        private set
    val messages = mutableStateListOf<ChatMessage>()
    private var nextId = 1L
    private var lastReadId by mutableStateOf(0L)
    val unreadCount get() = messages.count { !it.mine && it.id > lastReadId }

    fun markRead() { lastReadId = messages.maxOfOrNull { it.id } ?: 0L }

    fun beginSend(): String? {
        if (pendingText != null) return null
        if (draft.toByteArray(Charsets.UTF_8).size !in 1..20) {
            status = "Messages must be between 1 and 20 UTF-8 bytes"
            return null
        }
        pendingText = draft
        lastAttempt = ChatMessage(true, draft, "Sending…", nextId++, now())
        status = "Looking for a nearby phone…"
        return pendingText
    }

    fun onBluetoothUpdate(update: String) {
        status = update
        when {
            update.startsWith("Message received: ") -> {
                messages.add(ChatMessage(false, update.removePrefix("Message received: "), id = nextId++, createdAt = now()))
                if (peer != PeerObservation.CONNECTED) peer = PeerObservation.RECENT
                status = "Message received over Bluetooth. Identity not checked."
            }
            update.startsWith("Found ") -> peer = PeerObservation.CONNECTING
            update == "Connected. Sending message..." -> peer = PeerObservation.CONNECTED
            update == "Yap is visible to nearby phones." -> advertising = true
            update == "Message delivered to the other phone." -> {
                pendingText?.let { sent ->
                    lastAttempt?.copy(state = "Bluetooth acknowledged", acknowledgedAt = now())?.let {
                        messages.add(it)
                        lastAttempt = it
                    }
                    if (draft == sent) draft = ""
                }
                pendingText = null
                // The transport disconnects after a write; do not imply a persistent connection.
                if (peer != PeerObservation.NONE) peer = PeerObservation.RECENT
                status = "Bluetooth write acknowledged. Receipt and reading are not confirmed."
            }
            update.contains("failed", true) || update.contains("timed out", true) ||
                listOf("No nearby", "Disconnected before", "Couldn't", "Could not", "Please turn on",
                    "Bluetooth isn't", "BLE scanning isn't", "For now,", "Still connecting.")
                    .any { update.startsWith(it) } -> {
                finishUnconfirmed(update)
                if (peer != PeerObservation.NONE) peer = PeerObservation.RECENT
            }
        }
    }

    private fun finishUnconfirmed(reason: String) {
        if (pendingText != null) lastAttempt = lastAttempt?.copy(state = "Send not confirmed", failureReason = reason)
        pendingText = null
    }

    fun onStopped() {
        finishUnconfirmed("Yap stopped before the Bluetooth write was confirmed.")
        advertising = false
        if (peer != PeerObservation.NONE) peer = PeerObservation.RECENT
        status = "Bluetooth stopped. Keep Yap open and make this phone discoverable again."
    }

    fun clearHistory() {
        if (pendingText != null) return
        messages.clear()
        lastAttempt = null
        lastReadId = 0
    }
}
