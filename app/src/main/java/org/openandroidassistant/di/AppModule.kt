/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import androidx.room.Room
import dagger.Module
import org.openandroidassistant.data.OaaDatabase
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.openandroidassistant.data.settings.SettingsSerializer
import org.openandroidassistant.proto.AssistantSettings

@Module
@InstallIn(SingletonComponent::class)
internal object AppModule {

  @Provides
  @Singleton
  fun provideSettingsDataStore(
    @ApplicationContext context: Context
  ): DataStore<AssistantSettings> =
    DataStoreFactory.create(
      serializer = SettingsSerializer,
      produceFile = { context.dataStoreFile("settings.pb") },
    )

  @Provides
  @Singleton
  fun provideDatabase(@ApplicationContext context: Context): OaaDatabase =
    // Pre-release: a schema change bumps the version and starts the database over.
    Room.databaseBuilder(context, OaaDatabase::class.java, "oaa.db").fallbackToDestructiveMigration(dropAllTables = true).build()
}
