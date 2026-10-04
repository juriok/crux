package com.rokjuric.crux.workout

import com.rokjuric.crux.data.Session
import com.rokjuric.crux.data.SetTarget

/** One set to perform, in the order the workout runs. */
data class Step(
    val index: Int,
    val block: Int,
    val set: Int,
    val setCount: Int,
    val exerciseId: String,
    val target: SetTarget,
    val superset: String?,
)

/**
 * Flattens a session into the order sets are performed. Consecutive blocks
 * with the same superset label alternate round by round (A1, A2, A1, A2...);
 * everything else runs block by block.
 */
fun buildSteps(session: Session): List<Step> {
    val out = mutableListOf<Step>()
    val blocks = session.blocks
    var i = 0
    while (i < blocks.size) {
        val label = blocks[i].superset
        var j = i + 1
        if (label != null) {
            while (j < blocks.size && blocks[j].superset == label) j++
        }
        val group = i until j
        val rounds = group.maxOf { blocks[it].expanded.size }
        for (round in 0 until rounds) {
            for (b in group) {
                val sets = blocks[b].expanded
                if (round < sets.size) {
                    out += Step(out.size, b, round, sets.size, blocks[b].exercise, sets[round], label)
                }
            }
        }
        i = j
    }
    return out
}
