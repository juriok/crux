package com.rokjuric.crux

import com.rokjuric.crux.data.CruxJson
import com.rokjuric.crux.data.LogStatus
import com.rokjuric.crux.data.Plan
import com.rokjuric.crux.data.SetLog
import com.rokjuric.crux.data.SetTarget
import com.rokjuric.crux.data.WorkoutLog
import com.rokjuric.crux.ui.Fmt
import com.rokjuric.crux.workout.buildSteps
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The plan JSON the coach writes and the log JSON the server stores must round-trip through the app. */
class ContractTest {
    private val plan: Plan by lazy {
        CruxJson.decodeFromString<Plan>(File("../coach/plans/2026-10-02-baseline.json").readText())
    }

    @Test
    fun decodesThePublishedPlan() {
        assertEquals(5, plan.sessions.size)
        val pull = plan.session("b0-pull-test")!!
        assertTrue(pull.isGuided)
        assertEquals("added", plan.exercise("pullup").loadType)
        assertTrue(pull.blocks[0].sets[0].amrap)
        assertEquals(60, pull.blocks[2].sets[0].seconds)
        assertEquals(3, plan.session("b0-full-body")!!.blocks[0].expanded.size)
        assertNotNull(plan.session("b0-easy-run")!!.run?.workout)
        assertEquals("per hand", plan.exercise("db_press").loadNote)
    }

    @Test
    fun unknownFieldsAndNullsAreTolerated() {
        val p = CruxJson.decodeFromString<Plan>(
            """{"title":"x","future_field":1,"sessions":[{"id":"a","date":"2026-01-01","title":"t","rest_s":null,
               "blocks":[{"exercise":"e","sets":[{"reps":5,"rest_s":null,"new":true}]}]}]}"""
        )
        assertEquals(90, p.sessions[0].blocks[0].sets[0].restS)
        assertEquals("e", p.exercise("e").name)
    }

    @Test
    fun supersetsAlternateAndEverythingElseRunsInOrder() {
        val steps = buildSteps(plan.session("b0-full-body")!!)
        // goblet 3 + rdl 3 + (row/press) 6 + carry 3 + rotation 2
        assertEquals(17, steps.size)
        assertEquals(steps.indices.toList(), steps.map { it.index })
        assertEquals(
            listOf("db_row", "db_press", "db_row", "db_press", "db_row", "db_press"),
            steps.filter { it.superset == "A" }.map { it.exerciseId },
        )
        assertEquals(listOf(0, 0, 1, 1, 2, 2), steps.filter { it.superset == "A" }.map { it.set })
        assertEquals("band_external_rotation", steps.last().exerciseId)
    }

    @Test
    fun formatsLoadsAndTargets() {
        assertEquals("BW +10 kg", Fmt.load(10.0, "added"))
        assertEquals("BW", Fmt.load(0.0, "added"))
        assertEquals("62.5 kg", Fmt.load(62.5, "total"))
        assertEquals("10 kg assist", Fmt.load(10.0, "assisted"))
        assertNull(Fmt.load(5.0, "bodyweight"))
        assertEquals("10 reps · your weight · RPE 7", Fmt.target(SetTarget(reps = 10, rpe = 7.0), plan.exercise("db_press")))
        assertEquals("max hold · BW", Fmt.target(SetTarget(seconds = 60, amrap = true, load = 0.0), plan.exercise("dead_hang")))
        assertEquals("max reps · BW", Fmt.target(SetTarget(amrap = true, load = 0.0), plan.exercise("pullup")))
        assertEquals("5 × 3 reps · varied load", Fmt.block(plan.session("b0-pull-test")!!.blocks[1], plan.exercise("weighted_pullup")))
        assertEquals("1:02:03", Fmt.clock(3723))
        assertEquals("5:00 /km", Fmt.pace(1500.0, 5000.0))
    }

    @Test
    fun logRoundTripsAndIsWrittenForTheServerTest() {
        val log = WorkoutLog(
            id = "contract-test-log", date = "2026-10-01", sessionId = "b0-pull-test", planVersion = 1,
            title = "Pull test day", kind = "strength",
            startedAt = "2026-10-01T18:00:00+02:00", finishedAt = "2026-10-01T18:52:00+02:00",
            status = LogStatus.PARTIAL, rpe = 8, notes = "contract test - delete me",
            sets = listOf(
                SetLog("pullup", "Pull-up", "added", block = 0, set = 0, reps = 9, load = 0.0, loggedAt = "2026-10-01T18:05:00+02:00"),
                SetLog("weighted_pullup", "Weighted pull-up", "added", block = 1, set = 0, reps = 3, load = 5.0, rpe = 6.0,
                    targetReps = 3, targetLoad = 5.0, loggedAt = "2026-10-01T18:10:00+02:00"),
                SetLog("weighted_pullup", "Weighted pull-up", "added", block = 1, set = 1, reps = 3, load = 10.0, rpe = 8.0,
                    targetReps = 3, targetLoad = 10.0, loggedAt = "2026-10-01T18:14:00+02:00"),
                SetLog("weighted_pullup", "Weighted pull-up", "added", block = 1, set = 2, done = false, skipped = true,
                    targetReps = 3, targetLoad = 15.0, loggedAt = "2026-10-01T18:15:00+02:00"),
                SetLog("dead_hang", "Dead hang", "added", block = 2, set = 0, seconds = 47, load = 0.0,
                    targetSeconds = 60, loggedAt = "2026-10-01T18:20:00+02:00"),
            ),
        )
        val json = CruxJson.encodeToString(log)
        assertEquals(log, CruxJson.decodeFromString<WorkoutLog>(json))
        File("build").mkdirs()
        File("build/sample-log.json").writeText(json)
    }
}
