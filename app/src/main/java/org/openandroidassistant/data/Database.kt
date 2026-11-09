/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// ------------------------------------------------------------------ history

@Entity(tableName = "conversations")
data class ConversationEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val startedAtMs: Long,
  val updatedAtMs: Long,
  /** The first user request, for the history list. */
  val title: String = "",
)

/** One user turn and the assistant's response. */
@Entity(tableName = "exchanges")
data class ExchangeEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val conversationId: Long,
  val createdAtMs: Long,
  /** "typed", "transcript" (text-only and realtime), or "voice" (native audio in). */
  val userKind: String,
  /** What the user said or typed; for voice, a local transcript when one could be made. */
  val userText: String,
  /** The assistant's spoken text, in order. */
  val assistantText: String,
  /** What was shown, one line per item ("Contact: Harold Finch"). */
  val shown: String = "",
  /** The user's recorded voice, a WAV file name in the history audio folder; null when typed. */
  val audioFile: String? = null,
  /** The tool calls in order, a JSON array of {name, args, result}; empty when none. */
  val toolSteps: String = "",
)

@Dao
interface HistoryDao {
  @Insert suspend fun insertConversation(entity: ConversationEntity): Long

  @Query("UPDATE conversations SET updatedAtMs = :at, title = CASE WHEN title = '' THEN :title ELSE title END WHERE id = :id")
  suspend fun touchConversation(id: Long, at: Long, title: String)

  @Insert suspend fun insertExchange(entity: ExchangeEntity): Long

  /** Conversations with at least one saved exchange (the title is set by the first). */
  @Query("SELECT * FROM conversations WHERE title != '' ORDER BY updatedAtMs DESC")
  fun conversations(): Flow<List<ConversationEntity>>

  @Query("SELECT * FROM exchanges WHERE conversationId = :conversationId ORDER BY createdAtMs")
  fun exchanges(conversationId: Long): Flow<List<ExchangeEntity>>

  @Query("DELETE FROM conversations WHERE id = :id") suspend fun deleteConversation(id: Long)

  @Query("DELETE FROM exchanges WHERE conversationId = :id") suspend fun deleteExchanges(id: Long)

  @Query("SELECT audioFile FROM exchanges WHERE conversationId = :id AND audioFile IS NOT NULL")
  suspend fun audioFiles(id: Long): List<String>

  @Query("DELETE FROM conversations") suspend fun clearConversations()

  @Query("DELETE FROM exchanges") suspend fun clearExchanges()
}

// ------------------------------------------------------------------ clock: timers, alarms, reminders

@Entity(tableName = "timers")
data class TimerEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val label: String,
  val totalMs: Long,
  /** Wall-clock end time while running. */
  val endsAtMs: Long,
  /** Remaining time while paused; null while running. */
  val pausedRemainingMs: Long? = null,
  /** The timer went off and is ringing until dismissed. */
  val ringing: Boolean = false,
)

@Entity(tableName = "alarms")
data class AlarmEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val hour: Int,
  val minute: Int,
  val label: String,
  val enabled: Boolean,
  /** Bit i set = repeats on day i (0 = Monday). Zero = one-time. */
  val daysMask: Int = 0,
  /** The next time it rings, while enabled. */
  val nextAtMs: Long = 0,
)

@Entity(tableName = "reminders")
data class ReminderEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val text: String,
  /** When to remind; null for a reminder with no time. */
  val dueAtMs: Long?,
  val createdAtMs: Long,
  val done: Boolean = false,
)

@Dao
interface ClockDao {
  @Insert suspend fun insertTimer(t: TimerEntity): Long

  @Update suspend fun updateTimer(t: TimerEntity)

  @Delete suspend fun deleteTimer(t: TimerEntity)

  @Query("SELECT * FROM timers WHERE id = :id") suspend fun timer(id: Long): TimerEntity?

  @Query("SELECT * FROM timers ORDER BY endsAtMs") suspend fun timers(): List<TimerEntity>

  @Query("SELECT * FROM timers ORDER BY endsAtMs") fun timersFlow(): Flow<List<TimerEntity>>

  @Insert suspend fun insertAlarm(a: AlarmEntity): Long

  @Update suspend fun updateAlarm(a: AlarmEntity)

  @Delete suspend fun deleteAlarm(a: AlarmEntity)

  @Query("SELECT * FROM alarms WHERE id = :id") suspend fun alarm(id: Long): AlarmEntity?

  @Query("SELECT * FROM alarms ORDER BY hour, minute") suspend fun alarms(): List<AlarmEntity>

  @Insert suspend fun insertReminder(r: ReminderEntity): Long

  @Update suspend fun updateReminder(r: ReminderEntity)

  @Delete suspend fun deleteReminder(r: ReminderEntity)

  @Query("SELECT * FROM reminders WHERE id = :id") suspend fun reminder(id: Long): ReminderEntity?

  @Query("SELECT * FROM reminders WHERE done = 0 ORDER BY dueAtMs IS NULL, dueAtMs")
  suspend fun openReminders(): List<ReminderEntity>
}

// ------------------------------------------------------------------ notes

@Entity(tableName = "notes")
data class NoteEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val title: String,
  val body: String,
  val createdAtMs: Long,
  val updatedAtMs: Long,
)

@Dao
interface NotesDao {
  @Insert suspend fun insert(n: NoteEntity): Long

  @Update suspend fun update(n: NoteEntity)

  @Query("SELECT * FROM notes WHERE id = :id") suspend fun note(id: Long): NoteEntity?

  @Query(
    "SELECT * FROM notes WHERE :query = '' OR title LIKE '%' || :query || '%' OR body LIKE '%' || :query || '%' " +
      "ORDER BY updatedAtMs DESC LIMIT :limit"
  )
  suspend fun search(query: String, limit: Int = 10): List<NoteEntity>
}

@Database(
  entities =
    [
      ConversationEntity::class,
      ExchangeEntity::class,
      TimerEntity::class,
      AlarmEntity::class,
      ReminderEntity::class,
      NoteEntity::class,
    ],
  version = 4,
  exportSchema = false,
)
abstract class OaaDatabase : RoomDatabase() {
  abstract fun historyDao(): HistoryDao

  abstract fun clockDao(): ClockDao

  abstract fun notesDao(): NotesDao
}
