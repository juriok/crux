package com.rokjuric.crux.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rokjuric.crux.data.LogStatus
import com.rokjuric.crux.data.WorkoutLog

fun kindIcon(kind: String): ImageVector = when (kind) {
    "strength" -> Icons.Filled.FitnessCenter
    "run" -> Icons.AutoMirrored.Filled.DirectionsRun
    "climb" -> Icons.Filled.Terrain
    "mobility" -> Icons.Filled.SelfImprovement
    "walk" -> Icons.AutoMirrored.Filled.DirectionsWalk
    "hike" -> Icons.Filled.Hiking
    "rest" -> Icons.Filled.Bedtime
    else -> Icons.Filled.Timelapse
}

@Composable
fun StateChip(state: SessionState) {
    val (label, bg, fg) = when (state) {
        SessionState.DONE -> Triple("Done", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        SessionState.PARTIAL -> Triple("Partly done", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        SessionState.SKIPPED -> Triple("Skipped", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
        SessionState.IN_PROGRESS -> Triple("In progress", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        SessionState.MISSED -> Triple("Missed", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        SessionState.TODAY -> Triple("Today", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        SessionState.UPCOMING -> return
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state == SessionState.DONE) {
            Icon(Icons.Filled.CheckCircle, null, Modifier.size(14.dp), tint = fg)
            Spacer(Modifier.width(4.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(top = 20.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
fun InfoCard(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
fun Bullets(items: List<String>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { line ->
            Row {
                Text("•  ", color = MaterialTheme.colorScheme.primary)
                Text(line, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Coach text: blank lines split paragraphs, "- " lines become bullets. */
@Composable
fun CoachText(text: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        text.trim().split(Regex("\n\\s*\n")).forEach { para ->
            val lines = para.lines()
            if (lines.all { it.trimStart().startsWith("- ") }) {
                Bullets(lines.map { it.trimStart().removePrefix("- ") })
            } else {
                Text(para.trim(), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun MonoBox(text: String, modifier: Modifier = Modifier) {
    Text(
        text.trim(),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp),
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RpePicker(value: Int?, onChange: (Int?) -> Unit, range: IntRange = 1..10) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        range.forEach { n ->
            FilterChip(
                selected = value == n,
                onClick = { onChange(if (value == n) null else n) },
                label = { Text("$n") },
            )
        }
    }
}

fun rpeMeaning(rpe: Int?): String = when (rpe) {
    null -> "Tap a number: 1 = nothing, 10 = absolute limit"
    in 1..3 -> "Easy"
    in 4..5 -> "Moderate"
    6 -> "Somewhat hard"
    7 -> "Hard - could do 3 more reps"
    8 -> "Very hard - 2 more reps in the tank"
    9 -> "Near max - 1 more rep at most"
    else -> "Absolute limit"
}

/** Quick log for sessions without sets: runs, climbing days, mobility. */
@Composable
fun LogSessionDialog(
    title: String,
    existing: WorkoutLog?,
    onDismiss: () -> Unit,
    onSave: (status: String, rpe: Int?, notes: String) -> Unit,
) {
    var status by remember { mutableStateOf(existing?.status?.takeIf { it == LogStatus.SKIPPED } ?: LogStatus.DONE) }
    var rpe by remember { mutableStateOf(existing?.rpe) }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(LogStatus.DONE to "Done", LogStatus.SKIPPED to "Skipped").forEachIndexed { i, (value, label) ->
                        SegmentedButton(
                            selected = status == value,
                            onClick = { status = value },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                        ) { Text(label) }
                    }
                }
                if (status == LogStatus.DONE) {
                    Text("How hard was it?", style = MaterialTheme.typography.labelLarge)
                    RpePicker(rpe, { rpe = it })
                    Text(rpeMeaning(rpe), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                    label = { Text(if (status == LogStatus.DONE) "Notes for your coach" else "Why skipped?") },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(status, if (status == LogStatus.DONE) rpe else null, notes) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
