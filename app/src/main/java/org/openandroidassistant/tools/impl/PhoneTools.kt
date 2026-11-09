/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.tools.impl

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.MediaStore
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.KeyEvent
import com.google.gson.JsonObject
import javax.inject.Inject
import kotlin.math.roundToInt
import org.openandroidassistant.orchestrator.RenderCard
import org.openandroidassistant.permissions.AppCapability
import org.openandroidassistant.tools.BaseTool
import org.openandroidassistant.tools.HandleType
import org.openandroidassistant.tools.Handoff
import org.openandroidassistant.tools.LockedRisk
import org.openandroidassistant.tools.ParamKind
import org.openandroidassistant.tools.ResultItem
import org.openandroidassistant.tools.ToolCategory
import org.openandroidassistant.tools.ToolContext
import org.openandroidassistant.tools.ToolMetadata
import org.openandroidassistant.tools.ToolOutcome
import org.openandroidassistant.tools.ToolParam
import org.openandroidassistant.tools.ToolSchema
import org.openandroidassistant.tools.ValidatedCall

/**
 * Notification access, which also unlocks media sessions. The system binds it when the user
 * grants access in settings.
 */
class OaaNotificationListener : NotificationListenerService() {
  override fun onListenerConnected() {
    instance = this
  }

  override fun onListenerDisconnected() {
    if (instance === this) instance = null
  }

  companion object {
    @Volatile var instance: OaaNotificationListener? = null
      private set

    fun component(context: Context) = ComponentName(context, OaaNotificationListener::class.java)
  }
}

private fun ok(vararg pairs: Pair<String, Any?>): ToolOutcome.Success =
  ToolOutcome.Success(
    items = emptyList(),
    extras =
      JsonObject().apply {
        for ((k, v) in pairs) {
          when (v) {
            is Number -> addProperty(k, v)
            is Boolean -> addProperty(k, v)
            null -> {}
            else -> addProperty(k, v.toString())
          }
        }
      },
  )

// ------------------------------------------------------------------ media

class MediaControlTool @Inject constructor() : BaseTool() {
  override val schema =
    ToolSchema(
      name = "media_control",
      description = "Control whatever is playing: play, pause, next track, previous track.",
      params = listOf(ToolParam("action", ParamKind.ENUM, "What to do.", enumValues = listOf("play", "pause", "next", "previous"))),
    )

  override val metadata =
    ToolMetadata(
      id = "media_control",
      title = "Media controls",
      summary = "Play, pause, and skip.",
      category = ToolCategory.DEVICE,
      cancellable = false,
      allowedWhenLockedDefault = true,
    )

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome {
    val code =
      when (call.stringArg("action")) {
        "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
        "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
        "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
        "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        else -> return ToolOutcome.Steering("error", "Unknown action.")
      }
    val audio = context.appContext.getSystemService(AudioManager::class.java)
    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    return ok("done" to call.stringArg("action"))
  }
}

class PlayMusicTool @Inject constructor() : BaseTool() {
  override val schema =
    ToolSchema(
      name = "play_music",
      description = "Play a song, artist, album or playlist in the user's music app. The music app opens, which ends the conversation.",
      params =
        listOf(
          ToolParam("query", ParamKind.STRING, "What to play, like \"Bohemian Rhapsody by Queen\" or \"jazz\"."),
          ToolParam("app", ParamKind.HANDLE, "A music app's id from find_apps, if the user named one. Leave it out otherwise.", required = false, handleType = HandleType.APP),
        ),
    )

  companion object {
    /** The intent play_music sends; the apps that handle it are the ones it can play in. */
    fun playIntent(): Intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
  }

  override val metadata =
    ToolMetadata(
      id = "play_music",
      title = "Play music",
      summary = "Play songs and artists in your music app.",
      category = ToolCategory.DEVICE,
      cancellable = false,
      endsConversation = true,
      unlockVerb = "play that",
    )

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome {
    val query = call.stringArg("query") ?: return ToolOutcome.Steering("error", "What should I play?")
    val intent =
      playIntent().apply {
        putExtra(android.app.SearchManager.QUERY, query)
        putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    val pm = context.appContext.packageManager
    call.handleArg("app")?.let { entry ->
      val app = entry.payload as? AppEntry ?: AppEntry(entry.nativeId, entry.nativeId)
      if (appsHandling(context.appContext, intent).none { it.packageName == app.packageName }) {
        return ToolOutcome.Steering("error", "${app.label} can't play music by name. Tell the user, or play it without naming an app.")
      }
      intent.setPackage(app.packageName)
    }
    if (intent.resolveActivity(pm) == null) return ToolOutcome.Steering("error", "No music app here can play by name. Tell the user.")
    return ToolOutcome.Success(items = emptyList(), handoff = Handoff("Playing $query.", intent))
  }
}

class NowPlayingTool @Inject constructor() : BaseTool() {
  override val schema = ToolSchema(name = "now_playing", description = "What song or media is playing right now, and in which app.", params = emptyList())

  override val metadata =
    ToolMetadata(
      id = "now_playing",
      title = "What's playing",
      summary = "Tell you what song is playing.",
      category = ToolCategory.DEVICE,
      allowedWhenLockedDefault = true,
      requiredCapabilities = listOf(AppCapability.NOTIFICATION_ACCESS),
    )

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome {
    val manager = context.appContext.getSystemService(MediaSessionManager::class.java)
    val sessions =
      runCatching { manager.getActiveSessions(OaaNotificationListener.component(context.appContext)) }.getOrNull()
        ?: return ToolOutcome.Steering("error", "Notification access is off, so I can't see what's playing. Tell the user.")
    val session =
      sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull()
        ?: return ToolOutcome.Steering("empty", "Nothing is playing.")
    val meta = session.metadata
    val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return ToolOutcome.Steering("empty", "Nothing is playing.")
    val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
    val app = runCatching {
      context.appContext.packageManager.getApplicationLabel(context.appContext.packageManager.getApplicationInfo(session.packageName, 0)).toString()
    }.getOrNull()
    val playing = session.playbackState?.state == PlaybackState.STATE_PLAYING
    return ToolOutcome.Success(
      items =
        listOf(
          ResultItem(
            type = HandleType.MEDIA,
            nativeId = "${session.packageName}:$title",
            projection = mapOf("title" to title, "artist" to artist, "album" to meta.getString(MediaMetadata.METADATA_KEY_ALBUM), "app" to app, "playing" to playing),
            renderPayload = RenderCard.NowPlaying(title, artist, app, playing),
          )
        )
    )
  }
}

// ------------------------------------------------------------------ notifications

class ReadNotificationsTool @Inject constructor() : BaseTool() {
  override val schema =
    ToolSchema(
      name = "read_notifications",
      description = "Read the notifications currently on the phone, newest first, optionally from one app.",
      params = listOf(ToolParam("app", ParamKind.STRING, "Only this app's notifications.", required = false)),
    )

  override val metadata =
    ToolMetadata(
      id = "read_notifications",
      lockedRisk = LockedRisk.READS_PRIVATE,
      title = "Read notifications",
      summary = "Read the notifications on your phone.",
      category = ToolCategory.DEVICE,
      requiredCapabilities = listOf(AppCapability.NOTIFICATION_ACCESS),
    )

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome {
    val listener = OaaNotificationListener.instance
      ?: return ToolOutcome.Steering("error", "Notification access is off. Tell the user to turn it on in settings.")
    val pm = context.appContext.packageManager
    val wanted = call.stringArg("app")?.lowercase()
    val items =
      listener.activeNotifications
        .filter { it.packageName != context.appContext.packageName && !it.isOngoing }
        .filter { (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 }
        .sortedByDescending { it.postTime }
        .mapNotNull { sbn ->
          val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
          if (wanted != null && !app.lowercase().contains(wanted)) return@mapNotNull null
          val extras = sbn.notification.extras
          val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
          val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
          if (title.isBlank() && text.isBlank()) return@mapNotNull null
          val time = formatWhen(sbn.postTime)
          ResultItem(
            type = HandleType.NOTIFICATION,
            nativeId = sbn.key,
            projection = mapOf("app" to app, "title" to title, "text" to text.take(400), "time" to time),
            renderPayload = RenderCard.Notification(app, title, text, time),
          )
        }
        .take(15)
    return if (items.isEmpty()) ToolOutcome.Steering("empty", "There are no notifications.") else ToolOutcome.Success(items = items)
  }
}

// ------------------------------------------------------------------ device controls

class FlashlightTool @Inject constructor() : BaseTool() {
  override val schema =
    ToolSchema(name = "set_flashlight", description = "Turn the flashlight on or off.", params = listOf(ToolParam("on", ParamKind.BOOLEAN, "true for on.")))

  override val metadata =
    ToolMetadata(id = "set_flashlight", title = "Flashlight", summary = "Turn the flashlight on and off.", category = ToolCategory.DEVICE, cancellable = false, allowedWhenLockedDefault = true)

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome {
    val on = call.boolArg("on") ?: true
    val cameras = context.appContext.getSystemService(CameraManager::class.java)
    val id =
      cameras.cameraIdList.firstOrNull { cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        ?: return ToolOutcome.Steering("error", "This phone has no flashlight.")
    cameras.setTorchMode(id, on)
    return ok("flashlight" to if (on) "on" else "off")
  }
}

class VolumeTool @Inject constructor() : BaseTool() {
  override val schema =
    ToolSchema(
      name = "set_volume",
      description = "Set or change a volume. Pass level (0–100) or change.",
      params =
        listOf(
          ToolParam("stream", ParamKind.ENUM, "Which volume. Defaults to media.", required = false, enumValues = listOf("media", "ring", "alarm", "notification", "call")),
          ToolParam("level", ParamKind.INTEGER, "Percent, 0 to 100.", required = false, minimum = 0.0, maximum = 100.0),
          ToolParam("change", ParamKind.ENUM, "Relative change.", required = false, enumValues = listOf("up", "down", "mute", "unmute")),
        ),
    )

  override val metadata =
    ToolMetadata(id = "set_volume", title = "Volume", summary = "Change media, ring, and alarm volume.", category = ToolCategory.DEVICE, cancellable = false, allowedWhenLockedDefault = true)

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome {
    val audio = context.appContext.getSystemService(AudioManager::class.java)
    val stream =
      when (call.stringArg("stream")) {
        "ring" -> AudioManager.STREAM_RING
        "alarm" -> AudioManager.STREAM_ALARM
        "notification" -> AudioManager.STREAM_NOTIFICATION
        "call" -> AudioManager.STREAM_VOICE_CALL
        else -> AudioManager.STREAM_MUSIC
      }
    val max = audio.getStreamMaxVolume(stream)
    val min = audio.getStreamMinVolume(stream)
    try {
      call.longArg("level")?.let { pct -> audio.setStreamVolume(stream, (min + (max - min) * pct / 100.0).roundToInt(), 0) }
      when (call.stringArg("change")) {
        "up" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, 0)
        "down" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, 0)
        "mute" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
        "unmute" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
      }
    } catch (e: SecurityException) {
      return ToolOutcome.Steering("error", "Android won't let the assistant change that volume while Do Not Disturb is on. Tell the user.")
    }
    val now = audio.getStreamVolume(stream)
    return ok("volume_percent" to ((now - min) * 100 / maxOf(1, max - min)))
  }
}

class DoNotDisturbTool @Inject constructor() : BaseTool() {
  override val schema =
    ToolSchema(name = "set_do_not_disturb", description = "Turn Do Not Disturb on or off.", params = listOf(ToolParam("on", ParamKind.BOOLEAN, "true for on.")))

  override val metadata =
    ToolMetadata(
      id = "set_do_not_disturb",
      title = "Do Not Disturb",
      summary = "Turn Do Not Disturb on and off.",
      category = ToolCategory.DEVICE,
      cancellable = false,
      requiredCapabilities = listOf(AppCapability.DO_NOT_DISTURB),
    )

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome {
    val nm = context.appContext.getSystemService(NotificationManager::class.java)
    if (!nm.isNotificationPolicyAccessGranted) return ToolOutcome.Steering("error", "Do Not Disturb access is off. Tell the user to allow it in settings.")
    val on = call.boolArg("on") ?: true
    nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
    return ok("do_not_disturb" to if (on) "on" else "off")
  }
}

class BrightnessTool @Inject constructor() : BaseTool() {
  override val schema =
    ToolSchema(
      name = "set_brightness",
      description = "Set screen brightness, or turn adaptive brightness on.",
      params =
        listOf(
          ToolParam("level", ParamKind.INTEGER, "Percent, 0 to 100.", required = false, minimum = 0.0, maximum = 100.0),
          ToolParam("change", ParamKind.ENUM, "Relative change, or auto for adaptive brightness.", required = false, enumValues = listOf("up", "down", "auto")),
        ),
    )

  override val metadata =
    ToolMetadata(
      id = "set_brightness",
      title = "Brightness",
      summary = "Change screen brightness.",
      category = ToolCategory.DEVICE,
      cancellable = false,
      requiredCapabilities = listOf(AppCapability.MODIFY_SETTINGS),
    )

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome {
    val resolver = context.appContext.contentResolver
    if (!Settings.System.canWrite(context.appContext)) return ToolOutcome.Steering("error", "Permission to change system settings is off. Tell the user to allow it in settings.")
    val current = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
    val target =
      when (call.stringArg("change")) {
        "auto" -> {
          Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
          return ok("brightness" to "adaptive")
        }
        "up" -> (current + 51).coerceAtMost(255)
        "down" -> (current - 51).coerceAtLeast(1)
        else -> call.longArg("level")?.let { (it * 255 / 100).toInt().coerceIn(1, 255) } ?: return ToolOutcome.Steering("error", "Give a level or a change.")
      }
    Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
    Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, target)
    return ok("brightness_percent" to target * 100 / 255)
  }
}
