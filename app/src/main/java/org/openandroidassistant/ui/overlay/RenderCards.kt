/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.ui.overlay

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.openandroidassistant.orchestrator.RenderCard

/** Everything the assistant can put on screen. */
@Composable
fun RenderCardView(card: RenderCard) {
  when (card) {
    is RenderCard.Weather -> WeatherCard(card)
    is RenderCard.Contact -> ContactCard(card)
    is RenderCard.Message -> MessageCard(card)
    is RenderCard.Event -> EventCard(card)
    is RenderCard.Alarm -> Shell(Icons.Filled.Alarm) {
      Title(card.timeLabel)
      Sub(listOfNotNull(card.label, card.daysLabel, if (!card.enabled) "Off" else null).joinToString(" · ").ifEmpty { "Alarm" })
    }
    is RenderCard.Timer -> TimerCard(card)
    is RenderCard.Reminder -> Shell(Icons.Filled.NotificationsActive) {
      Title(card.text)
      Sub(card.timeLabel ?: "Reminder")
    }
    is RenderCard.Note -> Shell(Icons.AutoMirrored.Filled.StickyNote2) {
      Title(card.title.ifBlank { "Note" })
      Sub(card.body, maxLines = 6)
    }
    is RenderCard.Place -> PlaceCard(card)
    is RenderCard.Notification -> Shell(Icons.Filled.Notifications) {
      Label("${card.app} · ${card.timeLabel}")
      if (card.title.isNotBlank()) Title(card.title)
      if (card.text.isNotBlank()) Sub(card.text, maxLines = 4)
    }
    is RenderCard.NowPlaying -> Shell(Icons.Filled.MusicNote) {
      Title(card.title)
      Sub(listOfNotNull(card.artist, card.app).joinToString(" · ").ifEmpty { if (card.playing) "Playing" else "Paused" })
    }
    is RenderCard.WebResult -> WebCard(card)
    is RenderCard.ActionDone -> Shell(Icons.Filled.CheckCircle) { Title(card.summary) }
  }
}

@Composable
private fun Shell(icon: ImageVector, accent: Color? = null, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
  Row(
    Modifier.fillMaxWidth()
      .clip(RoundedCornerShape(18.dp))
      .background(MaterialTheme.colorScheme.surfaceContainerHighest)
      .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
      .padding(16.dp),
    horizontalArrangement = Arrangement.spacedBy(14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(accent ?: MaterialTheme.colorScheme.primaryContainer),
      contentAlignment = Alignment.Center,
    ) {
      Icon(icon, null, tint = if (accent != null) Color.White else MaterialTheme.colorScheme.onPrimaryContainer)
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
  }
}

@Composable
private fun Title(text: String) =
  Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)

@Composable
private fun Sub(text: String, maxLines: Int = 3) =
  Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = maxLines, overflow = TextOverflow.Ellipsis)

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)

private fun weatherIcon(summary: String): ImageVector =
  when {
    "thunder" in summary -> Icons.Filled.Thunderstorm
    "snow" in summary -> Icons.Filled.AcUnit
    "rain" in summary || "drizzle" in summary || "shower" in summary -> Icons.Filled.Umbrella
    "fog" in summary -> Icons.Filled.Cloud
    "cloud" in summary -> Icons.Filled.WbCloudy
    else -> Icons.Filled.WbSunny
  }

/** Current conditions plus the rest of the day, scrollable hour by hour (scenario 5). */
@Composable
private fun WeatherCard(card: RenderCard.Weather) {
  Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(vertical = 16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
      Icon(weatherIcon(card.summary), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
      Column(Modifier.weight(1f).padding(start = 12.dp)) {
        Title(card.place)
        Sub("${card.summary.replaceFirstChar { it.uppercase() }} · H ${card.high} L ${card.low}" + (card.precipitationChance?.let { " · $it rain" } ?: ""))
      }
      Text(card.temperature, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface)
    }
    if (card.hours.isNotEmpty()) {
      LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items(card.hours) { h ->
          Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(h.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(weatherIcon(h.summary), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
            Text(h.temperature, style = MaterialTheme.typography.titleSmall)
            Text(h.precipitationChance ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
          }
        }
      }
    }
  }
}

@Composable
private fun ContactCard(card: RenderCard.Contact) {
  val context = LocalContext.current
  Shell(Icons.Filled.Person) {
    Title(card.name)
    card.phone?.let { Sub(listOfNotNull(card.phoneLabel, it).joinToString(" · ")) }
    card.address?.let { Sub(it, maxLines = 2) }
    if (card.phone != null) {
      Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip(Icons.Filled.Call, "Call") {
          context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", card.phone, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
      }
    }
  }
}

@Composable
private fun Chip(icon: ImageVector, label: String, onClick: () -> Unit) {
  Row(
    Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
  }
}

/** A message bubble; the user's own messages sit on the right (the sent bubble, scenario 4). */
@Composable
private fun MessageCard(card: RenderCard.Message) {
  Column(Modifier.fillMaxWidth(), horizontalAlignment = if (card.outgoing) Alignment.End else Alignment.Start) {
    Text(
      if (card.outgoing && !card.from.startsWith("You")) "To ${card.from} · ${card.timeLabel}" else "${card.from} · ${card.timeLabel}",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
    )
    Text(
      card.body,
      style = MaterialTheme.typography.bodyLarge,
      color = if (card.outgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
      modifier =
        Modifier.widthIn(max = 300.dp)
          .clip(if (card.outgoing) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp))
          .background(if (card.outgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
          .padding(horizontal = 14.dp, vertical = 10.dp),
    )
  }
}

@Composable
private fun EventCard(card: RenderCard.Event) {
  Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(16.dp),
    horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Box(Modifier.width(4.dp).height(44.dp).clip(CircleShape).background(card.calendarColor?.let { Color(it) } ?: MaterialTheme.colorScheme.primary))
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Title(card.title)
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Filled.Schedule, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Sub(card.timeLabel)
      }
      card.location?.let {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          Icon(Icons.Filled.LocationOn, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
          Sub(it, maxLines = 1)
        }
      }
    }
  }
}

/** A live countdown (scenario 10). */
@Composable
private fun TimerCard(card: RenderCard.Timer) {
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(card) {
    while (card.pausedRemainingMs == null) {
      now = System.currentTimeMillis()
      delay(250)
    }
  }
  val remaining = card.pausedRemainingMs ?: (card.endsAtMillis - now).coerceAtLeast(0)
  val seconds = (remaining + 999) / 1000
  val label = if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)
  Shell(Icons.Filled.Timer) {
    Text(label, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
    Sub(listOfNotNull(card.label, if (card.pausedRemainingMs != null) "Paused" else if (remaining == 0L) "Time's up" else null).joinToString(" · ").ifEmpty { "Timer" })
    androidx.compose.material3.LinearProgressIndicator(
      progress = { if (card.totalMs > 0) remaining.toFloat() / card.totalMs else 0f },
      modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp).clip(CircleShape),
    )
  }
}

@Composable
private fun PlaceCard(card: RenderCard.Place) {
  val context = LocalContext.current
  Shell(Icons.Filled.LocationOn) {
    Title(card.name)
    Sub(listOfNotNull(card.category?.replaceFirstChar { it.uppercase() }, card.distanceLabel).joinToString(" · "))
    card.address?.let { Sub(it, maxLines = 2) }
    card.hoursLabel?.let { Sub(it, maxLines = 2) }
    card.phone?.let { phone ->
      Row(Modifier.padding(top = 6.dp)) {
        Chip(Icons.Filled.Call, phone) {
          context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
      }
    }
  }
}

@Composable
private fun WebCard(card: RenderCard.WebResult) {
  val context = LocalContext.current
  Shell(Icons.Filled.Language, onClick = {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(card.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
  }) {
    Title(card.title)
    Text(Uri.parse(card.url).host.orEmpty(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    card.snippet?.let { Sub(it) }
  }
}
