/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.tools.impl

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openandroidassistant.tools.BaseTool
import org.openandroidassistant.tools.Handoff
import org.openandroidassistant.tools.HandleType
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
 * Finds installed apps by name. Apps are named by result id everywhere else (open_app,
 * play_music), so the model never guesses a name and nothing opens from a near miss. Listing every
 * app in the schema instead cost about 1000 tokens a request.
 */
class FindAppsTool @Inject constructor() : BaseTool() {
  override val schema =
    ToolSchema(
      name = "find_apps",
      description = "Find installed apps by name. Returns ids for open_app and play_music.",
      params = listOf(ToolParam("query", ParamKind.STRING, "The app's name, or part of it, like \"maps\" or \"Spotify\".")),
    )

  override val metadata =
    ToolMetadata(
      id = "find_apps",
      title = "Find apps",
      summary = "Look up the apps on your phone.",
      category = ToolCategory.DEVICE,
      allowedWhenLockedDefault = true,
    )

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome =
    withContext(Dispatchers.IO) {
      val query = call.stringArg("query") ?: return@withContext ToolOutcome.Steering("error", "Which app?")
      val players = appsHandling(context.appContext, PlayMusicTool.playIntent()).map { it.packageName }.toSet()
      val found = rankApps(installedApps(context.appContext), query)
      if (found.isEmpty()) return@withContext ToolOutcome.Steering("empty", "No installed app is called anything like \"$query\". Tell the user.")
      ToolOutcome.Success(
        items =
          found.map { app ->
            ResultItem(
              type = HandleType.APP,
              nativeId = app.packageName,
              projection = mapOf("name" to app.label, "package" to app.packageName, "plays_music" to (app.packageName in players).takeIf { it }),
              renderPayload = app,
            )
          }
      )
    }
}

/**
 * Opens an app found with find_apps, or its app info or notification settings. The app opens after
 * the assistant finishes speaking, and the conversation ends.
 */
class AppLaunchTool @Inject constructor() : BaseTool() {
  override val schema =
    ToolSchema(
      name = "open_app",
      description = "Open an app found with find_apps, or its app info or notification settings page.",
      params =
        listOf(
          ToolParam("app", ParamKind.HANDLE, "The app's id from find_apps.", handleType = HandleType.APP),
          ToolParam("page", ParamKind.ENUM, "What to open. Default app.", required = false, enumValues = listOf("app", "app_info", "notifications")),
        ),
    )

  override val metadata =
    ToolMetadata(
      id = "open_app",
      title = "Open apps",
      summary = "Open an app, or its settings.",
      category = ToolCategory.DEVICE,
      cancellable = false,
      unlockVerb = "open that app",
      endsConversation = true,
    )

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome {
    val entry = call.handleArg("app") ?: return ToolOutcome.Steering("error", "Find the app with find_apps first.")
    val app = entry.payload as? AppEntry ?: AppEntry(entry.nativeId, entry.nativeId)
    val ctx = context.appContext
    val (intent, phrase) =
      when (call.stringArg("page")) {
        "app_info" ->
          Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")) to "Opening ${app.label}'s app info."
        "notifications" ->
          Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName) to "Opening ${app.label}'s notification settings."
        else ->
          (ctx.packageManager.getLaunchIntentForPackage(app.packageName) ?: return ToolOutcome.Steering("error", "${app.label} can't be opened. Tell the user.")) to
            "Opening ${app.label}."
      }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (intent.resolveActivity(ctx.packageManager) == null) return ToolOutcome.Steering("error", "That page can't be opened on this phone. Tell the user.")
    return ToolOutcome.Success(
      items = emptyList(),
      extras = com.google.gson.JsonObject().apply { addProperty("opening", app.label) },
      handoff = Handoff(phrase = phrase, intent = intent),
    )
  }
}

data class AppEntry(val label: String, val packageName: String)

/** The apps on the launcher, one per package, sorted by name. */
fun installedApps(context: Context): List<AppEntry> =
  appsHandling(context, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))

/** The apps that handle [intent], one per package, sorted by name. */
fun appsHandling(context: Context, intent: Intent): List<AppEntry> {
  val pm = context.packageManager
  return pm.queryIntentActivities(intent, 0)
    .mapNotNull { info ->
      val label = info.loadLabel(pm).toString().trim().orEmpty()
      val pkg = info.activityInfo?.packageName
      if (label.isEmpty() || pkg.isNullOrEmpty()) null else AppEntry(label, pkg)
    }
    .distinctBy { it.packageName }
    .sortedBy { it.label.lowercase() }
}

/**
 * The apps whose names best match [query], best first: the same name (case, spaces and punctuation
 * aside), then shared words ("maps" finds "Google Maps"), close spellings ("Mollie" finds
 * "Molly") and words of the package ("browser" finds Vanadium, app.vanadium.browser). Nothing opens
 * from this; the model picks an id, or asks the user.
 */
fun rankApps(apps: List<AppEntry>, query: String, limit: Int = 8): List<AppEntry> {
  fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
  fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()
  val q = norm(query)
  if (q.isEmpty()) return emptyList()
  val qWords = words(query)
  fun score(app: AppEntry): Double {
    val label = norm(app.label)
    if (label == q) return 2.0
    val spelling = 1.0 - editDistance(q, label).toDouble() / maxOf(q.length, label.length)
    val labelWords = words(app.label)
    val shared = if (qWords.isEmpty() || labelWords.isEmpty()) 0.0 else (qWords intersect labelWords).size.toDouble() / qWords.size
    val contained = if (q.length >= 3 && q in label) 0.8 else 0.0
    // A weaker hint: the package's own words ("browser" in app.vanadium.browser).
    val packageWords = app.packageName.lowercase().split('.').toSet() - GENERIC_PACKAGE_WORDS
    val inPackage = if (qWords.any { it.length >= 4 && it in packageWords }) 0.7 else 0.0
    return maxOf(spelling, shared, contained, inPackage)
  }
  return apps.distinctBy { it.packageName }.map { it to score(it) }.filter { it.second >= 0.6 }.sortedByDescending { it.second }.take(limit).map { it.first }
}

private val GENERIC_PACKAGE_WORDS = setOf("com", "org", "net", "io", "app", "apps", "android", "google", "mobile")

private fun editDistance(a: String, b: String): Int {
  var prev = IntArray(b.length + 1) { it }
  for (i in 1..a.length) {
    val cur = IntArray(b.length + 1)
    cur[0] = i
    for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
    prev = cur
  }
  return prev[b.length]
}
