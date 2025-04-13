@file:OptIn(ExperimentalMaterial3Api::class)

package page.smirnov.hodl.ui.base

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import page.smirnov.hodl.R

@Composable
fun <T : BaseViewModel> BaseScreen(
    viewModel: T,
    content: @Composable () -> Unit,
) {
    val screenState by viewModel.screenState.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        when (val state = screenState) {
            ScreenState.Loading -> {
                CircularProgressIndicator(
                    modifier = Modifier
                        .width(64.dp)
                        .align(Alignment.Center),
                    color = MaterialTheme.colorScheme.secondary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }

            ScreenState.Content -> {
                content()
            }

            is ScreenState.Error.StringMessage -> {
                ErrorScreen(
                    message = state.message,
                    onRetryClick = viewModel::onRetryClick,
                )
            }

            is ScreenState.Error.StringResourceMessage -> {
                ErrorScreen(
                    message = stringResource(state.messageResourceId),
                    onRetryClick = viewModel::onRetryClick,
                )
            }
        }

        errorMessage?.let { message ->
            ErrorMessage(
                message = message,
                onDismiss = viewModel::resetErrorMessage,
            )
        }
    }
}

@Composable
fun ErrorScreen(
    message: String,
    onRetryClick: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            text = message,
            modifier = Modifier.align(Alignment.Center)
        )
        Button(
            onClick = onRetryClick,
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Text("Retry")
        }
    }
}

@Composable
fun ErrorMessage(
    message: String,
    onDismiss: () -> Unit,
) {
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
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.error_generic),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    stringResource(R.string.error_dismiss_button),
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}