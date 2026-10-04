package com.rokjuric.crux.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class HttpResult(val code: Int, val body: String, val etag: String?) {
    val ok: Boolean get() = code in 200..299
}

class ApiException(message: String) : IOException(message)

/** Plain HttpURLConnection; the app makes a handful of small JSON calls. */
class Api(private val settings: Settings) {

    suspend fun request(
        method: String,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        auth: Boolean = true,
        baseUrl: String = settings.serverUrl,
    ): HttpResult = withContext(Dispatchers.IO) {
        val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/json")
            if (auth) conn.setRequestProperty("Authorization", "Bearer ${settings.token}")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else runCatching { conn.inputStream }.getOrNull()
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            HttpResult(code, text, conn.getHeaderField("ETag"))
        } finally {
            conn.disconnect()
        }
    }

    /** Human-readable reason from a FastAPI error body ({"detail": "..."}). */
    fun reason(r: HttpResult): String {
        val detail = Regex("\"detail\"\\s*:\\s*\"([^\"]*)\"").find(r.body)?.groupValues?.get(1)
        return "HTTP ${r.code}" + (detail?.let { ": $it" } ?: "")
    }
}
