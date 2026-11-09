/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.ui.history

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.openandroidassistant.data.ConversationEntity
import org.openandroidassistant.data.ExchangeEntity
import org.openandroidassistant.data.HistoryRecorder
import org.openandroidassistant.tools.impl.formatWhen
import org.openandroidassistant.ui.components.Explanation
import org.openandroidassistant.ui.components.SettingsPage

@HiltViewModel
class HistoryViewModel @Inject constructor(private val history: HistoryRecorder) : ViewModel() {
  val conversations = history.dao.conversations().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

  fun exchanges(id: Long): Flow<List<ExchangeEntity>> = history.dao.exchanges(id)

  fun delete(id: Long) = viewModelScope.launch { history.deleteConversation(id) }

  private var player: MediaPlayer? = null
  /** The exchange whose recording is playing. */
  val playing = MutableStateFlow<Long?>(null)

  /** Plays the exchange's recorded voice, or stops it if it's already playing. */
  fun togglePlay(e: ExchangeEntity) {
    val wasPlaying = playing.value == e.id
    stopPlayback()
    if (wasPlaying) return
    val file = history.audioDir.resolve(e.audioFile ?: return)
    if (!file.exists()) return
    player =
      MediaPlayer().apply {
        setDataSource(file.absolutePath)
        // Not plain stop(): inside apply that's MediaPlayer.stop(), and the button stayed on Stop.
        setOnCompletionListener { stopPlayback() }
        prepare()
        start()
      }
    playing.value = e.id
  }

  private fun stopPlayback() {
    player?.release()
    player = null
    playing.value = null
  }

  override fun onCleared() = stopPlayback()
}

/** Past conversations, newest first. */
@Composable
fun HistoryList(vm: HistoryViewModel, onBack: () -> Unit, open: (Long) -> Unit) {
  val list by vm.conversations.collectAsState()
  // The conversation whose delete button was tapped once. Any touch outside that button disarms it
  // (including the start of a scroll), and so does waiting a few seconds.
  var armed by remember { mutableStateOf<Long?>(null) }
  val deleteBounds = remember { HashMap<Long, Rect>() }
  var page by remember { mutableStateOf<LayoutCoordinates?>(null) }
  LaunchedEffect(armed) {
    if (armed != null) {
      delay(3_000)
      armed = null
    }
  }
  Box(
    Modifier.fillMaxSize().onGloballyPositioned { page = it }.pointerInput(Unit) {
      awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val at = page?.takeIf { it.isAttached }?.localToWindow(down.position)
        val a = armed
        if (a != null && (at == null || deleteBounds[a]?.contains(at) != true)) armed = null
      }
    }
  ) {
    SettingsPage("History", onBack) {
      val items = list ?: return@SettingsPage
      if (items.isEmpty()) Explanation("No saved conversations yet.")
      for (c in items) {
        key(c.id) {
          ConversationRow(
            c,
            armed = armed == c.id,
            onClick = { open(c.id) },
            onDeleteTap = {
              if (armed == c.id) {
                armed = null
                vm.delete(c.id)
              } else armed = c.id
            },
            onDeleteBounds = { deleteBounds[c.id] = it },
          )
        }
      }
    }
  }
}

@Composable
private fun ConversationRow(c: ConversationEntity, armed: Boolean, onClick: () -> Unit, onDeleteTap: () -> Unit, onDeleteBounds: (Rect) -> Unit) {
  Row(
    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 24.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(c.title.ifBlank { "Conversation" }, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
      Text(formatWhen(c.updatedAtMs), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    DeleteButton(armed, onDeleteTap, Modifier.onGloballyPositioned { onDeleteBounds(it.boundsInWindow()) })
  }
}

/** Delete takes two taps: the first turns the button red and pops up a hint above it. */
@Composable
private fun DeleteButton(armed: Boolean, onTap: () -> Unit, modifier: Modifier = Modifier) {
  Box(modifier) {
    IconButton(
      onClick = onTap,
      colors =
        if (armed) IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
        else IconButtonDefaults.iconButtonColors(),
    ) {
      Icon(Icons.Filled.Delete, if (armed) "Tap again to delete" else "Delete")
    }
    if (armed) {
      val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
      Popup(popupPositionProvider = AboveEnd(gap), properties = PopupProperties(focusable = false)) {
        Text(
          "Tap again to delete",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.inverseOnSurface,
          modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.inverseSurface).padding(horizontal = 10.dp, vertical = 6.dp),
        )
      }
    }
  }
}

/** Puts a popup just above its anchor, right edges aligned, kept inside the window. */
private class AboveEnd(private val gap: Int) : PopupPositionProvider {
  override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
    IntOffset(
      (anchorBounds.right - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
      (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(0),
    )
}

@Composable
fun ConversationDetail(vm: HistoryViewModel, id: Long, onBack: () -> Unit) {
  val exchanges by vm.exchanges(id).collectAsState(initial = emptyList())
  val playing by vm.playing.collectAsState()
  SettingsPage("Conversation", onBack) {
    SelectionContainer {
      Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        for (e in exchanges) {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (e.audioFile != null) {
              DisableSelection {
                IconButton(onClick = { vm.togglePlay(e) }) {
                  Icon(if (playing == e.id) Icons.Filled.Stop else Icons.Filled.PlayArrow, if (playing == e.id) "Stop" else "Play what you said")
                }
              }
            }
            Row(
              Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 14.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              if (e.userKind != "transcript") {
                Icon(if (e.userKind == "typed") Icons.Filled.Keyboard else Icons.Filled.Mic, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
              }
              Text(e.userText.ifBlank { "Voice request" }, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
          }
          for (step in remember(e.toolSteps) { parseSteps(e.toolSteps) }) ToolStepRow(step)
          if (e.assistantText.isNotBlank()) Text(e.assistantText, style = MaterialTheme.typography.bodyLarge)
          if (e.shown.isNotBlank()) {
            Text(
              e.shown,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp),
            )
          }
          Text(formatWhen(e.createdAtMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
      }
    }
  }
}

private class ToolStep(val name: String, val args: JSONObject, val result: Any?)

private fun parseSteps(json: String): List<ToolStep> =
  if (json.isBlank()) emptyList()
  else
    runCatching {
        val a = JSONArray(json)
        List(a.length()) { i ->
          val o = a.getJSONObject(i)
          ToolStep(o.optString("name"), o.optJSONObject("args") ?: JSONObject(), o.opt("result"))
        }
      }
      .getOrDefault(emptyList())

/** One tool call: its name and arguments on a line; tap for the full arguments and the app's answer. */
@Composable
private fun ToolStepRow(step: ToolStep) {
  var open by remember { mutableStateOf(false) }
  val failed = (step.result as? JSONObject)?.let { it.has("error") || it.optString("status") == "error" } == true
  Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer).clickable { open = !open }.padding(horizontal = 12.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Icon(
        Icons.Filled.Build,
        null,
        Modifier.size(16.dp),
        tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        step.name + "(" + step.args.keys().asSequence().joinToString(", ") { "$it: ${step.args.opt(it)}" } + ")",
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        maxLines = if (open) Int.MAX_VALUE else 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (open) "Hide details" else "Show details", Modifier.size(18.dp))
    }
    if (open) {
      Text("Arguments", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(step.args.toString(2), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
      Text("Result", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(
        when (val r = step.result) {
          is JSONObject -> r.toString(2)
          is JSONArray -> r.toString(2)
          else -> r.toString()
        },
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
      )
    }
  }
}
