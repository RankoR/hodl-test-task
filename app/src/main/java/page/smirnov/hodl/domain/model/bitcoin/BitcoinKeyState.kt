package page.smirnov.hodl.domain.model.bitcoin

import page.smirnov.hodl.data.model.key.BitcoinKey

/**
 * Represents the possible states of the user's Bitcoin key availability and validity.
 */
sealed interface BitcoinKeyState {

    /**
     * The initial state before the key has been loaded or checked
     */
    data object Unknown : BitcoinKeyState

    /**
     * Indicates that a valid Bitcoin key is present and available for use
     */
    data class Present(
        val bitcoinKey: BitcoinKey,
    ) : BitcoinKeyState

    /**
     * Indicates that an error occurred while trying to load or create the Bitcoin key
     */
    data class Error(
        val throwable: Throwable,
    ) : BitcoinKeyState
}