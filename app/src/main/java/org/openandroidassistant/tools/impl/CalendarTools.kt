/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.tools.impl

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openandroidassistant.orchestrator.RenderCard
import org.openandroidassistant.permissions.AppCapability
import org.openandroidassistant.tools.ApprovalCard
import org.openandroidassistant.tools.ApprovalContent
import org.openandroidassistant.tools.ApprovalLevel
import org.openandroidassistant.tools.BaseTool
import org.openandroidassistant.tools.HandleType
import org.openandroidassistant.tools.LockedRisk
import org.openandroidassistant.tools.ParamKind
import org.openandroidassistant.tools.ResultItem
import org.openandroidassistant.tools.ToolCategory
import org.openandroidassistant.tools.ToolContext
import org.openandroidassistant.tools.ToolMetadata
import org.openandroidassistant.tools.ToolOutcome
import org.openandroidassistant.tools.ToolParam
import org.openandroidassistant.tools.ToolSchema
import org.openandroidassistant.tools.ValidatedCall

private const val MAX_EVENTS = 15

/** An event as the app knows it, for cards and approval prompts. */
internal data class CalendarEvent(
  val id: Long,
  val title: String,
  val begin: Long,
  val end: Long,
  val allDay: Boolean,
  val location: String?,
  val color: Int?,
) {
  fun whenLabel(zone: ZoneId = ZoneId.systemDefault()): String {
    val start = Instant.ofEpochMilli(begin).atZone(if (allDay) ZoneId.of("UTC") else zone)
    if (allDay) return "${formatDay(start.toLocalDate(), zone)}, all day"
    val endTime = Instant.ofEpochMilli(end).atZone(zone)
    return "${formatDay(start.toLocalDate(), zone)}, ${formatTime(start.toLocalTime())}–${formatTime(endTime.toLocalTime())}"
  }

  /** For spoken prompts: "tomorrow, from 3:00 PM to 4:00 PM"; speech turns the times into words. */
  fun spokenWhen(zone: ZoneId = ZoneId.systemDefault()): String {
    val start = Instant.ofEpochMilli(begin).atZone(if (allDay) ZoneId.of("UTC") else zone)
    if (allDay) return "${spokenDay(start.toLocalDate(), zone)}, all day"
    val endTime = Instant.ofEpochMilli(end).atZone(zone)
    return "${spokenDay(start.toLocalDate(), zone)}, from ${formatTime(start.toLocalTime())} to ${formatTime(endTime.toLocalTime())}"
  }

  fun card() = RenderCard.Event(title = title, timeLabel = whenLabel(), location = location, calendarColor = color)

  fun item() =
    ResultItem(
      type = HandleType.EVENT,
      nativeId = id.toString(),
      projection = mapOf("title" to title, "when" to whenLabel(), "location" to location),
      renderPayload = card(),
    )
}

private fun parseStart(text: String?): LocalDateTime? = text?.let(::parseLocalDateTime)

class FindEventsTool @Inject constructor() : BaseTool() {
  override val schema =
    ToolSchema(
      name = "find_events",
      description = "Find calendar events between two dates, optionally matching words in the title or location.",
      params =
        listOf(
          ToolParam("from_date", ParamKind.STRING, "First day, YYYY-MM-DD. Defaults to today.", required = false),
          ToolParam("to_date", ParamKind.STRING, "Last day (inclusive), YYYY-MM-DD. Defaults to from_date.", required = false),
          ToolParam("query", ParamKind.STRING, "Only events whose title or location contains this.", required = false),
        ),
    )

  override val metadata =
    ToolMetadata(
      id = "find_events",
      lockedRisk = LockedRisk.READS_PRIVATE,
      title = "Find calendar events",
      summary = "Read your events for a day or a range of days.",
      category = ToolCategory.PLANNING,
      requiredCapabilities = listOf(AppCapability.CALENDAR),
      progressPhrase = "Checking your calendar.",
    )

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome =
    withContext(Dispatchers.IO) {
      val zone = ZoneId.systemDefault()
      val from = call.stringArg("from_date")?.let(::parseLocalDate) ?: LocalDate.now(zone)
      val to = call.stringArg("to_date")?.let(::parseLocalDate) ?: from
      if (to.isBefore(from)) return@withContext ToolOutcome.Steering("error", "to_date is before from_date.")
      val query = call.stringArg("query")?.lowercase()
      val events =
        queryInstances(
            context.appContext,
            from.atStartOfDay(zone).toInstant().toEpochMilli(),
            to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
          )
          .filter { e ->
            query == null || e.title.lowercase().contains(query) || e.location?.lowercase()?.contains(query) == true
          }
      if (events.isEmpty()) {
        ToolOutcome.Steering("empty", "No events in that range. Tell the user it's clear.")
      } else {
        ToolOutcome.Success(items = events.take(MAX_EVENTS).map { it.item() }, hasMore = events.size > MAX_EVENTS)
      }
    }
}

internal fun queryInstances(context: Context, begin: Long, end: Long): List<CalendarEvent> {
  val out = mutableListOf<CalendarEvent>()
  Instances.query(
      context.contentResolver,
      arrayOf(
        Instances.EVENT_ID,
        Instances.TITLE,
        Instances.BEGIN,
        Instances.END,
        Instances.ALL_DAY,
        Instances.EVENT_LOCATION,
        Instances.DISPLAY_COLOR,
      ),
      begin,
      end,
    )
    ?.use { c ->
      while (c.moveToNext()) {
        out +=
          CalendarEvent(
            id = c.getLong(0),
            title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "(No title)",
            begin = c.getLong(2),
            end = c.getLong(3),
            allDay = c.getInt(4) == 1,
            location = c.getString(5)?.takeIf { it.isNotBlank() },
            color = if (c.isNull(6)) null else c.getInt(6),
          )
      }
    }
  return out.sortedBy { it.begin }
}

private fun loadEvent(context: Context, id: Long): CalendarEvent? =
  context.contentResolver
    .query(
      ContentUris.withAppendedId(Events.CONTENT_URI, id),
      arrayOf(Events._ID, Events.TITLE, Events.DTSTART, Events.DTEND, Events.ALL_DAY, Events.EVENT_LOCATION, Events.DISPLAY_COLOR),
      null,
      null,
      null,
    )
    ?.use { c ->
      if (!c.moveToFirst()) null
      else
        CalendarEvent(
          id = c.getLong(0),
          title = c.getString(1) ?: "(No title)",
          begin = c.getLong(2),
          end = if (c.isNull(3)) c.getLong(2) + 3_600_000 else c.getLong(3),
          allDay = c.getInt(4) == 1,
          location = c.getString(5),
          color = if (c.isNull(6)) null else c.getInt(6),
        )
    }

/** The calendar new events go into: the primary, writable, visible one. */
private fun defaultCalendarId(context: Context): Long? =
  context.contentResolver
    .query(
      CalendarContract.Calendars.CONTENT_URI,
      arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
      "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR} AND " +
        "${CalendarContract.Calendars.VISIBLE} = 1",
      null,
      "${CalendarContract.Calendars.IS_PRIMARY} DESC, ${CalendarContract.Calendars._ID} ASC",
    )
    ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }

/** Start/end in epoch millis from the call's start, end and duration_minutes arguments. */
private fun span(start: LocalDateTime, endText: String?, durationMinutes: Long?, zone: ZoneId): Pair<Long, Long> {
  val begin = start.atZone(zone).toInstant().toEpochMilli()
  val end =
    endText?.let(::parseLocalDateTime)?.atZone(zone)?.toInstant()?.toEpochMilli()
      ?: (begin + (durationMinutes ?: 60) * 60_000)
  return begin to maxOf(end, begin)
}

class CreateEventTool @Inject constructor() : BaseTool() {
  override val schema =
    ToolSchema(
      name = "create_event",
      description = "Add an event to the user's calendar.",
      params =
        listOf(
          ToolParam("title", ParamKind.STRING, "Event title."),
          ToolParam("start", ParamKind.STRING, "Local start time, YYYY-MM-DDTHH:MM. For all-day events, YYYY-MM-DD."),
          ToolParam("end", ParamKind.STRING, "Local end time, YYYY-MM-DDTHH:MM.", required = false),
          ToolParam("duration_minutes", ParamKind.INTEGER, "Length when no end is given. Defaults to 60.", required = false, minimum = 1.0, maximum = 100_000.0),
          ToolParam("all_day", ParamKind.BOOLEAN, "True for an all-day event.", required = false),
          ToolParam("location", ParamKind.STRING, "Where it is.", required = false),
        ),
    )

  override val metadata =
    ToolMetadata(
      id = "create_event",
      lockedRisk = LockedRisk.ACTS_AS_YOU,
      title = "Create calendar events",
      summary = "Add new events to your calendar.",
      category = ToolCategory.PLANNING,
      defaultApproval = ApprovalLevel.LOW,
      cancellable = false,
      requiredCapabilities = listOf(AppCapability.CALENDAR_WRITE),
      unlockVerb = "add that event",
    )

  private fun preview(call: ValidatedCall): CalendarEvent? {
    val start = parseStart(call.stringArg("start")) ?: return null
    val allDay = call.boolArg("all_day") == true || call.stringArg("start")?.length == 10
    val zone = if (allDay) ZoneId.of("UTC") else ZoneId.systemDefault()
    val (begin, end) =
      if (allDay) {
        val day = start.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        day to day + 86_400_000
      } else span(start, call.stringArg("end"), call.longArg("duration_minutes"), zone)
    return CalendarEvent(0, call.stringArg("title") ?: "Event", begin, end, allDay, call.stringArg("location"), null)
  }

  override fun approvalContent(call: ValidatedCall, context: ToolContext): ApprovalContent {
    val event = preview(call)
    val title = call.stringArg("title") ?: "Event"
    val whenLabel = event?.whenLabel() ?: call.stringArg("start").orEmpty()
    return ApprovalContent(
      spokenPrompt = "Add $title, ${event?.spokenWhen() ?: whenLabel}, to your calendar?",
      card = ApprovalCard(title = "Add event", target = title, body = listOfNotNull(whenLabel, event?.location).joinToString("\n")),
    )
  }

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome =
    withContext(Dispatchers.IO) {
      val event = preview(call) ?: return@withContext ToolOutcome.Steering("error", "start must look like 2026-10-27T16:00.")
      val calendarId = defaultCalendarId(context.appContext)
        ?: return@withContext ToolOutcome.Steering("error", "There's no writable calendar on this phone. Tell the user.")
      val values =
        ContentValues().apply {
          put(Events.CALENDAR_ID, calendarId)
          put(Events.TITLE, event.title)
          put(Events.DTSTART, event.begin)
          put(Events.DTEND, event.end)
          put(Events.ALL_DAY, if (event.allDay) 1 else 0)
          put(Events.EVENT_TIMEZONE, if (event.allDay) "UTC" else TimeZone.getDefault().id)
          event.location?.let { put(Events.EVENT_LOCATION, it) }
        }
      val uri = context.appContext.contentResolver.insert(Events.CONTENT_URI, values)
        ?: return@withContext ToolOutcome.Steering("error", "The calendar didn't accept the event. Tell the user.")
      val saved = event.copy(id = ContentUris.parseId(uri))
      ToolOutcome.Success(items = listOf(saved.item().copy(autoShow = true)))
    }
}

class ChangeEventTool @Inject constructor() : BaseTool() {
  override val schema =
    ToolSchema(
      name = "change_event",
      description = "Change an existing calendar event: its title, time, or location. Pass only what changes.",
      params =
        listOf(
          ToolParam("event", ParamKind.HANDLE, "Event id from find_events.", handleType = HandleType.EVENT),
          ToolParam("title", ParamKind.STRING, "New title.", required = false),
          ToolParam("start", ParamKind.STRING, "New local start, YYYY-MM-DDTHH:MM. Keeps the length unless end is given.", required = false),
          ToolParam("end", ParamKind.STRING, "New local end, YYYY-MM-DDTHH:MM.", required = false),
          ToolParam("location", ParamKind.STRING, "New location.", required = false),
        ),
    )

  override val metadata =
    ToolMetadata(
      id = "change_event",
      lockedRisk = LockedRisk.ACTS_AS_YOU,
      title = "Change calendar events",
      summary = "Move, rename, or update events on your calendar.",
      category = ToolCategory.PLANNING,
      defaultApproval = ApprovalLevel.LOW,
      cancellable = false,
      requiredCapabilities = listOf(AppCapability.CALENDAR_WRITE),
      unlockVerb = "change that event",
    )

  private fun updated(original: CalendarEvent, call: ValidatedCall): CalendarEvent {
    val zone = ZoneId.systemDefault()
    val length = original.end - original.begin
    val begin = parseStart(call.stringArg("start"))?.atZone(zone)?.toInstant()?.toEpochMilli() ?: original.begin
    val end =
      call.stringArg("end")?.let(::parseLocalDateTime)?.atZone(zone)?.toInstant()?.toEpochMilli() ?: (begin + length)
    return original.copy(
      title = call.stringArg("title") ?: original.title,
      begin = begin,
      end = maxOf(end, begin),
      location = call.stringArg("location") ?: original.location,
    )
  }

  override fun approvalContent(call: ValidatedCall, context: ToolContext): ApprovalContent {
    val original = call.handleArg("event")?.let { loadEvent(context.appContext, it.nativeId.toLong()) }
    val next = original?.let { updated(it, call) }
    val changes = buildList {
      if (next != null && original.title != next.title) add("rename it to ${next.title}")
      if (next != null && (original.begin != next.begin || original.end != next.end)) add("move it to ${next.spokenWhen()}")
      if (next != null && original.location != next.location) add("set the location to ${next.location}")
    }
    val name = original?.title ?: "this event"
    return ApprovalContent(
      spokenPrompt = "Change $name: ${changes.joinToString(" and ").ifEmpty { "no changes" }}?",
      card =
        ApprovalCard(
          title = "Change event",
          target = name,
          body = next?.let { listOfNotNull(it.title, it.whenLabel(), it.location).joinToString("\n") }.orEmpty(),
        ),
    )
  }

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome =
    withContext(Dispatchers.IO) {
      val id = call.handleArg("event")?.nativeId?.toLongOrNull()
        ?: return@withContext ToolOutcome.Steering("error", "change_event needs an event id from find_events.")
      val original = loadEvent(context.appContext, id)
        ?: return@withContext ToolOutcome.Steering("error", "That event no longer exists.")
      val next = updated(original, call)
      val values =
        ContentValues().apply {
          put(Events.TITLE, next.title)
          put(Events.DTSTART, next.begin)
          put(Events.DTEND, next.end)
          put(Events.EVENT_LOCATION, next.location)
        }
      val rows = context.appContext.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, id), values, null, null)
      if (rows == 0) ToolOutcome.Steering("error", "The calendar didn't accept the change. Tell the user.")
      else ToolOutcome.Success(items = listOf(next.item().copy(autoShow = true)))
    }
}
