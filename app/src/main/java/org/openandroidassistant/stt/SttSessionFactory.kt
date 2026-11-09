/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.stt

import android.os.SystemClock
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.openandroidassistant.data.model.ArtifactStore
import org.openandroidassistant.data.model.Artifacts
import org.openandroidassistant.proto.AssistantSettings

private const val TAG = "SttSessionFactory"
private const val PERF_TAG = "OaaPerf"
/** The model holds about 640 MB; it's let go this long after the assistant goes idle. */
private const val IDLE_UNLOAD_MS = 5 * 60_000L

/** Hands out the speech recognizer once its model is downloaded; null before that. */
@Singleton
class SttSessionFactory @Inject constructor(private val artifactStore: ArtifactStore) {

  private var cached: ParakeetSttEngine? = null
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var unloadJob: Job? = null

  @Synchronized
  fun session(@Suppress("UNUSED_PARAMETER") settings: AssistantSettings): SttEngine? {
    unloadJob?.cancel()
    cached?.let { return it }
    if (!artifactStore.isInstalled(Artifacts.SPEECH)) return null
    return ParakeetSttEngine(artifactStore.installedPath(Artifacts.SPEECH)).also { cached = it }
  }

  /** Loads the recognizer while the user is still speaking. */
  suspend fun prewarm(settings: AssistantSettings): Boolean {
    val startedAt = SystemClock.elapsedRealtime()
    val ready =
      runCatching {
          val engine = session(settings) ?: return@runCatching false
          engine.ensureLoaded()
          true
        }
        .onFailure { Log.w(TAG, "STT prewarm failed", it) }
        .getOrDefault(false)
    Log.i(PERF_TAG, "stt_prewarm elapsed_ms=${SystemClock.elapsedRealtime() - startedAt} ready=$ready")
    return ready
  }

  /** The assistant went idle: free the model unless it's needed again soon. */
  @Synchronized
  fun idle() {
    unloadJob?.cancel()
    val engine = cached ?: return
    unloadJob =
      scope.launch {
        delay(IDLE_UNLOAD_MS)
        engine.unload()
        Log.i(TAG, "speech model unloaded after idle")
      }
  }
}
