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

interface EsploraApiRepository {

    fun getUtxo(
        address: String,
        cacheExpirationMs: Long,
    ): Flow<List<UtxoApiModel>>

    fun getConfirmedUtxo(
        address: String,
        cacheExpirationMs: Long,
    ): Flow<List<UtxoApiModel>>

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
                // It's actually non-null, but compiler doesn't see the smart cast for some reason, so leaving as is for now due to lack of time
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
                    contentType(ContentType.Any)

                    expectSuccess = true
                }
                .body<String>()
        }.map { txId ->
            BroadcastResultApiModel(
                txId = txId,
            )
        }.flowOn(ioDispatcher)
    }
}