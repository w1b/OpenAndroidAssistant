/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.tools.impl

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val WEEKDAY_DATE = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)
private val WEEKDAY_DATE_YEAR = DateTimeFormatter.ofPattern("EEE, MMM d, uuuu", Locale.US)

/** "Today, 2:14 PM", "Yesterday, 9:00 AM", "Tomorrow, 4:00 PM", "Mon, Oct 20, 3:00 PM". */
fun formatWhen(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
  val dt = Instant.ofEpochMilli(epochMillis).atZone(zone)
  return "${formatDay(dt.toLocalDate(), zone)}, ${TIME.format(dt)}"
}

fun formatDay(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String {
  val today = LocalDate.now(zone)
  return when (date) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    today.plusDays(1) -> "Tomorrow"
    else -> if (date.year == today.year) WEEKDAY_DATE.format(date) else WEEKDAY_DATE_YEAR.format(date)
  }
}

fun formatTime(time: LocalTime): String = TIME.format(time)

private val SPOKEN_DATE = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US)
private val SPOKEN_DATE_YEAR = DateTimeFormatter.ofPattern("EEEE, MMMM d, uuuu", Locale.US)

/** [formatDay] for speech: names in full ("Thursday, October 30"); "Thu, Oct 30" is read as is. */
fun spokenDay(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String {
  val today = LocalDate.now(zone)
  return when (date) {
    today -> "today"
    today.minusDays(1) -> "yesterday"
    today.plusDays(1) -> "tomorrow"
    else -> if (date.year == today.year) SPOKEN_DATE.format(date) else SPOKEN_DATE_YEAR.format(date)
  }
}

/** Parses "2026-10-27T16:00", "2026-10-27 16:00" or "2026-10-27T16:00:00" as local time. */
fun parseLocalDateTime(text: String): LocalDateTime? {
  val t = text.trim().replace(' ', 'T').removeSuffix("Z")
  return try {
    LocalDateTime.parse(t)
  } catch (e: DateTimeParseException) {
    runCatching { LocalDate.parse(t).atStartOfDay() }.getOrNull()
  }
}

fun parseLocalDate(text: String): LocalDate? =
  runCatching { LocalDate.parse(text.trim().take(10)) }.getOrNull()

/** "16:30" or "4:30 PM" → LocalTime. */
fun parseLocalTime(text: String): LocalTime? {
  val t = text.trim().uppercase(Locale.US)
  return runCatching { LocalTime.parse(t) }.getOrNull()
    ?: runCatching { LocalTime.parse(t, DateTimeFormatter.ofPattern("h:mm a", Locale.US)) }.getOrNull()
    ?: runCatching { LocalTime.parse(t, DateTimeFormatter.ofPattern("h a", Locale.US)) }.getOrNull()
}

/** "10 minutes", "1 hour 30 minutes", "45 seconds". */
fun formatDuration(totalSeconds: Long): String {
  val h = totalSeconds / 3600
  val m = (totalSeconds % 3600) / 60
  val s = totalSeconds % 60
  val parts = buildList {
    if (h > 0) add(if (h == 1L) "1 hour" else "$h hours")
    if (m > 0) add(if (m == 1L) "1 minute" else "$m minutes")
    if (s > 0 && h == 0L) add(if (s == 1L) "1 second" else "$s seconds")
  }
  return parts.joinToString(" ").ifEmpty { "0 seconds" }
}
