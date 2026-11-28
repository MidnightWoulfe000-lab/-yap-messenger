package org.yapmessenger.app

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
