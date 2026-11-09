/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import org.openandroidassistant.tools.AssistantTool
import org.openandroidassistant.tools.impl.AppLaunchTool
import org.openandroidassistant.tools.impl.BrightnessTool
import org.openandroidassistant.tools.impl.CallTool
import org.openandroidassistant.tools.impl.CancelAlarmTool
import org.openandroidassistant.tools.impl.CancelReminderTool
import org.openandroidassistant.tools.impl.CancelTimerTool
import org.openandroidassistant.tools.impl.ChangeAlarmTool
import org.openandroidassistant.tools.impl.ChangeEventTool
import org.openandroidassistant.tools.impl.ChangeTimerTool
import org.openandroidassistant.tools.impl.ContactsTool
import org.openandroidassistant.tools.impl.CreateEventTool
import org.openandroidassistant.tools.impl.CreateNoteTool
import org.openandroidassistant.tools.impl.CreateReminderTool
import org.openandroidassistant.tools.impl.DirectionsTool
import org.openandroidassistant.tools.impl.DoNotDisturbTool
import org.openandroidassistant.tools.impl.FindAppsTool
import org.openandroidassistant.tools.impl.FindEventsTool
import org.openandroidassistant.tools.impl.FindNotesTool
import org.openandroidassistant.tools.impl.FindPlacesTool
import org.openandroidassistant.tools.impl.FindRemindersTool
import org.openandroidassistant.tools.impl.FlashlightTool
import org.openandroidassistant.tools.impl.ListAlarmsTool
import org.openandroidassistant.tools.impl.ListTimersTool
import org.openandroidassistant.tools.impl.LocationTool
import org.openandroidassistant.tools.impl.MediaControlTool
import org.openandroidassistant.tools.impl.NowPlayingTool
import org.openandroidassistant.tools.impl.OpenSettingsTool
import org.openandroidassistant.tools.impl.PlayMusicTool
import org.openandroidassistant.tools.impl.ReadNotificationsTool
import org.openandroidassistant.tools.impl.ReadPageTool
import org.openandroidassistant.tools.impl.SetAlarmTool
import org.openandroidassistant.tools.impl.SetTimerTool
import org.openandroidassistant.tools.impl.SmsReadTool
import org.openandroidassistant.tools.impl.SmsSendTool
import org.openandroidassistant.tools.impl.TimeTool
import org.openandroidassistant.tools.impl.VolumeTool
import org.openandroidassistant.tools.impl.WeatherTool
import org.openandroidassistant.tools.impl.WebSearchTool

/** The tool catalog. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class AssistantModule {
  @Binds @IntoSet abstract fun contacts(t: ContactsTool): AssistantTool

  @Binds @IntoSet abstract fun call(t: CallTool): AssistantTool

  @Binds @IntoSet abstract fun readMessages(t: SmsReadTool): AssistantTool

  @Binds @IntoSet abstract fun sendMessage(t: SmsSendTool): AssistantTool

  @Binds @IntoSet abstract fun findEvents(t: FindEventsTool): AssistantTool

  @Binds @IntoSet abstract fun createEvent(t: CreateEventTool): AssistantTool

  @Binds @IntoSet abstract fun changeEvent(t: ChangeEventTool): AssistantTool

  @Binds @IntoSet abstract fun createReminder(t: CreateReminderTool): AssistantTool

  @Binds @IntoSet abstract fun findReminders(t: FindRemindersTool): AssistantTool

  @Binds @IntoSet abstract fun cancelReminder(t: CancelReminderTool): AssistantTool

  @Binds @IntoSet abstract fun setAlarm(t: SetAlarmTool): AssistantTool

  @Binds @IntoSet abstract fun listAlarms(t: ListAlarmsTool): AssistantTool

  @Binds @IntoSet abstract fun cancelAlarm(t: CancelAlarmTool): AssistantTool

  @Binds @IntoSet abstract fun changeAlarm(t: ChangeAlarmTool): AssistantTool

  @Binds @IntoSet abstract fun setTimer(t: SetTimerTool): AssistantTool

  @Binds @IntoSet abstract fun listTimers(t: ListTimersTool): AssistantTool

  @Binds @IntoSet abstract fun cancelTimer(t: CancelTimerTool): AssistantTool

  @Binds @IntoSet abstract fun changeTimer(t: ChangeTimerTool): AssistantTool

  @Binds @IntoSet abstract fun weather(t: WeatherTool): AssistantTool

  @Binds @IntoSet abstract fun createNote(t: CreateNoteTool): AssistantTool

  @Binds @IntoSet abstract fun findNotes(t: FindNotesTool): AssistantTool

  @Binds @IntoSet abstract fun findPlaces(t: FindPlacesTool): AssistantTool

  @Binds @IntoSet abstract fun directions(t: DirectionsTool): AssistantTool

  @Binds @IntoSet abstract fun mediaControl(t: MediaControlTool): AssistantTool

  @Binds @IntoSet abstract fun playMusic(t: PlayMusicTool): AssistantTool

  @Binds @IntoSet abstract fun nowPlaying(t: NowPlayingTool): AssistantTool

  @Binds @IntoSet abstract fun flashlight(t: FlashlightTool): AssistantTool

  @Binds @IntoSet abstract fun volume(t: VolumeTool): AssistantTool

  @Binds @IntoSet abstract fun doNotDisturb(t: DoNotDisturbTool): AssistantTool

  @Binds @IntoSet abstract fun brightness(t: BrightnessTool): AssistantTool

  @Binds @IntoSet abstract fun notifications(t: ReadNotificationsTool): AssistantTool

  @Binds @IntoSet abstract fun webSearch(t: WebSearchTool): AssistantTool

  @Binds @IntoSet abstract fun readPage(t: ReadPageTool): AssistantTool

  @Binds @IntoSet abstract fun openApp(t: AppLaunchTool): AssistantTool

  @Binds @IntoSet abstract fun findApps(t: FindAppsTool): AssistantTool

  @Binds @IntoSet abstract fun openSettings(t: OpenSettingsTool): AssistantTool

  @Binds @IntoSet abstract fun location(t: LocationTool): AssistantTool

  @Binds @IntoSet abstract fun time(t: TimeTool): AssistantTool
}
