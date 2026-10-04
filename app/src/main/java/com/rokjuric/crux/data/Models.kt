package com.rokjuric.crux.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Lenient on input (the plan is hand-written by the coach), complete on output. */
val CruxJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
    isLenient = true
}

// ------------------------------------------------------------------ plan (server -> phone)

@Serializable
data class Plan(
    val version: Int = 0,
    @SerialName("published_at") val publishedAt: String? = null,
    val title: String = "",
    val goal: String = "",
    @SerialName("coach_notes") val coachNotes: String = "",
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    val exercises: Map<String, Exercise> = emptyMap(),
    val sessions: List<Session> = emptyList(),
) {
    fun exercise(id: String): Exercise = exercises[id] ?: Exercise(name = id)
    fun session(id: String): Session? = sessions.firstOrNull { it.id == id }
}

@Serializable
data class Exercise(
    val name: String,
    val cues: List<String> = emptyList(),
    /** total = barbell/dumbbell kg, added = bodyweight + kg, assisted = bodyweight - kg, bodyweight = no load */
    @SerialName("load_type") val loadType: String = "total",
    /** e.g. "per hand" for dumbbells */
    @SerialName("load_note") val loadNote: String = "",
    val notes: String = "",
)

@Serializable
data class Session(
    val id: String,
    val date: String,
    val kind: String = "strength",
    val title: String = "",
    val summary: String = "",
    @SerialName("duration_min") val durationMin: Int? = null,
    val warmup: List<String> = emptyList(),
    val blocks: List<Block> = emptyList(),
    val cooldown: List<String> = emptyList(),
    val run: RunSpec? = null,
    val notes: String = "",
    val optional: Boolean = false,
) {
    /** Sessions with sets to tick off get the guided workout; the rest are just logged. */
    val isGuided: Boolean get() = blocks.isNotEmpty()
}

@Serializable
data class Block(
    val exercise: String,
    val sets: List<SetTarget> = emptyList(),
    val superset: String? = null,
    val note: String = "",
) {
    val expanded: List<SetTarget>
        get() = sets.flatMap { s -> List(s.repeat.coerceIn(1, 20)) { s } }
}

@Serializable
data class SetTarget(
    val reps: Int? = null,
    val seconds: Int? = null,
    val load: Double? = null,
    @SerialName("rest_s") val restS: Int = 90,
    val rpe: Double? = null,
    /** As many reps as possible (or, with seconds, hold as long as possible). */
    val amrap: Boolean = false,
    val repeat: Int = 1,
    val note: String = "",
)

@Serializable
data class RunSpec(
    val type: String = "easy",
    val target: String = "",
    /** intervals.icu workout text; this is what reaches the watch. */
    val workout: String = "",
    @SerialName("distance_km") val distanceKm: Double? = null,
)

// ------------------------------------------------------------------ logs (phone -> server)

object LogStatus {
    const val IN_PROGRESS = "in_progress"
    const val DONE = "done"
    const val PARTIAL = "partial"
    const val SKIPPED = "skipped"
}

@Serializable
data class WorkoutLog(
    val id: String,
    val date: String,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("plan_version") val planVersion: Int? = null,
    val title: String = "",
    val kind: String = "strength",
    @SerialName("started_at") val startedAt: String,
    @SerialName("finished_at") val finishedAt: String? = null,
    val status: String = LogStatus.IN_PROGRESS,
    val rpe: Int? = null,
    val notes: String = "",
    val sets: List<SetLog> = emptyList(),
    val app: String = "crux-android",
)

@Serializable
data class SetLog(
    val exercise: String,
    val name: String = "",
    @SerialName("load_type") val loadType: String = "total",
    val block: Int,
    val set: Int,
    val reps: Int? = null,
    val seconds: Int? = null,
    val load: Double? = null,
    val rpe: Double? = null,
    val done: Boolean = true,
    val skipped: Boolean = false,
    @SerialName("target_reps") val targetReps: Int? = null,
    @SerialName("target_seconds") val targetSeconds: Int? = null,
    @SerialName("target_load") val targetLoad: Double? = null,
    @SerialName("logged_at") val loggedAt: String = "",
)

// ------------------------------------------------------------------ intervals.icu (via server)

@Serializable
data class Wellness(
    /** the date, YYYY-MM-DD */
    val id: String,
    val sleepSecs: Double? = null,
    val sleepScore: Double? = null,
    val avgSleepingHR: Double? = null,
    val restingHR: Double? = null,
    val hrv: Double? = null,
    val weight: Double? = null,
    val vo2max: Double? = null,
    val steps: Double? = null,
)

@Serializable
data class Activity(
    @SerialName("start_date_local") val start: String = "",
    val type: String = "",
    val name: String = "",
    @SerialName("moving_time") val movingTime: Double? = null,
    val distance: Double? = null,
    @SerialName("average_heartrate") val avgHr: Double? = null,
    @SerialName("max_heartrate") val maxHr: Double? = null,
    @SerialName("icu_training_load") val load: Double? = null,
    @SerialName("total_elevation_gain") val elevation: Double? = null,
) {
    val date: String get() = start.take(10)
    val isRun: Boolean get() = type in setOf("Run", "TrailRun", "VirtualRun")
}
