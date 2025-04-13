@file:OptIn(ExperimentalMaterial3Api::class)

package page.smirnov.hodl.ui.main

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import page.smirnov.hodl.R
import page.smirnov.hodl.ui.base.BaseScreen
import page.smirnov.hodl.ui.icon.AppIcons
import page.smirnov.hodl.ui.icon.Bitcoin
import page.smirnov.hodl.ui.icon.Paste
import java.math.BigDecimal

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel(),
) {
    BaseScreen(viewModel) {

        val balance by viewModel.balanceBtc.collectAsState(initial = null)

        val amountFieldState by viewModel.amountFieldState.collectAsState()
        val addressFieldState by viewModel.addressFieldState.collectAsState()
        val sendButtonState by viewModel.sendButtonState.collectAsState()

        val fee by viewModel.fee.collectAsState()

        val amount by viewModel.amount.collectAsState()
        val address by viewModel.address.collectAsState()

        val sentTransactionTxId by viewModel.sentTransactionTxId.collectAsState()

        val focusManager = LocalFocusManager.current
        val clipboardManager = LocalClipboardManager.current

        Column(
            modifier = modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Header(
                balance = balance,
            )

            Spacer(modifier = Modifier.height(16.dp))

            AmountInput(
                amount = amount,
                fieldState = amountFieldState,
                onValueChange = viewModel::onAmountChange,
                onAction = { focusManager.moveFocus(FocusDirection.Down) },
            )

            Spacer(modifier = Modifier.height(8.dp))

            AddressInput(
                address = address,
                fieldState = addressFieldState,
                onValueChange = viewModel::onAddressChange,
                onAction = { focusManager.clearFocus() },
                onPasteClick = {
                    clipboardManager.getText()?.let { clipboardText ->
                        viewModel.onAddressChange(clipboardText.text)
                        focusManager.clearFocus()
                    }
                },
            )

            FeeLabel(fee = fee)

            Spacer(modifier = Modifier.height(8.dp))

            // Send Button
            Button(
                onClick = viewModel::onSendButtonClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                enabled = sendButtonState is SendButtonState.Enabled,
            ) {
                Text(
                    stringResource(R.string.title_send_button),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize)
                )
            }

            sentTransactionTxId?.let { txId ->
                SentTransactionDialog(
                    txId = txId,
                    onDismiss = viewModel::onSentDialogDismiss,
                    onSendMoreClick = viewModel::onSentDialogDismiss,
                )
            }
        }
    }
}

@Composable
private fun AmountInput(
    amount: String,
    fieldState: FieldState,
    onValueChange: (String) -> Unit,
    onAction: () -> Unit,
) {
    OutlinedTextField(
        value = amount,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.hint_amount_to_send)) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Next
        ),
        keyboardActions = KeyboardActions(
            onNext = { onAction() }
        ),
        singleLine = true,
        leadingIcon = {
            Icon(
                imageVector = AppIcons.Bitcoin,
                contentDescription = null
            )
        },
        isError = fieldState is FieldState.Error,
    )

    FieldStateIndicator(fieldState = fieldState)
}

@Composable
private fun Header(
    balance: BigDecimal?,
) {
    // Bitcoin Icon
    Image(
        painter = painterResource(id = R.drawable.bitcoin_btc_logo),
        contentDescription = stringResource(R.string.title_wallet_screen),
        modifier = Modifier.size(72.dp),
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Bitcoin Balance
    val balanceString = balance?.let {
        stringResource(R.string.format_wallet_balance_btc, it)
    } ?: stringResource(R.string.title_wallet_balance_loading)

    Text(
        text = balanceString,
        style = MaterialTheme.typography.headlineLarge,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun AddressInput(
    address: String,
    fieldState: FieldState,
    onValueChange: (String) -> Unit,
    onAction: () -> Unit,
    onPasteClick: () -> Unit,
) {
    OutlinedTextField(
        value = address,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.hint_receiver_address)) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(
            onDone = { onAction() }
        ),
        singleLine = true,
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.AccountBox,
                contentDescription = null
            )
        },
        trailingIcon = {
            IconButton(
                onClick = onPasteClick,
            ) {
                Icon(
                    imageVector = AppIcons.Paste,
                    contentDescription = stringResource(R.string.content_description_paste_address),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        isError = fieldState is FieldState.Error,
    )

    FieldStateIndicator(fieldState = fieldState)
}

@Composable
private fun FeeLabel(
    fee: BigDecimal?,
) {
    val text = fee?.let { stringResource(R.string.format_transaction_fee, it) } ?: " "
    val alpha = if (fee != null) 1f else 0f

    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.secondary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha), // A bit hacky, but doesn't move layout when appearing
        maxLines = 1,
    )
}

@Composable
private fun FieldStateIndicator(
    fieldState: FieldState,
) {
    val errorText = (fieldState as? FieldState.Error)?.let { stringResource(it.messageResId) }
    val text = errorText ?: " "
    val alpha = if (errorText != null) 1f else 0f

    Text(
        text = text,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .alpha(alpha), // A bit hacky, but doesn't move layout when appearing
        maxLines = 1,
    )
}


@Composable
private fun SentTransactionDialog(
    txId: String,
    onDismiss: () -> Unit,
    onSendMoreClick: () -> Unit,
) {
    val context = LocalContext.current
    val openTransactionUrl = {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://mempool.space/signet/tx/$txId"))
        context.startActivity(intent)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Success icon
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Title
            Text(
                text = stringResource(R.string.title_transaction_success),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Transaction ID text
            Text(
                text = stringResource(R.string.label_transaction_id),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            val displayTxId = if (txId.length > 20) {
                "${txId.take(10)}...${txId.takeLast(10)}"
            } else {
                txId
            }

            Text(
                text = displayTxId,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clickable { openTransactionUrl() }
                    .padding(8.dp),
                textDecoration = TextDecoration.Underline
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Send More button
            Button(
                onClick = onSendMoreClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    stringResource(R.string.title_send_more_button),
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
