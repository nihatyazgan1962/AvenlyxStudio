package com.formatci.mediastudio.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.formatci.mediastudio.domain.model.AppSettings
import com.formatci.mediastudio.domain.model.QualityProfile
import com.formatci.mediastudio.domain.model.ThemePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.studioDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "formatci_user_preferences"
)

class UserPreferencesRepository(
    private val dataStore: DataStore<Preferences>
) {
    constructor(context: Context) : this(context.studioDataStore)

    val settingsFlow: Flow<AppSettings> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            AppSettings(
                themePreference = enumOrDefault(
                    prefs[KEY_THEME_PREFERENCE],
                    ThemePreference.SYSTEM
                ),
                simpleMode = prefs[KEY_SIMPLE_MODE] ?: true,
                defaultQuality = enumOrDefault(
                    prefs[KEY_DEFAULT_QUALITY],
                    QualityProfile.BALANCED
                ),
                defaultVideoFormat = prefs[KEY_DEFAULT_VIDEO_FORMAT] ?: "mp4",
                defaultAudioFormat = prefs[KEY_DEFAULT_AUDIO_FORMAT] ?: "m4a",
                defaultImageFormat = prefs[KEY_DEFAULT_IMAGE_FORMAT] ?: "jpg",
                notificationsEnabled = prefs[KEY_NOTIFICATIONS_ENABLED] ?: true,
                autoCleanCache = prefs[KEY_AUTO_CLEAN_CACHE] ?: false
            )
        }

    suspend fun setThemePreference(theme: ThemePreference) {
        dataStore.edit { it[KEY_THEME_PREFERENCE] = theme.name }
    }

    suspend fun setSimpleMode(enabled: Boolean) {
        dataStore.edit { it[KEY_SIMPLE_MODE] = enabled }
    }

    suspend fun setDefaultQuality(quality: QualityProfile) {
        dataStore.edit { it[KEY_DEFAULT_QUALITY] = quality.name }
    }

    suspend fun setDefaultVideoFormat(format: String) {
        dataStore.edit { it[KEY_DEFAULT_VIDEO_FORMAT] = format }
    }

    suspend fun setDefaultAudioFormat(format: String) {
        dataStore.edit { it[KEY_DEFAULT_AUDIO_FORMAT] = format }
    }

    suspend fun setDefaultImageFormat(format: String) {
        dataStore.edit { it[KEY_DEFAULT_IMAGE_FORMAT] = format }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_NOTIFICATIONS_ENABLED] = enabled }
    }

    suspend fun setAutoCleanCache(enabled: Boolean) {
        dataStore.edit { it[KEY_AUTO_CLEAN_CACHE] = enabled }
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val current = AppSettings(
                themePreference = enumOrDefault(prefs[KEY_THEME_PREFERENCE], ThemePreference.SYSTEM),
                simpleMode = prefs[KEY_SIMPLE_MODE] ?: true,
                defaultQuality = enumOrDefault(prefs[KEY_DEFAULT_QUALITY], QualityProfile.BALANCED),
                defaultVideoFormat = prefs[KEY_DEFAULT_VIDEO_FORMAT] ?: "mp4",
                defaultAudioFormat = prefs[KEY_DEFAULT_AUDIO_FORMAT] ?: "m4a",
                defaultImageFormat = prefs[KEY_DEFAULT_IMAGE_FORMAT] ?: "jpg",
                notificationsEnabled = prefs[KEY_NOTIFICATIONS_ENABLED] ?: true,
                autoCleanCache = prefs[KEY_AUTO_CLEAN_CACHE] ?: false
            )
            val updated = transform(current)
            prefs[KEY_THEME_PREFERENCE] = updated.themePreference.name
            prefs[KEY_SIMPLE_MODE] = updated.simpleMode
            prefs[KEY_DEFAULT_QUALITY] = updated.defaultQuality.name
            prefs[KEY_DEFAULT_VIDEO_FORMAT] = updated.defaultVideoFormat
            prefs[KEY_DEFAULT_AUDIO_FORMAT] = updated.defaultAudioFormat
            prefs[KEY_DEFAULT_IMAGE_FORMAT] = updated.defaultImageFormat
            prefs[KEY_NOTIFICATIONS_ENABLED] = updated.notificationsEnabled
            prefs[KEY_AUTO_CLEAN_CACHE] = updated.autoCleanCache
        }
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, default: T): T {
        if (raw.isNullOrBlank()) return default
        return runCatching { enumValueOf<T>(raw) }.getOrDefault(default)
    }

    companion object {
        private val KEY_THEME_PREFERENCE = stringPreferencesKey("theme_preference")
        private val KEY_SIMPLE_MODE = booleanPreferencesKey("simple_mode")
        private val KEY_DEFAULT_QUALITY = stringPreferencesKey("default_quality")
        private val KEY_DEFAULT_VIDEO_FORMAT = stringPreferencesKey("default_video_format")
        private val KEY_DEFAULT_AUDIO_FORMAT = stringPreferencesKey("default_audio_format")
        private val KEY_DEFAULT_IMAGE_FORMAT = stringPreferencesKey("default_image_format")
        private val KEY_NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        private val KEY_AUTO_CLEAN_CACHE = booleanPreferencesKey("auto_clean_cache")
    }
}

