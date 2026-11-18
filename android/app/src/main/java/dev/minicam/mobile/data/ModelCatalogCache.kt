package dev.minicam.mobile.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The YOLO model catalog of the signed-in server, kept for the session (ADR 0016): the screen
 * fetches it once, and again only after an install ([refresh]) or a sign-out ([clear]).
 */
class ModelCatalogCache {
    private val mutex = Mutex()
    private var server: String? = null
    private var catalog: ModelCatalog? = null

    /** The cached catalog of [server], or the one [load] fetches (kept only if it succeeds). */
    suspend fun get(server: String, refresh: Boolean = false, load: suspend () -> ModelCatalog): ModelCatalog =
        mutex.withLock {
            val cached = catalog
            if (!refresh && cached != null && this.server == server) return cached
            load().also {
                this.server = server
                catalog = it
            }
        }

    fun clear() {
        server = null
        catalog = null
    }
}
