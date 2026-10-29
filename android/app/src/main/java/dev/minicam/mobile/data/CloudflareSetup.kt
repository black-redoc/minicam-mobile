package dev.minicam.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// Cloudflare side of "Remote access" (docs/adr/0009): with an API token the
// admin pastes (kept only in memory, never stored, logged or sent to minicam)
// create the tunnel, its route, the DNS record and the Access application,
// and return what minicam needs (TunnelSetup). Every step reuses what an
// earlier, interrupted run already created.

sealed class CloudflareException(message: String) : Exception(message) {
    /** Cloudflare refused the request; its own messages, joined. */
    class Api(message: String) : CloudflareException(message)

    /** The account has no Zero Trust organization (needed for Access). */
    data object ZeroTrustRequired : CloudflareException("Zero Trust is not enabled")

    data object InvalidResponse : CloudflareException("Unexpected response from Cloudflare")
}

@Serializable
data class CloudflareAccount(val id: String, val name: String)

@Serializable
data class CloudflareZone(val id: String, val name: String)

enum class CloudflareStep { ZERO_TRUST, TUNNEL, ROUTE, DNS, ACCESS }

/** Thin client for the parts of https://api.cloudflare.com/client/v4 the setup uses. */
class CloudflareClient(
    private val token: String,
    private val client: OkHttpClient = MinicamApi.defaultClient(),
    private val baseUrl: HttpUrl = BASE_URL.toHttpUrl(),
) {
    @Serializable
    private data class Message(val message: String)

    @Serializable
    private data class Envelope(
        val success: Boolean,
        val errors: List<Message>? = null,
        val result: JsonElement? = null,
    )

    @Serializable
    internal data class Identified(val id: String, val name: String? = null)

    @Serializable
    private data class Organization(@kotlinx.serialization.SerialName("auth_domain") val authDomain: String? = null)

    @Serializable
    private data class Record(val id: String, val content: String, val proxied: Boolean? = null)

    @Serializable
    private data class App(val id: String, val domain: String? = null, val aud: String? = null)

    override fun toString() = "CloudflareClient(token=…)"

    internal suspend fun <T> request(
        method: String,
        path: String,
        serializer: KSerializer<T>,
        query: List<Pair<String, String>> = emptyList(),
        body: JsonObject? = null,
    ): T {
        val url = baseUrl.newBuilder().addPathSegments(path).apply {
            query.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .method(method, body?.toString()?.toRequestBody(JSON))
            .build()
        val (code, text) = withContext(Dispatchers.IO) {
            client.newCall(request).await().use { it.code to it.body.string() }
        }
        val envelope = runCatching { json.decodeFromString(Envelope.serializer(), text) }
            .getOrElse { throw CloudflareException.InvalidResponse }
        if (!envelope.success || code !in 200..299) {
            val messages = envelope.errors.orEmpty().joinToString("; ") { it.message }
            throw CloudflareException.Api(messages.ifEmpty { "HTTP $code" })
        }
        val result = envelope.result ?: throw CloudflareException.InvalidResponse
        return runCatching { json.decodeFromJsonElement(serializer, result) }
            .getOrElse { throw CloudflareException.InvalidResponse }
    }

    suspend fun accounts(): List<CloudflareAccount> =
        request("GET", "accounts", ListSerializer(CloudflareAccount.serializer()), listOf("per_page" to "50"))

    suspend fun zones(accountId: String): List<CloudflareZone> = request(
        "GET",
        "zones",
        ListSerializer(CloudflareZone.serializer()),
        listOf("account.id" to accountId, "per_page" to "50"),
    )

    /** `https://<team>.cloudflareaccess.com`, or [CloudflareException.ZeroTrustRequired]. */
    suspend fun teamDomain(accountId: String): String {
        val organization = try {
            request("GET", "accounts/$accountId/access/organizations", Organization.serializer())
        } catch (_: CloudflareException.Api) {
            throw CloudflareException.ZeroTrustRequired
        }
        val domain = organization.authDomain
        if (domain.isNullOrEmpty()) throw CloudflareException.ZeroTrustRequired
        return "https://$domain"
    }

    /** The tunnel's id, reusing a live tunnel with the same name. */
    internal suspend fun tunnel(accountId: String, name: String): String {
        val existing = request(
            "GET",
            "accounts/$accountId/cfd_tunnel",
            ListSerializer(Identified.serializer()),
            listOf("name" to name, "is_deleted" to "false"),
        )
        existing.firstOrNull { it.name == name }?.let { return it.id }
        val body = buildJsonObject {
            put("config_src", "cloudflare")
            put("name", name)
        }
        return request("POST", "accounts/$accountId/cfd_tunnel", Identified.serializer(), body = body).id
    }

    internal suspend fun tunnelToken(accountId: String, tunnelId: String): String =
        request("GET", "accounts/$accountId/cfd_tunnel/$tunnelId/token", String.serializer())

    /** [hostname] → minicam on the device; anything else → 404. */
    internal suspend fun route(accountId: String, tunnelId: String, hostname: String) {
        val body = buildJsonObject {
            putJsonObject("config") {
                putJsonArray("ingress") {
                    addJsonObject {
                        put("hostname", hostname)
                        put("service", "http://127.0.0.1:8080")
                    }
                    addJsonObject { put("service", "http_status:404") }
                }
            }
        }
        request("PUT", "accounts/$accountId/cfd_tunnel/$tunnelId/configurations", JsonElement.serializer(), body = body)
    }

    internal suspend fun dnsRecord(zoneId: String, hostname: String, tunnelId: String) {
        val target = "$tunnelId.cfargotunnel.com"
        val existing = request(
            "GET",
            "zones/$zoneId/dns_records",
            ListSerializer(Record.serializer()),
            listOf("type" to "CNAME", "name" to hostname),
        )
        val body = buildJsonObject {
            put("comment", "minicam tunnel")
            put("content", target)
            put("name", hostname)
            put("proxied", true)
            put("type", "CNAME")
        }
        val record = existing.firstOrNull()
        when {
            record == null -> request("POST", "zones/$zoneId/dns_records", Identified.serializer(), body = body)
            record.content == target && record.proxied == true -> return
            else -> request("PATCH", "zones/$zoneId/dns_records/${record.id}", Identified.serializer(), body = body)
        }
    }

    /** A reusable Access policy allowing [email], reused by name. */
    internal suspend fun accessPolicy(accountId: String, name: String, email: String): String {
        val existing = request("GET", "accounts/$accountId/access/policies", ListSerializer(Identified.serializer()))
        val body = buildJsonObject {
            put("decision", "allow")
            putJsonArray("include") {
                addJsonObject { putJsonObject("email") { put("email", email) } }
            }
            put("name", name)
        }
        val policy = existing.firstOrNull { it.name == name }
            ?: return request("POST", "accounts/$accountId/access/policies", Identified.serializer(), body = body).id
        request("PUT", "accounts/$accountId/access/policies/${policy.id}", Identified.serializer(), body = body)
        return policy.id
    }

    /** The self-hosted Access application for [hostname]; returns its AUD tag. */
    internal suspend fun accessApplication(accountId: String, hostname: String, policyId: String): String {
        val existing = request("GET", "accounts/$accountId/access/apps", ListSerializer(App.serializer()))
        val body = buildJsonObject {
            put("domain", hostname)
            put("name", "minicam $hostname")
            putJsonArray("policies") {
                addJsonObject {
                    put("id", policyId)
                    put("precedence", 1)
                }
            }
            put("session_duration", "24h")
            put("type", "self_hosted")
        }
        val current = existing.firstOrNull { it.domain == hostname }
        val app = if (current == null) {
            request("POST", "accounts/$accountId/access/apps", App.serializer(), body = body)
        } else {
            request("PUT", "accounts/$accountId/access/apps/${current.id}", App.serializer(), body = body)
        }
        return app.aud?.takeIf { it.isNotEmpty() } ?: throw CloudflareException.InvalidResponse
    }

    companion object {
        const val BASE_URL = "https://api.cloudflare.com/client/v4/"
        private val JSON = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }
    }
}

object TunnelHostname {
    private val LABEL = Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?")

    /** `<label>.<zone>`, or null when [label] is not one valid DNS label. */
    fun make(label: String, zone: String): String? {
        val clean = label.trim().lowercase()
        return if (clean.length <= 63 && LABEL.matches(clean)) "$clean.$zone" else null
    }
}

class CloudflareSetup(private val client: CloudflareClient) {
    /** Runs every step for [hostname] in [zone], reporting each one before it starts. */
    suspend fun run(
        account: CloudflareAccount,
        zone: CloudflareZone,
        hostname: String,
        adminEmail: String,
        progress: suspend (CloudflareStep) -> Unit = {},
    ): TunnelSetup {
        progress(CloudflareStep.ZERO_TRUST)
        val teamDomain = client.teamDomain(account.id)
        progress(CloudflareStep.TUNNEL)
        val tunnelId = client.tunnel(account.id, "minicam-$hostname")
        val token = client.tunnelToken(account.id, tunnelId)
        progress(CloudflareStep.ROUTE)
        client.route(account.id, tunnelId, hostname)
        progress(CloudflareStep.DNS)
        client.dnsRecord(zone.id, hostname, tunnelId)
        progress(CloudflareStep.ACCESS)
        val policyId = client.accessPolicy(account.id, "minicam-$hostname users", adminEmail)
        val aud = client.accessApplication(account.id, hostname, policyId)
        return TunnelSetup(tunnelToken = token, hostname = hostname, teamDomain = teamDomain, aud = aud)
    }
}
