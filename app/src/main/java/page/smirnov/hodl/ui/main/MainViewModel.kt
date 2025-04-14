package page.smirnov.hodl.ui.main

import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import page.smirnov.hodl.R
import page.smirnov.hodl.di.qualifier.DispatcherDefault
import page.smirnov.hodl.di.qualifier.DispatcherIO
import page.smirnov.hodl.di.qualifier.DispatcherMain
import page.smirnov.hodl.domain.interactor.bitcoin.BitcoinAmountsConverter
import page.smirnov.hodl.domain.interactor.wallet.WalletInteractor
import page.smirnov.hodl.ui.base.BaseViewModel
import java.math.BigDecimal
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val walletInteractor: WalletInteractor,
    private val bitcoinAmountsConverter: BitcoinAmountsConverter,
    @DispatcherDefault
    defaultDispatcher: CoroutineDispatcher,
    @DispatcherIO
    ioDispatcher: CoroutineDispatcher,
    @DispatcherMain
    mainDispatcher: CoroutineDispatcher,
) : BaseViewModel(
    defaultDispatcher = defaultDispatcher,
    ioDispatcher = ioDispatcher,
    mainDispatcher = mainDispatcher
) {
    override val logger = Logger.withTag(LOG_TAG)

    /**
     * [StateFlow] emitting the current wallet balance in Satoshis, observed from the [WalletInteractor]
     */
    val balance = walletInteractor.balance

    /**
     * [StateFlow] emitting the current wallet balance formatted as BTC (BigDecimal), derived from [balance]
     */
    val balanceBtc = balance.map { balanceSatoshis ->
        balanceSatoshis?.let(bitcoinAmountsConverter::satoshisToBtc)
    }

    private val _amount = MutableStateFlow("")

    /**
     * [StateFlow] emitting the raw string value entered in the amount input field
     */
    val amount = _amount.asStateFlow()

    private val _address = MutableStateFlow("")

    /**
     * [StateFlow] emitting the raw string value entered in the address input field
     */
    val address = _address.asStateFlow()

    private val _sendButtonState = MutableStateFlow<SendButtonState>(SendButtonState.Disabled)
    val sendButtonState = _sendButtonState.asStateFlow()

    private val _amountFieldState = MutableStateFlow<FieldState>(FieldState.Ok)
    val amountFieldState = _amountFieldState.asStateFlow()

    private val _addressFieldState = MutableStateFlow<FieldState>(FieldState.Ok)
    val addressFieldState = _addressFieldState.asStateFlow()

    private val _fee = MutableStateFlow<BigDecimal?>(null)
    val fee = _fee.asStateFlow()

    private val _sentTransactionTxId = MutableStateFlow<String?>(null)
    val sentTransactionTxId = _sentTransactionTxId.asStateFlow()

    private var amountSatoshis = 0L
    private var recipientAddress = ""

    private var validationJob: Job? = null

    init {
        startListeningForBalance()
    }

    /**
     * Collects balance updates from the [WalletInteractor].
     * Currently only logs the balance, but could be used for other reactions.
     */
    private fun startListeningForBalance() {
        viewModelScope.launch {
            balance.collect { balance ->
                logger.d("Balance: $balance")
            }
        }
    }

    /**
     * Called when the amount input field value changes.
     * Updates the internal amount state ([_amount], [amountSatoshis]) and triggers input validation.
     * @param amount The new raw string value from the amount input field.
     */
    fun onAmountChange(amount: String) {
        logger.d { "onAmountChange: amount=$amount" }

        _amount.value = amount

        val amount = try {
            BigDecimal(amount.trim().replace(",", "."))
        } catch (e: NumberFormatException) {
            logger.d(e) { "Invalid amount: $amount" }
            BigDecimal(-1)
        }

        this.amountSatoshis = bitcoinAmountsConverter.btcToSatoshis(amount)

        triggerValidation()
    }

    /**
     * Called when the address input field value changes.
     * Updates the internal address state ([_address], [recipientAddress]) and triggers input validation.
     * @param address The new raw string value from the address input field.
     */
    fun onAddressChange(address: String) {
        logger.d { "onAddressChange: address=$address" }

        _address.value = address

        this.recipientAddress = address.trim()

        triggerValidation()
    }

    /**
     * Called when the send button is clicked.
     * Sets the button state to Sending, initiates the send operation via [WalletInteractor],
     * handles the success (shows dialog, resets fields) or error (shows error message) result,
     * and resets the button state.
     */
    fun onSendButtonClick() {
        logger.d { "onSendButtonClick" }

        _sendButtonState.value = SendButtonState.Sending

        viewModelScope.launch {
            walletInteractor
                .send(
                    address = recipientAddress,
                    amountSatoshis = amountSatoshis,
                )
                .onCompletion {
                    _sendButtonState.value = SendButtonState.Enabled
                }
                .onErrorShowMessage() // Simply show the exception text for now
                .collect { sendResult ->
                    logger.i { "onSendButtonClick: sendResult=$sendResult" }

                    _sentTransactionTxId.value = sendResult.txId

                    // Reset state
                    _amount.value = ""
                    _address.value = ""
                    amountSatoshis = 0L
                    recipientAddress = ""
                }
        }
    }

    /**
     * Called when the success dialog (shown after sending) is dismissed.
     * Clears the transaction ID state and triggers a balance update.
     */
    fun onSentDialogDismiss() {
        logger.d { "onSentDialogDismiss" }

        _sentTransactionTxId.value = null
        viewModelScope.launch(defaultDispatcher) {
            walletInteractor.updateBalance()
        }
    }

    /**
     * Triggers input validation with debouncing. Cancels any pending validation job
     * and schedules a new one after a short delay.
     */
    private fun triggerValidation() {
        validationJob?.cancel()

        validationJob = viewModelScope.launch(defaultDispatcher) {
            delay(INPUT_DEBOUNCE_MS)

            if (isActive) {
                performValidation()
            }
        }
    }

    /**
     * Performs the actual validation of the amount and address fields based on the current state.
     * Updates the [amountFieldState], [addressFieldState], [fee], and [sendButtonState] accordingly.
     * This runs on the [defaultDispatcher].
     */
    private suspend fun performValidation() {
        val currentBalance = balance.value ?: 0L
        val currentAmountSatoshis = amountSatoshis
        val currentAddress = recipientAddress

        val isAddressValid = walletInteractor.isAddressValid(address = currentAddress)
        _addressFieldState.value = if (isAddressValid || currentAddress.isEmpty()) {
            FieldState.Ok
        } else {
            FieldState.Error(R.string.error_invalid_address)
        }

        when {
            currentAmountSatoshis < 0 -> {
                _amountFieldState.value = FieldState.Error(R.string.error_invalid_amount)
                _fee.value = null
            }

            currentAmountSatoshis == 0L -> {
                _amountFieldState.value = FieldState.Ok
                _fee.value = null
            }

            else -> {
                val fee = calculateFee(
                    amountSatoshis = amountSatoshis,
                    recipientAddress = if (isAddressValid) currentAddress else null
                )

                _fee.value = bitcoinAmountsConverter.satoshisToBtc(fee)

                val totalAmount = currentAmountSatoshis + fee
                _amountFieldState.value = if (totalAmount <= currentBalance) {
                    FieldState.Ok
                } else {
                    FieldState.Error(R.string.error_not_enough_funds)
                }
            }
        }

        logger.d { "performValidation: isAddressValid=$isAddressValid, amountFieldState=${_amountFieldState.value}, amountSatoshis=$currentAmountSatoshis" }

        _sendButtonState.value = if (
            isAddressValid &&
            amountFieldState.value == FieldState.Ok &&
            currentAmountSatoshis > 0
        ) {
            SendButtonState.Enabled
        } else {
            SendButtonState.Disabled
        }
    }

    /**
     * Calculates the estimated transaction fee by calling the [WalletInteractor].
     * Uses a fallback address if the provided recipient address is null (invalid).
     * @param amountSatoshis The amount to send.
     * @param recipientAddress The recipient's address, or null if invalid.
     * @return The estimated fee in Satoshis.
     */
    private suspend fun calculateFee(amountSatoshis: Long, recipientAddress: String?): Long {
        val address = recipientAddress ?: FALLBACK_ADDRESS_FOR_FEE_CALCULATION

        return walletInteractor
            .calculateFee(
                address = address,
                amountSatoshis = amountSatoshis,
            )
            .first()
    }

    public override fun onCleared() {
        validationJob?.cancel()

        super.onCleared()
    }

    private companion object {
        private const val LOG_TAG = "MainViewModel"

        private const val INPUT_DEBOUNCE_MS = 300L

        /**
         * A valid Signet address used for fee calculation when the user-entered address is invalid.
         * This allows fee estimation even with an incomplete or incorrect recipient address.
         * Replace with an appropriate address for the target network if not using Signet.
         */
        private const val FALLBACK_ADDRESS_FOR_FEE_CALCULATION = "tb1q867pd52f2azl0n0qfe0kx8w6tpntx3awg2dl28"
    }
}