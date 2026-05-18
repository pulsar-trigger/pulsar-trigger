/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.transport.ccapi

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Thin HTTP client for Canon CCAPI. Wraps `HttpURLConnection` to keep the
 * dependency footprint small — no OkHttp; digest auth (RFC 7616) is
 * hand-rolled below.
 *
 * Versioning: at connect time we call `GET /ccapi` and pick the highest
 * supported version. The pinned [version] is then prefixed onto every
 * subsequent request, so callers say `post("/shooting/control/shutterbutton")`
 * and the client emits `…/ccapi/ver110/shooting/control/shutterbutton`.
 *
 * Auth: pass [Credentials] to the constructor when the camera is configured
 * with an account. The client sends the first request unauthenticated, parses
 * the `WWW-Authenticate: Digest …` challenge from the 401, and retries with
 * the computed response. The challenge is cached so subsequent requests
 * preflight the Authorization header (saving a round-trip per call). MD5,
 * MD5-sess, SHA-256, and SHA-256-sess are all supported.
 */
class CcapiClient(
    /** From `CameraDescription` — e.g. `http://192.168.1.2:8080/ccapi/` */
    private val baseAccessUrl: String,
    private val credentials: Credentials? = null,
) {
    companion object {
        private const val TAG = "CcapiClient"
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val READ_TIMEOUT_MS = 5_000
        /** Versions in descending preference. */
        private val PREFERRED_VERSIONS = listOf(
            "ver140", "ver130", "ver120", "ver110", "ver100",
        )
    }

    data class Credentials(val username: String, val password: String)

    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data class Http(val code: Int, val body: String) : Result<Nothing>
        data object NeedsAuth : Result<Nothing>
        data class Network(val cause: Throwable) : Result<Nothing>
    }

    /** Pinned API version, set after [connect]. */
    var version: String? = null
        private set

    /** Cached `/ccapi` endpoint list once we've connected. */
    var endpoints: Map<String, JSONArray> = emptyMap()
        private set

    private val rootUrl: String = baseAccessUrl.trimEnd('/')

    /** Cached digest challenge; populated on first 401 so subsequent requests
     *  can pre-authenticate. */
    @Volatile private var digestChallenge: DigestChallenge? = null
    @Volatile private var digestNonceCount: Int = 0

    suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        when (val r = rawGet(rootUrl)) {
            is Result.Ok -> {
                val obj = try { JSONObject(r.value) }
                          catch (e: Exception) {
                              return@withContext Result.Network(e)
                          }
                val available = mutableMapOf<String, JSONArray>()
                obj.keys().forEach { key ->
                    obj.optJSONArray(key)?.let { available[key] = it }
                }
                val pinned = PREFERRED_VERSIONS.firstOrNull { it in available.keys }
                if (pinned == null) {
                    Log.w(TAG, "No supported version in /ccapi response: ${available.keys}")
                    return@withContext Result.Network(IllegalStateException("no supported CCAPI version"))
                }
                version = pinned
                endpoints = available
                Log.i(TAG, "Connected to CCAPI, pinned $pinned (${available[pinned]?.length()} endpoints)")
                Result.Ok(Unit)
            }
            is Result.Http -> r
            is Result.NeedsAuth -> r
            is Result.Network -> r
        }
    }

    /**
     * Does this body advertise the given endpoint with the requested HTTP
     * verb? `path` is relative to `/ccapi/<version>` (e.g.
     * `/shooting/control/shutterbutton/manual`).
     *
     * Canon's actual `/ccapi` matrix entries look like
     * `{"url": "http://ip:port/ccapi/ver100/...", "get": bool, "post": bool,
     * "put": bool, "delete": bool}` — one boolean per verb rather than a
     * single `method` string. We extract the path component of the `url`
     * and check the boolean for the requested verb.
     */
    fun supports(path: String, method: String = "post"): Boolean {
        val arr = endpoints[version ?: return false] ?: return false
        val target = "/ccapi/$version$path"
        val verb = method.lowercase()
        for (i in 0 until arr.length()) {
            val ep = arr.optJSONObject(i) ?: continue
            val url = ep.optString("url")
            val pathPart = runCatching { URL(url).path }.getOrNull() ?: url
            if (pathPart != target) continue
            return ep.optBoolean(verb, false)
        }
        return false
    }

    suspend fun post(path: String, body: JSONObject? = null): Result<String> =
        withContext(Dispatchers.IO) {
            val ver = version ?: return@withContext Result.Network(
                IllegalStateException("CcapiClient not connected; call connect() first"))
            sendWithDigest("POST", "$rootUrl/$ver$path", body?.toString())
        }

    suspend fun put(path: String, body: JSONObject? = null): Result<String> =
        withContext(Dispatchers.IO) {
            val ver = version ?: return@withContext Result.Network(
                IllegalStateException("CcapiClient not connected; call connect() first"))
            sendWithDigest("PUT", "$rootUrl/$ver$path", body?.toString())
        }

    suspend fun get(path: String): Result<String> =
        withContext(Dispatchers.IO) {
            val ver = version ?: return@withContext Result.Network(
                IllegalStateException("CcapiClient not connected; call connect() first"))
            rawGet("$rootUrl/$ver$path")
        }

    private fun rawGet(url: String): Result<String> = sendWithDigest("GET", url, null)

    /** One-or-two attempt request handler. First send goes out with whatever
     *  Authorization header we can derive from a cached challenge (or none).
     *  On 401: if [credentials] are set, parse the fresh challenge and retry —
     *  but only once. Without credentials, a 401 returns [Result.NeedsAuth]. */
    private fun sendWithDigest(method: String, url: String, jsonBody: String?): Result<String> {
        val first = sendOnce(method, url, jsonBody, authHeader(method, url))
        if (first !is Attempt.NeedsAuth) return first.toResult()
        if (credentials == null) return Result.NeedsAuth
        val challenge = first.challenge ?: return Result.NeedsAuth
        digestChallenge = challenge
        digestNonceCount = 0
        return sendOnce(method, url, jsonBody, authHeader(method, url)).toResult()
    }

    private fun authHeader(method: String, url: String): String? {
        val challenge = digestChallenge ?: return null
        val creds = credentials ?: return null
        val nc = (++digestNonceCount).coerceAtLeast(1)
        return buildDigestHeader(challenge, creds, method, url, nc)
    }

    /** Single HTTP round-trip. Internal envelope so we can return either a
     *  normal result or a 401 with the parsed challenge attached. */
    private sealed interface Attempt {
        data class Ok(val body: String) : Attempt
        data class Http(val code: Int, val body: String) : Attempt
        data class NeedsAuth(val challenge: DigestChallenge?) : Attempt
        data class Network(val cause: Throwable) : Attempt
    }

    private fun Attempt.toResult(): Result<String> = when (this) {
        is Attempt.Ok -> Result.Ok(body)
        is Attempt.Http -> Result.Http(code, body)
        is Attempt.NeedsAuth -> Result.NeedsAuth
        is Attempt.Network -> Result.Network(cause)
    }

    private fun sendOnce(
        method: String,
        url: String,
        jsonBody: String?,
        authorization: String?,
    ): Attempt {
        val conn = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = method
                doInput = true
                if (jsonBody != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                if (authorization != null) {
                    setRequestProperty("Authorization", authorization)
                }
            }
        } catch (e: Exception) {
            return Attempt.Network(e)
        }
        return try {
            if (jsonBody != null) {
                conn.outputStream.use { it.write(jsonBody.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            when {
                code in 200..299 -> Attempt.Ok(text)
                code == 401 -> {
                    val auth = conn.getHeaderField("WWW-Authenticate")
                    Attempt.NeedsAuth(auth?.let { parseDigestChallenge(it) })
                }
                else -> {
                    Log.w(TAG, "HTTP $code from $url: $text")
                    Attempt.Http(code, text)
                }
            }
        } catch (e: Exception) {
            Attempt.Network(e)
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    // ── Digest auth (RFC 7616) ───────────────────────────────────────────

    private data class DigestChallenge(
        val realm: String,
        val nonce: String,
        val qop: String?,        // comma-separated; we pick "auth" when present
        val opaque: String?,
        val algorithm: String,   // MD5, MD5-sess, SHA-256, SHA-256-sess
    )

    private fun parseDigestChallenge(header: String): DigestChallenge? {
        val trimmed = header.trim()
        val prefix = "Digest "
        if (!trimmed.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) return null
        val params = parseQuotedKv(trimmed.substring(prefix.length))
        val realm = params["realm"] ?: return null
        val nonce = params["nonce"] ?: return null
        return DigestChallenge(
            realm = realm,
            nonce = nonce,
            qop = params["qop"],
            opaque = params["opaque"],
            algorithm = params["algorithm"] ?: "MD5",
        )
    }

    /** Tolerant `k=v[,k="v with spaces"]` parser for WWW-Authenticate. */
    private fun parseQuotedKv(s: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        var i = 0
        while (i < s.length) {
            while (i < s.length && (s[i] == ' ' || s[i] == ',')) i++
            if (i >= s.length) break
            val keyStart = i
            while (i < s.length && s[i] != '=' && s[i] != ',') i++
            if (i >= s.length || s[i] == ',') { i++; continue }
            val key = s.substring(keyStart, i).trim()
            i++  // skip '='
            val value: String
            if (i < s.length && s[i] == '"') {
                i++
                val vs = i
                while (i < s.length && s[i] != '"') i++
                value = s.substring(vs, i)
                if (i < s.length) i++
            } else {
                val vs = i
                while (i < s.length && s[i] != ',') i++
                value = s.substring(vs, i).trim()
            }
            if (key.isNotEmpty()) out[key] = value
        }
        return out
    }

    private fun buildDigestHeader(
        ch: DigestChallenge,
        creds: Credentials,
        method: String,
        fullUrl: String,
        nc: Int,
    ): String {
        // The digest URI is the request-target — path + query, no scheme/host.
        val u = URL(fullUrl)
        val pathQ = (u.path ?: "/") + (u.query?.let { "?$it" } ?: "")
        val algo = ch.algorithm.uppercase()
        val useSha256 = algo.startsWith("SHA-256")
        val sessionVariant = algo.endsWith("-SESS")
        val hash: (String) -> String = if (useSha256) ::sha256Hex else ::md5Hex

        val cnonce = randomHex(16)
        val ncHex = "%08x".format(nc)

        val ha1Base = hash("${creds.username}:${ch.realm}:${creds.password}")
        val ha1 = if (sessionVariant) hash("$ha1Base:${ch.nonce}:$cnonce") else ha1Base
        val ha2 = hash("$method:$pathQ")

        val qop = ch.qop
            ?.split(",")
            ?.map { it.trim() }
            ?.firstOrNull { it.equals("auth", ignoreCase = true) }
        val response = if (qop != null) {
            hash("$ha1:${ch.nonce}:$ncHex:$cnonce:$qop:$ha2")
        } else {
            hash("$ha1:${ch.nonce}:$ha2")
        }

        return buildString {
            append("Digest ")
            append("username=\"${creds.username}\", ")
            append("realm=\"${ch.realm}\", ")
            append("nonce=\"${ch.nonce}\", ")
            append("uri=\"$pathQ\", ")
            append("response=\"$response\"")
            if (qop != null) {
                append(", qop=$qop, nc=$ncHex, cnonce=\"$cnonce\"")
            }
            ch.opaque?.let { append(", opaque=\"$it\"") }
            if (!ch.algorithm.equals("MD5", ignoreCase = true)) {
                append(", algorithm=${ch.algorithm}")
            }
        }
    }

    private fun md5Hex(input: String): String = hashHex("MD5", input)
    private fun sha256Hex(input: String): String = hashHex("SHA-256", input)

    private fun hashHex(algorithm: String, input: String): String {
        val bytes = MessageDigest.getInstance(algorithm).digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun randomHex(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
