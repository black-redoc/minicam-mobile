package dev.minicam.mobile.data

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Played clips on disk (ADR 0016): a Media3 LRU cache in the app's private cache directory, so a
 * clip plays again without downloading it. Listed clips never change on the server, so a cached
 * copy is always current. One [SimpleCache] per directory and process, hence the singleton.
 */
@OptIn(UnstableApi::class)
object RecordingCache {
    const val MAX_BYTES = 512L * 1024 * 1024
    private const val DIRECTORY = "recordings"

    @Volatile
    private var cache: SimpleCache? = null

    /** Keyed by path, so the LAN and remote addresses of the same server share a clip. */
    val keyFactory = CacheKeyFactory { spec -> cacheKey(spec.uri.path.orEmpty()) }

    fun cacheKey(path: String): String = "clip:$path"

    private fun cache(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(
            File(context.applicationContext.cacheDir, DIRECTORY),
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { cache = it }
    }

    /** Reads clips through the cache, fetching misses with [upstream]. */
    fun dataSource(context: Context, upstream: DataSource.Factory): DataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache(context))
            .setUpstreamDataSourceFactory(upstream)
            .setCacheKeyFactory(keyFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** Forgets a deleted clip. */
    fun remove(context: Context, api: MinicamApi, id: Int) {
        runCatching { cache(context).removeResource(cacheKey(api.recordingVideoUrl(id).encodedPath)) }
    }

    /** Sign-out: no clip of this account stays on the phone. */
    fun clear(context: Context) {
        runCatching {
            val cache = cache(context)
            cache.keys.toList().forEach(cache::removeResource)
        }
    }
}
