package page.smirnov.hodl.domain.interactor.bitcoin

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import page.smirnov.hodl.data.repository.bitcoin.BitcoinKeyRepository
import page.smirnov.hodl.di.qualifier.DispatcherDefault
import page.smirnov.hodl.domain.model.bitcoin.BitcoinKeyState
import javax.inject.Inject

/**
 * Interactor responsible for providing reactive access to the current state of the user's Bitcoin key.
 * It handles the initial loading or creation of the key.
 */
interface BitcoinKeyInteractor {
    /**
     * A [StateFlow] emitting the current [BitcoinKeyState].
     * Starts with [BitcoinKeyState.Unknown] and transitions to [BitcoinKeyState.Present]
     * if a key is loaded/created successfully, or [BitcoinKeyState.Error] if an issue occurs.
     */
    val bitcoinKey: StateFlow<BitcoinKeyState>
}

internal class BitcoinKeyInteractorImpl @Inject constructor(
    private val bitcoinKeyRepository: BitcoinKeyRepository,
    @DispatcherDefault
    private val defaultDispatcher: CoroutineDispatcher,
) : BitcoinKeyInteractor {

    private val logger = Logger.withTag(LOG_TAG)

    private val _bitcoinKey = MutableStateFlow<BitcoinKeyState>(BitcoinKeyState.Unknown)
    override val bitcoinKey = _bitcoinKey.asStateFlow()

    private val coroutineScope = CoroutineScope(defaultDispatcher + SupervisorJob())

    init {
        initializeBitcoinKey()
    }

    /**
     * Attempts to load an existing Bitcoin key from the repository. If no key exists
     * ([BitcoinKeyRepository.NoKeyException]), it triggers the creation of a new key.
     * Updates the [bitcoinKey] StateFlow with the result ([BitcoinKeyState.Present] or [BitcoinKeyState.Error]).
     *
     * NOTE: This is a simplified implementation that creates a key transparently for the user.
     * In a real wallet app, the key creation process would involve user interaction
     * (e.g., showing the mnemonic, requiring confirmation).
     */
    private fun initializeBitcoinKey() {
        coroutineScope.launch {
            logger.d { "initializeBitcoinKey" }

            bitcoinKeyRepository
                .getKey()
                .catch { t ->
                    if (t is BitcoinKeyRepository.NoKeyException) {
                        logger.i { "initializeBitcoinKey: No key exists, will create" }
                        emit(bitcoinKeyRepository.createKey().first())
                    } else {
                        throw t
                    }
                }
                .flowOn(defaultDispatcher)
                .catch { t ->
                    logger.e(t) { "initializeBitcoinKey: Failed to get key" }

                    _bitcoinKey.value = BitcoinKeyState.Error(t)
                }
                .collect { bitcoinKey ->
                    logger.d { "initializeBitcoinKey: got bitcoin key" }

                    _bitcoinKey.value = BitcoinKeyState.Present(bitcoinKey)
                }
        }
    }

    private companion object {
        private const val LOG_TAG = "BitcoinKeyInteractor"
    }
}