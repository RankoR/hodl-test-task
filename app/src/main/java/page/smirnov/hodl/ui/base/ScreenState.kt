package page.smirnov.hodl.ui.base

sealed interface ScreenState {
    data object Loading : ScreenState
    data object Content : ScreenState

    sealed interface Error : ScreenState {
        data class StringMessage(
            val message: String,
        ) : Error

        data class StringResourceMessage(
            val messageResourceId: Int,
        ) : Error
    }
}