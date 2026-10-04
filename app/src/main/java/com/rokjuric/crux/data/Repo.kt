package com.rokjuric.crux.data

import android.content.Context
import com.rokjuric.crux.sync.SyncWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import java.io.IOException

data class SyncState(
    val running: Boolean = false,
    val error: String? = null,
)

/**
 * Offline-first: the screens only ever read Room. [refresh] moves data between
 * Room and the server - logs up, plan and watch data down - whenever there is a
 * connection.
 */
class Repo(private val context: Context, private val db: CruxDb, val settings: Settings) {
    private val api = Api(settings)
    private val syncLock = Mutex()

    private val _sync = MutableStateFlow(SyncState())
    val sync: StateFlow<SyncState> = _sync.asStateFlow()

    val plan: Flow<Plan?> = kvFlow(KEY_PLAN) { CruxJson.decodeFromString<Plan>(it) }
    val wellness: Flow<List<Wellness>> = kvFlow(KEY_WELLNESS) { CruxJson.decodeFromString<List<Wellness>>(it) }
        .map { it ?: emptyList() }
    val activities: Flow<List<Activity>> = kvFlow(KEY_ACTIVITIES) { CruxJson.decodeFromString<List<Activity>>(it) }
        .map { it ?: emptyList() }
    val icuConfigured: Flow<Boolean?> = kvFlow(KEY_ICU) { it == "1" }
    val lastSync: Flow<Long?> = kvFlow(KEY_LAST_SYNC) { it.toLong() }

    val logs: Flow<List<WorkoutLog>> = db.logs().observeAll().map { rows -> rows.mapNotNull { decodeLog(it.json) } }
    val unsyncedCount: Flow<Int> = db.logs().observeUnsyncedCount()

    private fun <T> kvFlow(key: String, parse: (String) -> T): Flow<T?> =
        db.kv().observe(key).map { row -> row?.value?.let { runCatching { parse(it) }.getOrNull() } }
            .distinctUntilChanged()

    private fun decodeLog(json: String): WorkoutLog? =
        runCatching { CruxJson.decodeFromString<WorkoutLog>(json) }.getOrNull()

    private suspend fun putKv(key: String, value: String) =
        db.kv().put(KvEntity(key, value, System.currentTimeMillis()))

    // ------------------------------------------------------------------ logs

    suspend fun activeLog(): WorkoutLog? = db.logs().active()?.let { decodeLog(it.json) }

    suspend fun getLog(id: String): WorkoutLog? = db.logs().get(id)?.let { decodeLog(it.json) }

    suspend fun saveLog(log: WorkoutLog) {
        db.logs().put(
            LogEntity(
                id = log.id, date = log.date, sessionId = log.sessionId, status = log.status,
                json = CruxJson.encodeToString(log), synced = false, updatedAt = System.currentTimeMillis(),
            )
        )
        if (log.status != LogStatus.IN_PROGRESS) SyncWorker.enqueue(context)
    }

    /** Drops an unfinished workout. It never reached the server, so this is local only. */
    suspend fun discardLog(id: String) {
        db.logs().delete(id)
        db.kv().delete(snapshotKey(id))
    }

    /** Deletes a finished log on the server first, so it cannot come back on the next sync. */
    suspend fun deleteLog(id: String): Result<Unit> = runCatching {
        if (settings.token.isNotBlank()) {
            val r = api.request("DELETE", "/api/logs/$id")
            if (!r.ok) throw ApiException(api.reason(r))
        }
        db.logs().delete(id)
        db.kv().delete(snapshotKey(id))
    }

    // A copy of the session (and its exercises) a workout was started from, so a
    // plan update mid-workout cannot pull the exercises out from under it.
    private fun snapshotKey(logId: String) = "snapshot:$logId"

    suspend fun saveSnapshot(logId: String, mini: Plan) =
        putKv(snapshotKey(logId), CruxJson.encodeToString(mini))

    suspend fun snapshot(logId: String): Plan? =
        db.kv().get(snapshotKey(logId))?.value?.let { runCatching { CruxJson.decodeFromString<Plan>(it) }.getOrNull() }

    // ------------------------------------------------------------------ server

    suspend fun pair(serverUrl: String, code: String): Result<Unit> = runCatching {
        val url = serverUrl.trim().trimEnd('/')
        val r = api.request("POST", "/api/pair", """{"code":"${code.filter { it.isDigit() }}"}""", auth = false, baseUrl = url)
        if (!r.ok) throw ApiException(api.reason(r))
        val token = Regex("\"token\"\\s*:\\s*\"([^\"]+)\"").find(r.body)?.groupValues?.get(1)
            ?: throw ApiException("server sent no token")
        settings.setServerUrl(url)
        settings.setToken(token)
        refresh()
    }

    suspend fun refresh(): Result<Unit> = syncLock.withLock {
        if (settings.token.isBlank()) {
            return Result.failure(ApiException("Not paired with a server yet"))
        }
        _sync.value = _sync.value.copy(running = true)
        val result = runCatching {
            uploadLogs()
            pullPlan()
            pullIcu()
            pullServerLogs()
            putKv(KEY_LAST_SYNC, System.currentTimeMillis().toString())
        }
        _sync.value = SyncState(running = false, error = result.exceptionOrNull()?.let { describe(it) })
        result
    }

    private fun describe(e: Throwable): String = when (e) {
        is ApiException -> e.message ?: "server error"
        is IOException -> "No connection to the server"
        else -> e.message ?: e.javaClass.simpleName
    }

    private suspend fun uploadLogs() {
        val pending = db.logs().unsynced()
        if (pending.isEmpty()) return
        val body = pending.joinToString(",", "[", "]") { it.json }
        val r = api.request("POST", "/api/logs", body)
        if (!r.ok) throw ApiException("Upload failed - ${api.reason(r)}")
        pending.forEach { db.logs().markSynced(it.id, it.updatedAt) }
    }

    private suspend fun pullPlan() {
        val etag = db.kv().get(KEY_PLAN_ETAG)?.value
        val headers = if (etag != null && db.kv().get(KEY_PLAN) != null) mapOf("If-None-Match" to etag) else emptyMap()
        val r = api.request("GET", "/api/plan", headers = headers)
        when {
            r.code == 304 || r.code == 404 -> Unit
            r.ok -> {
                CruxJson.decodeFromString<Plan>(r.body) // refuse to store something we cannot read
                putKv(KEY_PLAN, r.body)
                r.etag?.let { putKv(KEY_PLAN_ETAG, it) }
            }
            else -> throw ApiException(api.reason(r))
        }
    }

    private suspend fun pullIcu() {
        val w = api.request("GET", "/api/wellness?days=14")
        if (w.code == 409) {
            putKv(KEY_ICU, "0")
            return
        }
        if (w.ok) {
            putKv(KEY_WELLNESS, w.body)
            putKv(KEY_ICU, "1")
        }
        val a = api.request("GET", "/api/activities?days=42")
        if (a.ok) putKv(KEY_ACTIVITIES, a.body)
        // 502 = server up but intervals.icu unreachable; keep the cached copies.
    }

    /** After a reinstall, history comes back from the server. */
    private suspend fun pullServerLogs() {
        val r = api.request("GET", "/api/logs?days=365")
        if (!r.ok) return
        val known = db.logs().allIds().toSet()
        val remote = runCatching { CruxJson.decodeFromString<List<WorkoutLog>>(r.body) }.getOrNull() ?: return
        for (log in remote) {
            if (log.id in known) continue
            db.logs().put(
                LogEntity(
                    id = log.id, date = log.date, sessionId = log.sessionId, status = log.status,
                    json = CruxJson.encodeToString(log), synced = true, updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    suspend fun testConnection(url: String): Result<String> = runCatching {
        val r = api.request("GET", "/api/status", auth = false, baseUrl = url.trim().trimEnd('/'))
        if (!r.ok) throw ApiException(api.reason(r))
        r.body
    }

    companion object {
        private const val KEY_PLAN = "plan"
        private const val KEY_PLAN_ETAG = "plan_etag"
        private const val KEY_WELLNESS = "wellness"
        private const val KEY_ACTIVITIES = "activities"
        private const val KEY_ICU = "icu_configured"
        private const val KEY_LAST_SYNC = "last_sync"
    }
}
