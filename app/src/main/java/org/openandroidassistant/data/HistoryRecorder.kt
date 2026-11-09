/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.data

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openandroidassistant.runtime.AudioSegment
import org.openandroidassistant.runtime.toWavBytes
import org.openandroidassistant.data.settings.SettingsRepository
import org.openandroidassistant.proto.HistoryMode
import org.openandroidassistant.stt.SttSessionFactory
import org.openandroidassistant.stt.asSentence

private const val TAG = "HistoryRecorder"

/** Saves conversations for the history screen, unless history is turned off. */
@Singleton
class HistoryRecorder
@Inject
constructor(
  @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
  db: OaaDatabase,
  private val settings: SettingsRepository,
  private val stt: SttSessionFactory,
) {
  val dao: HistoryDao = db.historyDao()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val mutex = Mutex()
  /** The user's recorded requests, one WAV per exchange. */
  val audioDir: java.io.File = context.filesDir.resolve("history-audio")

  /** Starts a saved conversation; null when history is off. */
  suspend fun startConversation(): Long? =
    mutex.withLock {
      if (settings.current().history == HistoryMode.HISTORY_OFF) return null
      val now = System.currentTimeMillis()
      dao.insertConversation(ConversationEntity(startedAtMs = now, updatedAtMs = now))
    }

  /**
   * Saves one exchange. [voicePcm] is the user's recorded voice (16 kHz), kept so it can be played
   * back unless history is text only; when there's no text yet (the model heard the audio
   * directly), a local transcript is made from it for the history screen either way.
   */
  fun saveExchange(
    conversationId: Long?,
    userKind: String,
    userText: String,
    assistantText: String,
    shown: String,
    toolSteps: String,
    voicePcm: ShortArray?,
  ) {
    conversationId ?: return
    scope.launch {
      runCatching {
          val text =
            if (voicePcm != null && userText.isBlank()) {
              val s = settings.current()
              stt.session(s)?.transcribe(voicePcm)?.asSentence().orEmpty()
            } else userText
          mutex.withLock {
            val mode = settings.current().history
            if (mode == HistoryMode.HISTORY_OFF) return@withLock
            val now = System.currentTimeMillis()
            val audioFile =
              voicePcm?.takeIf { it.isNotEmpty() && mode == HistoryMode.HISTORY_TEXT_AND_VOICE }?.let { pcm ->
                val name = "$conversationId-$now.wav"
                audioDir.mkdirs()
                audioDir.resolve(name).writeBytes(AudioSegment.fromShorts(pcm).toWavBytes())
                name
              }
            dao.insertExchange(
              ExchangeEntity(
                conversationId = conversationId,
                createdAtMs = now,
                userKind = userKind,
                userText = text,
                assistantText = assistantText,
                shown = shown,
                toolSteps = toolSteps,
                audioFile = audioFile,
              )
            )
            dao.touchConversation(conversationId, now, text.ifBlank { assistantText }.take(80))
          }
        }
        .onFailure { Log.w(TAG, "Couldn't save exchange", it) }
    }
  }

  suspend fun deleteConversation(id: Long) =
    mutex.withLock {
      dao.audioFiles(id).forEach { audioDir.resolve(it).delete() }
      dao.deleteExchanges(id)
      dao.deleteConversation(id)
    }

  suspend fun clear() =
    mutex.withLock {
      dao.clearExchanges()
      dao.clearConversations()
      audioDir.deleteRecursively()
    }
}
