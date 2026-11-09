/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.model

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.openandroidassistant.data.model.ArtifactStore
import org.openandroidassistant.data.model.LocalModel
import org.openandroidassistant.data.settings.SettingsRepository
import org.openandroidassistant.runtime.LoadState
import org.openandroidassistant.runtime.toWavBytes
import org.openandroidassistant.tools.ToolSchema

private const val TAG = "LitertChatModel"
private const val PERF_TAG = "OaaPerf"

private const val TOP_K = 64
private const val TOP_P = 0.95
private const val TEMPERATURE = 0.4
private const val MAX_NUM_TOKENS = 8192

/**
 * The on-device model over LiteRT-LM. Gemma hears audio directly (native audio in); the other
 * models are text-only. The app mediates every tool call (`automaticToolCalling = false`).
 *
 * LiteRT-LM supports one native conversation at a time; [openSession] waits for the previous one
 * to close.
 */
@OptIn(ExperimentalApi::class)
@Singleton
class LitertChatModel
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val artifactStore: ArtifactStore,
  private val settingsRepository: SettingsRepository,
) : ChatModel {
  /** The chosen on-device model; set from settings before each conversation. */
  @Volatile var selected: LocalModel = LocalModel.GEMMA_4_E2B
  private var loaded: LocalModel? = null

  override val displayName: String
    get() = selected.displayName

  override val kind: ModelKind
    get() = selected.kind

  override val isLocal = true

  private val _loadState = MutableStateFlow<LoadState>(LoadState.Unloaded)
  val loadState: StateFlow<LoadState> = _loadState

  /** All blocking engine JNI work is serialized on one thread. */
  private val engineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
  private val mutex = Mutex()
  private val sessionLease = Mutex()
  private var engine: Engine? = null

  override suspend fun prepare() {
    if (engine != null && loaded != selected) unload()
    mutex.withLock {
      if (engine != null) return
      _loadState.value = LoadState.Loading
      try {
        withContext(engineDispatcher) {
          val modelPath = resolveModelPath()
          ExperimentalFlags.enableBenchmark = systemProperty("debug.oaa.benchmark") == "on"
          ExperimentalFlags.enableSpeculativeDecoding = systemProperty("debug.oaa.speculative") == "on"
          engine = initializeWithFallback(modelPath)
          loaded = selected
        }
        _loadState.value = LoadState.Ready
      } catch (e: Exception) {
        Log.e(TAG, "Engine load failed", e)
        _loadState.value = LoadState.Failed(e.message ?: "Model load failed")
        throw e
      }
    }
  }

  suspend fun unload() {
    sessionLease.withLock {
      mutex.withLock {
        withContext(engineDispatcher) {
          runCatching { warm?.first?.close() }
          warm = null
          runCatching { engine?.close() }
          engine = null
        }
        _loadState.value = LoadState.Unloaded
      }
    }
  }

  /** A conversation created and primed while idle, with the spec it was built for. */
  private var warm: Pair<Conversation, Int>? = null

  override suspend fun openSession(spec: SessionSpec): ChatSession {
    prepare()
    sessionLease.lock()
    try {
      val conversation =
        mutex.withLock {
          withContext(engineDispatcher) {
            val pooled = warm?.takeIf { it.second == fingerprint(spec) }?.first
            warm?.takeIf { pooled == null }?.first?.let { runCatching { it.close() } }
            warm = null
            pooled ?: createConversation(spec)
          }
        }
      return LitertSession(conversation, engineDispatcher) { sessionLease.unlock() }
    } catch (e: Exception) {
      sessionLease.unlock()
      throw e
    }
  }

  /**
   * While the model is loaded and idle, builds the next conversation and runs one tiny exchange
   * through it. LiteRT-LM only prefills the system prompt and tools on the first message, so this
   * moves seconds of prefill off the next request. Skipped while a conversation is open.
   */
  suspend fun warmUp(spec: SessionSpec) {
    if (engine == null || sessionLease.isLocked) return
    val key = fingerprint(spec)
    mutex.withLock {
      if (warm?.second == key || sessionLease.isLocked) return
      withContext(engineDispatcher) {
        warm?.first?.let { runCatching { it.close() } }
        warm = null
        val conversation = createConversation(spec)
        val started = SystemClock.elapsedRealtime()
        val ok =
          runCatching {
              conversation.sendMessage(Message.user("(Startup check from the app. Reply with only the word: Ready)"), emptyMap())
            }
            .isSuccess
        Log.i(PERF_TAG, "warm_up ok=$ok elapsed_ms=${SystemClock.elapsedRealtime() - started}")
        if (ok) warm = conversation to key else runCatching { conversation.close() }
      }
    }
  }

  private fun fingerprint(spec: SessionSpec): Int =
    31 * spec.systemPrompt.hashCode() + spec.tools.joinToString { it.toFunctionDeclarationJson().toString() }.hashCode()

  /** Must run on [engineDispatcher]. GPU first on real hardware, CPU as the fallback. */
  private fun initializeWithFallback(modelPath: String): Engine {
    var lastError: Exception? = null
    for (backend in backendPlan()) {
      try {
        val started = SystemClock.elapsedRealtime()
        return Engine(engineConfig(modelPath, backend)).also {
          it.initialize()
          Log.i(PERF_TAG, "engine_init backend=${backend::class.simpleName} " +
            "elapsed_ms=${SystemClock.elapsedRealtime() - started}")
        }
      } catch (e: Exception) {
        Log.w(TAG, "Backend ${backend::class.simpleName} failed", e)
        lastError = e
      }
    }
    throw lastError ?: IllegalStateException("No usable backend")
  }

  private fun engineConfig(modelPath: String, backend: Backend) =
    EngineConfig(
      modelPath = modelPath,
      backend = backend,
      visionBackend = null,
      // Only models that hear audio have an audio encoder; asking a text-only model for one
      // fails ("TF_LITE_AUDIO_ENCODER_HW not found").
      audioBackend = if (selected.kind == ModelKind.AUDIO_IN) Backend.CPU() else null,
      maxNumTokens = MAX_NUM_TOKENS,
      cacheDir = File(context.filesDir, "litertlm-cache").apply { mkdirs() }.absolutePath,
    )

  private fun backendPlan(): List<Backend> =
    when (systemProperty("debug.oaa.backend")) {
      "cpu" -> listOf(Backend.CPU())
      "gpu" -> listOf(Backend.GPU())
      // x86_64 images are emulators, which have no usable GPU delegate.
      else ->
        if (android.os.Build.SUPPORTED_ABIS.firstOrNull() == "x86_64") listOf(Backend.CPU())
        else listOf(Backend.GPU(), Backend.CPU())
    }

  /** Must run on [engineDispatcher]. */
  private fun createConversation(spec: SessionSpec): Conversation {
    val engine = checkNotNull(engine) { "Engine not loaded" }
    return engine.createConversation(
      ConversationConfig(
        systemInstruction = Contents.of(listOf(Content.Text(spec.systemPrompt))),
        initialMessages = emptyList(),
        tools = spec.tools.map { it.toToolProvider() },
        samplerConfig = SamplerConfig(topK = TOP_K, topP = TOP_P, temperature = TEMPERATURE),
        automaticToolCalling = false,
      )
    )
  }

  private suspend fun resolveModelPath(): String {
    systemProperty("debug.oaa.modelpath")?.let { if (File(it).isFile) return it }
    val imported = settingsRepository.current().importedModelPath
    if (imported.isNotEmpty() && File(imported).isFile) return imported
    val installed = artifactStore.installedPath(selected.artifact)
    check(installed.isFile) { "Model not installed" }
    return installed.absolutePath
  }

  /** True once the model file is on the device. */
  fun isInstalled(model: LocalModel = selected): Boolean =
    artifactStore.isInstalled(model.artifact) || systemProperty("debug.oaa.modelpath") != null
}

/** Declares one tool to LiteRT-LM. `execute` never runs: the app executes every call. */
private fun ToolSchema.toToolProvider(): ToolProvider {
  val declaration = toFunctionDeclarationJson().toString()
  return tool(
    object : OpenApiTool {
      override fun getToolDescriptionJsonString(): String = declaration

      override fun execute(paramsJsonString: String): String =
        throw IllegalStateException("Tool '$name' is executed by the app, not LiteRT-LM")
    }
  )
}

@OptIn(ExperimentalApi::class)
private class LitertSession(
  private val conversation: Conversation,
  private val dispatcher: kotlinx.coroutines.CoroutineDispatcher,
  private val onClosed: () -> Unit,
) : ChatSession {
  private val closed = AtomicBoolean(false)
  private var callCounter = 0

  override suspend fun run(input: ModelInput, onText: (String) -> Unit): RunResult =
    withContext(dispatcher) {
      val startedAt = SystemClock.elapsedRealtime()
      suspendCancellableCoroutine { continuation ->
        val streamed = StringBuilder()
        var last: Message? = null
        conversation.sendMessageAsync(
          input.toMessage(),
          object : MessageCallback {
            override fun onMessage(message: Message) {
              val text = message.contents.contents.filterIsInstance<Content.Text>()
                .joinToString("") { it.text }
              if (text.isNotEmpty()) {
                streamed.append(text)
                onText(streamed.toString())
              }
              last = message
            }

            override fun onDone() {
              if (!continuation.isActive) return
              val calls =
                last?.toolCalls.orEmpty().map { call ->
                  ModelToolCall(id = "call_${callCounter++}", name = call.name, args = call.arguments)
                }
              Log.i(PERF_TAG, "litert_run elapsed_ms=${SystemClock.elapsedRealtime() - startedAt} " +
                "text_chars=${streamed.length} tool_calls=${calls.size}")
              continuation.resume(RunResult.Complete(ModelMessage(streamed.toString(), calls)))
            }

            override fun onError(throwable: Throwable) {
              if (!continuation.isActive) return
              if (throwable is CancellationException) {
                continuation.resume(RunResult.Cancelled(streamed.toString()))
              } else {
                Log.e(TAG, "run failed", throwable)
                continuation.resume(
                  RunResult.Error(ModelError.Failed(throwable.message ?: "Inference error"))
                )
              }
            }
          },
          emptyMap(),
        )
        continuation.invokeOnCancellation { runCatching { conversation.cancelProcess() } }
      }
    }

  override fun cancel() {
    runCatching { conversation.cancelProcess() }
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    runCatching { conversation.close() }
    onClosed()
  }
}

/**
 * LiteRT-LM generates after every message it receives, so results and the user's next message
 * travel together in one message. Gemma's template renders a tool turn from
 * `Content.ToolResponse` entries keyed by tool name.
 */
private fun ModelInput.toMessage(): Message {
  val contents = mutableListOf<Content>()
  for (result in toolResults) {
    contents += Content.ToolResponse(result.name, result.content.toResponseObject())
  }
  user?.let { u ->
    u.audio?.let { contents += Content.AudioBytes(it.toWavBytes()) }
    // Text goes after audio.
    listOfNotNull(u.text, u.context).filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let {
      contents += Content.Text(it.joinToString("\n\n"))
    }
  }
  return if (toolResults.isNotEmpty()) Message.tool(Contents.of(contents))
  else Message.user(Contents.of(contents))
}

private val gson = com.google.gson.Gson()

private fun String.toResponseObject(): Any =
  try {
    gson.fromJson(this, Map::class.java) ?: mapOf("result" to this)
  } catch (e: Exception) {
    mapOf("result" to this)
  }

private fun systemProperty(name: String): String? =
  try {
    @Suppress("PrivateApi")
    val clazz = Class.forName("android.os.SystemProperties")
    clazz.getMethod("get", String::class.java).invoke(null, name) as? String
  } catch (e: Exception) {
    null
  }?.takeIf { it.isNotEmpty() }
