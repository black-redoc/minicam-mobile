package dev.minicam.mobile.data

import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * REST client for the minicam endpoints the viewer needs
 * (docs/ARCHITECTURE.md "Data flow"). Admin endpoints live in AdminApi.kt.
 */
class MinicamApi(
    val config: ServerConfig,
    private val store: CredentialStore,
    client: OkHttpClient = defaultClient(),
) {
    // A redirect from a WAN host means Cloudflare Access wants a login; cookies
    // are managed explicitly through CredentialStore, not a cookie jar.
    val client: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    val hasSession: Boolean get() = store.get(CredentialKey.SESSION_COOKIE) != null

    suspend fun login(email: String, password: String): User {
        val body = json.encodeToString(LoginRequest.serializer(), LoginRequest(email, password))
            .toRequestBody(JSON)
        return send("POST", "/api/auth/login", body, isLogin = true) { response, text ->
            val cookie = Cookie.parseAll(response.request.url, response.headers)
                .firstOrNull { it.name == SESSION_COOKIE }
                ?: throw MinicamException.InvalidResponse
            store.set(CredentialKey.SESSION_COOKIE, cookie.value)
            decode(User.serializer(), text)
        }
    }

    suspend fun logout() {
        runCatching { send("POST", "/api/auth/logout", EMPTY) { _, _ -> } }
        store.clearSession()
    }

    suspend fun me(): User = send("GET", "/api/auth/me") { _, text -> decode(User.serializer(), text) }

    suspend fun cameras(): List<CameraSummary> = send("GET", "/api/me/cameras") { _, text ->
        decode(kotlinx.serialization.builtins.ListSerializer(CameraSummary.serializer()), text)
    }

    suspend fun streamToken(camera: String): String =
        send("POST", "/api/stream/${ServerConfig.encodePathSegment(camera)}/token", EMPTY) { _, text ->
            decode(StreamTokenResponse.serializer(), text).token
        }

    suspend fun ptz(camera: String, action: PtzAction) {
        send("POST", "/api/ptz/${ServerConfig.encodePathSegment(camera)}/${action.path}", EMPTY) { _, _ -> }
    }

    /** Request for the stream WebSocket handshake, carrying the same auth headers. */
    fun streamRequest(camera: String, token: String): Request =
        authorized(Request.Builder().url(config.streamUrl(camera, token))).build()

    /**
     * Headers every request to the server carries: the session cookie and, on WAN hosts, the
     * Access token. For clients that build their own requests on [client] (the video player).
     */
    fun authHeaders(): Map<String, String> = buildMap {
        store.get(CredentialKey.SESSION_COOKIE)?.let { put("Cookie", "$SESSION_COOKIE=$it") }
        if (config.isWan) store.get(CredentialKey.ACCESS_TOKEN)?.let { put(ACCESS_TOKEN_HEADER, it) }
    }

    // -- Plumbing ---------------------------------------------------------

    internal suspend fun <T> get(path: String, serializer: KSerializer<T>): T =
        send("GET", path) { _, text -> decode(serializer, text) }

    internal suspend fun <B, T> sendJson(
        method: String,
        path: String,
        bodySerializer: KSerializer<B>,
        body: B,
        serializer: KSerializer<T>,
    ): T = send(method, path, json.encodeToString(bodySerializer, body).toRequestBody(JSON)) { _, text ->
        decode(serializer, text)
    }

    internal suspend fun sendEmpty(method: String, path: String) {
        send(method, path, if (method == "DELETE") null else EMPTY) { _, _ -> }
    }

    /** Streams the body of `GET [path]` into [out]: for files too large to read as text. */
    internal suspend fun download(path: String, out: OutputStream) {
        val request = authorized(Request.Builder().url(config.url(path))).build()
        withContext(Dispatchers.IO) {
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) check(response.code, response.body.string(), isLogin = false)
                response.body.byteStream().copyTo(out)
            }
        }
    }

    private suspend fun <T> send(
        method: String,
        path: String,
        body: RequestBody? = null,
        isLogin: Boolean = false,
        parse: (Response, String) -> T,
    ): T {
        val request = authorized(
            Request.Builder()
                .url(config.url(path))
                .header("Accept", "application/json")
                .method(method, body),
        ).build()
        // Reading the body is blocking network I/O: never on the caller's
        // dispatcher, which is Main for viewModelScope (KI-6).
        return withContext(Dispatchers.IO) {
            client.newCall(request).await().use { response ->
                val text = response.body.string()
                check(response.code, text, isLogin)
                parse(response, text)
            }
        }
    }

    private fun authorized(builder: Request.Builder): Request.Builder {
        authHeaders().forEach { (name, value) -> builder.header(name, value) }
        return builder
    }

    private fun check(code: Int, text: String, isLogin: Boolean) {
        when (code) {
            in 200..299 -> return
            // Only Cloudflare Access redirects; minicam's JSON API never does.
            in 300..399 -> throw MinicamException.AccessRequired
            401 -> {
                if (isLogin) throw MinicamException.InvalidCredentials
                store.clearSession()
                throw MinicamException.Unauthorized
            }
            403 -> {
                val detail = detail(text)
                // Cloudflare's own 403 page is HTML, minicam's is JSON with `detail`.
                if (config.isWan && detail == null) throw MinicamException.AccessRequired
                throw MinicamException.Forbidden(detail ?: "Forbidden")
            }
            404 -> throw MinicamException.NotFound
            429 -> throw MinicamException.RateLimited
            // minicam's validation errors (400/409/502/503 …) explain themselves.
            else -> throw detail(text)?.let { MinicamException.Rejected(it) } ?: MinicamException.Server(code)
        }
    }

    private fun <T> decode(serializer: KSerializer<T>, text: String): T =
        runCatching { json.decodeFromString(serializer, text) }
            .getOrElse { throw MinicamException.InvalidResponse }

    private fun detail(text: String): String? =
        runCatching { json.decodeFromString(ErrorBody.serializer(), text).detail }.getOrNull()

    companion object {
        const val SESSION_COOKIE = "minicam_session"
        const val ACCESS_TOKEN_HEADER = "cf-access-token"
        private val JSON = "application/json".toMediaType()
        private val EMPTY = ByteArray(0).toRequestBody(null)
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder().build()
    }
}

/** Suspends on an OkHttp call; cancelling the coroutine cancels the call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) = continuation.resume(response)

            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }
        },
    )
}
