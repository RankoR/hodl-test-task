package page.smirnov.hodl

import androidx.activity.compose.setContent
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import page.smirnov.hodl.ui.base.ScreenState
import page.smirnov.hodl.ui.main.FieldState
import page.smirnov.hodl.ui.main.MainScreen
import page.smirnov.hodl.ui.main.MainViewModel
import page.smirnov.hodl.ui.main.SendButtonState
import page.smirnov.hodl.ui.theme.HodlTestTaskTheme
import java.math.BigDecimal

@ExperimentalMaterial3Api
@RunWith(AndroidJUnit4::class)
class MainScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private lateinit var mockViewModel: MainViewModel

    private val screenStateFlow = MutableStateFlow<ScreenState>(ScreenState.Content)
    private val errorMessageFlow = MutableStateFlow<String?>(null)
    private val balanceBtcFlow = MutableStateFlow<BigDecimal?>(null)
    private val amountFieldStateFlow = MutableStateFlow<FieldState>(FieldState.Ok)
    private val addressFieldStateFlow = MutableStateFlow<FieldState>(FieldState.Ok)
    private val sendButtonStateFlow = MutableStateFlow<SendButtonState>(SendButtonState.Disabled)
    private val feeFlow = MutableStateFlow<BigDecimal?>(null)
    private val amountFlow = MutableStateFlow("")
    private val addressFlow = MutableStateFlow("")
    private val sentTransactionTxIdFlow = MutableStateFlow<String?>(null)

    @Before
    fun setUp() {
        mockViewModel = mockk(relaxed = true)

        every { mockViewModel.screenState } returns screenStateFlow.asStateFlow()
        every { mockViewModel.errorMessage } returns errorMessageFlow.asStateFlow()
        every { mockViewModel.balanceBtc } returns balanceBtcFlow.asStateFlow()
        every { mockViewModel.amountFieldState } returns amountFieldStateFlow.asStateFlow()
        every { mockViewModel.addressFieldState } returns addressFieldStateFlow.asStateFlow()
        every { mockViewModel.sendButtonState } returns sendButtonStateFlow.asStateFlow()
        every { mockViewModel.fee } returns feeFlow.asStateFlow()
        every { mockViewModel.amount } returns amountFlow.asStateFlow()
        every { mockViewModel.address } returns addressFlow.asStateFlow()
        every { mockViewModel.sentTransactionTxId } returns sentTransactionTxIdFlow.asStateFlow()

        composeTestRule.activity.setContent {
            HodlTestTaskTheme {
                MainScreen(viewModel = mockViewModel)
            }
        }
    }

    @Test
    fun mainScreen_whenLoading_showsLoadingIndicator() {
        screenStateFlow.value = ScreenState.Loading

        composeTestRule.onNodeWithTag("CircularProgressIndicator", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNode(isRoot()).printToLog("LoadingState")
        composeTestRule.onNodeWithText(getString(R.string.hint_amount_to_send)).assertDoesNotExist()
        composeTestRule.onNodeWithText(getString(R.string.hint_receiver_address)).assertDoesNotExist()
    }

    @Test
    fun mainScreen_whenContentState_showsContent() {
        screenStateFlow.value = ScreenState.Content
        balanceBtcFlow.value = BigDecimal("1.23456789")
        amountFlow.value = "0.1"
        addressFlow.value = "test_address"
        feeFlow.value = BigDecimal("0.0001")
        sendButtonStateFlow.value = SendButtonState.Enabled

        composeTestRule.onNodeWithText(getString(R.string.format_wallet_balance_btc, BigDecimal("1.23456789")))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(getString(R.string.hint_amount_to_send)).assertIsDisplayed()
        composeTestRule.onNodeWithText("0.1").assertIsDisplayed()
        composeTestRule.onNodeWithText(getString(R.string.hint_receiver_address)).assertIsDisplayed()
        composeTestRule.onNodeWithText("test_address").assertIsDisplayed()
        composeTestRule.onNodeWithText(getString(R.string.format_transaction_fee, BigDecimal("0.0001")))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(getString(R.string.title_send_button)).assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun mainScreen_whenBalanceIsNull_showsLoadingBalanceText() {
        screenStateFlow.value = ScreenState.Content
        balanceBtcFlow.value = null

        composeTestRule.onNodeWithText(getString(R.string.title_wallet_balance_loading)).assertIsDisplayed()
    }

    @Test
    fun mainScreen_whenScreenErrorState_showsErrorScreenAndRetry() {
        val errorMessage = "Failed to load data"
        screenStateFlow.value = ScreenState.Error.StringMessage(errorMessage)

        composeTestRule.onNodeWithText(errorMessage).assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry").assertIsDisplayed().performClick()

        verify { mockViewModel.onRetryClick() }
    }

    @Test
    fun mainScreen_whenViewModelErrorState_showsErrorMessageDialog() {
        val errorMessage = "Something went wrong!"
        errorMessageFlow.value = errorMessage

        composeTestRule.onNodeWithText(getString(R.string.error_generic)).assertIsDisplayed()
        composeTestRule.onNodeWithText(errorMessage).assertIsDisplayed()
        composeTestRule.onNodeWithText(getString(R.string.error_dismiss_button)).assertIsDisplayed().performClick()

        verify { mockViewModel.resetErrorMessage() }
    }

    @Test
    fun amountInput_whenValueChanged_callsViewModel() {
        val testAmount = "0.5"
        composeTestRule.onNodeWithText(getString(R.string.hint_amount_to_send))
            .performTextInput(testAmount)

        verify { mockViewModel.onAmountChange(testAmount) }
    }

    @Test
    fun amountInput_whenStateIsError_showsError() {
        amountFieldStateFlow.value = FieldState.Error(R.string.error_invalid_amount)

        composeTestRule.onNodeWithText(getString(R.string.error_invalid_amount)).assertIsDisplayed()
    }

    @Test
    fun addressInput_whenValueChanged_callsViewModel() {
        val testAddress = "bc1q..."
        composeTestRule.onNodeWithText(getString(R.string.hint_receiver_address))
            .performTextInput(testAddress)

        verify { mockViewModel.onAddressChange(testAddress) }
    }


    @Test
    fun addressInput_whenStateIsError_showsError() {
        addressFieldStateFlow.value = FieldState.Error(R.string.error_invalid_address)

        composeTestRule.onNodeWithText(getString(R.string.error_invalid_address)).assertIsDisplayed()
    }

    @Test
    fun feeLabel_whenFeeIsNotNull_isDisplayed() {
        feeFlow.value = BigDecimal("0.000123")
        composeTestRule.onNodeWithText(getString(R.string.format_transaction_fee, BigDecimal("0.000123")))
            .assertIsDisplayed()
    }

    @Test
    fun sendButton_whenStateIsEnabled_isEnabled() {
        sendButtonStateFlow.value = SendButtonState.Enabled
        composeTestRule.onNodeWithText(getString(R.string.title_send_button)).assertIsEnabled()
    }

    @Test
    fun sendButton_whenStateIsDisabled_isNotEnabled() {
        sendButtonStateFlow.value = SendButtonState.Disabled
        composeTestRule.onNodeWithText(getString(R.string.title_send_button)).assertIsNotEnabled()
    }

    @Test
    fun sendButton_whenStateIsSending_isNotEnabled() {
        sendButtonStateFlow.value = SendButtonState.Sending
        composeTestRule.onNodeWithText(getString(R.string.title_send_button)).assertIsNotEnabled()
    }

    @Test
    fun sendButton_whenClicked_callsViewModel() {
        sendButtonStateFlow.value = SendButtonState.Enabled
        composeTestRule.onNodeWithText(getString(R.string.title_send_button)).performClick()

        verify { mockViewModel.onSendButtonClick() }
    }

    @Test
    fun sentTransactionDialog_whenTxIdIsNotNull_isDisplayed() {
        val txId = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f61234"
        val truncatedTxId = "${txId.take(10)}...${txId.takeLast(10)}"
        sentTransactionTxIdFlow.value = txId

        composeTestRule.onNodeWithText(getString(R.string.title_transaction_success)).assertIsDisplayed()
        composeTestRule.onNodeWithText(getString(R.string.label_transaction_id)).assertIsDisplayed()
        composeTestRule.onNodeWithText(truncatedTxId).assertIsDisplayed()
        composeTestRule.onNodeWithText(getString(R.string.title_send_more_button)).assertIsDisplayed()
    }

    @Test
    fun sentTransactionDialog_whenTxIdIsShort_isDisplayedWithoutTruncation() {
        val txId = "a1b2c3d4e5f6"
        sentTransactionTxIdFlow.value = txId

        composeTestRule.onNodeWithText(getString(R.string.title_transaction_success)).assertIsDisplayed()
        composeTestRule.onNodeWithText(getString(R.string.label_transaction_id)).assertIsDisplayed()
        composeTestRule.onNodeWithText(txId).assertIsDisplayed() // Check non-truncated
        composeTestRule.onNodeWithText(getString(R.string.title_send_more_button)).assertIsDisplayed()
    }

    @Test
    fun sentTransactionDialog_whenSendMoreClicked_callsViewModel() {
        val txId = "a1b2c3d4e5f6"
        sentTransactionTxIdFlow.value = txId

        composeTestRule.onNodeWithText(getString(R.string.title_send_more_button)).performClick()

        verify { mockViewModel.onSentDialogDismiss() }
    }

    @Test
    fun sentTransactionDialog_whenDismissed_callsViewModel() {
        val txId = "a1b2c3d4e5f6"
        sentTransactionTxIdFlow.value = txId

        composeTestRule.onNodeWithText(getString(R.string.title_send_more_button)).performClick()
        verify { mockViewModel.onSentDialogDismiss() }
    }

    @Test
    fun sentTransactionDialog_whenTxIdClicked_attemptsToOpenUrl() {
        val txId = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f61234"
        val truncatedTxId = "${txId.take(10)}...${txId.takeLast(10)}"
        sentTransactionTxIdFlow.value = txId

        composeTestRule.onNodeWithText(truncatedTxId).assertHasClickAction()
    }

    private fun getString(id: Int, vararg formatArgs: Any): String {

        return composeTestRule.activity.getString(id, *formatArgs)
    }
}
