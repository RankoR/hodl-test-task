package page.smirnov.hodl

import app.cash.turbine.test
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import page.smirnov.hodl.domain.interactor.bitcoin.BitcoinAmountsConverter
import page.smirnov.hodl.domain.interactor.wallet.WalletInteractor
import page.smirnov.hodl.domain.model.wallet.SendResult
import page.smirnov.hodl.ui.main.FieldState
import page.smirnov.hodl.ui.main.MainViewModel
import page.smirnov.hodl.ui.main.SendButtonState
import page.smirnov.hodl.util.extension.flow.errorFlow
import java.math.BigDecimal

@ExperimentalCoroutinesApi
@RunWith(JUnit4::class)
class MainViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val mockWalletInteractor: WalletInteractor = mockk(relaxed = true)
    private val mockBitcoinAmountsConverter: BitcoinAmountsConverter = mockk(relaxed = true)

    private lateinit var viewModel: MainViewModel

    private val balanceFlow = MutableStateFlow<Long?>(null)

    private val validBtcAddress = "tb1q867pd52f2azl0n0qfe0kx8w6tpntx3awg2dl28"
    private val invalidBtcAddress = "invalid_address"
    private val validAmount = "0.00123456"
    private val invalidAmount = "abc"
    private val zeroAmount = "0"
    private val validAmountSatoshis = 123456L
    private val tooLargeAmount = "9999999"
    private val tooLargeAmountSatoshis = 999999900000000L
    private val calculatedFee = 446L
    private val calculatedFeeBtc = BigDecimal("0.00000446")
    private val testTxId = "6a42a35cc3a73ec02a8514fcb104f3c6c8daf7f297e1bf47c6eae5f35c37a0f9"

    @Before
    fun setup() {
        MockKAnnotations.init(this)

        Dispatchers.setMain(testDispatcher)

        every { mockWalletInteractor.balance } returns balanceFlow
        every { mockBitcoinAmountsConverter.btcToSatoshis(any()) } answers {
            when (val amount = firstArg<BigDecimal>()) {
                BigDecimal(validAmount) -> validAmountSatoshis
                BigDecimal(tooLargeAmount) -> tooLargeAmountSatoshis
                BigDecimal.ZERO -> 0L
                else -> -1L
            }
        }
        every { mockBitcoinAmountsConverter.satoshisToBtc(any()) } answers {
            when (val satoshis = firstArg<Long>()) {
                calculatedFee -> calculatedFeeBtc
                else -> BigDecimal.valueOf(satoshis).divide(BigDecimal("100000000"))
            }
        }
        every { mockWalletInteractor.isAddressValid(validBtcAddress) } returns true
        every { mockWalletInteractor.isAddressValid(invalidBtcAddress) } returns false
        every { mockWalletInteractor.isAddressValid("") } returns false
        every { mockWalletInteractor.calculateFee(any(), any()) } returns flowOf(calculatedFee)

        viewModel = MainViewModel(
            walletInteractor = mockWalletInteractor,
            bitcoinAmountsConverter = mockBitcoinAmountsConverter,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            mainDispatcher = testDispatcher,
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `balance transformation maps satoshis to BTC correctly`() = runTest {
        balanceFlow.value = 12345600L

        viewModel.balanceBtc.test {
            assertEquals(BigDecimal("0.123456"), awaitItem())
        }

        verify { mockBitcoinAmountsConverter.satoshisToBtc(12345600L) }
    }

    @Test
    fun `onAmountChange with valid amount updates state correctly`() = runTest {
        viewModel.amount.test {
            assertEquals("", awaitItem())

            viewModel.onAmountChange(validAmount)

            assertEquals(validAmount, awaitItem())
        }

        verify { mockBitcoinAmountsConverter.btcToSatoshis(BigDecimal(validAmount)) }
    }

    @Test
    fun `onAddressChange updates address state correctly`() = runTest {
        viewModel.address.test {
            assertEquals("", awaitItem())

            viewModel.onAddressChange(validBtcAddress)

            assertEquals(validBtcAddress, awaitItem())
        }
    }

    @Test
    fun `validation with valid inputs enables send button`() = runTest {
        balanceFlow.value = 1000000L

        viewModel.onAddressChange(validBtcAddress)
        viewModel.onAmountChange(validAmount)

        advanceTimeBy(500)

        viewModel.sendButtonState.test {
            assertEquals(SendButtonState.Enabled, awaitItem())
        }

        viewModel.amountFieldState.test {
            assertEquals(FieldState.Ok, awaitItem())
        }

        viewModel.addressFieldState.test {
            assertEquals(FieldState.Ok, awaitItem())
        }

        viewModel.fee.test {
            assertEquals(calculatedFeeBtc, awaitItem())
        }
    }

    @Test
    fun `validation with invalid address shows error state`() = runTest {
        viewModel.onAddressChange(invalidBtcAddress)

        advanceTimeBy(500)

        viewModel.addressFieldState.test {
            val state = awaitItem()
            assertTrue(state is FieldState.Error)
            assertEquals(page.smirnov.hodl.R.string.error_invalid_address, (state as FieldState.Error).messageResId)
        }

        viewModel.sendButtonState.test {
            assertEquals(SendButtonState.Disabled, awaitItem())
        }
    }

    @Test
    fun `validation with empty address keeps field in OK state`() = runTest {
        viewModel.onAddressChange("")

        advanceTimeBy(500)

        viewModel.addressFieldState.test {
            assertEquals(FieldState.Ok, awaitItem())
        }

        viewModel.sendButtonState.test {
            assertEquals(SendButtonState.Disabled, awaitItem())
        }
    }

    @Test
    fun `validation with invalid amount shows error state`() = runTest {
        viewModel.onAddressChange(validBtcAddress)
        viewModel.onAmountChange(invalidAmount)

        advanceTimeBy(500)

        viewModel.amountFieldState.test {
            val state = awaitItem()
            assertTrue(state is FieldState.Error)
            assertEquals(page.smirnov.hodl.R.string.error_invalid_amount, (state as FieldState.Error).messageResId)
        }

        viewModel.sendButtonState.test {
            assertEquals(SendButtonState.Disabled, awaitItem())
        }
    }

    @Test
    fun `validation with too large amount shows not enough funds error`() = runTest {
        balanceFlow.value = 100000L

        viewModel.onAddressChange(validBtcAddress)
        viewModel.onAmountChange(tooLargeAmount)

        advanceTimeBy(500)

        viewModel.amountFieldState.test {
            val state = awaitItem()
            assertTrue(state is FieldState.Error)
            assertEquals(R.string.error_not_enough_funds, (state as FieldState.Error).messageResId)
        }

        viewModel.sendButtonState.test {
            assertEquals(SendButtonState.Disabled, awaitItem())
        }
    }

    @Test
    fun `validation with zero amount keeps field in OK state but disables button`() = runTest {
        viewModel.onAddressChange(validBtcAddress)
        viewModel.onAmountChange(zeroAmount)

        advanceUntilIdle()

        viewModel.amountFieldState.test {
            assertEquals(FieldState.Ok, awaitItem())
        }

        viewModel.fee.test {
            assertNull(awaitItem())
        }

        viewModel.sendButtonState.test {
            assertEquals(SendButtonState.Disabled, awaitItem())
        }
    }

    @Test
    fun `onSendButtonClick with success sends transaction and updates state`() = runTest {
        val txId = testTxId

        every { mockWalletInteractor.send(any(), any()) } returns flowOf(SendResult(txId))

        viewModel.onAddressChange(validBtcAddress)
        viewModel.onAmountChange(validAmount)

        advanceTimeBy(500)

        viewModel.onSendButtonClick()

        viewModel.sendButtonState.test {
            assertEquals(SendButtonState.Sending, awaitItem())
            assertEquals(SendButtonState.Enabled, awaitItem())
        }

        viewModel.sentTransactionTxId.test {
            assertEquals(txId, awaitItem())
        }

        viewModel.amount.test {
            assertEquals("", awaitItem())
        }

        viewModel.address.test {
            assertEquals("", awaitItem())
        }

        verify { mockWalletInteractor.send(validBtcAddress, validAmountSatoshis) }
    }

    @Test
    fun `onSendButtonClick with error shows error message`() = runTest {
        val errorMessage = "Network error"

        every { mockWalletInteractor.send(any(), any()) } returns errorFlow(RuntimeException(errorMessage))

        viewModel.onAddressChange(validBtcAddress)
        viewModel.onAmountChange(validAmount)

        advanceTimeBy(500)

        viewModel.onSendButtonClick()

        viewModel.sendButtonState.test {
            assertEquals(SendButtonState.Sending, awaitItem())
            assertEquals(SendButtonState.Enabled, awaitItem())
        }

        viewModel.errorMessage.test {
            assertNotNull(awaitItem())
        }

        verify { mockWalletInteractor.send(validBtcAddress, validAmountSatoshis) }
    }

    @Test
    fun `onSentDialogDismiss clears txId and updates balance`() = runTest {
        every { mockWalletInteractor.send(any(), any()) } returns flowOf(SendResult(testTxId))

        viewModel.sentTransactionTxId.test {
            assertNull(awaitItem())

            viewModel.onSendButtonClick()

            assertEquals(testTxId, awaitItem())

            viewModel.onSentDialogDismiss()

            assertNull(awaitItem())
        }

        verify { mockWalletInteractor.send(any(), any()) }
    }

    @Test
    fun `validation is debounced on rapid input changes`() = runTest {
        viewModel.onAddressChange(validBtcAddress)

        viewModel.onAmountChange("1")
        viewModel.onAmountChange("1.")
        viewModel.onAmountChange("1.2")
        viewModel.onAmountChange(validAmount)

        advanceTimeBy(100)

        verify(exactly = 0) { mockWalletInteractor.calculateFee(any(), any()) }

        advanceUntilIdle()

        verify(exactly = 1) { mockWalletInteractor.calculateFee(any(), any()) }
    }

    @Test
    fun `validation job is cancelled on onCleared`() = runTest {
        viewModel.onAddressChange(validBtcAddress)
        viewModel.onAmountChange(validAmount)

        viewModel.onCleared()

        advanceTimeBy(500)

        verify(exactly = 0) { mockWalletInteractor.calculateFee(any(), any()) }
    }

    @Test
    fun `validation uses fallback address for fee calculation if address invalid`() = runTest {
        val fallbackAddress = "tb1q867pd52f2azl0n0qfe0kx8w6tpntx3awg2dl28"

        viewModel.onAddressChange("")
        viewModel.onAmountChange(validAmount)

        advanceTimeBy(500)

        viewModel.fee.test {
            advanceUntilIdle()
            assertEquals(calculatedFeeBtc, awaitItem())
        }

        verify { mockWalletInteractor.calculateFee(fallbackAddress, validAmountSatoshis) }
    }
}
