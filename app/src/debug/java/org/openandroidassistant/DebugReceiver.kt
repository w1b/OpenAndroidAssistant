/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.openandroidassistant.data.settings.Secret
import org.openandroidassistant.data.settings.SecretStore
import org.openandroidassistant.data.settings.SettingsRepository
import org.openandroidassistant.data.settings.effectiveTtsPlaybackSpeed
import org.openandroidassistant.orchestrator.ConversationController
import org.openandroidassistant.proto.ModelProvider

/**
 * Debug builds only: configure the app and drive conversations from adb, for emulator tests.
 *
 * adb shell am broadcast -n org.openandroidassistant.app/org.openandroidassistant.DebugReceiver \
 *   --es openrouter_key sk-or-… --es provider openrouter --es openrouter_model google/gemini-3.8-flash
 * adb shell am broadcast -n …/…DebugReceiver --es approve yes|no
 * adb shell am broadcast -n …/…DebugReceiver --es text "What time is it?"
 * adb shell am broadcast -n …/…DebugReceiver --es audio /data/user/0/org.openandroidassistant.app/files/benchmarks/clip.pcm
 * adb shell am broadcast -n …/…DebugReceiver --es feed /data/user/0/…/files/benchmarks/answer.pcm
 */
@AndroidEntryPoint
class DebugReceiver : BroadcastReceiver() {
  @Inject lateinit var secrets: SecretStore
  @Inject lateinit var settings: SettingsRepository
  @Inject lateinit var controller: ConversationController
  @Inject lateinit var kitten: org.openandroidassistant.audio.KittenTtsSynthesizer
  @Inject lateinit var tts: org.openandroidassistant.audio.TtsSpeaker
  @Inject lateinit var normalizer: org.openandroidassistant.audio.SpeechNormalizer

  override fun onReceive(context: Context, intent: Intent) {
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
      try {
        intent.getStringExtra("openrouter_key")?.let { secrets.set(Secret.OPENROUTER_KEY, it) }
        intent.getStringExtra("openai_key")?.let { secrets.set(Secret.OPENAI_KEY, it) }
        settings.update { b ->
          intent.getStringExtra("provider")?.let {
            b.provider =
              when (it) {
                "local" -> ModelProvider.PROVIDER_LOCAL
                "openai" -> ModelProvider.PROVIDER_OPENAI
                "realtime" -> ModelProvider.PROVIDER_OPENAI_REALTIME
                else -> ModelProvider.PROVIDER_OPENROUTER
              }
          }
          intent.getStringExtra("openrouter_model")?.let { b.setOpenrouterModel(it).setOpenrouterModelInfoCached(false) }
          intent.getStringExtra("openrouter_reasoning")?.let { b.openrouterReasoning = it }
          intent.getStringExtra("local_model")?.let { b.localModel = it }
          intent.getStringExtra("searxng_url")?.let { b.searxngUrl = it }
          intent.getStringExtra("web_search")?.let { mode ->
            val m = org.openandroidassistant.proto.WebSearchMode.valueOf("WEB_SEARCH_" + mode.uppercase())
            b.setOpenrouterWebSearch(m).setOpenaiWebSearch(m).setLocalWebSearch(m)
          }
          intent.getStringExtra("about_name")?.let { b.aboutName = it }
          intent.getStringExtra("about_home")?.let { b.aboutHome = it }
          intent.getStringExtra("history")?.let { b.history = org.openandroidassistant.proto.HistoryMode.valueOf("HISTORY_" + it.uppercase()) }
        }
        intent.getStringExtra("send_sms_to")?.let { to ->
          val sms = context.getSystemService(android.telephony.SmsManager::class.java)
          sms.sendTextMessage(to, null, intent.getStringExtra("send_sms_body").orEmpty(), null, null)
        }
        when (intent.getStringExtra("approve")) {
          "yes" -> controller.onApproveTapped()
          "no" -> controller.onDeclineTapped()
        }
        if (intent.getBooleanExtra("dismiss", false)) controller.onOverlayDismissed()
        intent.getStringExtra("text")?.let { controller.onDebugText(it) }
        intent.getStringExtra("audio")?.let { controller.onDebugAudio(it, startTurn = true) }
        // Feeds whatever is listening now (an approval, a follow-up) instead of starting a turn.
        intent.getStringExtra("feed")?.let { controller.onDebugAudio(it, startTurn = false) }
        // Runs for minutes: on its own thread, past the broadcast's time limit.
        intent.getStringExtra("stt_bench")?.let { spec ->
          val threads = intent.getIntExtra("threads", 4)
          val clips = intent.getStringExtra("clips") ?: "benchmarks/stt/clips"
          Thread { runCatching { SttBench.run(context.applicationContext, spec, threads, clips) }.onFailure { Log.e("SttBench", "failed", it) } }.start()
        }
        intent.getStringExtra("preview")?.let { controller.debugPreview(it) }
        intent.getStringExtra("approval_test")?.let { controller.debugApproval(it, intent.getStringExtra("approval_answer"), intent.getIntExtra("approval_at", 0)) }
        intent.getStringExtra("tts_dump")?.let { ttsDump(context, it) }
        intent.getStringExtra("tts_say")?.let { text ->
          // The overlay's path: sentences into a SpeechQueue, timings in OaaPerf.
          val chunker = org.openandroidassistant.orchestrator.SentenceChunker()
          val queue = org.openandroidassistant.orchestrator.SpeechQueue(this, tts, normalizer, { "en" }, { true }) { _, _ -> }
          for ((offset, sentence) in chunker.take(text) + listOfNotNull(chunker.rest(text))) queue.enqueue(1, sentence, offset)
          queue.awaitIdle()
          queue.close()
        }
        Log.i("DebugReceiver", "applied ${intent.extras?.keySet()}")
      } finally {
        pending.finish()
      }
    }
  }

  /** Synthesizes each sentence of [text] with KittenTTS and writes raw PCM to cache/tts_dump/, logging timings. */
  private suspend fun ttsDump(context: Context, text: String) {
    val dir = context.cacheDir.resolve("tts_dump").apply { deleteRecursively(); mkdirs() }
    val chunker = org.openandroidassistant.orchestrator.SentenceChunker()
    // Through the normalizer, as everything spoken is.
    val spoken = normalizer.normalize(text, "en")
    val sentences = chunker.take(spoken).map { it.second } + listOfNotNull(chunker.rest(spoken)?.second)
    val speed = settings.current().effectiveTtsPlaybackSpeed
    sentences.forEachIndexed { i, sentence ->
      val start = android.os.SystemClock.elapsedRealtime()
      val audio = kitten.synthesize(sentence.trim(), "Hugo", speed) ?: return
      val ms = android.os.SystemClock.elapsedRealtime() - start
      dir.resolve("$i.pcm").writeBytes(audio.bytes)
      Log.i("TtsDump", "i=$i rate=${audio.sampleRate} synth_ms=$ms audio_ms=${audio.bytes.size * 500L / audio.sampleRate} speed=$speed text=${sentence.trim()}")
    }
  }
}
