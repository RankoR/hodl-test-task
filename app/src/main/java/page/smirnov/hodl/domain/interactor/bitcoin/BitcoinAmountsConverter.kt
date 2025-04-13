package page.smirnov.hodl.domain.interactor.bitcoin

import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject

interface BitcoinAmountsConverter {
    fun satoshisToBtc(satoshis: Long): BigDecimal
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
