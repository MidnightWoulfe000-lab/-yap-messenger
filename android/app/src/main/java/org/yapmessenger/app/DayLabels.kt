package org.yapmessenger.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Day labels for chat history. Message times are reported by the sender's phone, so they are
 * labelled as given and never used to reorder messages (audit finding 12).
 */

/** One row of the conversation list: either a day separator or a message. */
internal sealed interface ConversationRow {
    val key: String
    data class Day(val label: String, override val key: String) : ConversationRow
    data class Message(val message: ChatMessage) : ConversationRow { override val key get() = "message:${message.id}" }
}

internal fun localDay(epochMillis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

/** How many days before [today] the day falls; negative when it is in the future. */
private fun daysAgo(day: LocalDate, today: LocalDate): Long = ChronoUnit.DAYS.between(day, today)

/**
 * "Today", "Yesterday", the weekday for the rest of the last week ("Monday"), otherwise a full
 * date ("27 November", or "27 November 2025" when it is not the current year). Future days,
 * which a sender's clock can produce, always get the full date.
 */
internal fun dayLabel(day: LocalDate, today: LocalDate, locale: Locale): String = when (daysAgo(day, today)) {
    0L -> "Today"
    1L -> "Yesterday"
    in 2L..6L -> DateTimeFormatter.ofPattern("EEEE", locale).format(day)
    else -> DateTimeFormatter.ofPattern(if (day.year == today.year) "d MMMM" else "d MMMM yyyy", locale).format(day)
}

/**
 * Inserts a day separator before the first message and wherever the local day changes from the
 * previous message, keeping the given order. A message without a time (0) stays in the current run.
 */
internal fun withDaySeparators(messages: List<ChatMessage>, today: LocalDate, zone: ZoneId, locale: Locale): List<ConversationRow> {
    val rows = ArrayList<ConversationRow>(messages.size + 4)
    var current: LocalDate? = null
    for (message in messages) {
        if (message.createdAt > 0) {
            val day = localDay(message.createdAt, zone)
            if (day != current) {
                rows += ConversationRow.Day(dayLabel(day, today, locale), "day:${message.id}")
                current = day
            }
        }
        rows += ConversationRow.Message(message)
    }
    return rows
}

/** Chat list time: the time for today, then "Yesterday", the weekday, or a short date ("27 Nov"). */
internal fun chatListTime(epochMillis: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault()): String {
    if (epochMillis == 0L) return ""
    val day = localDay(epochMillis, zone)
    val today = localDay(now, zone)
    return when (daysAgo(day, today)) {
        0L -> messageTime(epochMillis, zone)
        1L -> "Yesterday"
        in 2L..6L -> DateTimeFormatter.ofPattern("EEEE", locale).format(day)
        else -> DateTimeFormatter.ofPattern(if (day.year == today.year) "d MMM" else "d MMM yyyy", locale).format(day)
    }
}
