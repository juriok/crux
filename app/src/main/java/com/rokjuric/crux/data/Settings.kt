package com.rokjuric.crux.data

import android.content.Context
import com.rokjuric.crux.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsState(
    val serverUrl: String,
    val token: String,
) {
    val paired: Boolean get() = token.isNotBlank()
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("crux", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    val serverUrl: String get() = _state.value.serverUrl
    val token: String get() = _state.value.token

    private fun read() = SettingsState(
        serverUrl = prefs.getString("server_url", null) ?: BuildConfig.DEFAULT_SERVER,
        token = prefs.getString("token", null) ?: "",
    )

    fun setServerUrl(url: String) {
        prefs.edit().putString("server_url", url.trim().trimEnd('/')).apply()
        _state.value = read()
    }

    fun setToken(token: String) {
        prefs.edit().putString("token", token).apply()
        _state.value = read()
    }

    fun unpair() = setToken("")
}
