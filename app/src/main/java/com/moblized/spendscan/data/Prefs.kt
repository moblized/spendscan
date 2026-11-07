package com.moblized.spendscan.data

import android.content.Context
import androidx.core.content.edit
import com.moblized.spendscan.paperless.PaperlessConfig
import com.moblized.spendscan.paperless.SyncProblem
import com.moblized.spendscan.paperless.SyncResult
import com.moblized.spendscan.server.ServerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeChoice { SYSTEM, LIGHT, DARK }

/** How the last try at sending to Paperless went. [problem] is null when it went through. */
data class PaperlessRun(val problem: SyncProblem?, val at: Long)

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

    // Paperless-ngx, also kept out of Google backups for its token.
    private val pl = context.getSharedPreferences("paperless", Context.MODE_PRIVATE)

    private val _paperless = MutableStateFlow(
        PaperlessConfig(pl.getString("url", "").orEmpty(), pl.getString("token", "").orEmpty(), pl.getBoolean("on", false)),
    )
    val paperless: StateFlow<PaperlessConfig> = _paperless

    fun setPaperless(config: PaperlessConfig) {
        _paperless.value = config
        pl.edit {
            putString("url", config.url)
            putString("token", config.token)
            putBoolean("on", config.on)
        }
    }

    private val _paperlessRun = MutableStateFlow(
        pl.getLong("ranAt", 0).takeIf { it > 0 }?.let { at ->
            PaperlessRun(pl.getString("problem", null)?.let { runCatching { SyncProblem.valueOf(it) }.getOrNull() }, at)
        },
    )
    val paperlessRun: StateFlow<PaperlessRun?> = _paperlessRun

    fun setPaperlessResult(result: SyncResult, at: Long) {
        val problem = when (result) {
            SyncResult.Off -> return
            is SyncResult.Finished -> null
            is SyncResult.Stopped -> result.problem
        }
        _paperlessRun.value = PaperlessRun(problem, at)
        pl.edit {
            putLong("ranAt", at)
            if (problem == null) remove("problem") else putString("problem", problem.name)
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
