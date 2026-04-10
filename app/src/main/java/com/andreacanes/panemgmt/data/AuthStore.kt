package com.andreacanes.panemgmt.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AuthConfig(
    val baseUrl: String,
    val bearerToken: String,
)

private val Context.authDataStore by preferencesDataStore(name = "auth")

class AuthStore(private val context: Context) {

    val configFlow: Flow<AuthConfig?> = context.authDataStore.data.map { prefs ->
        val url = prefs[KEY_BASE_URL]?.takeIf { it.isNotBlank() }
        val token = prefs[KEY_TOKEN]?.takeIf { it.isNotBlank() }
        if (url != null && token != null) AuthConfig(url, token) else null
    }

    suspend fun save(baseUrl: String, bearerToken: String) {
        context.authDataStore.edit { prefs ->
            prefs[KEY_BASE_URL] = baseUrl
            prefs[KEY_TOKEN] = bearerToken
        }
    }

    suspend fun clear() {
        context.authDataStore.edit { prefs ->
            prefs.remove(KEY_BASE_URL)
            prefs.remove(KEY_TOKEN)
        }
    }

    private companion object {
        val KEY_BASE_URL = stringPreferencesKey("base_url")
        val KEY_TOKEN = stringPreferencesKey("bearer_token")
    }
}
