package dev.minicam.mobile.data

/** Which address of the server the app talks to (docs/adr/0013). */
enum class Network { LOCAL, REMOTE }

val ServerConfig.network: Network get() = if (isWan) Network.REMOTE else Network.LOCAL

/**
 * The local (`http`, LAN host) and remote (`https`) address of one minicam, and which one is
 * active (docs/adr/0013). Both reach the same server, so switching keeps the session and the
 * Access token; an address outside the pair is another server and clears them.
 */
class ServerAddresses(private val store: CredentialStore) {
    init {
        // Before ADR 0013 the app kept a single `server_url`.
        if (store.get(CredentialKey.LAN_URL) == null && store.get(CredentialKey.WAN_URL) == null) {
            store.get(CredentialKey.SERVER_URL)?.let(::parse)?.let { save(it, activate = true) }
        }
    }

    val local: ServerConfig? get() = store.get(CredentialKey.LAN_URL)?.let(::parse)
    val remote: ServerConfig? get() = store.get(CredentialKey.WAN_URL)?.let(::parse)

    val active: ServerConfig?
        get() = when (store.get(CredentialKey.ACTIVE_NETWORK)) {
            Network.LOCAL.name -> local
            Network.REMOTE.name -> remote
            else -> null
        }

    operator fun get(network: Network): ServerConfig? = if (network == Network.LOCAL) local else remote

    /**
     * Makes [config] the active address. Returns true when it is one of the saved pair (the
     * session is kept); otherwise it is a new server and every credential is cleared.
     */
    fun use(config: ServerConfig): Boolean {
        if (this[config.network] == config) {
            save(config, activate = true)
            return true
        }
        store.clearSession()
        store.set(CredentialKey.ACCESS_TOKEN, null)
        store.set(CredentialKey.LAN_URL, null)
        store.set(CredentialKey.WAN_URL, null)
        save(config, activate = true)
        return false
    }

    /** Saves [config] as the other address of the active server, keeping every credential. */
    fun add(config: ServerConfig) = save(config, activate = false)

    /** Activates the saved [network] address and returns it; null when it was never added. */
    fun switchTo(network: Network): ServerConfig? = this[network]?.also { save(it, activate = true) }

    /** Remote without an Access token must go through Cloudflare Access first. */
    fun needsAccessLogin(config: ServerConfig): Boolean = config.isWan && store.get(CredentialKey.ACCESS_TOKEN) == null

    private fun save(config: ServerConfig, activate: Boolean) {
        val key = if (config.network == Network.LOCAL) CredentialKey.LAN_URL else CredentialKey.WAN_URL
        store.set(key, config.origin())
        if (activate) {
            store.set(CredentialKey.ACTIVE_NETWORK, config.network.name)
            store.set(CredentialKey.SERVER_URL, config.origin())
        }
    }

    private fun parse(url: String): ServerConfig? = runCatching { ServerConfig.parse(url) }.getOrNull()
}
