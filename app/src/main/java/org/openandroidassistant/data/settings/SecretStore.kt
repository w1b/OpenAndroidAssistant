/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.data.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

private const val TAG = "SecretStore"
private const val KEY_ALIAS = "oaa_secrets_v1"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"

/** API keys and logins the app stores. */
enum class Secret(val title: String) {
  OPENROUTER_KEY("OpenRouter API key"),
  OPENAI_KEY("OpenAI API key"),
  FIRECRAWL_KEY("Firecrawl API key"),
  GOOGLE_PLACES_KEY("Google Places API key"),
  TAVILY_KEY("Tavily API key"),
  JINA_KEY("Jina API key"),
  SEARXNG_USERNAME("SearXNG username"),
  SEARXNG_PASSWORD("SearXNG password"),
  SEARXNG_HEADERS("SearXNG headers"),
}

/**
 * API keys, encrypted at rest with an AES-GCM key held in the Android Keystore. The file is
 * excluded from backups along with the rest of the app's data.
 */
@Singleton
class SecretStore @Inject constructor(@ApplicationContext context: Context) {
  private val file = File(context.filesDir, "secrets.bin")
  private val values = MutableStateFlow(load())

  /** The stored keys, for settings UI. */
  val present: StateFlow<Map<Secret, String>> = values

  fun get(secret: Secret): String? = values.value[secret]?.takeIf { it.isNotBlank() }

  @Synchronized
  fun set(secret: Secret, value: String?) {
    val next = values.value.toMutableMap()
    if (value.isNullOrBlank()) next.remove(secret) else next[secret] = value.trim()
    save(next)
    values.value = next
  }

  private fun load(): Map<Secret, String> {
    if (!file.isFile) return emptyMap()
    return try {
      val bytes = file.readBytes()
      val iv = bytes.copyOfRange(0, 12)
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
      val obj = JSONObject(String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8))
      Secret.entries.mapNotNull { s -> obj.optString(s.name).takeIf { it.isNotEmpty() }?.let { s to it } }.toMap()
    } catch (e: Exception) {
      Log.w(TAG, "Couldn't read stored keys", e)
      emptyMap()
    }
  }

  private fun save(map: Map<Secret, String>) {
    val obj = JSONObject()
    map.forEach { (k, v) -> obj.put(k.name, v) }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key())
    val sealed = cipher.iv + cipher.doFinal(obj.toString().toByteArray(Charsets.UTF_8))
    val tmp = File(file.parentFile, "${file.name}.tmp")
    tmp.writeBytes(sealed)
    tmp.renameTo(file)
  }

  private fun key(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    generator.init(
      KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build()
    )
    return generator.generateKey()
  }
}

/** "sk-or-…a9f2": enough to recognize a key without showing it. */
fun maskSecret(value: String): String =
  if (value.length <= 10) "•".repeat(value.length)
  else value.take(6) + "…" + value.takeLast(4)
