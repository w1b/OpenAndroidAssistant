/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.ui

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.openandroidassistant.ui.history.ConversationDetail
import org.openandroidassistant.ui.history.HistoryList
import org.openandroidassistant.ui.history.HistoryViewModel
import org.openandroidassistant.ui.home.HomeScreen
import org.openandroidassistant.ui.settings.AboutAppSettings
import org.openandroidassistant.ui.settings.AboutYouSettings
import org.openandroidassistant.ui.settings.BehaviorSettings
import org.openandroidassistant.ui.settings.ConversationSettings
import org.openandroidassistant.ui.settings.ModelSettings
import org.openandroidassistant.ui.settings.OpenRouterModelPicker
import org.openandroidassistant.ui.settings.SettingsHome
import org.openandroidassistant.ui.settings.SettingsViewModel
import org.openandroidassistant.ui.settings.SpeechSettings
import org.openandroidassistant.ui.settings.ToolsSettings
import org.openandroidassistant.ui.settings.WakeWordSettings
import org.openandroidassistant.ui.settings.WebSettings

@Composable
fun OaaApp(startRoute: String = "home") {
  val nav = rememberNavController()
  val back: () -> Unit = { nav.popBackStack() }
  NavHost(navController = nav, startDestination = startRoute) {
    composable("home") {
      HomeScreen(
        onOpenSettings = { nav.navigate("settings") },
        onOpenModel = { nav.navigate("settings/model") },
        onOpenHistory = { nav.navigate("history") },
      )
    }
    composable("settings") { SettingsHome(hiltViewModel<SettingsViewModel>(), back) { nav.navigate("settings/$it") } }
    composable("settings/model") { ModelSettings(hiltViewModel(), back) { nav.navigate("settings/model/openrouter") } }
    composable("settings/model/openrouter") { OpenRouterModelPicker(hiltViewModel(), back) }
    composable("settings/about") { AboutYouSettings(hiltViewModel(), back) }
    composable("settings/behavior") { BehaviorSettings(hiltViewModel(), back) }
    composable("settings/tools") { ToolsSettings(hiltViewModel(), back) { nav.navigate("settings/$it") } }
    composable("settings/web") { WebSettings(hiltViewModel(), back) }
    composable("settings/speech") { SpeechSettings(hiltViewModel(), back) }
    composable("settings/conversations") { ConversationSettings(hiltViewModel(), back) { nav.navigate("history") } }
    composable("settings/wakeword") { WakeWordSettings(hiltViewModel(), back) }
    composable("settings/about-app") { AboutAppSettings(back) }
    composable("history") { HistoryList(hiltViewModel<HistoryViewModel>(), back) { nav.navigate("history/$it") } }
    composable("history/{id}") { entry ->
      ConversationDetail(hiltViewModel(), entry.arguments?.getString("id")?.toLongOrNull() ?: 0, back)
    }
  }
}
