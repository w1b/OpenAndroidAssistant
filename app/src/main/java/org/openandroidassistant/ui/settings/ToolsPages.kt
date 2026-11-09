/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.librewakeword.client.RequestWakewordAccess
import org.openandroidassistant.data.settings.allowedWhenLocked
import org.openandroidassistant.data.settings.approvalLevel
import org.openandroidassistant.data.settings.effectiveLocationMode
import org.openandroidassistant.permissions.AppCapability
import org.openandroidassistant.permissions.CapabilityGroup
import org.openandroidassistant.proto.AssistantSettings
import org.openandroidassistant.proto.LocationMode
import org.openandroidassistant.tools.ApprovalLevel
import org.openandroidassistant.tools.AssistantTool
import org.openandroidassistant.tools.LockedRisk
import org.openandroidassistant.tools.ToolExposureState
import org.openandroidassistant.tools.impl.WebSearchTool
import org.openandroidassistant.trigger.AssistantWakewordService
import org.openandroidassistant.ui.components.Explanation
import org.openandroidassistant.ui.components.SettingsLazyPage
import org.openandroidassistant.ui.components.SettingsPage

/** The approval columns, left to right; null is Not allowed. */
private val Columns: List<ApprovalLevel?> = listOf(ApprovalLevel.NONE, ApprovalLevel.LOW, ApprovalLevel.HIGH, ApprovalLevel.CRITICAL, null)

private fun columnIcon(level: ApprovalLevel?): ImageVector =
  when (level) {
    ApprovalLevel.NONE -> Icons.Filled.Bolt
    ApprovalLevel.LOW -> Icons.Outlined.ChatBubbleOutline
    ApprovalLevel.HIGH -> Icons.Outlined.TouchApp
    ApprovalLevel.CRITICAL -> Icons.Filled.Fingerprint
    null -> Icons.Filled.Block
  }

/** The legend: what each column means, in full. */
private fun columnMeaning(level: ApprovalLevel?): String =
  when (level) {
    ApprovalLevel.NONE -> "Runs right away, without asking you first."
    ApprovalLevel.LOW -> "Asks you first. Say yes, or tap, to let it go ahead."
    ApprovalLevel.HIGH -> "Asks you first. Only a tap lets it go ahead."
    ApprovalLevel.CRITICAL -> "Asks you first. A tap, then your fingerprint or screen lock, lets it go ahead."
    null -> "Not allowed. The assistant can't use it, and says so if you ask."
  }

private fun columnLabel(level: ApprovalLevel?): String =
  when (level) {
    ApprovalLevel.NONE -> "Runs right away"
    ApprovalLevel.LOW -> "Asks, voice or tap"
    ApprovalLevel.HIGH -> "Asks, tap only"
    ApprovalLevel.CRITICAL -> "Asks, tap and fingerprint"
    null -> "Not allowed"
  }

private fun lockedRiskWarning(risk: LockedRisk): String =
  when (risk) {
    LockedRisk.ACTS_AS_YOU -> "Anyone holding your phone could do this as you, without unlocking it."
    LockedRisk.READS_PRIVATE -> "Anyone holding your phone could hear this, without unlocking it."
  }

private const val LOCKED_MEANING = "When checked, it also works while the phone is locked. Otherwise the assistant asks you to unlock first."

/** Each tool's card and its name on that card. A tool not listed here gets a card of its own. */
private val Placement: Map<String, Pair<String, String>> =
  mapOf(
    "find_contact" to ("Contacts" to "Find"),
    "call" to ("Phone calls" to "Call"),
    "read_messages" to ("Text messages" to "Read"),
    "send_message" to ("Text messages" to "Send"),
    "find_events" to ("Calendar" to "Find"),
    "create_event" to ("Calendar" to "Create"),
    "change_event" to ("Calendar" to "Change"),
    "set_alarm" to ("Alarms" to "Set"),
    "list_alarms" to ("Alarms" to "List"),
    "change_alarm" to ("Alarms" to "Change"),
    "cancel_alarm" to ("Alarms" to "Delete"),
    "set_timer" to ("Timers" to "Set"),
    "list_timers" to ("Timers" to "List"),
    "change_timer" to ("Timers" to "Change"),
    "cancel_timer" to ("Timers" to "Cancel"),
    "create_reminder" to ("Reminders" to "Create"),
    "find_reminders" to ("Reminders" to "Find"),
    "cancel_reminder" to ("Reminders" to "Cancel"),
    "create_note" to ("Notes" to "Save"),
    "find_notes" to ("Notes" to "Find"),
    "find_places" to (PLACES to "Find places"),
    "get_location" to (PLACES to "Where am I"),
    "get_directions" to (PLACES to "Directions"),
    "get_weather" to ("Weather" to "Forecast"),
    "web_search" to ("Web" to "Search"),
    "read_page" to ("Web" to "Read pages"),
    "read_notifications" to ("Notifications" to "Read"),
    "now_playing" to ("Notifications" to "What's playing"),
    "play_music" to ("Music and media" to "Play music"),
    "media_control" to ("Music and media" to "Media controls"),
    "find_apps" to ("Apps" to "Find"),
    "open_app" to ("Apps" to "Open"),
    "set_flashlight" to ("Phone settings" to "Flashlight"),
    "set_volume" to ("Phone settings" to "Volume"),
    "set_brightness" to ("Phone settings" to "Brightness"),
    "set_do_not_disturb" to ("Phone settings" to "Do Not Disturb"),
    "open_settings" to ("Phone settings" to "Open a settings page"),
  )

private const val PLACES = "Places and directions"

/** One radio column; six of them fit beside the tool name on a phone. */
private val CELL = 38.dp

/** The right edge of the grid, from the screen edge: the card's margin plus its padding. */
private val GRID_END = 22.dp

/**
 * Everything the assistant may do, on one page: per tool, its approval level or Not
 * allowed, whether it works while the phone is locked, and the Android permission it needs. Tools
 * are grouped by what they act on; the column icons stay pinned while the list scrolls.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ToolsSettings(vm: SettingsViewModel, onBack: () -> Unit, open: (String) -> Unit) {
  val context = LocalContext.current
  val s by vm.settings.collectAsState()
  val revision by vm.accessRevision.collectAsState()
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  androidx.compose.runtime.DisposableEffect(lifecycle) {
    val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.accessRevision.value += 1 }
    lifecycle.addObserver(observer)
    onDispose { lifecycle.removeObserver(observer) }
  }
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.accessRevision.value += 1 }
  val appInfo = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
  // Asks Android: the system screen for special access, the permission dialog otherwise, or App
  // info once Android has stopped asking.
  val allow = { cap: AppCapability ->
    val special = cap.settingsIntent(context)
    if (special != null) {
      context.startActivity(special)
    } else {
      val alreadyAsked = cap.name in vm.settings.value.requestedCapabilityIdsList
      vm.markRequested(cap)
      if (alreadyAsked) appInfo() else launcher.launch(cap.missingPermissions(context).toTypedArray())
    }
  }
  val families =
    remember {
      vm.catalog()
        .groupBy { Placement[it.metadata.id]?.first ?: it.metadata.title }
        .toList()
        .sortedBy { (name, _) -> Placement.values.map { it.first }.distinct().indexOf(name).let { if (it < 0) Int.MAX_VALUE else it } }
        .map { (name, tools) -> name to tools.sortedBy { t -> Placement.keys.indexOf(t.metadata.id) }.map { it to (Placement[it.metadata.id]?.second ?: it.metadata.title) } }
    }
  var confirmReset by remember { mutableStateOf(false) }
  SettingsLazyPage("Tools", onBack) {
    item { Legend() }
    stickyHeader { ColumnHeader() }
    for ((name, rows) in families) {
      item(key = name) {
        // Re-read permissions when returning from Android's screens.
        val missing = remember(revision, s) { rows.map { (tool, _) -> vm.missingAccess(tool, s) } }
        FamilyCard(vm, s, name, rows, missing, allow, open)
      }
    }
    item { AppCard(vm, revision, allow) }
    item {
      Row(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = appInfo) { Text("Android permissions") }
        TextButton(onClick = { confirmReset = true }) { Text("Reset to defaults") }
      }
    }
  }
  if (confirmReset) {
    AlertDialog(
      onDismissRequest = { confirmReset = false },
      text = { Text("Put every tool back to how it came: when it asks, whether it's allowed, and whether it works while locked.") },
      confirmButton = {
        TextButton(
          onClick = {
            vm.resetToolChoices()
            confirmReset = false
          }
        ) { Text("Reset") }
      },
      dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
    )
  }
}

@Composable
private fun Legend() {
  Column(
    Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 14.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    for (c in Columns) LegendLine(columnIcon(c), columnMeaning(c), error = c == null)
    LegendLine(Icons.Outlined.Lock, LOCKED_MEANING, error = false)
  }
}

@Composable
private fun LegendLine(icon: ImageVector, text: String, error: Boolean) {
  Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Icon(icon, null, Modifier.size(20.dp), tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun ColumnHeader() {
  Row(
    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(start = 24.dp, end = GRID_END, top = 12.dp, bottom = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Spacer(Modifier.weight(1f))
    for (c in Columns) {
      Box(Modifier.width(CELL), contentAlignment = Alignment.Center) {
        Icon(columnIcon(c), columnLabel(c), Modifier.size(20.dp), tint = if (c == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    Box(Modifier.width(CELL), contentAlignment = Alignment.Center) {
      Icon(Icons.Outlined.Lock, "Works while locked", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun FamilyCard(
  vm: SettingsViewModel,
  s: AssistantSettings,
  name: String,
  rows: List<Pair<AssistantTool, String>>,
  missing: List<List<AppCapability>>,
  allow: (AppCapability) -> Unit,
  open: (String) -> Unit,
) {
  // A permission every tool on the card needs is asked for once, on the card.
  val shared = missing.firstOrNull()?.firstOrNull()?.takeIf { cap -> rows.size > 1 && missing.all { cap in it } && !needsHome(cap, s) }
  Column(
    Modifier.padding(horizontal = 12.dp, vertical = 5.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 4.dp)
  ) {
    Row(Modifier.heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Column(Modifier.weight(1f)) {
        Text(name, style = MaterialTheme.typography.titleMedium)
        if (shared != null) Text("Needs ${shared.title.lowercase()}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      if (shared != null) FilledTonalButton(onClick = { allow(shared) }) { Text("Allow") }
    }
    rows.forEachIndexed { i, (tool, label) ->
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      ToolGridRow(vm, s, tool, label, if (shared != null) emptyList() else missing[i], dimmed = shared != null, allow, open)
    }
    if (name == PLACES) {
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      LocationRow(vm, s)
    }
  }
}

private fun needsHome(cap: AppCapability, s: AssistantSettings) = cap == AppCapability.LOCATION && s.effectiveLocationMode == LocationMode.LOCATION_HOME

@Composable
private fun ToolGridRow(
  vm: SettingsViewModel,
  s: AssistantSettings,
  tool: AssistantTool,
  label: String,
  missing: List<AppCapability>,
  dimmed: Boolean,
  allow: (AppCapability) -> Unit,
  open: (String) -> Unit,
) {
  val disabled = s.toolPrefsMap[tool.metadata.id]?.disabled == true
  val level = if (disabled) null else s.approvalLevel(tool)
  val locked = s.allowedWhenLocked(tool)
  val needs = missing.firstOrNull()
  val risk = tool.metadata.lockedRisk.takeIf { locked && !disabled && !dimmed }
  Column(Modifier.alpha(if (dimmed) 0.45f else 1f)) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = if (disabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f).padding(end = 6.dp, top = 4.dp, bottom = 4.dp),
      )
      val fix: Pair<String, () -> Unit>? =
        when {
          tool.metadata.id == WebSearchTool.ID && vm.exposure(tool, s) == ToolExposureState.NOT_CONFIGURED -> "Choose a web search" to { open("web") }
          needs != null && needsHome(needs, s) -> "Add your home address" to { open("about") }
          needs != null -> "Allow ${needs.title.lowercase()}" to { allow(needs) }
          else -> null
        }
      if (fix != null) {
        FilledTonalButton(onClick = fix.second, modifier = Modifier.width(CELL * 6), contentPadding = PaddingValues(horizontal = 12.dp)) {
          Text(fix.first, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        return@Row
      }
      for (c in Columns) {
        Box(
          Modifier.size(CELL, 44.dp).selectable(selected = c == level, enabled = !dimmed, role = Role.RadioButton) { vm.setToolChoice(tool, c) }.semantics { contentDescription = "${tool.metadata.title}: ${columnLabel(c)}" },
          contentAlignment = Alignment.Center,
        ) {
          RadioButton(
            selected = c == level,
            onClick = null,
            enabled = !dimmed,
            colors = if (c == null) RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.error) else RadioButtonDefaults.colors(),
          )
        }
      }
      Box(
        Modifier.size(CELL, 44.dp).toggleable(value = locked, enabled = !dimmed && !disabled, role = Role.Checkbox) { vm.setAllowedWhenLocked(tool, it) }.semantics { contentDescription = "${tool.metadata.title}: works while locked" },
        contentAlignment = Alignment.Center,
      ) {
        Checkbox(checked = locked, onCheckedChange = null, enabled = !dimmed && !disabled)
      }
    }
    if (risk != null) {
      Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Filled.Error, null, Modifier.size(16.dp).padding(top = 1.dp), tint = MaterialTheme.colorScheme.error)
        Text(lockedRiskWarning(risk), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
      }
    }
  }
}

/** The location tools use: the phone's, the home address, or none. */
@Composable
private fun LocationRow(vm: SettingsViewModel, s: AssistantSettings) {
  val modes = listOf(LocationMode.LOCATION_DEVICE to "Phone", LocationMode.LOCATION_HOME to "Home", LocationMode.LOCATION_OFF to "Off")
  Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("Location", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
      SingleChoiceSegmentedButtonRow(Modifier.width(CELL * 6)) {
        modes.forEachIndexed { i, (mode, label) ->
          SegmentedButton(
            selected = s.effectiveLocationMode == mode,
            onClick = { vm.update { it.setLocationMode(mode) } },
            shape = SegmentedButtonDefaults.itemShape(i, modes.size),
            icon = {},
          ) { Text(label) }
        }
      }
    }
    Text(
      when (s.effectiveLocationMode) {
        LocationMode.LOCATION_HOME -> s.aboutHome.ifBlank { "Uses your home address. Add it in About you." }
        LocationMode.LOCATION_OFF -> "Off. The assistant asks where you mean."
        else -> "Uses your phone's location. The model never sees coordinates."
      },
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** The app's own permissions: the mic, and notifications for timers and alarms. */
@Composable
private fun AppCard(vm: SettingsViewModel, revision: Int, allow: (AppCapability) -> Unit) {
  Column(
    Modifier.padding(horizontal = 12.dp, vertical = 5.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 12.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text("The app", style = MaterialTheme.typography.titleMedium)
    for (cap in AppCapability.entries.filter { it.group == CapabilityGroup.ASSISTANT }) {
      val granted = remember(revision) { vm.isGranted(cap) }
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
          Text(cap.title, style = MaterialTheme.typography.bodyLarge)
          Text(cap.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (granted) Text("Allowed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        else FilledTonalButton(onClick = { allow(cap) }) { Text("Allow") }
      }
    }
  }
}

@Composable
fun WakeWordSettings(vm: SettingsViewModel, onBack: () -> Unit) {
  val s by vm.settings.collectAsState()
  val context = LocalContext.current
  val installed = runCatching { context.packageManager.getPackageInfo("org.librewakeword", 0) }.isSuccess
  val request = rememberLauncherForActivityResult(RequestWakewordAccess()) { approved -> vm.update { it.setLibrewakewordLinked(approved) } }
  SettingsPage("Wake word", onBack) {
    Explanation("Start the assistant by saying a wake word. This needs LibreWakeWord, a separate app that does the listening.")
    when {
      !installed -> Explanation("Install LibreWakeWord first.")
      s.librewakewordLinked -> {
        Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
          Text("Connected. Choose the wake word in LibreWakeWord.")
        }
      }
      else -> Button(onClick = { request.launch(android.content.ComponentName(context, AssistantWakewordService::class.java)) }, modifier = Modifier.padding(horizontal = 24.dp)) {
        Icon(Icons.Filled.Lock, null)
        Text("  Connect LibreWakeWord")
      }
    }
    Explanation("Without a wake word, start the assistant from its Quick Settings tile, or make it your phone's default digital assistant.")
  }
}
