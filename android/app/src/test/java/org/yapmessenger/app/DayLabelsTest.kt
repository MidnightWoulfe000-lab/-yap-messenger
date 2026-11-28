package org.yapmessenger.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class DayLabelsTest {
    private val zone = ZoneId.of("Europe/London")
    private val uk = Locale.UK
    private val today = LocalDate.of(2026, 11, 27) // a Friday

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0, nano: Int = 0) =
        LocalDateTime.of(year, month, day, hour, minute, second, nano).atZone(zone).toInstant().toEpochMilli()

    private fun message(id: Long, createdAt: Long) = ChatMessage(false, "m$id", id = id, createdAt = createdAt)

    private fun labels(rows: List<ConversationRow>) = rows.map {
        when (it) { is ConversationRow.Day -> "[${it.label}]"; is ConversationRow.Message -> it.message.body }
    }

    @Test fun todayAndYesterday() {
        assertEquals("Today", dayLabel(today, today, uk))
        assertEquals("Yesterday", dayLabel(today.minusDays(1), today, uk))
    }

    @Test fun weekdayWithinTheLastWeek() {
        assertEquals("Wednesday", dayLabel(today.minusDays(2), today, uk))
        assertEquals("Saturday", dayLabel(today.minusDays(6), today, uk))
    }

    @Test fun fullDateForOlderDaysThisYear() {
        // Seven days ago shares today's weekday, so it gets a date instead.
        assertEquals("20 November", dayLabel(today.minusDays(7), today, uk))
        assertEquals("3 January", dayLabel(LocalDate.of(2026, 1, 3), today, uk))
    }

    @Test fun yearOnlyWhenNotTheCurrentYear() {
        assertEquals("27 November 2025", dayLabel(LocalDate.of(2025, 11, 27), today, uk))
        // New Year: yesterday is still "Yesterday" even though the year differs.
        assertEquals("Yesterday", dayLabel(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 1), uk))
        assertEquals("24 December 2026", dayLabel(LocalDate.of(2026, 12, 24), LocalDate.of(2027, 1, 1), uk))
    }

    @Test fun futureDaysFromAFastSenderClockGetAFullDate() {
        assertEquals("28 November", dayLabel(today.plusDays(1), today, uk))
    }

    @Test fun weekdayAndMonthFollowTheLocale() {
        assertEquals("mercredi", dayLabel(today.minusDays(2), today, Locale.FRANCE))
        assertEquals("3 janvier", dayLabel(LocalDate.of(2026, 1, 3), today, Locale.FRANCE))
    }

    @Test fun separatorWhereverTheDayChanges() {
        val rows = withDaySeparators(listOf(
            message(1, at(2026, 11, 20, 9, 0)),
            message(2, at(2026, 11, 26, 22, 0)),
            message(3, at(2026, 11, 26, 23, 30)),
            message(4, at(2026, 11, 27, 9, 50)),
            message(5, at(2026, 11, 27, 10, 5)),
        ), today, zone, uk)
        assertEquals(listOf("[20 November]", "m1", "[Yesterday]", "m2", "m3", "[Today]", "m4", "m5"), labels(rows))
    }

    @Test fun midnightBoundary() {
        val rows = withDaySeparators(listOf(
            message(1, at(2026, 11, 26, 23, 59, 59, 999_000_000)),
            message(2, at(2026, 11, 27, 0, 0)),
        ), today, zone, uk)
        assertEquals(listOf("[Yesterday]", "m1", "[Today]", "m2"), labels(rows))
    }

    @Test fun usesTheGivenTimeZone() {
        // 23:30 in London is 00:30 the next day in Paris.
        val late = at(2026, 11, 26, 23, 30)
        assertEquals(listOf("[Yesterday]", "m1"), labels(withDaySeparators(listOf(message(1, late)), today, zone, uk)))
        assertEquals(listOf("[Today]", "m1"), labels(withDaySeparators(listOf(message(1, late)), today, ZoneId.of("Europe/Paris"), uk)))
    }

    @Test fun keepsTheGivenOrderWhenTimesGoBackwards() {
        // Sender-reported times are not trusted for ordering, so an out-of-order message just gets its own label.
        val rows = withDaySeparators(listOf(
            message(1, at(2026, 11, 27, 9, 0)),
            message(2, at(2026, 11, 26, 21, 0)),
            message(3, at(2026, 11, 27, 9, 5)),
        ), today, zone, uk)
        assertEquals(listOf("[Today]", "m1", "[Yesterday]", "m2", "[Today]", "m3"), labels(rows))
        assertEquals(rows.size, rows.map { it.key }.toSet().size) // list keys stay unique
    }

    @Test fun messagesWithoutATimeStayInTheCurrentRun() {
        val rows = withDaySeparators(listOf(message(1, 0), message(2, at(2026, 11, 27, 9, 0)), message(3, 0)), today, zone, uk)
        assertEquals(listOf("m1", "[Today]", "m2", "m3"), labels(rows))
        assertEquals(emptyList<ConversationRow>(), withDaySeparators(emptyList(), today, zone, uk))
    }

    @Test fun chatListTime() {
        val now = at(2026, 11, 27, 12, 0)
        assertEquals(messageTime(at(2026, 11, 27, 9, 50), zone), chatListTime(at(2026, 11, 27, 9, 50), now, zone, uk))
        assertEquals("Yesterday", chatListTime(at(2026, 11, 26, 22, 0), now, zone, uk))
        assertEquals("Monday", chatListTime(at(2026, 11, 23, 8, 0), now, zone, uk))
        assertEquals("20 Nov", chatListTime(at(2026, 11, 20, 8, 0), now, zone, uk))
        assertEquals("27 Nov 2025", chatListTime(at(2025, 11, 27, 8, 0), now, zone, uk))
        assertEquals("", chatListTime(0, now, zone, uk))
    }
}
