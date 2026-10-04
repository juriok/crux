package com.rokjuric.crux.ui

import com.rokjuric.crux.data.Block
import com.rokjuric.crux.data.Exercise
import com.rokjuric.crux.data.SetLog
import com.rokjuric.crux.data.SetTarget
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

object Fmt {
    private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val longDayFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)

    fun num(v: Double): String = BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()

    /** "BW +10 kg", "60 kg", "10 kg assist" - or null when there is no load to show. */
    fun load(load: Double?, loadType: String): String? = when {
        loadType == "bodyweight" -> null
        loadType == "added" -> when {
            load == null || load == 0.0 -> "BW"
            load > 0 -> "BW +${num(load)} kg"
            else -> "BW −${num(abs(load))} kg"
        }
        load == null -> null
        loadType == "assisted" -> "${num(abs(load))} kg assist"
        else -> "${num(load)} kg"
    }

    fun loadWithNote(load: Double?, ex: Exercise): String? =
        load(load, ex.loadType)?.let { if (ex.loadNote.isNotBlank() && ex.loadType == "total") "$it ${ex.loadNote}" else it }

    fun work(t: SetTarget): String = when {
        t.seconds != null && t.amrap -> "max hold"
        t.seconds != null -> "${t.seconds} s"
        t.amrap && t.reps != null -> "${t.reps}+ reps"
        t.amrap -> "max reps"
        t.reps != null -> "${t.reps} reps"
        else -> "see note"
    }

    fun target(t: SetTarget, ex: Exercise): String = listOfNotNull(
        work(t),
        if (t.load == null && ex.loadType == "total") "your weight" else loadWithNote(t.load, ex),
        t.rpe?.let { "RPE ${num(it)}" },
    ).joinToString(" · ")

    /** One line per block: "4 × 5 reps · BW +10 kg". */
    fun block(b: Block, ex: Exercise): String {
        val sets = b.expanded
        if (sets.isEmpty()) return ""
        val works = sets.map { work(it) }
        val loads = sets.map { it.load }.distinct()
        val w = if (works.distinct().size == 1) "${sets.size} × ${works[0]}" else works.joinToString(", ")
        val l = if (loads.size == 1) loadWithNote(loads[0], ex) else "varied load"
        return listOfNotNull(w, l).joinToString(" · ")
    }

    fun setLog(s: SetLog): String {
        val work = when {
            s.seconds != null -> "${s.seconds} s"
            s.reps != null -> "${s.reps}"
            else -> "done"
        }
        val l = load(s.load, s.loadType)
        return if (l != null) "$work × $l" else work
    }

    fun day(date: String): String = runCatching { LocalDate.parse(date).format(dayFmt) }.getOrDefault(date)
    fun longDay(date: LocalDate): String = date.format(longDayFmt)

    fun clock(totalSeconds: Long): String {
        val s = abs(totalSeconds)
        val h = s / 3600
        val m = s % 3600 / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    fun pace(movingSeconds: Double?, meters: Double?): String? {
        if (movingSeconds == null || meters == null || meters < 100) return null
        val perKm = movingSeconds / (meters / 1000)
        return "%d:%02d /km".format((perKm / 60).toInt(), (perKm % 60).toInt())
    }

    fun ago(epochMs: Long, now: Long = System.currentTimeMillis()): String {
        val min = (now - epochMs) / 60_000
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 48 * 60 -> "${min / 60} h ago"
            else -> "${min / 1440} days ago"
        }
    }

    fun kindLabel(kind: String): String = when (kind) {
        "strength" -> "Gym"
        "run" -> "Run"
        "climb" -> "Climb"
        "mobility" -> "Mobility"
        "walk" -> "Walk"
        "hike" -> "Hike"
        "rest" -> "Rest"
        else -> kind.replaceFirstChar { it.uppercase() }
    }
}
