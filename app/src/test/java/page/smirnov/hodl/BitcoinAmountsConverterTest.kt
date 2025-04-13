package page.smirnov.hodl

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import page.smirnov.hodl.domain.interactor.bitcoin.BitcoinAmountsConverter
import page.smirnov.hodl.domain.interactor.bitcoin.BitcoinAmountsConverterImpl
import java.math.BigDecimal
import java.math.RoundingMode

class BitcoinAmountsConverterTest {

    private lateinit var converter: BitcoinAmountsConverter
    private val satoshisInBtc = BigDecimal("100000000")
    private val btcScale = 8

    @Before
    fun setUp() {
        converter = BitcoinAmountsConverterImpl()
    }

    @Test
    fun `satoshisToBtc converts 100000000 satoshis to 1 BTC`() {
        val satoshis = 100000000L
        val expectedBtc = BigDecimal("1")

        val result = converter.satoshisToBtc(satoshis)

        assertEquals(0, expectedBtc.compareTo(result))
        assertEquals(0, expectedBtc.compareTo(result.stripTrailingZeros()))
    }

    @Test
    fun `satoshisToBtc converts 1 satoshi to 0,00000001 BTC`() {
        val satoshis = 1L
        val expectedBtc = BigDecimal("0.00000001")

        val result = converter.satoshisToBtc(satoshis)

        assertEquals(0, expectedBtc.compareTo(result))
        assertEquals(btcScale, result.scale())
        assertEquals(expectedBtc.toPlainString(), result.toPlainString())
    }

    @Test
    fun `satoshisToBtc converts 0 satoshis to 0 BTC`() {
        val satoshis = 0L
        val expectedBtc = BigDecimal.ZERO

        val result = converter.satoshisToBtc(satoshis)

        assertEquals(0, expectedBtc.compareTo(result))
        assertEquals(btcScale, result.scale())
        assertEquals("0.00000000", result.toPlainString())
        assertEquals("0", result.stripTrailingZeros().toPlainString())
    }

    @Test
    fun `satoshisToBtc converts max Long satoshis without overflow`() {
        val satoshis = Long.MAX_VALUE
        val expectedBtc = BigDecimal(Long.MAX_VALUE).divide(satoshisInBtc, btcScale, RoundingMode.HALF_UP)

        val result = converter.satoshisToBtc(satoshis)

        assertEquals(0, expectedBtc.compareTo(result))
    }

    @Test
    fun `satoshisToBtc converts negative satoshis correctly`() {
        val satoshis = -50000000L
        val expectedBtc = BigDecimal("-0.5")

        val result = converter.satoshisToBtc(satoshis)

        assertEquals(0, expectedBtc.compareTo(result))
        assertEquals(expectedBtc.toPlainString(), result.stripTrailingZeros().toPlainString())
        assertEquals(btcScale, result.scale())
    }

    @Test
    fun `btcToSatoshis converts 1 BTC to 100000000 satoshis`() {
        val btc = BigDecimal("1.0")
        val expectedSatoshis = 100000000L
        val result = converter.btcToSatoshis(btc)
        assertEquals(expectedSatoshis, result)

        val btcScale8 = BigDecimal("1.00000000")
        val resultScale8 = converter.btcToSatoshis(btcScale8)
        assertEquals(expectedSatoshis, resultScale8)

        val btcScale0 = BigDecimal("1")
        val resultScale0 = converter.btcToSatoshis(btcScale0)
        assertEquals(expectedSatoshis, resultScale0)
    }

    @Test
    fun `btcToSatoshis converts 0,00000001 BTC to 1 satoshi`() {
        val btc = BigDecimal("0.00000001")
        val expectedSatoshis = 1L
        val result = converter.btcToSatoshis(btc)
        assertEquals(expectedSatoshis, result)
    }

    @Test
    fun `btcToSatoshis converts 0 BTC to 0 satoshis`() {
        val btc = BigDecimal.ZERO
        val expectedSatoshis = 0L
        val result = converter.btcToSatoshis(btc)
        assertEquals(expectedSatoshis, result)
    }

    @Test
    fun `btcToSatoshis handles fractional satoshis by truncating`() {
        val btc = BigDecimal("0.000000015")
        val expectedSatoshis = 1L
        val result = converter.btcToSatoshis(btc)
        assertEquals(expectedSatoshis, result)

        val btcNegative = BigDecimal("-0.000000015")
        val expectedSatoshisNegative = -1L
        val resultNegative = converter.btcToSatoshis(btcNegative)
        assertEquals(expectedSatoshisNegative, resultNegative)
    }

    @Test
    fun `btcToSatoshis converts negative BTC correctly`() {
        val btc = BigDecimal("-0.5")
        val expectedSatoshis = -50000000L
        val result = converter.btcToSatoshis(btc)
        assertEquals(expectedSatoshis, result)
    }

    @Test
    fun `conversion is reversible for integer satoshi values`() {
        val originalSatoshis = 123456789L

        val btc = converter.satoshisToBtc(originalSatoshis)
        val satoshis = converter.btcToSatoshis(btc)

        assertEquals(originalSatoshis, satoshis)

        val originalSatoshisZero = 0L
        val btcZero = converter.satoshisToBtc(originalSatoshisZero)
        val satoshisZero = converter.btcToSatoshis(btcZero)
        assertEquals(originalSatoshisZero, satoshisZero)

        val originalSatoshisNegative = -98765432L
        val btcNegative = converter.satoshisToBtc(originalSatoshisNegative)
        val satoshisNegative = converter.btcToSatoshis(btcNegative)
        assertEquals(originalSatoshisNegative, satoshisNegative)
    }

    @Test
    fun `large BTC values convert correctly`() {
        val btc = BigDecimal("21000000")
        val expectedSatoshis = 21000000L * 100000000L

        val result = converter.btcToSatoshis(btc)

        assertEquals(expectedSatoshis, result)
    }
}
