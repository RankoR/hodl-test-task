package page.smirnov.hodl.ui.main

import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import page.smirnov.hodl.R
import page.smirnov.hodl.di.qualifier.DispatcherDefault
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
    @DispatcherMain
    ioDispatcher: CoroutineDispatcher,
    @DispatcherMain
    mainDispatcher: CoroutineDispatcher,
) : BaseViewModel(
    defaultDispatcher = defaultDispatcher,
    ioDispatcher = ioDispatcher,
    mainDispatcher = mainDispatcher
) {
    override val logger = Logger.withTag(LOG_TAG)

    val balance = walletInteractor.balance
    val balanceBtc = balance.map { balanceSatoshis ->
        balanceSatoshis?.let(bitcoinAmountsConverter::satoshisToBtc)
    }

    private val _amount = MutableStateFlow("")
    val amount = _amount.asStateFlow()

    private val _address = MutableStateFlow("")
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

    private fun startListeningForBalance() {
        viewModelScope.launch {
            balance.collect { balance ->
                logger.d("Balance: $balance")
            }
        }
    }

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

    fun onAddressChange(address: String) {
        logger.d { "onAddressChange: address=$address" }

        _address.value = address

        this.recipientAddress = address.trim()

        triggerValidation()
    }

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

    fun onSentDialogDismiss() {
        logger.d { "onSentDialogDismiss" }

        _sentTransactionTxId.value = null
        viewModelScope.launch(defaultDispatcher) {
            walletInteractor.updateBalance()
        }
    }

    private fun triggerValidation() {
        validationJob?.cancel()

        validationJob = viewModelScope.launch(defaultDispatcher) {
            delay(INPUT_DEBOUNCE_MS)

            if (isActive) {
                performValidation()
            }
        }
    }

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

        private const val FALLBACK_ADDRESS_FOR_FEE_CALCULATION = "tb1q867pd52f2azl0n0qfe0kx8w6tpntx3awg2dl28"
    }
}