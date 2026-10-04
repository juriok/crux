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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rokjuric.crux.data.Activity
import com.rokjuric.crux.data.LogStatus
import com.rokjuric.crux.data.WorkoutLog
import java.time.Duration
import java.time.OffsetDateTime
import kotlin.math.roundToInt

private fun duration(log: WorkoutLog): String? = runCatching {
    val d = Duration.between(OffsetDateTime.parse(log.startedAt), OffsetDateTime.parse(log.finishedAt))
    if (d.toMinutes() < 1) null else "${d.toMinutes()} min"
}.getOrNull()

private fun statusLabel(status: String) = when (status) {
    LogStatus.DONE -> SessionState.DONE
    LogStatus.PARTIAL -> SessionState.PARTIAL
    LogStatus.SKIPPED -> SessionState.SKIPPED
    else -> SessionState.IN_PROGRESS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: MainViewModel, onOpenLog: (String) -> Unit) {
    val logs by vm.logs.collectAsStateWithLifecycle()
    val acts by vm.activities.collectAsStateWithLifecycle()
    val icu by vm.icuConfigured.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val finished = logs.filter { it.status != LogStatus.IN_PROGRESS }

    Scaffold(topBar = { TopAppBar(title = { Text("History", fontWeight = FontWeight.SemiBold) }) }) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Logged") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Watch") })
            }
            if (tab == 0) {
                if (finished.isEmpty()) {
                    Text("Finished workouts show up here.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(contentPadding = PaddingValues(16.dp)) {
                    items(finished, key = { it.id }) { log ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenLog(log.id) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(kindIcon(log.kind), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(log.title.ifBlank { "Workout" }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text(
                                    listOfNotNull(
                                        Fmt.day(log.date),
                                        log.sets.count { it.done }.takeIf { it > 0 }?.let { "$it sets" },
                                        duration(log),
                                        log.rpe?.let { "RPE $it" },
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            StateChip(statusLabel(log.status))
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            } else {
                if (icu != true) {
                    Text(
                        "Watch activities appear here once intervals.icu is connected to your server. See Settings.",
                        Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (acts.isEmpty()) {
                    Text("No watch activities in the last six weeks.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(contentPadding = PaddingValues(16.dp)) {
                    items(acts.sortedByDescending { it.start }) { a -> ActivityRow(a) }
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(a: Activity) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            kindIcon(if (a.isRun) "run" else if (a.type == "WeightTraining") "strength" else if (a.type.contains("Walk")) "walk" else "other"),
            null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(a.name.ifBlank { a.type }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                listOfNotNull(
                    Fmt.day(a.date),
                    a.distance?.takeIf { it > 0 }?.let { "%.2f km".format(it / 1000) },
                    a.movingTime?.let { Fmt.clock(it.toLong()) },
                    if (a.isRun) Fmt.pace(a.movingTime, a.distance) else null,
                    a.avgHr?.let { "HR ${it.roundToInt()}" },
                    a.load?.let { "load ${it.roundToInt()}" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(vm: MainViewModel, logId: String, onBack: () -> Unit) {
    val logs by vm.logs.collectAsStateWithLifecycle()
    val log = logs.firstOrNull { it.id == logId }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var progressFor by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(log?.let { Fmt.day(it.date) } ?: "") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (log != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
                },
            )
        },
    ) { inner ->
        if (log == null) {
            Text("Log not found.", Modifier.padding(inner).padding(24.dp))
            return@Scaffold
        }
        val groups = log.sets.groupBy { it.block }.toSortedMap()
        LazyColumn(Modifier.padding(inner).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(log.title.ifBlank { "Workout" }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    StateChip(statusLabel(log.status))
                }
                Text(
                    listOfNotNull(Fmt.kindLabel(log.kind), duration(log), log.rpe?.let { "session RPE $it" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (log.notes.isNotBlank()) {
                    Spacer(Modifier.padding(4.dp))
                    InfoCard { Text("“${log.notes}”", style = MaterialTheme.typography.bodyMedium) }
                }
                error?.let {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CloudOff, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(6.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            items(groups.entries.toList(), key = { it.key }) { (_, sets) ->
                val first = sets.first()
                InfoCard(modifier = Modifier.clickable { progressFor = first.exercise }) {
                    Text(first.name.ifBlank { first.exercise }, style = MaterialTheme.typography.titleMedium)
                    sets.forEach { s ->
                        val target = listOfNotNull(
                            s.targetReps?.let { "$it reps" },
                            s.targetSeconds?.let { "$it s" },
                            Fmt.load(s.targetLoad, s.loadType),
                        ).joinToString(" · ")
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            Text("${s.set + 1}", Modifier.width(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                if (s.skipped) "skipped" else Fmt.setLog(s) + (s.rpe?.let { " · RPE ${Fmt.num(it)}" } ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                                color = if (s.skipped) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            )
                            if (target.isNotBlank()) Text("plan: $target", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text("Tap for history", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }

    progressFor?.let { eid ->
        ModalBottomSheet(onDismissRequest = { progressFor = null }) {
            val past = logs.filter { l -> l.status != LogStatus.IN_PROGRESS && l.sets.any { it.exercise == eid && it.done } }
            val name = past.firstNotNullOfOrNull { l -> l.sets.firstOrNull { it.exercise == eid }?.name } ?: eid
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.padding(6.dp))
                past.take(20).forEach { l ->
                    val sets = l.sets.filter { it.exercise == eid && it.done }
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(Fmt.day(l.date), Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(sets.joinToString(",  ") { Fmt.setLog(it) }, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    if (confirmDelete && log != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this log?") },
            text = { Text("It is removed from the phone and the server, so your coach won't see it either.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteLog(log.id) { err -> if (err == null) onBack() else error = err }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
