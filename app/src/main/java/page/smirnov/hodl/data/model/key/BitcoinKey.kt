package page.smirnov.hodl.data.model.key

import org.bitcoinj.crypto.ECKey

/**
 * Represents a derived Bitcoin key pair (using bitcoinj's ECKey) and its
 * P2WPKH address string for the configured network.
 */
data class BitcoinKey(
    val ecKey: ECKey,
    val address: String,
)