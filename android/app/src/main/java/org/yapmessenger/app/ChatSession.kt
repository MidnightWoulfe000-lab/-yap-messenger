package org.yapmessenger.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal data class ChatMessage(val mine: Boolean, val body: String, val state: String = "")

/** UI state for the existing, foreground-only Bluetooth prototype. No saved history. */
internal class ChatSession {
    var status by mutableStateOf("Ready to connect")
    var draft by mutableStateOf("")
    var pendingText: String? by mutableStateOf(null)
        private set
    val messages = mutableStateListOf<ChatMessage>()

    fun beginSend(): String? {
        if (pendingText != null) return null
        if (draft.toByteArray(Charsets.UTF_8).size !in 1..20) {
            status = "Messages must be between 1 and 20 UTF-8 bytes"
            return null
        }
        pendingText = draft
        status = "Looking for a nearby phone..."
        return pendingText
    }

    fun onBluetoothUpdate(update: String) {
        status = update
        when {
            update.startsWith("Message received: ") ->
                messages.add(ChatMessage(false, update.removePrefix("Message received: ")))
            update == "Message delivered to the other phone." -> {
                pendingText?.let { sent ->
                    messages.add(ChatMessage(true, sent, "Bluetooth acknowledged"))
                    if (draft == sent) draft = ""
                }
                pendingText = null
                status = "Bluetooth write acknowledged. Receipt and reading are not confirmed."
            }
            update.contains("failed", true) || update.contains("timed out", true) ||
                listOf("No nearby", "Disconnected before", "Couldn't", "Could not", "Please turn on",
                    "Bluetooth isn't", "BLE scanning isn't", "For now,", "Still connecting.")
                    .any { update.startsWith(it) } -> pendingText = null
        }
    }

    fun onStopped() {
        pendingText = null
        status = "Bluetooth stopped. Keep Yap open and make this phone discoverable again."
    }
}
