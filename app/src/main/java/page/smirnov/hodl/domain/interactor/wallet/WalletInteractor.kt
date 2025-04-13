@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalStdlibApi::class)

package page.smirnov.hodl.domain.interactor.wallet

import co.touchlab.kermit.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.io.IOException
import org.bitcoinj.base.AddressParser
import org.bitcoinj.base.Coin
import org.bitcoinj.base.Sha256Hash
import org.bitcoinj.base.exceptions.AddressFormatException
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.Transaction.SigHash
import org.bitcoinj.core.TransactionOutPoint
import org.bitcoinj.script.ScriptBuilder
import page.smirnov.hodl.data.model.api.UtxoApiModel
import page.smirnov.hodl.data.model.key.BitcoinKey
import page.smirnov.hodl.data.repository.api.EsploraApiRepository
import page.smirnov.hodl.di.qualifier.DispatcherDefault
import page.smirnov.hodl.domain.exception.wallet.NoBitcoinKeyException
import page.smirnov.hodl.domain.exception.wallet.NotEnoughFundsException
import page.smirnov.hodl.domain.interactor.bitcoin.BitcoinKeyInteractor
import page.smirnov.hodl.domain.model.bitcoin.BitcoinKeyState
import page.smirnov.hodl.domain.model.wallet.SendResult
import page.smirnov.hodl.util.extension.flow.typedFlow
import javax.inject.Inject

interface WalletInteractor {
    val balance: StateFlow<Long?>

    fun updateBalance()

    fun isAddressValid(address: String): Boolean
    fun calculateFee(address: String, amountSatoshis: Long): Flow<Long>

    fun send(address: String, amountSatoshis: Long): Flow<SendResult>

}

internal class WalletInteractorImpl @Inject constructor(
    private val bitcoinKeyInteractor: BitcoinKeyInteractor,
    private val esploraApiRepository: EsploraApiRepository,
    @DispatcherDefault
    private val defaultDispatcher: CoroutineDispatcher,
) : WalletInteractor {

    private val logger = Logger.withTag(LOG_TAG)

    private val _balance = MutableStateFlow<Long?>(null)
    override val balance = _balance.asStateFlow()

    private val addressParser = AddressParser.getDefault()

    private val coroutineScope = CoroutineScope(defaultDispatcher + SupervisorJob())

    init {
        startListeningToBitcoinKey()
    }

    private fun startListeningToBitcoinKey() {
        coroutineScope.launch {
            bitcoinKeyInteractor
                .bitcoinKey
                .filterIsInstance<BitcoinKeyState.Present>()
                .collect {
                    logger.d { "Got bitcoin key" }

                    updateBalance()
                }
        }
    }

    override fun updateBalance() {
        logger.d { "updateBalance" }

        coroutineScope.launch {
            getBalance()
                .catch { t ->
                    logger.e(t) { "updateBalance: Failed to get balance" }

                    if (t is IOException) {
                        scheduleUpdateBalance(delayMs = BALANCE_LOAD_RETRY_INTERVAL_MS)
                    }
                }
                .flowOn(defaultDispatcher)
                .collect { balance ->
                    logger.d { "updateBalance: Got balance $balance" }

                    _balance.value = balance
                }
        }
    }

    private fun scheduleUpdateBalance(delayMs: Long) {
        coroutineScope.launch {
            delay(delayMs)

            updateBalance()
        }
    }

    private fun getBalance(): Flow<Long> {
        logger.d { "getBalance" }

        return getConfirmedUtxoList(ignoreCache = true)
            .map { utxoList -> utxoList.sumOf { it.value } }
            .flowOn(defaultDispatcher)
    }

    override fun isAddressValid(address: String): Boolean {
        logger.d { "isAddressValid: address=$address" }

        return try {
            addressParser.parseAddress(address)
            true
        } catch (_: AddressFormatException) {
            false
        }
    }

    override fun calculateFee(address: String, amountSatoshis: Long): Flow<Long> {
        logger.d { "calculateFee: address=$address, amountSatoshis=$amountSatoshis" }

        return createSignedTransaction(address = address, amountSatoshis = amountSatoshis, ignoreUtxoCache = false)
            .map { transaction ->
                transaction.length * FEE_RATE
            }
    }

    override fun send(address: String, amountSatoshis: Long): Flow<SendResult> {
        logger.d { "send: address=$address, amountSatoshis=$amountSatoshis" }

        return combine(
            createSignedTransaction(address = address, amountSatoshis = amountSatoshis, ignoreUtxoCache = true),
            getBalance(),
        ) { transaction, balance ->
            val fee = transaction.length * FEE_RATE
            if (amountSatoshis + fee > balance) {
                logger.d { "send: Need ${amountSatoshis + fee}, but have only $balance" }
                throw NotEnoughFundsException()
            }

            transaction
        }.flatMapConcat { transaction ->
            esploraApiRepository
                .broadcastTransaction(transactionHex = transaction)
        }.map { result ->
            SendResult(txId = result.txId)
        }.onCompletion {
            updateBalance()
        }
    }

    /**
     * Get UTXO list for current key from the API
     * @param ignoreCache If true, always get fresh values from API without caching
     * @return Flow with list of UTXO, or empty flow if no bitcoin key present
     */
    private fun getUtxoList(ignoreCache: Boolean): Flow<List<UtxoApiModel>> {
        logger.d { "getUtxoList: ignoreCache=$ignoreCache" }

        return typedFlow {
            bitcoinKey ?: throw NoBitcoinKeyException()
        }
            .mapNotNull { it.address }
            .flatMapConcat { address ->
                val cacheExpirationMs = if (ignoreCache) 0 else UTXO_CACHE_EXPIRATION_MS
                esploraApiRepository.getUtxo(address = address, cacheExpirationMs = cacheExpirationMs)
            }
            .onEach { utxoList ->
                logger.d { "getUtxoList: ${utxoList.size} entries" }
            }
    }

    private fun getConfirmedUtxoList(ignoreCache: Boolean): Flow<List<UtxoApiModel>> {
        logger.d { "getConfirmedUtxoList: ignoreCache=$ignoreCache" }

        return getUtxoList(ignoreCache = ignoreCache).map { utxoList -> utxoList.filter { it.status.isConfirmed } }
    }

    private fun createSignedTransaction(
        address: String,
        amountSatoshis: Long,
        ignoreUtxoCache: Boolean,
    ): Flow<String> {
        logger.d { "createSignedTransaction: address=$address, amountSatoshis=$amountSatoshis, ignoreUtxoCache=$ignoreUtxoCache" }

        return getConfirmedUtxoList(ignoreCache = ignoreUtxoCache)
            .map { utxoList ->
                selectUtxosWithDynamicFee(
                    utxos = utxoList,
                    amount = amountSatoshis,
                    feeRate = FEE_RATE,
                )
            }
            .map { utxoListForTransaction ->
                val transaction = Transaction()
                val bitcoinKey = bitcoinKey ?: throw NoBitcoinKeyException()

                transaction.addOutput(Coin.valueOf(amountSatoshis), addressParser.parseAddress(address))

                val estimatedTxSize = estimateTransactionSize(
                    inputs = utxoListForTransaction.utxoList.size,
                    outputs = if (utxoListForTransaction.totalInput > amountSatoshis) 2 else 1
                )
                val fee = estimatedTxSize * FEE_RATE

                val change = utxoListForTransaction.totalInput - amountSatoshis - fee
                if (change > 0) {
                    transaction.addOutput(
                        Coin.valueOf(change),
                        addressParser.parseAddress(bitcoinKey.address)
                    )
                }

                utxoListForTransaction.utxoList.forEach { utxo ->
                    val scriptPubKey = ScriptBuilder.createOutputScript(addressParser.parseAddress(bitcoinKey.address))
                    val transactionOutPoint = TransactionOutPoint(utxo.vout, Sha256Hash.wrap(utxo.txid))
                    transaction.addSignedInput(
                        transactionOutPoint,
                        scriptPubKey,
                        Coin.valueOf(utxo.value),
                        bitcoinKey.ecKey,
                        SigHash.ALL,
                        true
                    )
                }

                transaction.serialize().toHexString()
            }
    }

    private fun selectUtxosWithDynamicFee(
        utxos: List<UtxoApiModel>,
        amount: Long,
        feeRate: Long,
    ): UtxoListForTransaction {
        logger.d { "selectUtxosWithDynamicFee: utxos=${utxos.size}, amount=$amount, feeRate=$feeRate" }

        var selectedUtxos = listOf<UtxoApiModel>()
        var totalInput = 0L
        var requiredFee = 0L
        var iterations = 0

        do {
            val estimatedTxSize = estimateTransactionSize(
                inputs = selectedUtxos.size,
                outputs = if (totalInput > amount + requiredFee) 2 else 1
            )
            requiredFee = estimatedTxSize * feeRate

            val needed = amount + requiredFee
            val selectedUtxosForTransaction = selectUtxos(utxos, needed)

            selectedUtxos = selectedUtxosForTransaction.utxoList
            totalInput = selectedUtxosForTransaction.totalInput
            iterations++

        } while (totalInput < amount + requiredFee && iterations < MAX_UTXO_SELECTION_ITERATIONS)

        return return UtxoListForTransaction(
            utxoList = selectedUtxos,
            totalInput = totalInput,
        )
    }

    private fun selectUtxos(
        utxos: List<UtxoApiModel>,
        requiredAmount: Long,
    ): UtxoListForTransaction {
        logger.d { "selectUtxos: utxos=${utxos.size}, requiredAmount=$requiredAmount" }

        var totalInput = 0L
        val selectedUtxos = mutableListOf<UtxoApiModel>()

        for (utxo in utxos.sortedByDescending { it.value }) {
            selectedUtxos.add(utxo)
            totalInput += utxo.value
            if (totalInput >= requiredAmount) break
        }

        return UtxoListForTransaction(
            utxoList = selectedUtxos,
            totalInput = totalInput,
        )
    }

    private fun estimateTransactionSize(inputs: Int, outputs: Int): Int {
        return when {
            inputs == 0 -> 0
            else -> {
                BASE_TRANSACTION_HEADER_SIZE +
                        (inputs * PER_INPUT_SIZE) +
                        (outputs * PER_OUTPUT_SIZE)
            }
        }
    }

    private val bitcoinKey: BitcoinKey?
        get() {
            return bitcoinKeyInteractor
                .bitcoinKey
                .value
                .takeIf { it is BitcoinKeyState.Present }
                ?.let { it as BitcoinKeyState.Present }
                ?.bitcoinKey
        }

    private data class UtxoListForTransaction(
        val utxoList: List<UtxoApiModel>,
        val totalInput: Long,
    )

    private companion object {
        private const val LOG_TAG = "WalletInteractor"

        private const val BALANCE_LOAD_RETRY_INTERVAL_MS = 1000L * 1

        private const val UTXO_CACHE_EXPIRATION_MS = 1000L * 60

        // Hardcoding for simplicity
        private const val FEE_RATE = 1L

        private const val MAX_UTXO_SELECTION_ITERATIONS = 1337

        // Not universal, but no time to implement it correctly
        private const val BASE_TRANSACTION_HEADER_SIZE = 10
        private const val PER_INPUT_SIZE = 32 + 4 + 1 + 4 + 72
        const val OUTPUT_AMOUNT_SIZE = 8
        const val OUTPUT_SCRIPT_PUBKEY_SIZE = 1 + 22
        const val PER_OUTPUT_SIZE = OUTPUT_AMOUNT_SIZE + OUTPUT_SCRIPT_PUBKEY_SIZE
    }
}