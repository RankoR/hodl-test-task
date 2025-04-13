package page.smirnov.hodl

import app.cash.turbine.test
import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.resources.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import page.smirnov.hodl.data.model.api.BroadcastResultApiModel
import page.smirnov.hodl.data.model.api.UtxoApiModel
import page.smirnov.hodl.data.repository.api.EsploraApiRepositoryImpl

@ExperimentalCoroutinesApi
class EsploraApiRepositoryTest {

    private val testDispatcher = StandardTestDispatcher()

    private val testAddress = "tb1qtestaddress"
    private val testTransactionHex = "0100000001..."
    private val testTxId = "a1b2c3d4e5f6"
    private val defaultCacheExpirationMs = 60_000L

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun createMockClient(mockEngine: HttpClientEngineBase): HttpClient {
        return HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(json)
            }
            install(Resources)
        }
    }

    @Test
    fun `getUtxo WHEN cache is empty SHOULD call API, return data, and update cache`() = runTest(testDispatcher) {
        val expectedUtxoList = listOf(
            UtxoApiModel("txid1", 1, UtxoApiModel.Status(true), 1000),
            UtxoApiModel("txid2", 0, UtxoApiModel.Status(false), 2000)
        )
        var apiCalledCount = 0

        val mockEngine = MockEngine { request ->
            apiCalledCount++
            assertTrue(request.url.encodedPath.endsWith("/address/$testAddress/utxo"))
            assertEquals(HttpMethod.Get, request.method)
            respond(
                content = json.encodeToString(expectedUtxoList),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val httpClient = createMockClient(mockEngine)

        val repository = EsploraApiRepositoryImpl(
            httpClient = httpClient,
            ioDispatcher = testDispatcher,
        )

        repository.getUtxo(testAddress, defaultCacheExpirationMs).test {
            assertEquals(expectedUtxoList, awaitItem())
            awaitComplete()
        }

        assertEquals(1, apiCalledCount)
        assertEquals(1, mockEngine.requestHistory.size)
    }

    @Test
    fun `getUtxo WHEN cache is valid SHOULD return cached data without calling API`() = runTest(testDispatcher) {
        val cachedUtxoList = listOf(UtxoApiModel("txid_cached", 0, UtxoApiModel.Status(true), 5000))
        var apiCalledCount = 0

        val mockEngine = MockEngine { request ->
            apiCalledCount++
            assertTrue(request.url.encodedPath.endsWith("/address/$testAddress/utxo"))
            assertEquals(HttpMethod.Get, request.method)
            respond(
                content = json.encodeToString(cachedUtxoList),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val httpClient = createMockClient(mockEngine)
        val repository = EsploraApiRepositoryImpl(httpClient, testDispatcher)

        repository.getUtxo(testAddress, defaultCacheExpirationMs).first()
        assertEquals(1, apiCalledCount) // Ensure API was called once to populate

        repository.getUtxo(testAddress, defaultCacheExpirationMs).test {
            assertEquals(cachedUtxoList, awaitItem())
            awaitComplete()
        }

        assertEquals(1, apiCalledCount)
        assertEquals(1, mockEngine.requestHistory.size)
    }

    @Test
    fun `getUtxo WHEN cache is expired SHOULD call API again and return fresh data`() = runTest(testDispatcher) {
        val freshUtxoList = listOf(UtxoApiModel("txid_fresh", 1, UtxoApiModel.Status(false), 6000))
        var apiCalledCount = 0

        val mockEngine = MockEngine { request ->
            apiCalledCount++
            assertTrue(request.url.encodedPath.endsWith("/address/$testAddress/utxo"))
            assertEquals(HttpMethod.Get, request.method)
            respond(
                content = json.encodeToString(freshUtxoList),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val httpClient = createMockClient(mockEngine)
        val repository = EsploraApiRepositoryImpl(httpClient, testDispatcher)

        repository.getUtxo(testAddress, defaultCacheExpirationMs).first()
        assertEquals(1, apiCalledCount) // Called once

        repository.getUtxo(testAddress, 0L).test { // cacheExpirationMs = 0
            assertEquals(freshUtxoList, awaitItem()) // Should get fresh data again
            awaitComplete()
        }

        assertEquals(2, apiCalledCount)
        assertEquals(2, mockEngine.requestHistory.size)
    }

    @Test
    fun `getUtxo WHEN API call fails SHOULD propagate the exception`() = runTest(testDispatcher) {
        var apiCalledCount = 0
        val mockEngine = MockEngine { request ->
            apiCalledCount++
            assertTrue(request.url.encodedPath.endsWith("/address/$testAddress/utxo"))
            assertEquals(HttpMethod.Get, request.method)
            respondError(
                HttpStatusCode.InternalServerError,
                "Server Error",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            )
        }
        val httpClient = createMockClient(mockEngine)
        val repository = EsploraApiRepositoryImpl(httpClient, testDispatcher)

        repository.getUtxo(testAddress, defaultCacheExpirationMs).test {
            val error = awaitError()
            assertTrue(
                "Expected ServerResponseException but got ${error::class.simpleName}",
                error is ServerResponseException
            )
            val clientError = error as ServerResponseException
            assertEquals(HttpStatusCode.InternalServerError, clientError.response.status)
        }

        assertEquals(1, apiCalledCount)
        assertEquals(1, mockEngine.requestHistory.size)
    }

    @Test
    fun `getConfirmedUtxo SHOULD call getUtxo and filter for confirmed UTXOs`() = runTest(testDispatcher) {
        val confirmedUtxo = UtxoApiModel("txid_conf", 0, UtxoApiModel.Status(true, 123), 1000)
        val unconfirmedUtxo = UtxoApiModel("txid_unconf", 1, UtxoApiModel.Status(false), 2000)
        val mixedUtxoList = listOf(confirmedUtxo, unconfirmedUtxo)
        val expectedFilteredList = listOf(confirmedUtxo)
        var apiCalledCount = 0

        val mockEngine = MockEngine { request ->
            apiCalledCount++
            assertTrue(request.url.encodedPath.endsWith("/address/$testAddress/utxo"))
            assertEquals(HttpMethod.Get, request.method)
            respond(
                content = json.encodeToString(mixedUtxoList),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val httpClient = createMockClient(mockEngine)
        val repository = EsploraApiRepositoryImpl(httpClient, testDispatcher)

        repository.getConfirmedUtxo(testAddress, defaultCacheExpirationMs).test {
            assertEquals(expectedFilteredList, awaitItem())
            awaitComplete()
        }

        assertEquals(1, apiCalledCount)
        assertEquals(1, mockEngine.requestHistory.size)
    }

    @Test
    fun `getConfirmedUtxo WHEN getUtxo fails SHOULD propagate the exception`() = runTest(testDispatcher) {
        var apiCalledCount = 0
        val mockEngine = MockEngine { request ->
            apiCalledCount++
            assertTrue(request.url.encodedPath.endsWith("/address/$testAddress/utxo"))
            assertEquals(HttpMethod.Get, request.method)
            respondError(HttpStatusCode.NotFound, "Address not found")
        }
        val httpClient = createMockClient(mockEngine)
        val repository = EsploraApiRepositoryImpl(httpClient, testDispatcher)

        repository.getConfirmedUtxo(testAddress, defaultCacheExpirationMs).test {
            val error = awaitError()
            assertTrue(
                "Expected ClientRequestException but got ${error::class.simpleName}",
                error is ClientRequestException
            )
            assertEquals(HttpStatusCode.NotFound, (error as ClientRequestException).response.status)
        }

        assertEquals(1, apiCalledCount)
        assertEquals(1, mockEngine.requestHistory.size)
    }

    @Test
    fun `getConfirmedUtxo WHEN getUtxo returns empty list SHOULD return empty list`() = runTest(testDispatcher) {
        val emptyList = emptyList<UtxoApiModel>()
        var apiCalledCount = 0

        val mockEngine = MockEngine { request ->
            apiCalledCount++
            assertTrue(request.url.encodedPath.endsWith("/address/$testAddress/utxo"))
            assertEquals(HttpMethod.Get, request.method)
            respond(
                content = json.encodeToString(emptyList), // Respond with empty JSON array
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val httpClient = createMockClient(mockEngine)
        val repository = EsploraApiRepositoryImpl(httpClient, testDispatcher)

        repository.getConfirmedUtxo(testAddress, defaultCacheExpirationMs).test {
            assertEquals(emptyList, awaitItem())
            awaitComplete()
        }

        assertEquals(1, apiCalledCount)
        assertEquals(1, mockEngine.requestHistory.size)
    }

    @Test
    fun `broadcastTransaction WHEN API call is successful SHOULD return BroadcastResultApiModel`() =
        runTest(testDispatcher) {
            val expectedResult = BroadcastResultApiModel(txId = testTxId)
            var apiCalledCount = 0

            val mockEngine = MockEngine { request ->
                apiCalledCount++
                assertTrue(request.url.encodedPath.endsWith("/tx"))
                assertEquals(HttpMethod.Post, request.method)

                respond(
                    content = testTxId,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                )
            }
            val httpClient = createMockClient(mockEngine)
            val repository = EsploraApiRepositoryImpl(httpClient, testDispatcher)

            repository.broadcastTransaction(testTransactionHex).test {
                assertEquals(expectedResult, awaitItem())
                awaitComplete()
            }

            assertEquals(1, apiCalledCount)
            assertEquals(1, mockEngine.requestHistory.size)
        }

    @Test
    fun `broadcastTransaction WHEN API call fails SHOULD propagate the exception`() = runTest(testDispatcher) {
        var apiCalledCount = 0
        val errorMessage = "Invalid transaction hex"

        val mockEngine = MockEngine { request ->
            apiCalledCount++
            assertTrue(request.url.encodedPath.endsWith("/tx"))
            assertEquals(HttpMethod.Post, request.method)
            respondError(
                HttpStatusCode.BadRequest,
                errorMessage,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            )
        }
        val httpClient = createMockClient(mockEngine)
        val repository = EsploraApiRepositoryImpl(httpClient, testDispatcher)

        repository.broadcastTransaction(testTransactionHex).test {
            val error = awaitError()
            assertTrue(
                "Expected ClientRequestException but got ${error::class.simpleName}",
                error is ClientRequestException
            )
            val clientError = error as ClientRequestException
            assertEquals(HttpStatusCode.BadRequest, clientError.response.status)
        }

        assertEquals(1, apiCalledCount)
        assertEquals(1, mockEngine.requestHistory.size)
    }
}
