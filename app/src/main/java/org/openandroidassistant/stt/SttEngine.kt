/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.stt

/**
 * Local speech-to-text. Used for the text-only transcript, voice approval, and
 * history transcripts.
 */
interface SttEngine {
  /** Loads the language model. Idempotent. */
  suspend fun ensureLoaded()

  suspend fun unload()

  /** A new recognition stream. Feed it 16 kHz mono PCM frames from one thread. */
  suspend fun openStream(): SttStream

  /** Transcribes a finished recording. */
  suspend fun transcribe(pcm: ShortArray): String
}

interface SttStream : AutoCloseable {
  /**
   * Feeds one frame and whether voice detection heard speech in it; returns the live transcript
   * so far, which may lag and still change.
   */
  fun accept(frame: ShortArray, speech: Boolean): String

  /** The transcript of everything fed so far, decoded now; waits for the model if it's still loading. */
  fun peek(): String

  /** Returns the transcript of everything fed. */
  fun finish(): String
}

/** Starts the text with a capital, for display. */
fun String.asSentence(): String = trim().replaceFirstChar { it.uppercase() }.replace(Regex("\\bi\\b"), "I").replace(Regex("\\bi'"), "I'")
