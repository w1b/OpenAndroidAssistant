/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    // LibreWakeWord's client SDK isn't hosted yet: publish it from a LibreWakeWord checkout with
    // ./gradlew :client:publishReleasePublicationToMavenLocal
    mavenLocal()
    google()
    mavenCentral()
    // sherpa-onnx publishes its Android AAR on GitHub Releases rather than Maven Central.
    // Keep it as a normal versioned Gradle dependency so clean builds fetch the pinned release.
    ivy {
      name = "sherpaOnnxGitHubReleases"
      url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download")
      patternLayout { artifact("v[revision]/[artifact]-[revision].[ext]") }
      metadataSources { artifact() }
      content { includeModule("com.k2fsa", "sherpa-onnx") }
    }
  }
}

rootProject.name = "OpenAndroidAssistant"

include(":app")
