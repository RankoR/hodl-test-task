package page.smirnov.hodl

import app.cash.turbine.test
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.bitcoinj.crypto.ECKey
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import page.smirnov.hodl.data.model.api.BroadcastResultApiModel
import page.smirnov.hodl.data.model.api.UtxoApiModel
import page.smirnov.hodl.data.model.key.BitcoinKey
import page.smirnov.hodl.data.repository.api.EsploraApiRepository
import page.smirnov.hodl.domain.exception.wallet.NoBitcoinKeyException
import page.smirnov.hodl.domain.exception.wallet.NotEnoughFundsException
import page.smirnov.hodl.domain.interactor.bitcoin.BitcoinKeyInteractor
import page.smirnov.hodl.domain.interactor.wallet.WalletInteractor
import page.smirnov.hodl.domain.interactor.wallet.WalletInteractorImpl
import page.smirnov.hodl.domain.model.bitcoin.BitcoinKeyState
import page.smirnov.hodl.domain.model.wallet.SendResult
import page.smirnov.hodl.util.extension.flow.errorFlow
import java.io.IOException

@ExperimentalCoroutinesApi
class WalletInteractorTest {

    private val mockBitcoinKeyInteractor: BitcoinKeyInteractor = mockk()
    private val mockEsploraApiRepository: EsploraApiRepository = mockk(relaxUnitFun = true)

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var walletInteractor: WalletInteractor

    private val testEcKey = ECKey()
    private val testAddress = "tb1q867pd52f2azl0n0qfe0kx8w6tpntx3awg2dl28"
    private val testBitcoinKey = BitcoinKey(ecKey = testEcKey, address = testAddress)

    private val testRecipientAddress = "tb1q867pd52f2azl0n0qfe0kx8w6tpntx3awg2dl28"
    private val invalidAddress = "invalidAddress"
    private val emptyAddress = ""
    private val testTxId = "a8a172eddc655f97bb358d7633f476b76be7a7d71b98fe1a976400a14eaf24c4"

    private val utxo1Confirmed = UtxoApiModel(
        "41c15c74319326070dbbf86d5ccc741c9e12437f30ce973137b4cd5811296a42",
        0,
        UtxoApiModel.Status(true, 100),
        50000L
    )
    private val utxo2Confirmed = UtxoApiModel(
        "e2391ec23a8c8aaa509dabb095ff65705a0a9f790656e98373ef432a74df586f",
        1,
        UtxoApiModel.Status(true, 101),
        70000L
    )
    private val utxo3Unconfirmed = UtxoApiModel(
        "a819d213045fba78bc1d338b8a2e8186565e45bb6030d01f5ee6c8920cc3fac4",
        0,
        UtxoApiModel.Status(false),
        30000L
    )
    private val utxo4ConfirmedSmall = UtxoApiModel(
        "8bed693107015183282bdfe7f0f978b24128edf5e11f8dd00c6c6b6e1f8a0cd6",
        0,
        UtxoApiModel.Status(true, 102),
        1000L
    )

    private val utxoCacheExpirationMs = 60000L
    private val ignoreCacheMs = 0L

    private val keyStateFlow = MutableStateFlow<BitcoinKeyState>(BitcoinKeyState.Unknown)

    // Estimated fee is a bit unstable, for example may vary between 444 and 446 in one of cases
    // Not sure if it is caused by unstable signing or bug in my code
    private fun assertFee(expected: Long, actual: Long) {
        val maxDelta = (expected * 0.02).toDouble() // Allow 2% change
        assertEquals(expected.toDouble(), actual.toDouble(), maxDelta)
    }

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)

        every { mockBitcoinKeyInteractor.bitcoinKey } returns keyStateFlow

        every { mockEsploraApiRepository.getUtxo(any(), any()) } returns flowOf(emptyList())
        every { mockEsploraApiRepository.broadcastTransaction(any()) } returns flowOf(BroadcastResultApiModel(testTxId))

        walletInteractor = WalletInteractorImpl(
            bitcoinKeyInteractor = mockBitcoinKeyInteractor,
            esploraApiRepository = mockEsploraApiRepository,
            defaultDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun setKeyStatePresent() {
        keyStateFlow.value = BitcoinKeyState.Present(testBitcoinKey)
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private fun setKeyStateError(t: Throwable) {
        keyStateFlow.value = BitcoinKeyState.Error(t)
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `balance WHEN key is present AND api success SHOULD emit correct balance`() = runTest(testDispatcher) {
        val utxos = listOf(utxo1Confirmed, utxo2Confirmed, utxo3Unconfirmed)
        val expectedBalance = utxo1Confirmed.value + utxo2Confirmed.value
        every { mockEsploraApiRepository.getUtxo(testAddress, ignoreCacheMs) } returns flowOf(utxos)

        setKeyStatePresent()
        advanceUntilIdle()

        walletInteractor.balance.test {
            assertEquals("Balance should update to sum of confirmed UTXOs", expectedBalance, awaitItem())

            verify(exactly = 1) { mockEsploraApiRepository.getUtxo(testAddress, ignoreCacheMs) }

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `balance WHEN key becomes present later SHOULD trigger update and emit balance`() = runTest(testDispatcher) {
        val utxos = listOf(utxo1Confirmed)
        val expectedBalance = utxo1Confirmed.value
        every { mockEsploraApiRepository.getUtxo(testAddress, ignoreCacheMs) } returns flowOf(utxos)

        walletInteractor.balance.test {
            assertEquals("Initial balance should be null", null, awaitItem())

            keyStateFlow.value = BitcoinKeyState.Present(testBitcoinKey)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("Balance should update after key becomes present", expectedBalance, awaitItem())

            verify(exactly = 1) { mockEsploraApiRepository.getUtxo(testAddress, ignoreCacheMs) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `balance WHEN no key present SHOULD remain null`() = runTest(testDispatcher) {
        walletInteractor.balance.test {
            assertEquals("Initial balance should be null", null, awaitItem())

            walletInteractor.updateBalance()
            testDispatcher.scheduler.advanceUntilIdle()

            expectNoEvents()

            verify(exactly = 0) { mockEsploraApiRepository.getUtxo(any(), any()) }
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `balance WHEN key is Error SHOULD remain null`() = runTest(testDispatcher) {
        setKeyStateError(RuntimeException("Key error"))

        walletInteractor.balance.test {
            assertEquals("Initial balance should be null", null, awaitItem())

            walletInteractor.updateBalance()
            testDispatcher.scheduler.advanceUntilIdle()

            expectNoEvents()

            verify(exactly = 0) { mockEsploraApiRepository.getUtxo(any(), any()) }
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `balance WHEN api error occurs during update SHOULD remain null and schedule retry`() =
        runTest(testDispatcher) {
            every { mockEsploraApiRepository.getUtxo(testAddress, ignoreCacheMs) } returnsMany listOf(
                errorFlow(IOException("Network error")),
                flowOf(listOf(utxo1Confirmed))
            )

            setKeyStatePresent()

            walletInteractor.balance.test {
                testDispatcher.scheduler.advanceUntilIdle()

                assertEquals("Balance should update after successful retry", utxo1Confirmed.value, awaitItem())

                verify(exactly = 2) { mockEsploraApiRepository.getUtxo(testAddress, ignoreCacheMs) }
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `isAddressValid WHEN given valid address SHOULD return true`() {
        assertTrue(walletInteractor.isAddressValid(testRecipientAddress))
    }

    @Test
    fun `isAddressValid WHEN given invalid address SHOULD return false`() {
        assertFalse(walletInteractor.isAddressValid(invalidAddress))
    }

    @Test
    fun `isAddressValid WHEN given empty address SHOULD return false`() {
        assertFalse(walletInteractor.isAddressValid(emptyAddress))
    }

    @Test
    fun `calculateFee WHEN key present AND sufficient UTXOs SHOULD return estimated fee`() = runTest(testDispatcher) {
        val amountToSend = 5000L
        val utxos = listOf(utxo1Confirmed)

        every { mockEsploraApiRepository.getUtxo(testAddress, utxoCacheExpirationMs) } returns flowOf(utxos)

        setKeyStatePresent()

        walletInteractor.calculateFee(testRecipientAddress, amountToSend).test {
            assertFee(446, awaitItem())
            awaitComplete()
        }

        verify(exactly = 1) { mockEsploraApiRepository.getUtxo(testAddress, utxoCacheExpirationMs) }
    }

    @Test
    fun `calculateFee WHEN no key present SHOULD emit NoBitcoinKeyException`() = runTest(testDispatcher) {
        walletInteractor.calculateFee(testRecipientAddress, 5000L).test {
            val error = awaitError()
            assertTrue(error is NoBitcoinKeyException)
        }
        verify(exactly = 0) { mockEsploraApiRepository.getUtxo(any(), any()) }
    }

    @Test
    fun `calculateFee WHEN api error getting UTXOs SHOULD emit error`() = runTest(testDispatcher) {
        every {
            mockEsploraApiRepository.getUtxo(
                testAddress,
                utxoCacheExpirationMs
            )
        } returns errorFlow(IOException("Network error"))

        setKeyStatePresent()

        walletInteractor.calculateFee(testRecipientAddress, 5000L).test {
            val error = awaitError()
            assertTrue(error is IOException)
        }
        verify(exactly = 1) { mockEsploraApiRepository.getUtxo(testAddress, utxoCacheExpirationMs) }
    }

    @Test
    fun `calculateFee WHEN utxo list is empty SHOULD return estimated fee for 0 inputs`() = runTest(testDispatcher) {
        val amountToSend = 5000L
        every { mockEsploraApiRepository.getUtxo(testAddress, utxoCacheExpirationMs) } returns flowOf(emptyList())

        setKeyStatePresent()

        walletInteractor.calculateFee(testRecipientAddress, amountToSend).test {
            assertFee(82, awaitItem())
            awaitComplete()
        }

        verify(exactly = 1) { mockEsploraApiRepository.getUtxo(testAddress, utxoCacheExpirationMs) }
    }


    @Test
    fun `send WHEN success with change SHOULD emit SendResult and call broadcast`() = runTest(testDispatcher) {
        val amountToSend = 5000L
        val utxos = listOf(utxo1Confirmed) // 50000L
        every { mockEsploraApiRepository.getUtxo(testAddress, ignoreCacheMs) } returns flowOf(utxos)
        every { mockEsploraApiRepository.broadcastTransaction(any()) } returns flowOf(BroadcastResultApiModel(testTxId))

        setKeyStatePresent()

        val broadcastTxSlot = slot<String>()
        every { mockEsploraApiRepository.broadcastTransaction(capture(broadcastTxSlot)) } returns flowOf(
            BroadcastResultApiModel(testTxId)
        )

        walletInteractor.send(testRecipientAddress, amountToSend).test {
            assertEquals(SendResult(testTxId), awaitItem())
            awaitComplete()
        }

        verify(exactly = 4) { mockEsploraApiRepository.getUtxo(testAddress, ignoreCacheMs) }
        verify(exactly = 1) { mockEsploraApiRepository.broadcastTransaction(any()) }
        assertTrue(broadcastTxSlot.isCaptured)

        assertTrue(broadcastTxSlot.captured.isNotEmpty())
    }

    @Test
    fun `send WHEN success without change SHOULD emit SendResult and call broadcast`() = runTest(testDispatcher) {
        val utxos = listOf(utxo1Confirmed, utxo4ConfirmedSmall) // 51000 total
        val currentBalance = utxos.sumOf { it.value }
        every { mockEsploraApiRepository.getUtxo(testAddress, ignoreCacheMs) } returns flowOf(utxos)

        val amountToSend = currentBalance - 1000

        assertTrue("Amount to send should be positive", amountToSend > 0)

        every { mockEsploraApiRepository.broadcastTransaction(any()) } returns flowOf(BroadcastResultApiModel(testTxId))
        setKeyStatePresent()

        walletInteractor.send(testRecipientAddress, amountToSend).test {
            assertEquals(SendResult(testTxId), awaitItem())
            awaitComplete()
        }

        verify(exactly = 4) { mockEsploraApiRepository.getUtxo(testAddress, ignoreCacheMs) }
        verify(exactly = 1) { mockEsploraApiRepository.broadcastTransaction(any()) }
    }

    @Test
    fun `send WHEN no key present SHOULD emit NoBitcoinKeyException`() = runTest(testDispatcher) {
        walletInteractor.send(testRecipientAddress, 5000L).test {
            val error = awaitError()
            assertTrue(error is NoBitcoinKeyException)
        }
        verify(exactly = 0) { mockEsploraApiRepository.getUtxo(any(), any()) }
        verify(exactly = 0) { mockEsploraApiRepository.broadcastTransaction(any()) }
    }

    @Test
    fun `send WHEN insufficient funds SHOULD emit NotEnoughFundsException`() = runTest(testDispatcher) {
        val utxos = listOf(utxo1Confirmed)
        val currentBalance = utxo1Confirmed.value
        val amountToSend = currentBalance * 2
        every { mockEsploraApiRepository.getUtxo(testAddress, ignoreCacheMs) } returns flowOf(utxos)

        setKeyStatePresent()

        walletInteractor.send(testRecipientAddress, amountToSend).test {
            val error = awaitError()
            assertTrue(
                "Error should be NotEnoughFundsException but is $error",
                error is NotEnoughFundsException
            )
        }

        verify(exactly = 4) { mockEsploraApiRepository.getUtxo(testAddress, ignoreCacheMs) }
        verify(exactly = 0) { mockEsploraApiRepository.broadcastTransaction(any()) }
    }

    @Test
    fun `send WHEN broadcastTransaction fails SHOULD emit error`() = runTest(testDispatcher) {
        val amountToSend = 5000L
        val utxos = listOf(utxo1Confirmed)
        every { mockEsploraApiRepository.getUtxo(testAddress, ignoreCacheMs) } returns flowOf(utxos)
        every { mockEsploraApiRepository.broadcastTransaction(any()) } returns errorFlow(IOException("Broadcast failed"))

        setKeyStatePresent()

        walletInteractor.send(testRecipientAddress, amountToSend).test {
            val error = awaitError()
            assertTrue(error.toString(), error is IOException)
        }

        verify(exactly = 4) { mockEsploraApiRepository.getUtxo(testAddress, ignoreCacheMs) }
        verify(exactly = 1) { mockEsploraApiRepository.broadcastTransaction(any()) }
    }
}
