/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.tools.impl

import java.time.ZoneId
import javax.inject.Inject
import org.openandroidassistant.clock.ClockManager
import org.openandroidassistant.clock.daysLabel
import org.openandroidassistant.data.AlarmEntity
import org.openandroidassistant.data.ReminderEntity
import org.openandroidassistant.data.TimerEntity
import org.openandroidassistant.orchestrator.RenderCard
import org.openandroidassistant.tools.BaseTool
import org.openandroidassistant.tools.HandleType
import org.openandroidassistant.tools.ParamKind
import org.openandroidassistant.tools.ResultItem
import org.openandroidassistant.tools.ToolCategory
import org.openandroidassistant.tools.ToolContext
import org.openandroidassistant.tools.ToolMetadata
import org.openandroidassistant.tools.ToolOutcome
import org.openandroidassistant.tools.ToolParam
import org.openandroidassistant.tools.ToolSchema
import org.openandroidassistant.tools.ValidatedCall

// ------------------------------------------------------------------ timers

internal fun TimerEntity.remainingMs(now: Long = System.currentTimeMillis()): Long =
  pausedRemainingMs ?: (endsAtMs - now).coerceAtLeast(0)

internal fun TimerEntity.item(autoShow: Boolean = false) =
  ResultItem(
    type = HandleType.TIMER,
    nativeId = id.toString(),
    projection =
      mapOf(
        "label" to label,
        "length" to formatDuration(totalMs / 1000),
        "remaining" to formatDuration((remainingMs() + 999) / 1000),
        "state" to when {
          ringing -> "ringing"
          pausedRemainingMs != null -> "paused"
          else -> "running"
        },
      ),
    renderPayload = RenderCard.Timer(endsAtMillis = endsAtMs, totalMs = totalMs, label = label.ifBlank { null }, pausedRemainingMs = pausedRemainingMs),
    autoShow = autoShow,
  )

private val timerMetadata =
  ToolMetadata(id = "", title = "", category = ToolCategory.PLANNING, cancellable = false, allowedWhenLockedDefault = true)

class SetTimerTool @Inject constructor(private val clock: ClockManager) : BaseTool() {
  override val schema =
    ToolSchema(
      name = "set_timer",
      description = "Start a countdown timer.",
      params =
        listOf(
          ToolParam("seconds", ParamKind.INTEGER, "Length in seconds (ten minutes is 600).", minimum = 1.0, maximum = 86_400.0),
          ToolParam("label", ParamKind.STRING, "Optional name, like \"pasta\".", required = false),
        ),
    )

  override val metadata = timerMetadata.copy(id = "set_timer", title = "Set timers", summary = "Start countdown timers.")

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome {
    val seconds = call.longArg("seconds") ?: return ToolOutcome.Steering("error", "How long should the timer be?")
    val timer = clock.startTimer(seconds * 1000, call.stringArg("label").orEmpty())
    return ToolOutcome.Success(items = listOf(timer.item(autoShow = true)))
  }
}

class ListTimersTool @Inject constructor(private val clock: ClockManager) : BaseTool() {
  override val schema = ToolSchema(name = "list_timers", description = "List the timers that are running, paused or ringing.", params = emptyList())

  override val metadata = timerMetadata.copy(id = "list_timers", title = "List timers", summary = "See your timers.", cancellable = true)

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome {
    val timers = clock.dao.timers()
    return if (timers.isEmpty()) ToolOutcome.Steering("empty", "There are no timers. Tell the user.")
    else ToolOutcome.Success(items = timers.map { it.item() })
  }
}

class CancelTimerTool @Inject constructor(private val clock: ClockManager) : BaseTool() {
  override val schema =
    ToolSchema(
      name = "cancel_timer",
      description = "Cancel or dismiss timers. Pass a timer id from list_timers or set_timer, or all=true.",
      params =
        listOf(
          ToolParam("timer", ParamKind.HANDLE, "Timer id.", required = false, handleType = HandleType.TIMER),
          ToolParam("all", ParamKind.BOOLEAN, "Cancel every timer.", required = false),
        ),
    )

  override val metadata = timerMetadata.copy(id = "cancel_timer", title = "Cancel timers", summary = "Stop or dismiss timers.")

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome {
    val targets =
      if (call.boolArg("all") == true) clock.dao.timers()
      else listOfNotNull(call.handleArg("timer")?.nativeId?.toLongOrNull()?.let { clock.dao.timer(it) })
    if (targets.isEmpty()) return ToolOutcome.Steering("empty", "No matching timer. Call list_timers to see them.")
    targets.forEach { clock.deleteTimer(it) }
    return ToolOutcome.Success(items = emptyList(), extras = com.google.gson.JsonObject().apply { addProperty("cancelled", targets.size) })
  }
}

class ChangeTimerTool @Inject constructor(private val clock: ClockManager) : BaseTool() {
  override val schema =
    ToolSchema(
      name = "change_timer",
      description = "Add or remove time, pause, or resume a timer.",
      params =
        listOf(
          ToolParam("timer", ParamKind.HANDLE, "Timer id from list_timers or set_timer.", handleType = HandleType.TIMER),
          ToolParam("add_seconds", ParamKind.INTEGER, "Seconds to add; negative to take time off.", required = false, minimum = -86_400.0, maximum = 86_400.0),
          ToolParam("action", ParamKind.ENUM, "pause or resume.", required = false, enumValues = listOf("pause", "resume")),
          ToolParam("label", ParamKind.STRING, "New name.", required = false),
        ),
    )

  override val metadata = timerMetadata.copy(id = "change_timer", title = "Change timers", summary = "Add time to, pause, or resume timers.")

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome {
    var timer =
      call.handleArg("timer")?.nativeId?.toLongOrNull()?.let { clock.dao.timer(it) }
        ?: return ToolOutcome.Steering("error", "That timer is gone. Call list_timers.")
    val now = System.currentTimeMillis()
    when (call.stringArg("action")) {
      "pause" -> if (timer.pausedRemainingMs == null) timer = timer.copy(pausedRemainingMs = timer.remainingMs(now))
      "resume" -> timer.pausedRemainingMs?.let { timer = timer.copy(endsAtMs = now + it, pausedRemainingMs = null) }
    }
    call.longArg("add_seconds")?.let { add ->
      val delta = add * 1000
      timer =
        if (timer.pausedRemainingMs != null) timer.copy(pausedRemainingMs = (timer.pausedRemainingMs + delta).coerceAtLeast(1000))
        else timer.copy(endsAtMs = maxOf(timer.endsAtMs + delta, now + 1000))
      timer = timer.copy(totalMs = (timer.totalMs + delta).coerceAtLeast(1000))
    }
    call.stringArg("label")?.let { timer = timer.copy(label = it) }
    clock.updateTimer(timer)
    return ToolOutcome.Success(items = listOf(timer.item(autoShow = true)))
  }
}

// ------------------------------------------------------------------ alarms

internal fun AlarmEntity.timeLabel(): String = formatTime(java.time.LocalTime.of(hour, minute))

internal fun AlarmEntity.item(autoShow: Boolean = false) =
  ResultItem(
    type = HandleType.ALARM,
    nativeId = id.toString(),
    projection =
      mapOf(
        "time" to timeLabel(),
        "label" to label,
        "repeats" to (daysLabel(daysMask) ?: "once"),
        "on" to enabled,
        "next" to if (enabled && nextAtMs > 0) formatWhen(nextAtMs) else null,
      ),
    renderPayload = RenderCard.Alarm(timeLabel = timeLabel(), label = label.ifBlank { null }, enabled = enabled, daysLabel = daysLabel(daysMask)),
    autoShow = autoShow,
  )

/** "weekdays", "every day", "weekends", "mon,wed,fri", "once" → day mask. Null when unparseable. */
internal fun parseDays(text: String?): Int? {
  val t = text?.trim()?.lowercase() ?: return 0
  return when (t) {
    "", "once", "none", "no" -> 0
    "every day", "daily", "everyday" -> 0b1111111
    "weekdays" -> 0b0011111
    "weekends" -> 0b1100000
    else -> {
      val names = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
      var mask = 0
      for (part in t.split(Regex("[,\\s]+")).filter { it.isNotEmpty() }) {
        val i = names.indexOfFirst { part.startsWith(it) }
        if (i < 0) return null
        mask = mask or (1 shl i)
      }
      mask
    }
  }
}

private val alarmMetadata =
  ToolMetadata(id = "", title = "", category = ToolCategory.PLANNING, cancellable = false, allowedWhenLockedDefault = true)

private val REPEAT_PARAM =
  ToolParam("repeat", ParamKind.STRING, "once (default), every day, weekdays, weekends, or days like \"mon,wed,fri\".", required = false)

class SetAlarmTool @Inject constructor(private val clock: ClockManager) : BaseTool() {
  override val schema =
    ToolSchema(
      name = "set_alarm",
      description = "Set an alarm.",
      params =
        listOf(
          ToolParam("time", ParamKind.STRING, "Local time in 24-hour HH:MM, like 06:30 or 18:00."),
          ToolParam("label", ParamKind.STRING, "Optional name.", required = false),
          REPEAT_PARAM,
        ),
    )

  override val metadata = alarmMetadata.copy(id = "set_alarm", title = "Set alarms", summary = "Create alarms.")

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome {
    val time = call.stringArg("time")?.let(::parseLocalTime) ?: return ToolOutcome.Steering("error", "time must be HH:MM in 24-hour time.")
    val days = parseDays(call.stringArg("repeat")) ?: return ToolOutcome.Steering("error", "Couldn't read repeat. Use weekdays, every day, or days like mon,wed.")
    val alarm = clock.saveAlarm(AlarmEntity(hour = time.hour, minute = time.minute, label = call.stringArg("label").orEmpty(), enabled = true, daysMask = days))
    return ToolOutcome.Success(items = listOf(alarm.item(autoShow = true)))
  }
}

class ListAlarmsTool @Inject constructor(private val clock: ClockManager) : BaseTool() {
  override val schema = ToolSchema(name = "list_alarms", description = "List the user's alarms.", params = emptyList())

  override val metadata = alarmMetadata.copy(id = "list_alarms", title = "List alarms", summary = "See your alarms.", cancellable = true)

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome {
    val alarms = clock.dao.alarms()
    return if (alarms.isEmpty()) ToolOutcome.Steering("empty", "There are no alarms. Tell the user.")
    else ToolOutcome.Success(items = alarms.map { it.item() })
  }
}

class CancelAlarmTool @Inject constructor(private val clock: ClockManager) : BaseTool() {
  override val schema =
    ToolSchema(
      name = "cancel_alarm",
      description = "Delete alarms. Pass an alarm id from list_alarms or set_alarm, or all=true. To keep an alarm but turn it off, use change_alarm.",
      params =
        listOf(
          ToolParam("alarm", ParamKind.HANDLE, "Alarm id.", required = false, handleType = HandleType.ALARM),
          ToolParam("all", ParamKind.BOOLEAN, "Delete every alarm.", required = false),
        ),
    )

  override val metadata = alarmMetadata.copy(id = "cancel_alarm", title = "Delete alarms", summary = "Remove alarms.")

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome {
    val targets =
      if (call.boolArg("all") == true) clock.dao.alarms()
      else listOfNotNull(call.handleArg("alarm")?.nativeId?.toLongOrNull()?.let { clock.dao.alarm(it) })
    if (targets.isEmpty()) return ToolOutcome.Steering("empty", "No matching alarm. Call list_alarms to see them.")
    targets.forEach { clock.deleteAlarm(it) }
    return ToolOutcome.Success(items = emptyList(), extras = com.google.gson.JsonObject().apply { addProperty("deleted", targets.size) })
  }
}

class ChangeAlarmTool @Inject constructor(private val clock: ClockManager) : BaseTool() {
  override val schema =
    ToolSchema(
      name = "change_alarm",
      description = "Change an alarm's time, name or repeat days, or turn it on or off.",
      params =
        listOf(
          ToolParam("alarm", ParamKind.HANDLE, "Alarm id from list_alarms or set_alarm.", handleType = HandleType.ALARM),
          ToolParam("time", ParamKind.STRING, "New local time, 24-hour HH:MM.", required = false),
          ToolParam("label", ParamKind.STRING, "New name.", required = false),
          REPEAT_PARAM,
          ToolParam("on", ParamKind.BOOLEAN, "true to turn it on, false to turn it off.", required = false),
        ),
    )

  override val metadata = alarmMetadata.copy(id = "change_alarm", title = "Change alarms", summary = "Edit or turn alarms on and off.")

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome {
    var alarm =
      call.handleArg("alarm")?.nativeId?.toLongOrNull()?.let { clock.dao.alarm(it) }
        ?: return ToolOutcome.Steering("error", "That alarm is gone. Call list_alarms.")
    call.stringArg("time")?.let { text ->
      val t = parseLocalTime(text) ?: return ToolOutcome.Steering("error", "time must be HH:MM in 24-hour time.")
      alarm = alarm.copy(hour = t.hour, minute = t.minute, enabled = true)
    }
    call.stringArg("label")?.let { alarm = alarm.copy(label = it) }
    if (call.args["repeat"] != null) {
      alarm = alarm.copy(daysMask = parseDays(call.stringArg("repeat")) ?: return ToolOutcome.Steering("error", "Couldn't read repeat."))
    }
    call.boolArg("on")?.let { alarm = alarm.copy(enabled = it) }
    return ToolOutcome.Success(items = listOf(clock.saveAlarm(alarm).item(autoShow = true)))
  }
}

// ------------------------------------------------------------------ reminders

internal fun ReminderEntity.item(autoShow: Boolean = false): ResultItem {
  val whenLabel = dueAtMs?.let(::formatWhen)
  return ResultItem(
    type = HandleType.REMINDER,
    nativeId = id.toString(),
    projection = mapOf("text" to text, "when" to (whenLabel ?: "no time set")),
    renderPayload = RenderCard.Reminder(text = text, timeLabel = whenLabel),
    autoShow = autoShow,
  )
}

private val reminderMetadata =
  ToolMetadata(id = "", title = "", category = ToolCategory.PLANNING, cancellable = false)

class CreateReminderTool @Inject constructor(private val clock: ClockManager) : BaseTool() {
  override val schema =
    ToolSchema(
      name = "create_reminder",
      description = "Create a reminder. The phone shows a notification at the given time.",
      params =
        listOf(
          ToolParam("text", ParamKind.STRING, "What to remind the user about, short, like \"Call the dentist\"."),
          ToolParam("time", ParamKind.STRING, "Local time to remind, YYYY-MM-DDTHH:MM. Omit for a reminder with no time.", required = false),
        ),
    )

  override val metadata = reminderMetadata.copy(id = "create_reminder", title = "Create reminders", summary = "Get a notification at a time you choose.")

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome {
    val text = call.stringArg("text") ?: return ToolOutcome.Steering("error", "What should the reminder say?")
    val due =
      call.stringArg("time")?.let {
        (parseLocalDateTime(it) ?: return ToolOutcome.Steering("error", "time must look like 2026-10-27T16:00."))
          .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
      }
    if (due != null && due < System.currentTimeMillis()) return ToolOutcome.Steering("error", "That time has already passed. Check the date.")
    val saved = clock.saveReminder(ReminderEntity(text = text, dueAtMs = due, createdAtMs = System.currentTimeMillis()))
    return ToolOutcome.Success(items = listOf(saved.item(autoShow = true)))
  }
}

class FindRemindersTool @Inject constructor(private val clock: ClockManager) : BaseTool() {
  override val schema =
    ToolSchema(
      name = "find_reminders",
      description = "List upcoming reminders, optionally only those containing some words.",
      params = listOf(ToolParam("query", ParamKind.STRING, "Words to match.", required = false)),
    )

  override val metadata = reminderMetadata.copy(id = "find_reminders", title = "Find reminders", summary = "See your reminders.", cancellable = true)

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome {
    val query = call.stringArg("query")?.lowercase()
    val found = clock.dao.openReminders().filter { query == null || it.text.lowercase().contains(query) }
    return if (found.isEmpty()) ToolOutcome.Steering("empty", "No reminders matched. Tell the user.")
    else ToolOutcome.Success(items = found.map { it.item() })
  }
}

class CancelReminderTool @Inject constructor(private val clock: ClockManager) : BaseTool() {
  override val schema =
    ToolSchema(
      name = "cancel_reminder",
      description = "Delete a reminder.",
      params = listOf(ToolParam("reminder", ParamKind.HANDLE, "Reminder id from find_reminders.", handleType = HandleType.REMINDER)),
    )

  override val metadata = reminderMetadata.copy(id = "cancel_reminder", title = "Cancel reminders", summary = "Remove reminders.")

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome {
    val reminder =
      call.handleArg("reminder")?.nativeId?.toLongOrNull()?.let { clock.dao.reminder(it) }
        ?: return ToolOutcome.Steering("error", "That reminder is gone.")
    clock.deleteReminder(reminder)
    return ToolOutcome.Success(items = emptyList(), extras = com.google.gson.JsonObject().apply { addProperty("deleted", reminder.text) })
  }
}
