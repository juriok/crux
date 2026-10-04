package com.rokjuric.crux.workout

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.rokjuric.crux.CruxApp
import com.rokjuric.crux.data.LogStatus
import com.rokjuric.crux.data.Plan
import com.rokjuric.crux.data.Session
import com.rokjuric.crux.data.SetLog
import com.rokjuric.crux.data.WorkoutLog
import com.rokjuric.crux.ui.Fmt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/** What the user typed for the set in front of them. */
data class SetInput(
    val reps: Int? = null,
    val load: Double? = null,
    val seconds: Int? = null,
    val rpe: Double? = null,
)

data class WorkoutUi(
    val loading: Boolean = true,
    val error: String? = null,
    /** Another unfinished workout that has to be finished or discarded first. */
    val conflict: WorkoutLog? = null,
    /** The session plus only the exercises it uses. */
    val plan: Plan = Plan(),
    val session: Session? = null,
    val steps: List<Step> = emptyList(),
    val log: WorkoutLog? = null,
    val current: Int = 0,
    val restEndsAt: Long = 0L,
    val finishing: Boolean = false,
    val closed: Boolean = false,
    /** exercise id -> "Tue 29 Sep: 5, 5, 5, 4 × BW +10 kg" */
    val lastTime: Map<String, String> = emptyMap(),
    val lastLoad: Map<String, Double> = emptyMap(),
) {
    val step: Step? get() = steps.getOrNull(current)
    fun logged(step: Step): SetLog? = log?.sets?.firstOrNull { it.block == step.block && it.set == step.set }
    val doneCount: Int get() = log?.sets?.count { it.done } ?: 0
    val loggedCount: Int get() = log?.sets?.size ?: 0
    val allLogged: Boolean get() = steps.isNotEmpty() && steps.all { logged(it) != null }
}

class WorkoutViewModel(app: Application, handle: SavedStateHandle) : AndroidViewModel(app) {
    private val repo = (app as CruxApp).repo
    private val sessionId: String = checkNotNull(handle["sessionId"])

    private val _ui = MutableStateFlow(WorkoutUi())
    val ui: StateFlow<WorkoutUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val active = repo.activeLog()
        if (active != null && active.sessionId != sessionId) {
            _ui.value = WorkoutUi(loading = false, conflict = active)
            return
        }
        val mini: Plan? = if (active != null) {
            repo.snapshot(active.id) ?: currentMini()
        } else {
            currentMini()
        }
        val session = mini?.sessions?.firstOrNull()
        if (mini == null || session == null) {
            _ui.value = WorkoutUi(loading = false, error = "This session is no longer in the plan.")
            return
        }
        val steps = buildSteps(session)
        val (lastTime, lastLoad) = history(session, mini, excludeId = active?.id)
        val state = WorkoutUi(
            loading = false, plan = mini, session = session, steps = steps, log = active,
            lastTime = lastTime, lastLoad = lastLoad,
        )
        val first = steps.indexOfFirst { state.logged(it) == null }
        _ui.value = state.copy(current = if (first >= 0) first else 0, finishing = active != null && first < 0)
        if (active != null) showProgress()
    }

    private suspend fun currentMini(): Plan? {
        val plan = repo.plan.first() ?: return null
        val session = plan.session(sessionId) ?: return null
        val used = session.blocks.map { it.exercise }.toSet()
        return plan.copy(exercises = plan.exercises.filterKeys { it in used }, sessions = listOf(session))
    }

    private suspend fun history(session: Session, plan: Plan, excludeId: String?): Pair<Map<String, String>, Map<String, Double>> {
        val logs = repo.logs.first().filter { it.id != excludeId && it.status != LogStatus.IN_PROGRESS }
        val text = mutableMapOf<String, String>()
        val loads = mutableMapOf<String, Double>()
        for (eid in session.blocks.map { it.exercise }.distinct()) {
            val last = logs.firstOrNull { l -> l.sets.any { it.exercise == eid && it.done } } ?: continue
            val sets = last.sets.filter { it.exercise == eid && it.done }
            val work = sets.joinToString(", ") { it.seconds?.let { s -> "${s}s" } ?: it.reps?.toString() ?: "✓" }
            val ex = plan.exercise(eid)
            val loadsUsed = sets.mapNotNull { it.load }.distinct()
            val loadText = when {
                loadsUsed.size == 1 -> Fmt.load(loadsUsed[0], ex.loadType)
                loadsUsed.size > 1 -> loadsUsed.joinToString("/") { Fmt.num(it) } + " kg"
                else -> null
            }
            text[eid] = "${Fmt.day(last.date)}: $work" + (loadText?.let { " × $it" } ?: "")
            sets.lastOrNull()?.load?.let { loads[eid] = it }
        }
        return text to loads
    }

    /** Pre-fills the inputs: what was logged, else the plan's target, else last time's load. */
    fun defaults(step: Step): SetInput {
        val s = _ui.value
        s.logged(step)?.let { return SetInput(it.reps, it.load, it.seconds, it.rpe) }
        val ex = s.plan.exercise(step.exerciseId)
        val t = step.target
        val load = t.load ?: s.lastLoad[step.exerciseId] ?: if (ex.loadType == "added") 0.0 else null
        return SetInput(
            reps = t.reps ?: if (t.seconds == null) (if (t.amrap) 0 else null) else null,
            load = if (ex.loadType == "bodyweight") null else load,
            seconds = t.seconds?.let { if (t.amrap) 0 else it },
            rpe = null,
        )
    }

    private fun now(): String = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString()

    /** The log is created on the first logged set, so peeking at a workout leaves nothing behind. */
    private suspend fun ensureLog(): WorkoutLog {
        _ui.value.log?.let { return it }
        val s = _ui.value
        val session = s.session!!
        val log = WorkoutLog(
            id = UUID.randomUUID().toString(),
            date = LocalDate.now().toString(),
            sessionId = session.id,
            planVersion = s.plan.version,
            title = session.title,
            kind = session.kind,
            startedAt = now(),
        )
        repo.saveSnapshot(log.id, s.plan)
        _ui.update { it.copy(log = log) }
        return log
    }

    private fun record(step: Step, input: SetInput?, skipped: Boolean, log: WorkoutLog): WorkoutLog {
        val ex = _ui.value.plan.exercise(step.exerciseId)
        val entry = SetLog(
            exercise = step.exerciseId,
            name = ex.name,
            loadType = ex.loadType,
            block = step.block,
            set = step.set,
            reps = if (skipped) null else input?.reps,
            seconds = if (skipped) null else input?.seconds,
            load = if (skipped) null else input?.load,
            rpe = if (skipped) null else input?.rpe,
            done = !skipped,
            skipped = skipped,
            targetReps = step.target.reps,
            targetSeconds = step.target.seconds,
            targetLoad = step.target.load,
            loggedAt = now(),
        )
        val others = log.sets.filterNot { it.block == step.block && it.set == step.set }
        return log.copy(sets = (others + entry).sortedWith(compareBy({ it.block }, { it.set })))
    }

    private fun nextUnlogged(after: Int, s: WorkoutUi): Int? {
        val n = s.steps.size
        for (k in 1..n) {
            val i = (after + k) % n
            if (s.logged(s.steps[i]) == null) return i
        }
        return null
    }

    fun completeSet(input: SetInput) = viewModelScope.launch {
        val step = _ui.value.step ?: return@launch
        val log = record(step, input, skipped = false, log = ensureLog())
        repo.saveLog(log)
        advance(log, restAfter = step.target.restS)
    }

    fun skipSet() = viewModelScope.launch {
        val step = _ui.value.step ?: return@launch
        val log = record(step, null, skipped = true, log = ensureLog())
        repo.saveLog(log)
        advance(log, restAfter = 0)
    }

    fun skipExercise() = viewModelScope.launch {
        val s = _ui.value
        val step = s.step ?: return@launch
        var log = ensureLog()
        for (st in s.steps.filter { it.block == step.block }) {
            if (s.copy(log = log).logged(st) == null) log = record(st, null, skipped = true, log = log)
        }
        repo.saveLog(log)
        advance(log, restAfter = 0)
    }

    private fun advance(log: WorkoutLog, restAfter: Int) {
        val withLog = _ui.value.copy(log = log)
        val next = nextUnlogged(withLog.current, withLog)
        val restEndsAt = if (next != null && restAfter > 0) System.currentTimeMillis() + restAfter * 1000L else 0L
        _ui.value = withLog.copy(current = next ?: withLog.current, restEndsAt = restEndsAt, finishing = next == null)
        showProgress()
    }

    fun jumpTo(index: Int) {
        _ui.update { it.copy(current = index.coerceIn(0, (it.steps.size - 1).coerceAtLeast(0)), finishing = false) }
        showProgress()
    }

    fun addRest(seconds: Int) {
        _ui.update {
            val base = maxOf(it.restEndsAt, System.currentTimeMillis())
            it.copy(restEndsAt = base + seconds * 1000L)
        }
        showProgress()
    }

    fun endRest() {
        _ui.update { it.copy(restEndsAt = 0L) }
        showProgress()
    }

    fun startFinish() = _ui.update { it.copy(finishing = true, restEndsAt = 0L) }
    fun cancelFinish() = _ui.update { it.copy(finishing = false) }

    fun finish(rpe: Int?, notes: String) = viewModelScope.launch {
        val s = _ui.value
        val log = s.log ?: return@launch
        val status = if (s.allLogged && log.sets.none { it.skipped }) LogStatus.DONE else LogStatus.PARTIAL
        repo.saveLog(log.copy(status = status, finishedAt = now(), rpe = rpe, notes = notes.trim()))
        RestTimerService.stop(getApplication())
        _ui.update { it.copy(closed = true) }
    }

    fun discard() = viewModelScope.launch {
        _ui.value.log?.let { repo.discardLog(it.id) }
        RestTimerService.stop(getApplication())
        _ui.update { it.copy(closed = true) }
    }

    /** Throws away the other unfinished workout so this one can start. */
    fun discardConflict() = viewModelScope.launch {
        _ui.value.conflict?.let { repo.discardLog(it.id) }
        RestTimerService.stop(getApplication())
        _ui.value = WorkoutUi()
        load()
    }

    fun nextLabel(step: Step?): String {
        step ?: return "All sets done"
        val ex = _ui.value.plan.exercise(step.exerciseId)
        return "${ex.name} · set ${step.set + 1}/${step.setCount} · ${Fmt.target(step.target, ex)}"
    }

    /** Mirrors the workout into the ongoing notification (and the rest countdown). */
    private fun showProgress() {
        val s = _ui.value
        if (s.log == null) return
        val label = if (s.finishing) "All sets done - tap to finish" else nextLabel(s.step)
        RestTimerService.update(getApplication(), s.session?.title ?: "Workout", label, s.restEndsAt)
    }
}
