/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.ui.overlay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.halilibo.richtext.commonmark.Markdown
import com.halilibo.richtext.ui.material3.RichText
import org.openandroidassistant.model.ModelKind
import org.openandroidassistant.orchestrator.ApprovalUi
import org.openandroidassistant.orchestrator.AssistantPhase
import org.openandroidassistant.orchestrator.ConversationController
import org.openandroidassistant.orchestrator.Exchange
import org.openandroidassistant.orchestrator.ExchangePart
import org.openandroidassistant.orchestrator.OverlayUiState
import org.openandroidassistant.orchestrator.UserEntry
import org.openandroidassistant.tools.ApprovalLevel

/**
 * The assistant overlay: the current screen dimmed, the assistant at the bottom.
 * The latest exchange is front and center; earlier ones are a scroll up away.
 */
@Composable
fun OverlayScreen(controller: ConversationController, onDismiss: () -> Unit) {
  val state by controller.uiState.collectAsState()
  val scrim by animateFloatAsState(if (state.visible) 0.5f else 0f, label = "scrim")

  Box(
    modifier =
      Modifier.fillMaxSize()
        .background(Color.Black.copy(alpha = scrim))
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    contentAlignment = Alignment.BottomCenter,
  ) {
    AnimatedVisibility(
      visible = state.visible,
      enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
      exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
    ) {
      Panel(state, controller, onDismiss)
    }
  }
}

@Composable
private fun Panel(state: OverlayUiState, controller: ConversationController, onDismiss: () -> Unit) {
  Surface(
    modifier =
      Modifier.fillMaxWidth()
        .statusBarsPadding()
        .imePadding()
        // Taps on the panel don't fall through to the scrim (which dismisses).
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { controller.onOverlayInteraction() },
    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    color = MaterialTheme.colorScheme.surfaceContainer,
    tonalElevation = 3.dp,
    shadowElevation = 16.dp,
  ) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 16.dp, bottom = 12.dp)) {
      StatusLine(state)
      Conversation(state, Modifier.padding(horizontal = 20.dp))
      state.approval?.let { ApprovalCardView(it, controller::onApproveTapped, controller::onDeclineTapped) }
      state.unlockPrompt?.let { UnlockPrompt(it) }
      state.notice?.takeIf { state.exchanges.lastOrNull()?.parts.isNullOrEmpty() }?.let { Notice(it) }
      if (state.typing) {
        TypingRow(onSend = controller::onTypedSubmit, onClose = { controller.onKeyboardToggled(false) })
      } else {
        Controls(state, controller, onDismiss)
      }
    }
  }
}

/** Turn-based state label. Realtime shows none. */
@Composable
private fun StatusLine(state: OverlayUiState) {
  if (state.realtime != null) return
  val text =
    when (state.phase) {
      AssistantPhase.LISTENING -> "Listening"
      AssistantPhase.THINKING -> state.workingLabel ?: "Thinking"
      AssistantPhase.SPEAKING -> state.workingLabel ?: "Speaking"
      AssistantPhase.APPROVAL -> "Approval"
      AssistantPhase.WAKING_UP -> "Waking up"
      AssistantPhase.IDLE -> null
    }
  Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(28.dp)) {
    AnimatedContent(targetState = text, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "status") { label ->
      if (label != null) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
      }
    }
    if (state.phase == AssistantPhase.THINKING || state.phase == AssistantPhase.WAKING_UP) {
      LinearProgressIndicator(Modifier.fillMaxWidth(0.25f).height(2.dp).clip(CircleShape))
    }
  }
}

/**
 * The conversation, newest at the bottom. The viewport is sized to the latest exchange, so
 * earlier exchanges sit just above it, one scroll away.
 */
@Composable
private fun Conversation(state: OverlayUiState, modifier: Modifier) {
  val exchanges = state.exchanges
  if (exchanges.isEmpty() && state.liveTranscript.isEmpty()) return
  val density = LocalDensity.current
  var latestHeight by remember { mutableIntStateOf(0) }
  val listState = rememberLazyListState()
  LaunchedEffect(exchanges.size) { listState.scrollToItem(0) }
  BoxWithConstraints(modifier.fillMaxWidth()) {
    val max = maxHeight * 0.7f
    val viewport = with(density) { latestHeight.toDp() }.coerceIn(48.dp, if (max > 0.dp) max else 480.dp)
    LazyColumn(
      state = listState,
      reverseLayout = true,
      modifier = Modifier.fillMaxWidth().heightIn(max = if (exchanges.size > 1) viewport else 2000.dp),
      verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.Bottom),
    ) {
      val ordered = exchanges.asReversed()
      items(ordered, key = { it.id }) { exchange ->
        val latest = exchange.id == exchanges.last().id
        ExchangeView(
          exchange = exchange,
          liveTranscript = if (latest) state.liveTranscript else "",
          faded = !latest,
          modifier = if (latest) Modifier.onSizeChanged { latestHeight = it.height } else Modifier,
        )
      }
      if (exchanges.isEmpty()) item { LiveTranscript(state.liveTranscript) }
    }
  }
}

@Composable
private fun ExchangeView(exchange: Exchange, liveTranscript: String, faded: Boolean, modifier: Modifier) {
  Column(modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    exchange.user?.let { UserBubble(it, faded) }
    // The reply reads first, then what it's about, whatever order the model produced them in.
    val ordered = exchange.parts.filterIsInstance<ExchangePart.Speech>() + exchange.parts.filter { it !is ExchangePart.Speech }
    for (part in ordered) {
      when (part) {
        is ExchangePart.Speech -> SpeechText(part.text, part.revealedChars, faded)
        is ExchangePart.Card -> RenderCardView(part.card)
        is ExchangePart.Markdown -> MarkdownBlock(part.text)
      }
    }
    if (liveTranscript.isNotEmpty()) LiveTranscript(liveTranscript)
  }
}

@Composable
private fun UserBubble(entry: UserEntry, faded: Boolean) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
    Row(
      Modifier.widthIn(max = 300.dp)
        .clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = if (faded) 0.6f else 1f))
        .padding(horizontal = 14.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      when (entry) {
        UserEntry.Voice -> {
          Icon(Icons.Filled.Mic, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
          Text("Voice request", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        is UserEntry.Text -> {
          if (entry.typed) Icon(Icons.Filled.Keyboard, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
          Text(entry.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
      }
    }
  }
}

@Composable
private fun LiveTranscript(text: String) {
  if (text.isEmpty()) return
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
    Text(
      text,
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.widthIn(max = 320.dp),
    )
  }
}

/** The reply lights up in sync with speech. */
@Composable
private fun SpeechText(text: String, revealedChars: Int?, faded: Boolean) {
  if (text.isBlank()) return
  val spoken = MaterialTheme.colorScheme.onSurface.copy(alpha = if (faded) 0.7f else 1f)
  val upcoming = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
  val rendered =
    buildAnnotatedString {
      append(text)
      if (revealedChars != null && revealedChars < text.length) {
        val end = revealedChars.coerceIn(0, text.length)
        addStyle(SpanStyle(color = upcoming), end, text.length)
        val featherStart = (end - 2).coerceAtLeast(0)
        for (i in featherStart until end) {
          addStyle(SpanStyle(color = lerp(upcoming, spoken, (end - i) / 3f)), i, i + 1)
        }
      }
    }
  Text(
    rendered,
    style = if (faded) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleLarge,
    color = spoken,
  )
}

@Composable
private fun MarkdownBlock(text: String) {
  Box(
    Modifier.fillMaxWidth()
      .clip(RoundedCornerShape(16.dp))
      .background(MaterialTheme.colorScheme.surfaceContainerHighest)
      .padding(16.dp)
  ) {
    // Selectable, so a command or recipe can be copied.
    androidx.compose.foundation.text.selection.SelectionContainer { RichText { Markdown(text) } }
  }
}

@Composable
private fun ApprovalCardView(ui: ApprovalUi, onApprove: () -> Unit, onDecline: () -> Unit) {
  Column(
    Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
      .fillMaxWidth()
      .clip(RoundedCornerShape(20.dp))
      .background(MaterialTheme.colorScheme.surfaceContainerHighest)
      .padding(18.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(ui.card.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Text(ui.card.target, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    if (ui.card.body.isNotBlank()) {
      Text(ui.card.body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
    val hint =
      when {
        ui.realtime -> "The assistant can't hear you right now. Say yes or no, or tap."
        ui.level == ApprovalLevel.LOW && ui.listening -> "Say yes or no, or tap."
        ui.level == ApprovalLevel.CRITICAL -> "Tap, then confirm it's you."
        else -> null
      }
    hint?.let {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (ui.listening) Icon(Icons.Filled.Mic, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        if (ui.level == ApprovalLevel.CRITICAL) Icon(Icons.Filled.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      OutlinedButton(onClick = onDecline, modifier = Modifier.weight(1f)) { Text("Cancel") }
      Button(onClick = onApprove, modifier = Modifier.weight(1f)) { Text(ui.card.title.substringBefore(' ').ifBlank { "Approve" }) }
    }
  }
}

@Composable
private fun UnlockPrompt(prompt: String) {
  Row(
    Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
      .fillMaxWidth()
      .clip(RoundedCornerShape(16.dp))
      .background(MaterialTheme.colorScheme.tertiaryContainer)
      .padding(14.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
    Text(prompt, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onTertiaryContainer)
  }
}

@Composable
private fun Notice(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.titleMedium,
    color = MaterialTheme.colorScheme.onSurface,
    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
  )
}

@Composable
private fun Controls(state: OverlayUiState, controller: ConversationController, onDismiss: () -> Unit) {
  val busy = state.phase != AssistantPhase.IDLE && state.phase != AssistantPhase.LISTENING
  // The mic stays centered; side buttons sit at the edges.
  Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp)) {
    Box(Modifier.align(Alignment.CenterStart)) { RoundIcon(Icons.Filled.Keyboard, "Type instead") { controller.onKeyboardToggled(true) } }
    Box(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 112.dp), contentAlignment = Alignment.Center) {
      if (state.realtime != null) RealtimeLevels(state) else MicControl(state, controller::onMicPressed)
    }
    Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (state.realtime != null) {
        RoundIcon(if (state.realtime.muted) Icons.Filled.MicOff else Icons.Filled.Mic, if (state.realtime.muted) "Unmute" else "Mute") {
          controller.onMicPressed()
        }
      } else if (busy) {
        RoundIcon(Icons.Filled.Stop, "Stop", controller::onStopPressed)
      }
      RoundIcon(Icons.Filled.Close, "Close", onDismiss)
    }
  }
}

/**
 * Native audio in: the mic button morphs into a waveform that builds up as you speak.
 * Text-only: a small level indicator next to the live transcript above.
 */
@Composable
private fun MicControl(state: OverlayUiState, onClick: () -> Unit) {
  val listening = state.phase == AssistantPhase.LISTENING
  val waveform = listening && state.modelKind == ModelKind.AUDIO_IN
  val container by
    animateColorAsState(if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer, label = "mic color")
  val content by
    animateColorAsState(if (listening) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer, label = "mic content")
  BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    val width by animateDpAsState(if (waveform) maxWidth else 64.dp, spring(dampingRatio = 0.78f, stiffness = 430f), label = "mic width")
    Box(
      Modifier.width(width)
        .height(64.dp)
        .clip(RoundedCornerShape(32.dp))
        .background(container)
        .semantics {
          contentDescription = if (listening) "Done speaking" else "Speak"
          role = Role.Button
        }
        .clickable(onClick = onClick),
      contentAlignment = Alignment.Center,
    ) {
      if (waveform) {
        Waveform(micLevel = state.micLevel, active = true, color = content, modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 18.dp))
      } else if (listening) {
        LevelDot(state.micLevel, content)
      } else {
        Icon(Icons.Filled.Mic, null, tint = content)
      }
    }
  }
}

@Composable
private fun LevelDot(level: Float, color: Color) {
  val scale by animateFloatAsState((0.45f + level * 8f).coerceIn(0.45f, 1f), label = "level")
  Box(Modifier.size((36 * scale).dp).clip(CircleShape).background(color))
}

/** Realtime: a level indicator for each side. */
@Composable
private fun RealtimeLevels(state: OverlayUiState) {
  val rt = state.realtime ?: return
  Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
      LevelBar("You", if (rt.muted) 0f else rt.userLevel, MaterialTheme.colorScheme.secondary)
      LevelBar("Assistant", rt.assistantLevel, MaterialTheme.colorScheme.primary)
    }
    rt.activity?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
  }
}

@Composable
private fun LevelBar(label: String, level: Float, color: Color) {
  val width by animateDpAsState((12 + level * 400).coerceIn(12f, 96f).dp, label = "level $label")
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Box(Modifier.height(10.dp).width(width).clip(CircleShape).background(color))
    Spacer(Modifier.height(4.dp))
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun TypingRow(onSend: (String) -> Unit, onClose: () -> Unit) {
  var text by rememberSaveable { mutableStateOf("") }
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { focus.requestFocus() }
  Row(
    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    RoundIcon(Icons.Filled.Mic, "Speak instead", onClose)
    TextField(
      value = text,
      onValueChange = { text = it },
      modifier = Modifier.weight(1f).focusRequester(focus),
      placeholder = { Text("Type a request") },
      shape = RoundedCornerShape(28.dp),
      colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
      keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) onSend(text).also { text = "" } }),
      singleLine = false,
      maxLines = 4,
    )
    RoundIcon(Icons.AutoMirrored.Filled.Send, "Send") { if (text.isNotBlank()) onSend(text).also { text = "" } }
  }
}

@Composable
private fun RoundIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
  Box(
    Modifier.size(48.dp)
      .clip(CircleShape)
      .background(MaterialTheme.colorScheme.surfaceContainerHighest)
      .semantics { contentDescription = description; role = Role.Button }
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}
