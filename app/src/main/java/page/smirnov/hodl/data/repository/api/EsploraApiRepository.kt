package page.smirnov.hodl.data.repository.api


import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import page.smirnov.hodl.data.model.api.BroadcastResultApiModel
import page.smirnov.hodl.data.model.api.EsploraApiResource
import page.smirnov.hodl.data.model.api.UtxoApiModel
import page.smirnov.hodl.di.qualifier.DefaultHttpClient
import page.smirnov.hodl.di.qualifier.DispatcherIO
import page.smirnov.hodl.util.extension.flow.typedFlow
import javax.inject.Inject

/**
 * Repository interface for interacting with the Esplora API to fetch blockchain data.
 */
interface EsploraApiRepository {

    /**
     * Fetches the list of Unspent Transaction Outputs (UTXOs) for a given Bitcoin address.
     * Implements a simple time-based in-memory cache.
     *
     * @param address The Bitcoin address to query.
     * @param cacheExpirationMs The duration in milliseconds for which the cached data is considered valid.
     *                          Set to 0 to force a refresh.
     * @return A Flow emitting the list of UTXOs
     */
    fun getUtxo(
        address: String,
        cacheExpirationMs: Long,
    ): Flow<List<UtxoApiModel>>

    /**
     * Fetches only the *confirmed* UTXOs for a given Bitcoin address.
     *
     * @param address The Bitcoin address to query.
     * @param cacheExpirationMs The duration in milliseconds for which the cached data is considered valid
     *                          (passed down to [getUtxo]). Set to 0 to force a refresh.
     * @return A Flow emitting the list of confirmed UTXOs.
     */
    fun getConfirmedUtxo(
        address: String,
        cacheExpirationMs: Long,
    ): Flow<List<UtxoApiModel>>

    /**
     * Broadcasts a signed Bitcoin transaction to the network.
     *
     * @param transactionHex The raw transaction in hexadecimal format.
     * @return A Flow emitting the result containing the transaction ID (txid) upon successful broadcast.
     */
    fun broadcastTransaction(transactionHex: String): Flow<BroadcastResultApiModel>
}

internal class EsploraApiRepositoryImpl @Inject constructor(
    @DefaultHttpClient
    private val httpClient: HttpClient,
    @DispatcherIO
    private val ioDispatcher: CoroutineDispatcher,
) : EsploraApiRepository {

    private var cachedUtxo: List<UtxoApiModel>? = null
    private var cachedUtxoUpdateTimestamp = 0L

    override fun getUtxo(address: String, cacheExpirationMs: Long): Flow<List<UtxoApiModel>> {
        return typedFlow {
            val now = System.currentTimeMillis()
            val utxoCache = this@EsploraApiRepositoryImpl.cachedUtxo

            if (utxoCache != null && now - cachedUtxoUpdateTimestamp < cacheExpirationMs) {
                // It's actually non-null, but compiler can't use the smart cast for some reason, so leaving as is for now due to lack of time
                return@typedFlow requireNotNull(utxoCache)
            }

            val resource = EsploraApiResource.Address.Detail.Utxo(
                parent = EsploraApiResource.Address.Detail(
                    address = address
                )
            )

            httpClient
                .get(resource) {
                    expectSuccess = true
                }
                .body<List<UtxoApiModel>>()
                .also {
                    this@EsploraApiRepositoryImpl.cachedUtxo = it
                    cachedUtxoUpdateTimestamp = now
                }
        }.flowOn(ioDispatcher)
    }

    override fun getConfirmedUtxo(address: String, cacheExpirationMs: Long): Flow<List<UtxoApiModel>> {
        return getUtxo(address = address, cacheExpirationMs = cacheExpirationMs).map { utxo ->
            utxo.filter { it.status.isConfirmed }
        }
    }

    override fun broadcastTransaction(transactionHex: String): Flow<BroadcastResultApiModel> {
        return typedFlow {
            httpClient
                .post(EsploraApiResource.Transactions()) {
                    setBody(transactionHex)

                    // API expects plain text for the transaction hex
                    contentType(ContentType.Any)

                    expectSuccess = true
                }
                .body<String>() // API returns the txid as a plain string in the response body
        }.map { txId ->
            BroadcastResultApiModel(
                txId = txId,
            )
        }.flowOn(ioDispatcher)
    }
}