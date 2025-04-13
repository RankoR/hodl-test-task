package page.smirnov.hodl.domain.model.bitcoin

import page.smirnov.hodl.data.model.key.BitcoinKey

sealed interface BitcoinKeyState {

    data object Unknown : BitcoinKeyState

    data class Present(
        val bitcoinKey: BitcoinKey,
    ) : BitcoinKeyState

    data class Error(
        val throwable: Throwable,
    ) : BitcoinKeyState
}