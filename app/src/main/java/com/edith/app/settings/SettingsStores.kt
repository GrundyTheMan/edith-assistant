package com.edith.app.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile

/** The settings, in the app's private DataStore file `settings`. Create one per process: DataStore allows one instance per file. */
fun DataStoreSettingsRepository.Companion.create(context: Context): DataStoreSettingsRepository =
    DataStoreSettingsRepository(PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") })
