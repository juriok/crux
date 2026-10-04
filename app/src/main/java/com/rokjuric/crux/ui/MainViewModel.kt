package com.rokjuric.crux.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rokjuric.crux.CruxApp
import com.rokjuric.crux.data.Activity
import com.rokjuric.crux.data.LogStatus
import com.rokjuric.crux.data.Plan
import com.rokjuric.crux.data.Session
import com.rokjuric.crux.data.SettingsState
import com.rokjuric.crux.data.SyncState
import com.rokjuric.crux.data.Wellness
import com.rokjuric.crux.data.WorkoutLog
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class SessionState { DONE, PARTIAL, SKIPPED, IN_PROGRESS, MISSED, TODAY, UPCOMING }

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val repo = (app as CruxApp).repo

    private fun <T> kotlinx.coroutines.flow.Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val plan: StateFlow<Plan?> = repo.plan.state(null)
    val logs: StateFlow<List<WorkoutLog>> = repo.logs.state(emptyList())
    val wellness: StateFlow<List<Wellness>> = repo.wellness.state(emptyList())
    val activities: StateFlow<List<Activity>> = repo.activities.state(emptyList())
    val icuConfigured: StateFlow<Boolean?> = repo.icuConfigured.state(null)
    val lastSync: StateFlow<Long?> = repo.lastSync.state(null)
    val unsynced: StateFlow<Int> = repo.unsyncedCount.state(0)
    val sync: StateFlow<SyncState> = repo.sync
    val settings: StateFlow<SettingsState> = repo.settings.state

    val active: StateFlow<WorkoutLog?> =
        repo.logs.combine(repo.plan) { logs, _ -> logs.firstOrNull { it.status == LogStatus.IN_PROGRESS } }.state(null)

    fun refresh() = viewModelScope.launch { repo.refresh() }

    fun today(): String = LocalDate.now().toString()

    /** The log that answers a plan session, if any. */
    fun logFor(session: Session, logs: List<WorkoutLog>): WorkoutLog? =
        logs.filter { it.sessionId == session.id }.maxByOrNull { if (it.status == LogStatus.IN_PROGRESS) 0 else 1 }

    /** A watch run on the day of a run session counts as doing it. */
    fun runFor(session: Session, acts: List<Activity>): Activity? =
        if (session.kind == "run") acts.firstOrNull { it.isRun && it.date == session.date } else null

    fun stateOf(session: Session, logs: List<WorkoutLog>, acts: List<Activity>): SessionState {
        val log = logFor(session, logs)
        when (log?.status) {
            LogStatus.DONE -> return SessionState.DONE
            LogStatus.PARTIAL -> return SessionState.PARTIAL
            LogStatus.SKIPPED -> return SessionState.SKIPPED
            LogStatus.IN_PROGRESS -> return SessionState.IN_PROGRESS
        }
        if (runFor(session, acts) != null) return SessionState.DONE
        val today = today()
        return when {
            session.date < today -> SessionState.MISSED
            session.date == today -> SessionState.TODAY
            else -> SessionState.UPCOMING
        }
    }

    /** For sessions without sets: runs, climbing days, mobility. */
    fun logSimple(session: Session, plan: Plan, status: String, rpe: Int?, notes: String) = viewModelScope.launch {
        val now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString()
        val existing = logFor(session, logs.value)
        repo.saveLog(
            WorkoutLog(
                id = existing?.id ?: UUID.randomUUID().toString(),
                date = session.date.takeIf { it <= today() } ?: today(),
                sessionId = session.id,
                planVersion = plan.version,
                title = session.title,
                kind = session.kind,
                startedAt = existing?.startedAt ?: now,
                finishedAt = now,
                status = status,
                rpe = rpe,
                notes = notes.trim(),
            )
        )
    }

    fun deleteLog(id: String, onResult: (String?) -> Unit) = viewModelScope.launch {
        val r = repo.deleteLog(id)
        onResult(r.exceptionOrNull()?.let { "Couldn't delete: ${it.message ?: "no connection"}" })
    }
}
