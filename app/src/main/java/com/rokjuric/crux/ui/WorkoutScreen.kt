package com.rokjuric.crux.ui

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rokjuric.crux.data.Exercise
import com.rokjuric.crux.workout.SetInput
import com.rokjuric.crux.workout.Step
import com.rokjuric.crux.workout.WorkoutUi
import com.rokjuric.crux.workout.WorkoutViewModel
import kotlinx.coroutines.delay
import java.time.OffsetDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutScreen(onClose: () -> Unit, vm: WorkoutViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var overview by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(ui.closed) { if (ui.closed) onClose() }
    BackHandler(enabled = ui.finishing && !ui.allLogged) { vm.cancelFinish() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(ui.session?.title ?: "Workout", maxLines = 1, fontWeight = FontWeight.SemiBold)
                        if (ui.steps.isNotEmpty()) {
                            Text(
                                "${ui.loggedCount} of ${ui.steps.size} sets" + (ui.log?.let { " · " + elapsed(it.startedAt) } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close (the workout stays open)") } },
                actions = {
                    if (ui.steps.isNotEmpty()) {
                        IconButton(onClick = { overview = true }) { Icon(Icons.AutoMirrored.Filled.FormatListBulleted, "All sets") }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Finish now") }, enabled = ui.log != null, onClick = { menu = false; vm.startFinish() })
                            DropdownMenuItem(text = { Text("Discard workout") }, onClick = { menu = false; confirmDiscard = true })
                        }
                    }
                },
            )
        },
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            when {
                ui.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                ui.conflict != null -> Conflict(ui, onDiscard = { vm.discardConflict() }, onBack = onClose)
                ui.error != null -> Text(ui.error!!, Modifier.padding(24.dp))
                ui.finishing -> FinishView(ui, onSave = vm::finish, onBack = vm::cancelFinish, onDiscard = { confirmDiscard = true })
                else -> ui.step?.let { step -> ActiveSet(ui, step, vm) }
            }
        }
    }

    if (overview) {
        ModalBottomSheet(onDismissRequest = { overview = false }) {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(ui.steps) { i, st ->
                    val ex = ui.plan.exercise(st.exerciseId)
                    val logged = ui.logged(st)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { vm.jumpTo(i); overview = false }
                            .background(if (i == ui.current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            when {
                                logged?.skipped == true -> Icons.Filled.SkipNext
                                logged != null -> Icons.Filled.CheckCircle
                                else -> Icons.Filled.RadioButtonUnchecked
                            },
                            null,
                            Modifier.size(20.dp),
                            tint = if (logged?.done == true) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${ex.name} · ${st.set + 1}/${st.setCount}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                logged?.let { if (it.skipped) "skipped" else Fmt.setLog(it) } ?: Fmt.target(st.target, ex),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this workout?") },
            text = { Text("Nothing from it will be saved or sent to your coach.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; vm.discard() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun elapsed(startedAt: String): String {
    val start = remember(startedAt) { runCatching { OffsetDateTime.parse(startedAt).toInstant().toEpochMilli() }.getOrNull() } ?: return ""
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(start) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    return Fmt.clock((now - start) / 1000)
}

@Composable
private fun Conflict(ui: WorkoutUi, onDiscard: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Another workout is still open", style = MaterialTheme.typography.titleMedium)
        Text(
            "“${ui.conflict?.title}” was started and never finished. Resume it from Today, or discard it to start this one.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Button(onClick = onDiscard) { Text("Discard it") }
        }
    }
}

@Composable
private fun ActiveSet(ui: WorkoutUi, step: Step, vm: WorkoutViewModel) {
    val ex = ui.plan.exercise(step.exerciseId)
    val block = ui.session?.blocks?.getOrNull(step.block)
    // Inputs reset whenever the step changes.
    var input by remember(step.index, ui.logged(step)) { mutableStateOf(vm.defaults(step)) }
    var cuesOpen by remember(step.exerciseId) { mutableStateOf(false) }
    var warmupOpen by remember { mutableStateOf(ui.log == null) }
    val next = ui.steps.drop(step.index + 1).firstOrNull { ui.logged(it) == null }
        ?: ui.steps.take(step.index).firstOrNull { ui.logged(it) == null }

    Column(Modifier.fillMaxSize()) {
        LinearProgressIndicator(
            progress = { if (ui.steps.isEmpty()) 0f else ui.loggedCount.toFloat() / ui.steps.size },
            modifier = Modifier.fillMaxWidth(),
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RestBanner(ui.restEndsAt, onAdd = { vm.addRest(30) }, onSkip = vm::endRest)

            val warmup = ui.session?.warmup.orEmpty()
            if (warmup.isNotEmpty() && ui.log == null) {
                InfoCard(modifier = Modifier.animateContentSize()) {
                    Row(Modifier.fillMaxWidth().clickable { warmupOpen = !warmupOpen }, verticalAlignment = Alignment.CenterVertically) {
                        Text("Warm-up first", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Icon(if (warmupOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                    }
                    if (warmupOpen) {
                        Spacer(Modifier.height(6.dp))
                        Bullets(warmup)
                    }
                }
            }

            // What to do
            Column(Modifier.animateContentSize()) {
                if (step.superset != null) {
                    Text("Superset ${step.superset}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                }
                Text(ex.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Set ${step.set + 1} of ${step.setCount}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(Fmt.target(step.target, ex), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                val notes = listOf(step.target.note, block?.note.orEmpty()).filter { it.isNotBlank() }.distinct()
                notes.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
                ui.lastTime[step.exerciseId]?.let {
                    Text("Last time · $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
                if (ex.cues.isNotEmpty() || ex.notes.isNotBlank()) {
                    TextButton(onClick = { cuesOpen = !cuesOpen }, contentPadding = PaddingValues(0.dp)) {
                        Text(if (cuesOpen) "Hide technique" else "Technique")
                    }
                    if (cuesOpen) {
                        Bullets(ex.cues)
                        if (ex.notes.isNotBlank()) Text(ex.notes, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // What you did
            InfoCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (step.target.seconds != null) {
                        HoldTimer(step.target.seconds, step.target.amrap) { secs -> input = input.copy(seconds = secs) }
                        Stepper("Seconds", "${input.seconds ?: 0}",
                            onMinus = { input = input.copy(seconds = ((input.seconds ?: 0) - 5).coerceAtLeast(0)) },
                            onPlus = { input = input.copy(seconds = (input.seconds ?: 0) + 5) },
                        )
                    } else if (input.reps != null || step.target.reps != null || step.target.amrap) {
                        Stepper("Reps", "${input.reps ?: 0}",
                            onMinus = { input = input.copy(reps = ((input.reps ?: 0) - 1).coerceAtLeast(0)) },
                            onPlus = { input = input.copy(reps = (input.reps ?: 0) + 1) },
                        )
                    }
                    if (ex.loadType != "bodyweight") {
                        LoadStepper(ex, input.load) { input = input.copy(load = it) }
                    }
                    Column {
                        Text("How hard? (optional)", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(6.0, 7.0, 8.0, 9.0, 10.0).forEach { r ->
                                FilterChip(
                                    selected = input.rpe == r,
                                    onClick = { input = input.copy(rpe = if (input.rpe == r) null else r) },
                                    label = { Text(Fmt.num(r)) },
                                )
                            }
                        }
                        Text(
                            rpeMeaning(input.rpe?.toInt()).takeIf { input.rpe != null } ?: "RPE: 8 = two more reps left, 10 = nothing left",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            next?.let {
                Text(
                    "Up next · ${vm.nextLabel(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Thumb zone
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Button(
                onClick = { vm.completeSet(input) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(if (ui.logged(step) != null) "Update set" else "Log set", fontSize = 18.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = vm::skipSet) { Text("Skip set") }
                TextButton(onClick = vm::skipExercise) { Text("Skip exercise") }
            }
        }
    }
}

@Composable
private fun RestBanner(endsAt: Long, onAdd: () -> Unit, onSkip: () -> Unit) {
    if (endsAt <= 0L) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(endsAt) {
        while (System.currentTimeMillis() < endsAt) {
            now = System.currentTimeMillis()
            delay(200)
        }
        now = System.currentTimeMillis()
    }
    val remaining = ((endsAt - now + 999) / 1000).coerceAtLeast(0)
    val over = remaining == 0L
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(if (over) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(if (over) "Rest over" else "Rest", style = MaterialTheme.typography.labelLarge)
            Text(
                if (over) "Go!" else Fmt.clock(remaining),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (!over) {
            Column(horizontalAlignment = Alignment.End) {
                FilledTonalButton(onClick = onAdd) { Text("+30 s") }
                TextButton(onClick = onSkip) { Text("Skip rest") }
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, onEdit: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        FilledTonalIconButton(onClick = onMinus, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Remove, "Less") }
        Text(
            value,
            modifier = Modifier
                .widthIn(min = 120.dp)
                .clip(RoundedCornerShape(8.dp))
                .then(if (onEdit != null) Modifier.clickable(onClick = onEdit) else Modifier)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.SemiBold,
        )
        FilledTonalIconButton(onClick = onPlus, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Add, "More") }
    }
}

@Composable
private fun LoadStepper(ex: Exercise, load: Double?, onChange: (Double?) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    val shown = when {
        load == null -> "–"
        ex.loadType == "added" && load == 0.0 -> "BW"
        ex.loadType == "added" -> (if (load > 0) "+" else "") + Fmt.num(load)
        else -> Fmt.num(load)
    }
    Column {
        Stepper(
            label = when (ex.loadType) {
                "added" -> "Added kg"
                "assisted" -> "Assist kg"
                else -> "Weight kg" + if (ex.loadNote.isNotBlank()) " (${ex.loadNote})" else ""
            },
            value = shown,
            onMinus = { onChange(((load ?: 0.0) - 2.5).let { if (ex.loadType == "total") it.coerceAtLeast(0.0) else it }) },
            onPlus = { onChange((load ?: 0.0) + 2.5) },
            onEdit = { editing = true },
        )
        Text("Tap the number to type an exact weight", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (editing) {
        var text by remember { mutableStateOf(load?.let { Fmt.num(it) } ?: "") }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Weight (kg)") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = { if (ex.loadType == "added") Text("Extra weight on top of your body. 0 = bodyweight.") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    text.replace(',', '.').toDoubleOrNull()?.let(onChange)
                    editing = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
}

/**
 * For timed sets (hangs, holds, carries). Counts in with three beeps, then
 * counts down to the target - or up, for "hold as long as you can" sets.
 */
@Composable
private fun HoldTimer(target: Int?, amrap: Boolean, onResult: (Int) -> Unit) {
    var phase by remember(target, amrap) { mutableIntStateOf(0) } // 0 idle, 1 get ready, 2 holding
    var countIn by remember { mutableIntStateOf(3) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(0L) }
    val report by rememberUpdatedState(onResult)
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }.getOrNull() }
    DisposableEffect(Unit) { onDispose { tone?.release() } }

    LaunchedEffect(phase) {
        if (phase == 1) {
            for (i in 3 downTo 1) {
                countIn = i
                tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                delay(1000)
            }
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
            startedAt = SystemClock.elapsedRealtime()
            now = startedAt
            phase = 2
        } else if (phase == 2) {
            while (true) {
                now = SystemClock.elapsedRealtime()
                val secs = ((now - startedAt) / 1000).toInt()
                if (!amrap && target != null && secs >= target) {
                    tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 500)
                    report(target)
                    phase = 0
                    break
                }
                delay(100)
            }
        }
    }

    val held = ((now - startedAt) / 1000).toInt()
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (phase) {
            0 -> OutlinedButton(onClick = { phase = 1 }) {
                Text(if (amrap || target == null) "Start stopwatch" else "Start ${target} s timer")
            }
            1 -> Text("Get ready… $countIn", style = MaterialTheme.typography.headlineMedium)
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (amrap || target == null) "$held s" else "${(target - held).coerceAtLeast(0)} s",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                Button(
                    onClick = { onResult(held); phase = 0 },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Stop") }
            }
        }
    }
}

@Composable
private fun FinishView(ui: WorkoutUi, onSave: (Int?, String) -> Unit, onBack: () -> Unit, onDiscard: () -> Unit) {
    var rpe by remember { mutableStateOf<Int?>(null) }
    var notes by remember { mutableStateOf("") }
    val done = ui.log?.sets?.count { it.done } ?: 0
    val skipped = ui.log?.sets?.count { it.skipped } ?: 0
    val left = ui.steps.size - ui.loggedCount
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (left == 0) "Nice work." else "Finish early?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(
            listOfNotNull(
                "$done sets done",
                "$skipped skipped".takeIf { skipped > 0 },
                "$left not done".takeIf { left > 0 },
                ui.log?.let { elapsed(it.startedAt) },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InfoCard {
            Text("How hard was the whole session?", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            RpePicker(rpe, { rpe = it })
            Text(rpeMeaning(rpe), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            modifier = Modifier.fillMaxWidth().height(140.dp),
            label = { Text("Notes for your coach") },
            placeholder = { Text("Pain or niggles, felt strong or flat, swapped an exercise, gym was busy…") },
        )
        Button(
            onClick = { onSave(rpe, notes) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            enabled = ui.log != null,
        ) { Text("Save workout", fontSize = 18.sp) }
        if (left > 0) TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to the sets") }
        TextButton(onClick = onDiscard, modifier = Modifier.fillMaxWidth()) {
            Text("Discard workout", color = MaterialTheme.colorScheme.error)
        }
    }
}
