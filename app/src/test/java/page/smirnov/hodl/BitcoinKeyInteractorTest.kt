package page.smirnov.hodl

import app.cash.turbine.test
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.bitcoinj.crypto.ECKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import page.smirnov.hodl.data.model.key.BitcoinKey
import page.smirnov.hodl.data.repository.bitcoin.BitcoinKeyRepository
import page.smirnov.hodl.domain.interactor.bitcoin.BitcoinKeyInteractorImpl
import page.smirnov.hodl.domain.model.bitcoin.BitcoinKeyState
import page.smirnov.hodl.util.extension.flow.errorFlow

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class BitcoinKeyInteractorTest {

    private val mockBitcoinKeyRepository: BitcoinKeyRepository = mockk(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var bitcoinKeyInteractor: BitcoinKeyInteractorImpl

    private val mockEcKey: ECKey = mockk(relaxed = true)
    private val testAddress = "tb1qtestaddress"
    private val testBitcoinKey = BitcoinKey(ecKey = mockEcKey, address = testAddress)

    @Before
    fun setup() {
        MockKAnnotations.init(this)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `initializeBitcoinKey WHEN key exists SHOULD set Present state`() = runTest(testDispatcher) {
        coEvery { mockBitcoinKeyRepository.getKey() } returns flowOf(testBitcoinKey)

        bitcoinKeyInteractor = BitcoinKeyInteractorImpl(
            bitcoinKeyRepository = mockBitcoinKeyRepository,
            defaultDispatcher = testDispatcher
        )

        bitcoinKeyInteractor.bitcoinKey.test {
            val initialState = awaitItem()
            assertTrue(initialState is BitcoinKeyState.Unknown)

            val presentState = awaitItem()
            assertTrue(presentState is BitcoinKeyState.Present)
            assertEquals(testBitcoinKey, (presentState as BitcoinKeyState.Present).bitcoinKey)

            cancelAndIgnoreRemainingEvents()
        }

        verify(exactly = 1) { mockBitcoinKeyRepository.getKey() }
        verify(exactly = 0) { mockBitcoinKeyRepository.createKey() }
    }

    @Test
    fun `initializeBitcoinKey WHEN key does not exist SHOULD create key and set Present state`() =
        runTest(testDispatcher) {
            every { mockBitcoinKeyRepository.getKey() } returns errorFlow(BitcoinKeyRepository.NoKeyException())
            every { mockBitcoinKeyRepository.createKey() } returns flowOf(testBitcoinKey)

            bitcoinKeyInteractor = BitcoinKeyInteractorImpl(
                bitcoinKeyRepository = mockBitcoinKeyRepository,
                defaultDispatcher = testDispatcher
            )

            bitcoinKeyInteractor.bitcoinKey.test {
                val initialState = awaitItem()
                assertTrue(initialState is BitcoinKeyState.Unknown)

                val presentState = awaitItem()
                assertTrue(presentState is BitcoinKeyState.Present)
                assertEquals(testBitcoinKey, (presentState as BitcoinKeyState.Present).bitcoinKey)

                cancelAndIgnoreRemainingEvents()
            }

            verify(exactly = 1) { mockBitcoinKeyRepository.getKey() }
            verify(exactly = 1) { mockBitcoinKeyRepository.createKey() }
        }

    @Test
    fun `initializeBitcoinKey WHEN repository throws error other than NoKeyException SHOULD set Error state`() =
        runTest(testDispatcher) {
            val testException = RuntimeException("Test error")
            every { mockBitcoinKeyRepository.getKey() } returns errorFlow(testException)

            bitcoinKeyInteractor = BitcoinKeyInteractorImpl(
                bitcoinKeyRepository = mockBitcoinKeyRepository,
                defaultDispatcher = testDispatcher
            )

            bitcoinKeyInteractor.bitcoinKey.test {
                val initialState = awaitItem()
                assertTrue(initialState is BitcoinKeyState.Unknown)

                val errorState = awaitItem()
                assertTrue(errorState is BitcoinKeyState.Error)
                assertEquals(testException, (errorState as BitcoinKeyState.Error).throwable)

                cancelAndIgnoreRemainingEvents()
            }

            verify(exactly = 1) { mockBitcoinKeyRepository.getKey() }
            verify(exactly = 0) { mockBitcoinKeyRepository.createKey() }
        }

    @Test
    fun `initializeBitcoinKey WHEN createKey throws error SHOULD set Error state`() = runTest(testDispatcher) {
        val testException = RuntimeException("Create key error")
        every { mockBitcoinKeyRepository.getKey() } returns errorFlow(BitcoinKeyRepository.NoKeyException())
        every { mockBitcoinKeyRepository.createKey() } returns errorFlow(testException)

        bitcoinKeyInteractor = BitcoinKeyInteractorImpl(
            bitcoinKeyRepository = mockBitcoinKeyRepository,
            defaultDispatcher = testDispatcher
        )

        bitcoinKeyInteractor.bitcoinKey.test {
            val initialState = awaitItem()
            assertTrue(initialState is BitcoinKeyState.Unknown)

            val errorState = awaitItem()
            assertTrue(errorState is BitcoinKeyState.Error)
            assertEquals(testException, (errorState as BitcoinKeyState.Error).throwable)

            cancelAndIgnoreRemainingEvents()
        }

        verify(exactly = 1) { mockBitcoinKeyRepository.getKey() }
        verify(exactly = 1) { mockBitcoinKeyRepository.createKey() }
    }

    @Test
    fun `initializeBitcoinKey WHEN key exists but createKey needed later SHOULD handle state transition correctly`() =
        runTest(testDispatcher) {
            val mockBitcoinKey2 = BitcoinKey(ecKey = mockEcKey, address = "tb1qsecondaddress")

            val getKeyFlow = flow<BitcoinKey> {
                emit(testBitcoinKey)
                throw BitcoinKeyRepository.KeyDecodeException("Key became invalid")
            }

            every { mockBitcoinKeyRepository.getKey() } returns getKeyFlow
            every { mockBitcoinKeyRepository.createKey() } returns flowOf(mockBitcoinKey2)

            bitcoinKeyInteractor = BitcoinKeyInteractorImpl(
                bitcoinKeyRepository = mockBitcoinKeyRepository,
                defaultDispatcher = testDispatcher
            )

            bitcoinKeyInteractor.bitcoinKey.test {
                val initialState = awaitItem()
                assertTrue(initialState is BitcoinKeyState.Unknown)

                val presentState = awaitItem()
                assertTrue(presentState is BitcoinKeyState.Present)
                assertEquals(testBitcoinKey, (presentState as BitcoinKeyState.Present).bitcoinKey)

                val errorState = awaitItem()
                assertTrue(errorState is BitcoinKeyState.Error)
                assertTrue((errorState as BitcoinKeyState.Error).throwable is BitcoinKeyRepository.KeyDecodeException)

                cancelAndIgnoreRemainingEvents()
            }

            verify(exactly = 1) { mockBitcoinKeyRepository.getKey() }
        }
}
