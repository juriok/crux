package com.rokjuric.crux.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import com.rokjuric.crux.data.Session
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(vm: MainViewModel, onOpenSession: (String) -> Unit) {
    val plan by vm.plan.collectAsStateWithLifecycle()
    val logs by vm.logs.collectAsStateWithLifecycle()
    val acts by vm.activities.collectAsStateWithLifecycle()
    var notesOpen by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("Plan", fontWeight = FontWeight.SemiBold) }) }) { inner ->
        val p = plan
        if (p == null) {
            Column(Modifier.padding(inner).padding(24.dp)) {
                Text("No plan yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Plans are written by Claude Code and published to your server.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        val weeks = p.sessions.sortedBy { it.date }.groupBy { weekStart(it.date) }
        val thisWeek = weekStart(vm.today())
        LazyColumn(
            Modifier.padding(inner).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            item {
                InfoCard(modifier = Modifier.animateContentSize()) {
                    Text(p.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    if (p.startDate != null && p.endDate != null) {
                        Text(
                            "${Fmt.day(p.startDate)} – ${Fmt.day(p.endDate)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (p.goal.isNotBlank()) {
                        Spacer(Modifier.padding(4.dp))
                        Text(p.goal, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (p.coachNotes.isNotBlank()) {
                        Spacer(Modifier.padding(4.dp))
                        Row(
                            Modifier.fillMaxWidth().clickable { notesOpen = !notesOpen }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Coach notes",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(if (notesOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.primary)
                        }
                        if (notesOpen) CoachText(p.coachNotes)
                    }
                    Text(
                        "Version ${p.version}" + (p.publishedAt?.let { " · published ${it.take(10)}" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            weeks.forEach { (start, sessions) ->
                item(key = "w-$start") {
                    SectionTitle(if (start == thisWeek) "This week" else "Week of ${Fmt.day(start)}")
                }
                items(sessions.filter { it.kind != "rest" }, key = { it.id }) { s ->
                    SessionRow(s, vm.stateOf(s, logs, acts)) { onOpenSession(s.id) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

private fun weekStart(date: String): String = runCatching {
    LocalDate.parse(date).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
}.getOrDefault(date)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    vm: MainViewModel,
    sessionId: String,
    onBack: () -> Unit,
    onStartWorkout: (String) -> Unit,
    onOpenLog: (String) -> Unit,
) {
    val plan by vm.plan.collectAsStateWithLifecycle()
    val logs by vm.logs.collectAsStateWithLifecycle()
    val acts by vm.activities.collectAsStateWithLifecycle()
    var logging by remember { mutableStateOf(false) }
    val p = plan
    val s: Session? = p?.session(sessionId)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s?.let { Fmt.day(it.date) } ?: "") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        bottomBar = {
            if (s != null) {
                val state = vm.stateOf(s, logs, acts)
                val log = vm.logFor(s, logs)
                Surface(tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            s.isGuided && state == SessionState.IN_PROGRESS ->
                                Button(onClick = { onStartWorkout(s.id) }, Modifier.weight(1f)) { Text("Resume workout") }
                            s.isGuided && log != null ->
                                FilledTonalButton(onClick = { onOpenLog(log.id) }, Modifier.weight(1f)) { Text("View log") }
                            s.isGuided -> Button(onClick = { onStartWorkout(s.id) }, Modifier.weight(1f)) {
                                Icon(Icons.Filled.PlayArrow, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Start workout")
                            }
                            else -> FilledTonalButton(onClick = { logging = true }, Modifier.weight(1f)) {
                                Text(if (log != null) "Edit log" else "Log it")
                            }
                        }
                    }
                }
            }
        },
    ) { inner ->
        if (s == null) {
            Text("This session is no longer in the plan.", Modifier.padding(inner).padding(24.dp))
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(inner).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(kindIcon(s.kind), null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        Fmt.kindLabel(s.kind) + (s.durationMin?.let { " · $it min" } ?: "") + if (s.optional) " · optional" else "",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    StateChip(vm.stateOf(s, logs, acts))
                }
                Text(s.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                if (s.summary.isNotBlank()) Text(s.summary, style = MaterialTheme.typography.bodyLarge)
                if (s.notes.isNotBlank()) {
                    Spacer(Modifier.padding(4.dp))
                    CoachText(s.notes)
                }
            }
            if (s.warmup.isNotEmpty()) {
                item { SectionTitle("Warm-up") }
                item { Bullets(s.warmup) }
            }
            if (s.blocks.isNotEmpty()) {
                item { SectionTitle("Exercises") }
                items(s.blocks.withIndex().toList(), key = { "b-${it.index}" }) { (_, b) ->
                    val ex = p.exercise(b.exercise)
                    InfoCard(modifier = Modifier.padding(bottom = 8.dp)) {
                        if (b.superset != null) {
                            Text("Superset ${b.superset}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                        }
                        Text(ex.name, style = MaterialTheme.typography.titleMedium)
                        Text(Fmt.block(b, ex), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        val sets = b.expanded
                        if (sets.distinct().size > 1) {
                            sets.forEachIndexed { i, t ->
                                Text("Set ${i + 1}: ${Fmt.target(t, ex)}", style = MaterialTheme.typography.bodySmall)
                            }
                        } else {
                            sets.firstOrNull()?.let { t ->
                                Text(
                                    "Rest ${t.restS} s" + (t.rpe?.let { " · RPE ${Fmt.num(it)}" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        val notes = (listOf(b.note) + sets.map { it.note }).filter { it.isNotBlank() }.distinct()
                        notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (ex.cues.isNotEmpty()) {
                            Spacer(Modifier.padding(2.dp))
                            Bullets(ex.cues)
                        }
                    }
                }
            }
            s.run?.let { r ->
                item { SectionTitle("Run") }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (r.target.isNotBlank()) Text(r.target, style = MaterialTheme.typography.bodyLarge)
                        r.distanceKm?.let { Text("About ${Fmt.num(it)} km", style = MaterialTheme.typography.bodyMedium) }
                        if (r.workout.isNotBlank()) MonoBox(r.workout)
                    }
                }
            }
            if (s.cooldown.isNotEmpty()) {
                item { SectionTitle("Cool-down") }
                item { Bullets(s.cooldown) }
            }
        }
    }

    if (logging && s != null) {
        LogSessionDialog(
            title = s.title,
            existing = vm.logFor(s, logs),
            onDismiss = { logging = false },
            onSave = { status, rpe, notes ->
                vm.logSimple(s, p, status, rpe, notes)
                logging = false
            },
        )
    }
}
