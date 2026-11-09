/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.ui.home

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.openandroidassistant.data.model.DownloadState
import org.openandroidassistant.ui.components.DownloadProgress
import org.openandroidassistant.ui.components.isActive
import org.openandroidassistant.orchestrator.TriggerSource
import org.openandroidassistant.ui.components.CardBlock
import org.openandroidassistant.ui.overlay.AssistantOverlayActivity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
  onOpenSettings: () -> Unit,
  onOpenModel: () -> Unit,
  onOpenHistory: () -> Unit,
  viewModel: HomeViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsState()
  val context = LocalContext.current
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refresh() }
  DisposableEffect(lifecycle) {
    val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) viewModel.refresh() }
    lifecycle.addObserver(observer)
    onDispose { lifecycle.removeObserver(observer) }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("OpenAndroidAssistant") },
        actions = {
          IconButton(onClick = onOpenHistory) { Icon(Icons.Filled.History, "History") }
          IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, "Settings") }
        },
      )
    }
  ) { padding ->
    Column(
      Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      for (step in state.steps) {
        CardBlock {
          Text(if (step == SetupStep.KEY) "Add your ${state.providerName} API key" else step.title, style = MaterialTheme.typography.titleMedium)
          step.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
          val busy = step == SetupStep.SPEECH && state.speech.isActive
          if (busy) DownloadProgress(state.speech, horizontalPadding = 0.dp)
          if (step == SetupStep.SPEECH && state.speech is DownloadState.Failed) {
            Text("Download failed: ${(state.speech as DownloadState.Failed).message}", color = MaterialTheme.colorScheme.error)
          }
          if (!busy) OutlinedButton(
            enabled = !busy,
            onClick = {
              when (step) {
                SetupStep.MICROPHONE -> mic.launch(Manifest.permission.RECORD_AUDIO)
                SetupStep.MODEL, SetupStep.KEY, SetupStep.LOCAL_MODEL -> onOpenModel()
                SetupStep.SPEECH -> viewModel.downloadSpeech()
              }
            },
          ) { Text(if (step == SetupStep.SPEECH && state.speech is DownloadState.Failed) "Try again" else step.action) }
        }
      }
      Spacer(Modifier.height(24.dp))
      if (!state.ready) {
        // Until a model can answer, the setup steps above are the only thing to do.
        Text(
          "Finish the steps above to start talking to the assistant.",
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        )
      } else {
        Text(
          state.modelLabel,
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        )
        Button(
          onClick = { AssistantOverlayActivity.launch(context, TriggerSource.IN_APP) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(64.dp),
        ) {
          Icon(Icons.Filled.Mic, null)
          Text("  Talk to the assistant", style = MaterialTheme.typography.titleMedium)
        }
        Text(
          "Or use a wake word, the Quick Settings tile, or set it as your phone's assist app.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        )
      }
    }
  }
}
