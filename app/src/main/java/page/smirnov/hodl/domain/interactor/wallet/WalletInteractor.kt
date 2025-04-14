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

/**
 * Interactor responsible for core wallet functionalities like checking balance,
 * validating addresses, calculating fees, and sending transactions.
 */
interface WalletInteractor {
    /**
     * A [StateFlow] emitting the current confirmed wallet balance in Satoshis.
     * Emits `null` if the balance is unknown (e.g., before the first successful fetch or if no key is present).
     */
    val balance: StateFlow<Long?>

    /**
     * Triggers an asynchronous update of the wallet balance by fetching the latest UTXO information.
     * The result will be emitted via the [balance] StateFlow.
     */
    fun updateBalance()

    /**
     * Checks if the provided Bitcoin address string is valid for the configured network.
     * @param address The address string to validate.
     * @return `true` if the address is valid, `false` otherwise.
     */
    fun isAddressValid(address: String): Boolean

    /**
     * Calculates the estimated transaction fee in Satoshis required to send a given amount to a specific address.
     * This involves selecting potential UTXOs and estimating the size of the resulting transaction.
     *
     * @param address The recipient's Bitcoin address.
     * @param amountSatoshis The amount to be sent in Satoshis.
     * @return A Flow emitting the estimated fee in Satoshis.
     * @throws NoBitcoinKeyException if the user's key is not available.
     */
    fun calculateFee(address: String, amountSatoshis: Long): Flow<Long>

    /**
     * Creates, signs, and broadcasts a Bitcoin transaction to send a specified amount to a recipient address.
     * It selects appropriate UTXOs, calculates the fee, constructs the transaction, signs it,
     * and broadcasts it via the Esplora API. It also updates the balance upon completion.
     *
     * @param address The recipient's Bitcoin address.
     * @param amountSatoshis The amount to send in Satoshis.
     * @return A Flow emitting a [SendResult] containing the transaction ID upon successful broadcast.
     * @throws NoBitcoinKeyException if the user's key is not available.
     * @throws NotEnoughFundsException if the available confirmed balance is insufficient to cover the amount and fee.
     * @throws AddressFormatException if the recipient address is invalid.
     * @throws IOException or other network exceptions if API calls fail.
     */
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

    /**
     * Observes the [BitcoinKeyInteractor.bitcoinKey] state. When a key becomes present,
     * it triggers an initial balance update.
     */
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

    /**
     * Launches a coroutine to fetch and update the balance state.
     * Handles potential errors and schedules retries for network issues.
     */
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

    /**
     * Schedules a delayed call to [updateBalance]. Used for retrying after network errors.
     */
    private fun scheduleUpdateBalance(@Suppress("SameParameterValue") delayMs: Long) {
        coroutineScope.launch {
            delay(delayMs)

            updateBalance()
        }
    }

    /**
     * Fetches the confirmed UTXOs for the current key and calculates the total balance in Satoshis.
     * @return A Flow emitting the total confirmed balance.
     * @throws NoBitcoinKeyException if the key is not available.
     */
    private fun getBalance(): Flow<Long> {
        logger.d { "getBalance" }

        return getConfirmedUtxoList(ignoreCache = true)
            .map { utxoList -> utxoList.sumOf { it.value } }
            .flowOn(defaultDispatcher)
    }

    /**
     * Validates a Bitcoin address string using bitcoinj's [AddressParser].
     */
    override fun isAddressValid(address: String): Boolean {
        logger.d { "isAddressValid: address=$address" }

        return try {
            addressParser.parseAddress(address)
            true
        } catch (_: AddressFormatException) {
            false
        }
    }

    /**
     * Estimates the transaction fee by creating (but not broadcasting) a signed transaction
     * and calculating its size multiplied by the fee rate.
     */
    override fun calculateFee(address: String, amountSatoshis: Long): Flow<Long> {
        logger.d { "calculateFee: address=$address, amountSatoshis=$amountSatoshis" }

        return createSignedTransaction(address = address, amountSatoshis = amountSatoshis, ignoreUtxoCache = false)
            .map { transaction ->
                transaction.length * FEE_RATE
            }
    }

    /**
     * Orchestrates the process of sending a transaction:
     * 1. Creates a signed transaction hex.
     * 2. Fetches the current balance.
     * 3. Checks if funds (balance) are sufficient for the amount + calculated fee.
     * 4. Broadcasts the transaction hex if funds are sufficient.
     * 5. Updates the balance after the operation completes (success or failure).
     */
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
     * Fetches the list of all UTXOs (confirmed and unconfirmed) for the current Bitcoin key's address.
     * Handles the case where no key is present.
     *
     * @param ignoreCache If true, forces a fetch from the API, bypassing the in-memory cache.
     * @return Flow emitting the list of UTXOs, or throws [NoBitcoinKeyException] if no key is available.
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

    /**
     * Fetches only the *confirmed* UTXOs by filtering the result of [getUtxoList].
     */
    private fun getConfirmedUtxoList(ignoreCache: Boolean): Flow<List<UtxoApiModel>> {
        logger.d { "getConfirmedUtxoList: ignoreCache=$ignoreCache" }

        // TODO: Use `getConfirmedUtxo` from EsploraApiRepository
        return getUtxoList(ignoreCache = ignoreCache).map { utxoList -> utxoList.filter { it.status.isConfirmed } }
    }

    /**
     * Creates a signed Bitcoin transaction in hexadecimal format.
     * Selects UTXOs dynamically to cover the amount plus estimated fee.
     *
     * @param address The recipient address.
     * @param amountSatoshis The amount to send in Satoshis.
     * @param ignoreUtxoCache Whether to ignore the UTXO cache when fetching inputs.
     * @return Flow emitting the signed transaction hex string.
     * @throws NoBitcoinKeyException if the key is not available.
     * @throws NotEnoughFundsException if not enough confirmed UTXOs are available.
     */
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

    /**
     * Selects a list of UTXOs sufficient to cover a target amount plus a dynamically calculated fee.
     * This function iteratively estimates the fee based on the number of inputs and outputs,
     * recalculates the total amount needed (target + fee), and selects UTXOs until the
     * selected UTXOs' total value meets or exceeds the required amount.
     *
     * @param utxos The list of available confirmed UTXOs.
     * @param amount The target amount to send (excluding fees).
     * @param feeRate The fee rate in satoshis per byte (or vByte).
     * @return [UtxoListForTransaction] containing the selected UTXOs and their total value.
     * @throws NotEnoughFundsException if enough UTXOs cannot be found even after iterations.
     */
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

    /**
     * Selects UTXOs using a simple greedy approach (largest first) until the required amount is met.
     * Does not account for fees itself; used as a helper by [selectUtxosWithDynamicFee].
     *
     * @param utxos List of available UTXOs.
     * @param requiredAmount The target amount to reach.
     * @return [UtxoListForTransaction] containing the selected UTXOs and their total value.
     */
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

    /**
     * Estimates the size of a P2WPKH (native SegWit) transaction in bytes based on the number of inputs and outputs.
     * Note: This is an estimation and might not be perfectly accurate for all transaction types or edge cases.
     * It assumes standard P2WPKH inputs and outputs.
     *
     * @param inputs Number of transaction inputs.
     * @param outputs Number of transaction outputs.
     * @return Estimated transaction size in bytes.
     */
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

    /**
     * Safely retrieves the [BitcoinKey] from the [bitcoinKeyInteractor]'s state flow,
     * returning null if the state is not [BitcoinKeyState.Present].
     */
    private val bitcoinKey: BitcoinKey?
        get() {
            return bitcoinKeyInteractor
                .bitcoinKey
                .value
                .takeIf { it is BitcoinKeyState.Present }
                ?.let { it as BitcoinKeyState.Present }
                ?.bitcoinKey
        }

    /** Helper data class to hold the result of UTXO selection. */
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

        private const val MAX_UTXO_SELECTION_ITERATIONS = 32

        // Not universal, but no time to implement it correctly
        private const val BASE_TRANSACTION_HEADER_SIZE = 10
        private const val PER_INPUT_SIZE = 32 + 4 + 1 + 4 + 72
        const val OUTPUT_AMOUNT_SIZE = 8
        const val OUTPUT_SCRIPT_PUBKEY_SIZE = 1 + 22
        const val PER_OUTPUT_SIZE = OUTPUT_AMOUNT_SIZE + OUTPUT_SCRIPT_PUBKEY_SIZE
    }
}