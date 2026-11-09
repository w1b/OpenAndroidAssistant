/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.audio

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deterministic speech normalization: numerals, currency, units, abbreviations →
 * speakable form; strip anything unspeakable. Rule sets are per-locale with plain pass-through
 * as the fallback — never wrong-language expansions.
 */
@Singleton
class SpeechNormalizer @Inject constructor() {

  fun normalize(text: String, locale: String): String {
    // Order matters: strip structure (markdown/URLs) first, then apply locale rules that turn
    // symbols like ° % $ into words, then strip any *remaining* unspeakables (emoji, stray
    // symbols). Stripping symbols before the rules would delete the very characters the rules
    // consume (e.g. the degree sign).
    val structural = stripStructural(text)
    val spoken =
      when (locale.lowercase().substringBefore('-')) {
        "en" -> EnglishRules.apply(structural)
        else -> structural
      }
    return stripRemainingUnspeakable(spoken)
  }

  /** Markdown remnants, code fences, URLs. */
  private fun stripStructural(text: String): String =
    text
      .replace(Regex("```[\\s\\S]*?```"), " ")
      .replace(Regex("[*_#`~\\[\\]<>|]"), " ")
      .replace(Regex("https?://\\S+"), " ")
      .replace(Regex("\\s+"), " ")
      .trim()

  /** Emoji and leftover symbols the locale rules didn't turn into words. */
  private fun stripRemainingUnspeakable(text: String): String =
    text
      .replace(Regex("[\\p{So}\\p{Cn}\\p{Co}\\p{Cs}]"), " ")
      .replace(Regex("\\s+"), " ")
      .trim()
}

/** English normalization rules. */
private object EnglishRules {
  /** "3:00 PM", "7:05am", "15:30", "4:30". */
  private const val TIME = "\\b([01]?\\d|2[0-3]):([0-5]\\d)(?:\\s*([ap])\\.?\\s?m\\b\\.?)?"

  private val ONES = listOf("", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
  private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty")

  private fun words(n: Int): String = if (n < 20) ONES[n] else TENS[n / 10] + if (n % 10 > 0) " " + ONES[n % 10] else ""

  /** "three P M", "seven oh five A M", "four thirty"; 24-hour times get A M or P M. */
  private fun spokenTime(hour: Int, minute: Int, meridiem: String): String {
    val m = meridiem.lowercase()
    val half = if (m.isNotEmpty()) "" else if (hour in 13..23) "p" else if (hour == 0) "a" else ""
    val suffix = (m.ifEmpty { half }).let { if (it.isEmpty()) "" else " ${it.uppercase()} M" }
    val h12 = when {
      hour == 0 -> 12
      hour > 12 -> hour - 12
      else -> hour
    }
    val mins =
      when {
        minute == 0 -> if (suffix.isEmpty()) " o'clock" else ""
        minute < 10 -> " oh ${words(minute)}"
        else -> " ${words(minute)}"
      }
    return words(h12) + mins + suffix
  }

  private val currency =
    mapOf("$" to "dollar" , "€" to "euro", "£" to "pound", "¥" to "yen")

  private val abbreviations =
    mapOf(
      "e.g." to "for example",
      "i.e." to "that is",
      "etc." to "et cetera",
      "vs." to "versus",
      "approx." to "approximately",
      "min" to "minutes",
      "hr" to "hours",
      "km" to "kilometers",
      "mi" to "miles",
      "kg" to "kilograms",
      "lb" to "pounds",
      "°C" to "degrees celsius",
      "°F" to "degrees fahrenheit",
      "%" to " percent",
    )

  fun apply(text: String): String {
    var out = text

    // Currency: "$5.20" → "5 dollars and 20 cents", "$5" → "5 dollars".
    out =
      Regex("([$€£¥])(\\d+)(?:\\.(\\d{2}))?").replace(out) { m ->
        val unit = currency[m.groupValues[1]] ?: "dollars"
        val whole = m.groupValues[2]
        val cents = m.groupValues[3]
        val wholeWord = if (whole == "1") unit else "${unit}s"
        if (cents.isEmpty() || cents == "00") "$whole $wholeWord"
        else "$whole $wholeWord and ${cents.trimStart('0')} cents"
      }

    // Degrees: "72°" → "72 degrees".
    out = out.replace(Regex("(\\d+)\\s*°(?![CF])"), "$1 degrees")
    out = out.replace("°C", " degrees celsius").replace("°F", " degrees fahrenheit")

    // Clock times as words: KittenTTS read "3:00 PM" as numbers ("thousand"). Ranges first.
    out = out.replace(Regex("($TIME)\\s*[-–]\\s*($TIME)", RegexOption.IGNORE_CASE)) { "${it.groupValues[1]} to ${it.groupValues[5]}" }
    out = out.replace(Regex(TIME, RegexOption.IGNORE_CASE)) { m -> spokenTime(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3]) }

    // Simple unit/abbreviation words (word-bounded).
    for ((abbr, spoken) in abbreviations) {
      if (abbr.endsWith('.') || abbr == "%") {
        out = out.replace(abbr, " $spoken ")
      } else {
        out = out.replace(Regex("(?<=\\d)\\s*\\b${Regex.escape(abbr)}\\b"), " $spoken")
      }
    }

    // Large round numbers with separators: 1,234,567 → 1234567 (TTS reads digits groups fine).
    out = out.replace(Regex("(\\d),(\\d{3})\\b"), "$1$2")

    // Ranges: "5-10" → "5 to 10".
    out = out.replace(Regex("(?<=\\d)\\s*[-–]\\s*(?=\\d)"), " to ")

    return out.replace(Regex("\\s+"), " ").trim()
  }
}
