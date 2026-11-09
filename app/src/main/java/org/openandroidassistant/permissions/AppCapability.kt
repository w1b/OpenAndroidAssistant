/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.permissions

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * A user-understandable unit of access: one or more runtime permissions, or a special access
 * the user grants on a system settings screen. Tools refer to capabilities, never raw
 * permission strings.
 */
enum class AppCapability(
  val title: String,
  val description: String,
  /** Runtime permissions; empty for special access. */
  val permissions: List<String> = emptyList(),
  val group: CapabilityGroup,
  val coreRole: CoreRole = CoreRole.NONE,
) {
  MICROPHONE(
    title = "Microphone",
    description = "Hear what you say.",
    permissions = listOf(Manifest.permission.RECORD_AUDIO),
    group = CapabilityGroup.ASSISTANT,
    coreRole = CoreRole.REQUIRED,
  ),
  NOTIFICATIONS(
    title = "Notifications",
    description = "Show timers, alarms, reminders, and download progress.",
    permissions = listOf(Manifest.permission.POST_NOTIFICATIONS),
    group = CapabilityGroup.ASSISTANT,
    coreRole = CoreRole.RECOMMENDED,
  ),
  CONTACTS(
    title = "Contacts",
    description = "Find people by name.",
    permissions = listOf(Manifest.permission.READ_CONTACTS),
    group = CapabilityGroup.PERSONAL_DATA,
  ),
  CALENDAR(
    title = "Read calendar",
    description = "See your events.",
    permissions = listOf(Manifest.permission.READ_CALENDAR),
    group = CapabilityGroup.PERSONAL_DATA,
  ),
  CALENDAR_WRITE(
    title = "Edit calendar",
    description = "Add and change events.",
    permissions = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
    group = CapabilityGroup.ACTIONS,
  ),
  READ_MESSAGES(
    title = "Read text messages",
    description = "Search your SMS.",
    permissions = listOf(Manifest.permission.READ_SMS),
    group = CapabilityGroup.PERSONAL_DATA,
  ),
  SEND_MESSAGES(
    title = "Send text messages",
    description = "Send SMS for you.",
    permissions = listOf(Manifest.permission.SEND_SMS),
    group = CapabilityGroup.ACTIONS,
  ),
  PHONE_CALLS(
    title = "Phone calls",
    description = "Place calls for you.",
    permissions = listOf(Manifest.permission.CALL_PHONE),
    group = CapabilityGroup.ACTIONS,
  ),
  LOCATION(
    title = "Location",
    description = "Your approximate location, for weather and nearby places.",
    permissions = listOf(Manifest.permission.ACCESS_COARSE_LOCATION),
    group = CapabilityGroup.LOCATION,
  ),
  NOTIFICATION_ACCESS(
    title = "Notification access",
    description = "Read your notifications and see what's playing.",
    group = CapabilityGroup.SPECIAL,
  ),
  DO_NOT_DISTURB(
    title = "Do Not Disturb access",
    description = "Turn Do Not Disturb on and off.",
    group = CapabilityGroup.SPECIAL,
  ),
  MODIFY_SETTINGS(
    title = "Modify system settings",
    description = "Change screen brightness.",
    group = CapabilityGroup.SPECIAL,
  ),
  ;

  fun isGranted(context: Context): Boolean =
    when (this) {
      NOTIFICATION_ACCESS -> NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
      DO_NOT_DISTURB -> context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
      MODIFY_SETTINGS -> Settings.System.canWrite(context)
      else -> permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

  fun missingPermissions(context: Context): List<String> =
    permissions.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

  /** The system screen where special access is granted. */
  fun settingsIntent(context: Context): Intent? =
    when (this) {
      NOTIFICATION_ACCESS -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
      DO_NOT_DISTURB -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
      MODIFY_SETTINGS -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
      else -> null
    }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

enum class CapabilityGroup(val title: String) {
  ASSISTANT("Assistant"),
  PERSONAL_DATA("Your data"),
  ACTIONS("Actions"),
  LOCATION("Location"),
  SPECIAL("Special access"),
}

enum class CoreRole {
  NONE,
  REQUIRED,
  RECOMMENDED,
}
