package page.smirnov.hodl.ui.base

import androidx.lifecycle.ViewModel
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

abstract class BaseViewModel(
    protected val defaultDispatcher: CoroutineDispatcher,
    protected val ioDispatcher: CoroutineDispatcher,
    protected val mainDispatcher: CoroutineDispatcher,
) : ViewModel() {

    protected abstract val logger: Logger

    private val _screenState = MutableStateFlow<ScreenState>(ScreenState.Content)
    val screenState: StateFlow<ScreenState> = _screenState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    protected fun setScreenState(state: ScreenState) {
        _screenState.value = state
    }

    protected fun setLoadingScreenState() {
        setScreenState(ScreenState.Loading)
    }

    protected fun setContentScreenState() {
        setScreenState(ScreenState.Content)
    }

    protected fun setErrorScreenState(message: String) {
        setScreenState(ScreenState.Error.StringMessage(message = message))
    }

    protected fun setErrorScreenState(throwable: Throwable) {
        setScreenState(
            ScreenState.Error.StringMessage(
                message = throwable.message ?: throwable.toString()
            )
        )
    }

    protected fun <T> Flow<T>.onErrorShowMessage(
        getMessage: (throwable: Throwable) -> String = { throwable ->
            throwable.message ?: throwable.toString()
        },
    ): Flow<T> {
        return this.catch { throwable ->
            val message = getMessage(throwable)

            Logger.e(throwable = throwable) { message }

            withContext(mainDispatcher) {
                showErrorMessage(message = message)
                setContentScreenState()
            }
        }
    }

    fun showErrorMessage(message: String) {
        _errorMessage.value = message
    }

    fun resetErrorMessage() {
        _errorMessage.value = null
    }

    open fun onRetryClick() {
    }
}