package dev.minicam.mobile.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** docs/specs/019 AC3: the YOLO model catalog is fetched once per server and session. */
class ModelCatalogCacheTest {
    private val cache = ModelCatalogCache()
    private var fetches = 0

    private suspend fun load(release: String = "v1"): ModelCatalog {
        fetches++
        return ModelCatalog(release, emptyList())
    }

    @Test
    fun get_fetchesOnlyOnce() = runTest {
        cache.get("http://pi") { load() }
        val again = cache.get("http://pi") { load("v2") }

        assertEquals("v1", again.release)
        assertEquals(1, fetches)
    }

    @Test
    fun get_refreshFetchesAgain() = runTest {
        cache.get("http://pi") { load() }

        assertEquals("v2", cache.get("http://pi", refresh = true) { load("v2") }.release)
        assertEquals(2, fetches)
    }

    @Test
    fun get_anotherServerFetchesItsOwn() = runTest {
        cache.get("http://pi") { load() }
        cache.get("http://other") { load() }

        assertEquals(2, fetches)
    }

    @Test
    fun clear_forgetsTheCatalog() = runTest {
        cache.get("http://pi") { load() }
        cache.clear()
        cache.get("http://pi") { load() }

        assertEquals(2, fetches)
    }

    @Test
    fun get_aFailedFetchIsNotCached() = runTest {
        assertThrows(MinicamException.InvalidResponse::class.java) {
            kotlinx.coroutines.runBlocking { cache.get("http://pi") { throw MinicamException.InvalidResponse } }
        }
        cache.get("http://pi") { load() }

        assertEquals(1, fetches)
    }
}
