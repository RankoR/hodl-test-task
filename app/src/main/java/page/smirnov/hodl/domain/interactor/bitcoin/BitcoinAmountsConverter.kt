package page.smirnov.hodl.domain.interactor.bitcoin

import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject

/**
 * Utility interface for converting between Bitcoin (BTC) and Satoshis.
 * 1 BTC = 100,000,000 Satoshis.
 */
interface BitcoinAmountsConverter {
    /**
     * Converts an amount in Satoshis (Long) to BTC (BigDecimal).
     * @param satoshis The amount in Satoshis.
     * @return The equivalent amount in BTC as a BigDecimal with appropriate scale.
     */
    fun satoshisToBtc(satoshis: Long): BigDecimal

    /**
     * Converts an amount in BTC (BigDecimal) to Satoshis (Long).
     * Fractional Satoshis are truncated (integer division).
     * @param btc The amount in BTC.
     * @return The equivalent amount in Satoshis as a Long.
     */
    fun btcToSatoshis(btc: BigDecimal): Long
}

internal class BitcoinAmountsConverterImpl @Inject constructor() : BitcoinAmountsConverter {

    override fun satoshisToBtc(satoshis: Long): BigDecimal {
        return satoshis.toBigDecimal().divide(SATOSHIS_IN_BTC, BTC_SCALE, RoundingMode.HALF_UP)
    }

    override fun btcToSatoshis(btc: BigDecimal): Long {
        return (btc * SATOSHIS_IN_BTC).toLong()
    }

    private companion object {
        private const val BTC_SCALE = 8
        private val SATOSHIS_IN_BTC = BigDecimal("100000000") // 1e8
    }
}
