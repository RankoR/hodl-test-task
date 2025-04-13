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

interface BitcoinKeyInteractor {
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
     * Load or create bitcoin key if not exist.
     * NOTE: This is a simple implementation that creates key transparently for user.
     * In a real app we should split this process to show mnemonic to user and perform other onboarding actions
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