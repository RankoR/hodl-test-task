package page.smirnov.hodl

import app.cash.turbine.test
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.bitcoinj.base.Address
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.crypto.ECKey
import org.bitcoinj.crypto.HDKeyDerivation
import org.bitcoinj.wallet.DeterministicSeed
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import page.smirnov.hodl.data.repository.bitcoin.BitcoinKeyRepository
import page.smirnov.hodl.data.repository.bitcoin.BitcoinKeyRepositoryImpl
import page.smirnov.hodl.data.repository.encryption.EncryptedPreferences
import page.smirnov.hodl.util.extension.flow.errorFlow
import java.math.BigInteger
import java.security.SecureRandom

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class BitcoinKeyRepositoryTest {

    private val mockEncryptedPreferences: EncryptedPreferences = mockk(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var bitcoinKeyRepository: BitcoinKeyRepositoryImpl

    private val mockDeterministicKey: DeterministicKey = mockk(relaxed = true)
    private val mockECKey: ECKey = mockk(relaxed = true)
    private val mockAddress: Address = mockk(relaxed = true)
    private val mockDeterministicSeed: DeterministicSeed = mockk(relaxed = true)

    @Before
    fun setup() {
        MockKAnnotations.init(this)

        mockkStatic(HDKeyDerivation::class)
        mockkStatic(ECKey::class)

        bitcoinKeyRepository = BitcoinKeyRepositoryImpl(
            encryptedPreferences = mockEncryptedPreferences,
            defaultDispatcher = testDispatcher
        )

        every { mockDeterministicSeed.mnemonicCode } returns TEST_MNEMONIC.split(" ")
        every { mockDeterministicSeed.seedBytes } returns ByteArray(32) { 1 }

        every { mockECKey.privateKeyAsHex } returns TEST_PRIVATE_KEY
        every { mockECKey.publicKeyAsHex } returns TEST_PUBLIC_KEY
        every { mockECKey.toAddress(any(), any()) } returns mockAddress
        every { mockAddress.toString() } returns TEST_ADDRESS

        every { HDKeyDerivation.createMasterPrivateKey(any()) } returns mockDeterministicKey
        every<DeterministicKey> {
            HDKeyDerivation.deriveChildKey(
                any<DeterministicKey>(),
                any<ChildNumber>()
            )
        } returns mockDeterministicKey
        every { mockDeterministicKey.privKey } returns BigInteger(ByteArray(32) { 1 })

        every<ECKey> { ECKey.fromPrivate(any<BigInteger>()) } returns mockECKey
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun getKey_withExistingKey_shouldReturnBitcoinKey() = runTest(testDispatcher) {
        every { mockEncryptedPreferences.getString(SEED_PREFERENCE_KEY) } returns flowOf(TEST_MNEMONIC)

        mockkStatic(DeterministicSeed::class)
        every<DeterministicSeed> {
            DeterministicSeed.ofMnemonic(
                any<List<String>>(),
                any(),
                any()
            )
        } returns mockDeterministicSeed

        bitcoinKeyRepository.getKey().test {
            val bitcoinKey = awaitItem()

            assertEquals(TEST_PRIVATE_KEY, bitcoinKey.ecKey.privateKeyAsHex)
            assertEquals(TEST_PUBLIC_KEY, bitcoinKey.ecKey.publicKeyAsHex)
            assertEquals(TEST_ADDRESS, bitcoinKey.address)

            awaitComplete()
        }

        verify(exactly = 1) { mockEncryptedPreferences.getString(SEED_PREFERENCE_KEY) }
        verify(exactly = 1) { HDKeyDerivation.createMasterPrivateKey(any()) }
        verify(exactly = 1) { ECKey.fromPrivate(any<BigInteger>()) }
    }

    @Test
    fun getKey_withNoSavedKey_shouldThrowNoKeyException() = runTest(testDispatcher) {
        every { mockEncryptedPreferences.getString(SEED_PREFERENCE_KEY) } returns errorFlow(EncryptedPreferences.NoKeyException())

        bitcoinKeyRepository.getKey().test {
            val exception = awaitError()
            assertTrue(exception is BitcoinKeyRepository.NoKeyException)
        }
    }

    @Test
    fun getKey_withInvalidMnemonic_shouldThrowKeyDecodeException() = runTest(testDispatcher) {
        every { mockEncryptedPreferences.getString(SEED_PREFERENCE_KEY) } returns flowOf("invalid mnemonic")

        mockkStatic(DeterministicSeed::class)
        every {
            DeterministicSeed.ofMnemonic(
                any<List<String>>(),
                any(),
                any()
            )
        } throws IllegalArgumentException("Invalid mnemonic")

        bitcoinKeyRepository.getKey().test {
            val exception = awaitError()
            assertTrue(exception is BitcoinKeyRepository.KeyDecodeException)
        }
    }

    @Test
    fun createKey_shouldGenerateAndStoreSeed() = runTest(testDispatcher) {
        mockkConstructor(SecureRandom::class)
        mockkStatic(DeterministicSeed::class)

        every { DeterministicSeed.ofEntropy(any(), any(), any()) } returns mockDeterministicSeed

        bitcoinKeyRepository.createKey().test {
            val bitcoinKey = awaitItem()

            assertEquals(TEST_PRIVATE_KEY, bitcoinKey.ecKey.privateKeyAsHex)
            assertEquals(TEST_PUBLIC_KEY, bitcoinKey.ecKey.publicKeyAsHex)
            assertEquals(TEST_ADDRESS, bitcoinKey.address)

            awaitComplete()
        }

        val mnemonicList = mockDeterministicSeed.mnemonicCode
        coVerify { mockEncryptedPreferences.putString(SEED_PREFERENCE_KEY, mnemonicList!!.joinToString(" ")) }
        verify { DeterministicSeed.ofEntropy(any(), any(), any()) }
    }

    @Test
    fun createKey_withNoMnemonicInSeed_shouldThrowKeyException() = runTest(testDispatcher) {
        mockkConstructor(SecureRandom::class)
        mockkStatic(DeterministicSeed::class)

        every { DeterministicSeed.ofEntropy(any(), any(), any()) } returns mockDeterministicSeed
        every { mockDeterministicSeed.mnemonicCode } returns null

        bitcoinKeyRepository.createKey().test {
            val exception = awaitError()
            assertTrue(exception is BitcoinKeyRepository.KeyException)
            assertEquals("No mnemonic in seed", exception.message)
        }
    }

    @Test
    fun createKey_withDerivationFailure_shouldPropagateException() = runTest(testDispatcher) {
        mockkConstructor(SecureRandom::class)
        mockkStatic(DeterministicSeed::class)

        every { DeterministicSeed.ofEntropy(any(), any(), any()) } returns mockDeterministicSeed
        every { HDKeyDerivation.createMasterPrivateKey(any()) } throws IllegalArgumentException("Invalid seed")

        bitcoinKeyRepository.createKey().test {
            val exception = awaitError()
            assertTrue(exception is IllegalArgumentException)
            assertEquals("Invalid seed", exception.message)
        }
    }

    @Test
    fun bip44KeyDerivationPath_shouldFollowCorrectPath() = runTest(testDispatcher) {
        every { mockEncryptedPreferences.getString(SEED_PREFERENCE_KEY) } returns flowOf(TEST_MNEMONIC)

        mockkStatic(DeterministicSeed::class)
        every { DeterministicSeed.ofMnemonic(any<List<String>>(), any(), any()) } returns mockDeterministicSeed

        val masterKeySlot = slot<DeterministicKey>()
        val purposeKeySlot = slot<DeterministicKey>()
        val coinTypeKeySlot = slot<DeterministicKey>()
        val accountKeySlot = slot<DeterministicKey>()
        val changeKeySlot = slot<DeterministicKey>()

        every { HDKeyDerivation.createMasterPrivateKey(any()) } returns mockDeterministicKey
        every {
            HDKeyDerivation.deriveChildKey(
                capture(masterKeySlot),
                match<ChildNumber> { it.num() == 44 && it.isHardened })
        } returns mockDeterministicKey
        every {
            HDKeyDerivation.deriveChildKey(
                capture(purposeKeySlot),
                match<ChildNumber> { it.num() == 1 && it.isHardened })
        } returns mockDeterministicKey
        every {
            HDKeyDerivation.deriveChildKey(
                capture(coinTypeKeySlot),
                match<ChildNumber> { it.num() == 0 && it.isHardened })
        } returns mockDeterministicKey
        every {
            HDKeyDerivation.deriveChildKey(
                capture(accountKeySlot),
                match<ChildNumber> { it.num() == 0 && !it.isHardened })
        } returns mockDeterministicKey
        every {
            HDKeyDerivation.deriveChildKey(
                capture(changeKeySlot),
                match<ChildNumber> { it.num() == 0 && !it.isHardened })
        } returns mockDeterministicKey

        bitcoinKeyRepository.getKey().test {
            awaitItem()
            awaitComplete()
        }

        verify { HDKeyDerivation.deriveChildKey(any(), ChildNumber(44, true)) } // Purpose
        verify { HDKeyDerivation.deriveChildKey(any(), ChildNumber(1, true)) }  // Coin type (testnet)
        verify { HDKeyDerivation.deriveChildKey(any(), ChildNumber(0, true)) }  // Account
        verify { HDKeyDerivation.deriveChildKey(any(), ChildNumber(0, false)) } // Change
        verify { HDKeyDerivation.deriveChildKey(any(), ChildNumber(0, false)) } // Index
    }

    private companion object {
        private const val SEED_PREFERENCE_KEY = "seed_mnemonic"
        private const val TEST_MNEMONIC =
            "deadbeef feeddead badfood cafebabe deadcode abadcafe cccccccc abababab gogogogo deadbeef feeddead badfood"
        private const val TEST_PRIVATE_KEY = "b1946ac92492d2347c6235b4d2611184b1946ac92492d2347c6235b4d2611184"
        private const val TEST_PUBLIC_KEY = "b1946ac92492d2347c6235b4d2611184b1946ac92492d2347c6235b4d2611184aa"
        private const val TEST_ADDRESS = "mxXYDbdJQQG2ZnPxrsnAvUBaPRJUqwVTAy"
    }
}
