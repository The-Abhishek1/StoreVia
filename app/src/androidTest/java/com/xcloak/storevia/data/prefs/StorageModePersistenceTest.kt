package com.xcloak.storevia.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageModePersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences get() = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    @Before
    fun clearPreferences() {
        preferences.edit().clear().commit()
    }

    @After
    fun restorePreferences() {
        preferences.edit().clear().commit()
    }

    @Test
    fun selectedModeSurvivesRecreatingTheSettingsManager() {
        AppPrefs(context).update { copy(storageMode = StorageMode.CLOUD) }

        assertEquals(StorageMode.CLOUD, AppPrefs(context).settings.value.storageMode)
    }

    @Test
    fun oldOfflinePreferenceMigratesToPersistedLocalMode() {
        preferences.edit().putString("store_mode", "OFFLINE").commit()

        assertEquals(StorageMode.LOCAL, AppPrefs(context).settings.value.storageMode)
        assertEquals("LOCAL", preferences.getString("storage_mode", null))
    }
}
