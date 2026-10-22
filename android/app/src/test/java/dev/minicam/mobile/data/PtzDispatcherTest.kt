package dev.minicam.mobile.data

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.QueueDispatcher
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PtzDispatcherTest {
    private lateinit var server: MockWebServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val errors = mutableListOf<Throwable>()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    private fun dispatcher(): PtzDispatcher {
        val api = MinicamApi(ServerConfig.parse(server.url("/").toString()), InMemoryCredentialStore())
        return PtzDispatcher(scope, { api.ptz("entrada", it) }, { synchronized(errors) { errors += it } })
    }

    private fun takeActions(count: Int): List<String> = List(count) {
        server.takeRequest(5, TimeUnit.SECONDS)?.target?.substringAfterLast('/') ?: "<none>"
    }

    @Test
    fun tap_sendsDirectionThenStop() {
        repeat(2) { server.enqueue(MockResponse.Builder().code(200).body("{}").build()) }

        // A quick tap: press and release before the first request is on the wire.
        dispatcher().apply {
            dispatch(PtzAction.UP)
            dispatch(PtzAction.STOP)
        }

        assertEquals(listOf("up", "stop"), takeActions(2))
        assertTrue(errors.isEmpty())
    }

    @Test
    fun whileARequestIsInFlight_onlyTheLatestActionIsQueued() {
        val firstArrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : QueueDispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.target.endsWith("/up")) {
                    firstArrived.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
                return MockResponse.Builder().code(200).body("{}").build()
            }
        }

        val dispatcher = dispatcher()
        dispatcher.dispatch(PtzAction.UP)
        assertTrue(firstArrived.await(5, TimeUnit.SECONDS))
        dispatcher.dispatch(PtzAction.LEFT)
        dispatcher.dispatch(PtzAction.STOP)
        release.countDown()

        assertEquals(listOf("up", "stop"), takeActions(2))
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun failure_isReportedAndLaterActionsAreStillSent() {
        server.enqueue(MockResponse.Builder().code(503).body("""{"detail": "PTZ offline"}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        val failed = CountDownLatch(1)
        val api = MinicamApi(ServerConfig.parse(server.url("/").toString()), InMemoryCredentialStore())
        val dispatcher = PtzDispatcher(scope, { api.ptz("entrada", it) }) {
            synchronized(errors) { errors += it }
            failed.countDown()
        }

        dispatcher.dispatch(PtzAction.UP)
        assertTrue(failed.await(5, TimeUnit.SECONDS))
        dispatcher.dispatch(PtzAction.STOP)

        assertEquals(listOf("up", "stop"), takeActions(2))
        assertEquals(listOf(MinicamException.Server(503)), synchronized(errors) { errors.toList() })
    }
}
