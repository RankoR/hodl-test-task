package page.smirnov.hodl.data.model.key

import org.bitcoinj.crypto.ECKey

data class BitcoinKey(
    val ecKey: ECKey,
    val address: String,
)