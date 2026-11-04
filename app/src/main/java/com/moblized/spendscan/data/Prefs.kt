package com.moblized.spendscan.data

import android.content.Context
import androidx.core.content.edit
import com.moblized.spendscan.server.ServerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeChoice { SYSTEM, LIGHT, DARK }

/** Small settings. Receipts live in the database. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(
        runCatching { ThemeChoice.valueOf(p.getString("theme", null) ?: "SYSTEM") }.getOrDefault(ThemeChoice.SYSTEM),
    )
    val theme: StateFlow<ThemeChoice> = _theme

    private val _overallBudget = MutableStateFlow(p.getLong("overallBudget", -1).takeIf { it >= 0 })
    val overallBudget: StateFlow<Long?> = _overallBudget

    // Kept in its own file so the key stays out of Google backups.
    private val s = context.getSharedPreferences("server", Context.MODE_PRIVATE)

    private val _server = MutableStateFlow(
        ServerConfig(s.getString("url", "").orEmpty(), s.getString("key", "").orEmpty(), s.getString("model", "").orEmpty()),
    )
    val server: StateFlow<ServerConfig> = _server

    fun setServer(config: ServerConfig) {
        _server.value = config
        s.edit {
            putString("url", config.url)
            putString("key", config.key)
            putString("model", config.model)
        }
    }

    fun setTheme(choice: ThemeChoice) {
        _theme.value = choice
        p.edit { putString("theme", choice.name) }
    }

    fun setOverallBudget(cents: Long?) {
        _overallBudget.value = cents
        p.edit { if (cents == null) remove("overallBudget") else putLong("overallBudget", cents) }
    }

    var updateCheckedAt: Long
        get() = p.getLong("updateCheckedAt", 0)
        set(v) = p.edit { putLong("updateCheckedAt", v) }

    var snoozedVersion: Int
        get() = p.getInt("snoozedVersion", 0)
        set(v) = p.edit { putInt("snoozedVersion", v) }

    var snoozedUntil: Long
        get() = p.getLong("snoozedUntil", 0)
        set(v) = p.edit { putLong("snoozedUntil", v) }
}
