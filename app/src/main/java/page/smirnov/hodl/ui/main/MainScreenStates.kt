package page.smirnov.hodl.ui.main

sealed interface SendButtonState {
    data object Disabled : SendButtonState
    data object Enabled : SendButtonState
    data object Sending : SendButtonState
}

sealed interface FieldState {
    data object Ok : FieldState

    data class Error(
        val messageResId: Int,
    ) : FieldState
}