package com.rokjuric.crux.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rokjuric.crux.data.Activity
import com.rokjuric.crux.data.Plan
import com.rokjuric.crux.data.Session
import com.rokjuric.crux.data.Wellness
import com.rokjuric.crux.data.WorkoutLog
import java.time.LocalDate
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    vm: MainViewModel,
    onOpenSession: (String) -> Unit,
    onStartWorkout: (String) -> Unit,
    onOpenLog: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val plan by vm.plan.collectAsStateWithLifecycle()
    val logs by vm.logs.collectAsStateWithLifecycle()
    val acts by vm.activities.collectAsStateWithLifecycle()
    val wellness by vm.wellness.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val lastSync by vm.lastSync.collectAsStateWithLifecycle()
    val unsynced by vm.unsynced.collectAsStateWithLifecycle()
    val icu by vm.icuConfigured.collectAsStateWithLifecycle()
    val today = vm.today()
    var logging by remember { mutableStateOf<Session?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Today", fontWeight = FontWeight.SemiBold)
                        Text(
                            Fmt.longDay(LocalDate.now()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (sync.running) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(22.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Sync, "Sync now") }
                    }
                },
            )
        },
    ) { inner ->
        PullToRefreshBox(
            isRefreshing = sync.running,
            onRefresh = { vm.refresh() },
            modifier = Modifier.padding(inner).fillMaxSize(),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (!settings.paired) {
                    item {
                        InfoCard(container = MaterialTheme.colorScheme.primaryContainer) {
                            Text("Connect to your server", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.padding(4.dp))
                            Text(
                                "Your plan comes from Claude Code through your home server. On the PC run:\n" +
                                    "python coach/coach.py pair\nthen enter the 6-digit code in Settings.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.padding(4.dp))
                            Button(onClick = onOpenSettings) { Text("Open settings") }
                        }
                    }
                }

                active?.let { a ->
                    item {
                        InfoCard(container = MaterialTheme.colorScheme.primaryContainer) {
                            Text("Workout in progress", style = MaterialTheme.typography.labelLarge)
                            Text(a.title, style = MaterialTheme.typography.titleMedium)
                            Text("${a.sets.count { it.done }} sets logged", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.padding(4.dp))
                            Button(onClick = { a.sessionId?.let(onStartWorkout) }) {
                                Icon(Icons.Filled.PlayArrow, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Resume")
                            }
                        }
                    }
                }

                sync.error?.let { err ->
                    if (settings.paired) item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CloudOff, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "$err - showing the saved copy" + if (unsynced > 0) ", $unsynced workout(s) waiting to upload" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                if (wellness.isNotEmpty()) item { ReadinessCard(wellness) }

                val p = plan
                if (p == null) {
                    if (settings.paired) item {
                        InfoCard {
                            Text("No plan yet", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Tell Claude Code what you are training for. It writes the plan to your server and it shows up here.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                } else {
                    val todays = p.sessions.filter { it.date == today }
                    if (todays.isEmpty()) {
                        item {
                            InfoCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(kindIcon("rest"), null, tint = MaterialTheme.colorScheme.secondary)
                                    Spacer(Modifier.width(10.dp))
                                    Text("Rest day", style = MaterialTheme.typography.titleMedium)
                                }
                                Text(
                                    "Recovery is where the strength is built. Easy walk or mobility is fine.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    items(todays, key = { it.id }) { s ->
                        SessionCard(
                            session = s, plan = p, vm = vm, logs = logs, acts = acts, icuConfigured = icu == true,
                            onStart = { onStartWorkout(s.id) },
                            onDetails = { onOpenSession(s.id) },
                            onOpenLog = onOpenLog,
                            onLog = { logging = s },
                        )
                    }

                    val missedRecently = p.sessions.filter {
                        it.date < today && it.date >= LocalDate.now().minusDays(3).toString() &&
                            vm.stateOf(it, logs, acts) == SessionState.MISSED && it.kind != "rest"
                    }
                    if (missedRecently.isNotEmpty()) {
                        item { SectionTitle("Not logged yet") }
                        items(missedRecently, key = { "m-" + it.id }) { s ->
                            SessionRow(s, vm.stateOf(s, logs, acts)) { onOpenSession(s.id) }
                        }
                    }

                    val upcoming = p.sessions.filter { it.date > today && it.kind != "rest" }.take(4)
                    if (upcoming.isNotEmpty()) {
                        item { SectionTitle("Coming up") }
                        items(upcoming, key = { "u-" + it.id }) { s ->
                            SessionRow(s, vm.stateOf(s, logs, acts)) { onOpenSession(s.id) }
                        }
                    }

                    item {
                        Text(
                            "${p.title} · v${p.version}" + (lastSync?.let { " · synced ${Fmt.ago(it)}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }
    }

    logging?.let { s ->
        LogSessionDialog(
            title = s.title,
            existing = vm.logFor(s, logs),
            onDismiss = { logging = null },
            onSave = { status, rpe, notes ->
                plan?.let { vm.logSimple(s, it, status, rpe, notes) }
                logging = null
            },
        )
    }
}

@Composable
fun SessionCard(
    session: Session,
    plan: Plan,
    vm: MainViewModel,
    logs: List<WorkoutLog>,
    acts: List<Activity>,
    icuConfigured: Boolean,
    onStart: () -> Unit,
    onDetails: () -> Unit,
    onOpenLog: (String) -> Unit,
    onLog: () -> Unit,
) {
    val state = vm.stateOf(session, logs, acts)
    val log = vm.logFor(session, logs)
    val run = vm.runFor(session, acts)
    InfoCard(modifier = Modifier.clickable(onClick = onDetails), container = MaterialTheme.colorScheme.surfaceContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(kindIcon(session.kind), null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(
                Fmt.kindLabel(session.kind) + (session.durationMin?.let { " · $it min" } ?: "") +
                    if (session.optional) " · optional" else "",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            StateChip(state)
        }
        Spacer(Modifier.padding(4.dp))
        Text(session.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (session.summary.isNotBlank()) {
            Text(session.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (session.blocks.isNotEmpty()) {
            Spacer(Modifier.padding(4.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                session.blocks.forEach { b ->
                    val ex = plan.exercise(b.exercise)
                    Row {
                        Text(ex.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text(Fmt.block(b, ex), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        session.run?.let { r ->
            if (r.target.isNotBlank()) {
                Spacer(Modifier.padding(4.dp))
                Text(r.target, style = MaterialTheme.typography.bodyMedium)
            }
            if (r.workout.isNotBlank()) {
                Spacer(Modifier.padding(4.dp))
                MonoBox(r.workout)
            }
            if (icuConfigured && run == null) {
                Spacer(Modifier.padding(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Watch, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "intervals.icu sends the next planned run to your watch",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }

        run?.let { a ->
            Spacer(Modifier.padding(4.dp))
            Text(
                "Watch: " + listOfNotNull(
                    a.distance?.let { "%.2f km".format(it / 1000) },
                    a.movingTime?.let { Fmt.clock(it.toLong()) },
                    Fmt.pace(a.movingTime, a.distance),
                    a.avgHr?.let { "HR ${it.roundToInt()}" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
                fontWeight = FontWeight.Medium,
            )
        }

        if (log != null && log.notes.isNotBlank() && !session.isGuided) {
            Text("“${log.notes}”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.padding(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                session.isGuided && state == SessionState.IN_PROGRESS -> Button(onClick = onStart) { Text("Resume") }
                session.isGuided && log != null -> FilledTonalButton(onClick = { onOpenLog(log.id) }) { Text("View log") }
                session.isGuided -> Button(onClick = onStart) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Start workout")
                }
                log != null -> FilledTonalButton(onClick = onLog) { Text("Edit log") }
                else -> FilledTonalButton(onClick = onLog) { Text(if (session.kind == "run") "How did it go?" else "Log it") }
            }
            TextButton(onClick = onDetails) { Text("Details") }
        }
    }
}

@Composable
fun SessionRow(session: Session, state: SessionState, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val d = runCatching { LocalDate.parse(session.date) }.getOrNull()
            Text(
                d?.dayOfWeek?.name?.take(3)?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("${d?.dayOfMonth ?: ""}", style = MaterialTheme.typography.titleMedium)
        }
        Icon(kindIcon(session.kind), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(session.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                listOfNotNull(
                    Fmt.kindLabel(session.kind),
                    session.durationMin?.let { "$it min" },
                    "optional".takeIf { session.optional },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StateChip(state)
    }
}

@Composable
fun ReadinessCard(wellness: List<Wellness>) {
    val sorted = wellness.sortedBy { it.id }
    val latest = sorted.lastOrNull { it.sleepSecs != null || it.restingHR != null || it.hrv != null } ?: return
    val prior = sorted.filter { it.id < latest.id }.takeLast(7)
    val rhrAvg = prior.mapNotNull { it.restingHR }.takeIf { it.size >= 3 }?.average()
    val hrvAvg = prior.mapNotNull { it.hrv }.takeIf { it.size >= 3 }?.average()

    val sleepH = latest.sleepSecs?.div(3600)
    val flags = listOfNotNull(
        "short sleep".takeIf { sleepH != null && sleepH < 6.0 },
        "resting HR up".takeIf { latest.restingHR != null && rhrAvg != null && latest.restingHR > rhrAvg + 5 },
        "HRV down".takeIf { latest.hrv != null && hrvAvg != null && latest.hrv < hrvAvg * 0.85 },
    )
    val hint = when {
        flags.size >= 2 -> "Recovery looks low (${flags.joinToString()}). Stay at the easy end of today's targets, or swap for something easy."
        flags.size == 1 -> "One marker is off (${flags[0]}). Train as planned, but end sets early if they feel heavy."
        else -> "Recovery looks normal."
    }
    InfoCard(container = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            "Recovery · " + (if (latest.id == LocalDate.now().toString()) "last night" else Fmt.day(latest.id)),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Spacer(Modifier.padding(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            sleepH?.let { Metric("Sleep", "%.1f h".format(it)) }
            latest.restingHR?.let { Metric("Resting HR", "${it.roundToInt()}", rhrAvg?.let { a -> "avg ${a.roundToInt()}" }) }
            latest.hrv?.let { Metric("HRV", "${it.roundToInt()}", hrvAvg?.let { a -> "avg ${a.roundToInt()}" }) }
        }
        Spacer(Modifier.padding(4.dp))
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun Metric(label: String, value: String, sub: String? = null) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
        sub?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer) }
    }
}
