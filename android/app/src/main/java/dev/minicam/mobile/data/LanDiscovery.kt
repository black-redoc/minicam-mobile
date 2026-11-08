package dev.minicam.mobile.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.Inet4Address
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A minicam that answered on the LAN (`_minicam._tcp`, minicam ADR 0006). */
data class DiscoveredServer(val name: String, val baseUrl: String)

object DiscoveredServers {
    const val SERVICE_TYPE = "_minicam._tcp."

    /**
     * Base URL for a resolved service, or null unless it is a LAN address: the same rule as a
     * typed URL (plain http only to private IPv4 / `.local`), so discovery can't point the app
     * at a public host.
     */
    fun baseUrl(host: String, port: Int): String? {
        if (port !in 1..65535) return null
        val config = runCatching { ServerConfig.parse("http://$host:$port") }.getOrNull() ?: return null
        return if (config.isWan) null else config.origin()
    }
}

/**
 * Browses `_minicam._tcp` with NsdManager while [start]ed. Services are resolved one at a
 * time (NsdManager refuses concurrent resolves on older Android versions).
 */
class LanDiscovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val _servers = MutableStateFlow(emptyList<DiscoveredServer>())
    val servers: StateFlow<List<DiscoveredServer>> = _servers.asStateFlow()

    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (listener != null) return
        _servers.value = emptyList()
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) = enqueue(service)

            override fun onServiceLost(service: NsdServiceInfo) {
                _servers.update { list -> list.filterNot { it.name == service.serviceName } }
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                listener = null
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        listener = discovery
        nsd.discoverServices(DiscoveredServers.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        synchronized(pending) {
            pending.clear()
        }
    }

    private fun enqueue(service: NsdServiceInfo) {
        synchronized(pending) {
            pending.addLast(service)
            if (resolving) return
            resolving = true
        }
        resolveNext()
    }

    private fun resolveNext() {
        val next = synchronized(pending) {
            pending.removeFirstOrNull().also { if (it == null) resolving = false }
        } ?: return
        @Suppress("DEPRECATION") // registerServiceInfoCallback needs API 34; minSdk is 26.
        nsd.resolveService(
            next,
            object : NsdManager.ResolveListener {
                override fun onServiceResolved(service: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    val address = service.host
                    val url = (address as? Inet4Address)?.hostAddress?.let { DiscoveredServers.baseUrl(it, service.port) }
                    if (url != null) {
                        val server = DiscoveredServer(service.serviceName, url)
                        _servers.update { list -> list.filterNot { it.name == server.name } + server }
                    }
                    resolveNext()
                }

                override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) = resolveNext()
            },
        )
    }
}
