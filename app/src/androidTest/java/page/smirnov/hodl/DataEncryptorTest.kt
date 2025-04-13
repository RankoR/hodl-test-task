package page.smirnov.hodl

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.*
import io.mockk.impl.annotations.MockK
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import page.smirnov.hodl.data.model.encryption.EncryptedData
import page.smirnov.hodl.data.repository.encryption.DataEncryptorImpl
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
class DataEncryptorTest {

    private val testDispatcher = StandardTestDispatcher()

    @MockK
    private lateinit var mockKeyStore: KeyStore

    @MockK
    private lateinit var mockCipher: Cipher

    @MockK
    private lateinit var mockKeyGenerator: KeyGenerator

    @MockK
    private lateinit var mockSecretKey: SecretKey

    private lateinit var dataEncryptor: DataEncryptorImpl

    private val testPlainText = "sensitive bitcoin data"
    private val testEncryptedBytes = "encrypted_data".toByteArray()
    private val testIv = "initialization_vector".toByteArray()
    private val testEncryptedData = EncryptedData(testEncryptedBytes, testIv)
    private val keyAlias = "BitcoinKeysEncryptionKey_v1"

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)

        mockkStatic(KeyStore::class)
        every { KeyStore.getInstance(any()) } returns mockKeyStore
        every { mockKeyStore.load(null) } just runs

        mockkStatic(Cipher::class)
        every { Cipher.getInstance(any<String>()) } returns mockCipher

        mockkStatic(KeyGenerator::class)
        every { KeyGenerator.getInstance(any<String>(), any<String>()) } returns mockKeyGenerator

        dataEncryptor = DataEncryptorImpl(testDispatcher, testDispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        clearAllMocks()
        unmockkAll()
    }

    @Test
    fun encryptStringWithExistingKey() = runTest {
        setupExistingKeyScenario()
        every { mockCipher.init(Cipher.ENCRYPT_MODE, mockSecretKey) } just runs
        every { mockCipher.iv } returns testIv
        every { mockCipher.doFinal(any<ByteArray>()) } returns testEncryptedBytes

        val result = dataEncryptor.encryptString(testPlainText)

        assertEquals(testEncryptedBytes, result.data)
        assertEquals(testIv, result.iv)
        verify { mockCipher.init(Cipher.ENCRYPT_MODE, mockSecretKey) }
        verify { mockCipher.doFinal(any<ByteArray>()) }
    }

    @Test
    fun decryptStringWithExistingKey() = runTest {
        setupExistingKeyScenario()
        every { mockCipher.init(Cipher.DECRYPT_MODE, mockSecretKey, any<GCMParameterSpec>()) } just runs
        every { mockCipher.doFinal(testEncryptedBytes) } returns testPlainText.toByteArray(Charsets.UTF_8)

        val result = dataEncryptor.decryptString(testEncryptedData)

        assertEquals(testPlainText, result)
        verify {
            mockCipher.init(Cipher.DECRYPT_MODE, mockSecretKey, match<GCMParameterSpec> {
                it.tLen == 128 && it.iv.contentEquals(testIv)
            })
        }
        verify { mockCipher.doFinal(testEncryptedBytes) }
    }

    @Test
    fun encryptStringWithNewKey() = runTest {
        setupNewKeyScenario()
        every { mockCipher.init(Cipher.ENCRYPT_MODE, mockSecretKey) } just runs
        every { mockCipher.iv } returns testIv
        every { mockCipher.doFinal(any<ByteArray>()) } returns testEncryptedBytes

        val result = dataEncryptor.encryptString(testPlainText)

        assertEquals(testEncryptedBytes, result.data)
        assertEquals(testIv, result.iv)
        verify { mockKeyGenerator.init(any<KeyGenParameterSpec>()) }
        verify { mockKeyGenerator.generateKey() }
    }

    @Test
    fun encryptEmptyString() = runTest {
        setupExistingKeyScenario()
        every { mockCipher.init(Cipher.ENCRYPT_MODE, mockSecretKey) } just runs
        every { mockCipher.iv } returns testIv
        every { mockCipher.doFinal(match { it.isEmpty() }) } returns ByteArray(0)

        val result = dataEncryptor.encryptString("")

        assertEquals(0, result.data.size)
        assertEquals(testIv, result.iv)
    }

    @Test
    fun decryptEmptyEncryptedData() = runTest {
        setupExistingKeyScenario()
        every { mockCipher.init(Cipher.DECRYPT_MODE, mockSecretKey, any<GCMParameterSpec>()) } just runs
        every { mockCipher.doFinal(ByteArray(0)) } returns ByteArray(0)
        val emptyEncryptedData = EncryptedData(ByteArray(0), testIv)

        val result = dataEncryptor.decryptString(emptyEncryptedData)

        assertEquals("", result)
    }

    @Test
    fun getSecretKeyHandlesNonSecretKey() = runTest {
        val mockKey = mockk<java.security.Key>()
        every { mockKeyStore.containsAlias(keyAlias) } returns true
        every { mockKeyStore.getKey(keyAlias, null) } returns mockKey
        every { mockKeyStore.deleteEntry(keyAlias) } just runs

        setupKeyGenerationForNewKey()
        every { mockCipher.init(Cipher.ENCRYPT_MODE, mockSecretKey) } just runs
        every { mockCipher.iv } returns testIv
        every { mockCipher.doFinal(any<ByteArray>()) } returns testEncryptedBytes

        dataEncryptor.encryptString(testPlainText)

        verify { mockKeyStore.deleteEntry(keyAlias) }
        verify { mockKeyGenerator.generateKey() }
    }

    @Test(expected = KeyStoreException::class)
    fun encryptStringHandlesKeyStoreExceptions() = runTest {
        every { mockKeyStore.containsAlias(any()) } throws KeyStoreException("KeyStore error")

        setupKeyGenerationForNewKey()
        every { mockCipher.init(Cipher.ENCRYPT_MODE, mockSecretKey) } just runs
        every { mockCipher.iv } returns testIv
        every { mockCipher.doFinal(any<ByteArray>()) } returns testEncryptedBytes

        val result = dataEncryptor.encryptString(testPlainText)

        assertEquals(testEncryptedBytes, result.data)
    }

    @Test(expected = javax.crypto.IllegalBlockSizeException::class)
    fun decryptStringHandlesCipherInitializationExceptions() = runTest {
        setupExistingKeyScenario()
        every { mockCipher.init(Cipher.DECRYPT_MODE, mockSecretKey, any<GCMParameterSpec>()) } throws
                javax.crypto.IllegalBlockSizeException("Invalid IV")

        dataEncryptor.decryptString(testEncryptedData)
    }

    @Test(expected = java.security.NoSuchAlgorithmException::class)
    fun createSecretKeyHandlesKeyGeneratorExceptions() = runTest {
        every { mockKeyStore.containsAlias(keyAlias) } returns false
        every { KeyGenerator.getInstance(any<String>(), any<String>()) } throws
                java.security.NoSuchAlgorithmException("Algorithm not found")

        dataEncryptor.encryptString(testPlainText)
    }

    @Test
    fun keyGenParameterSpecConfiguration() = runTest {
        setupNewKeyScenario()
        val paramSpecSlot = slot<KeyGenParameterSpec>()
        every { mockKeyGenerator.init(capture(paramSpecSlot)) } just runs
        every { mockCipher.init(Cipher.ENCRYPT_MODE, mockSecretKey) } just runs
        every { mockCipher.iv } returns testIv
        every { mockCipher.doFinal(any<ByteArray>()) } returns testEncryptedBytes

        dataEncryptor.encryptString(testPlainText)

        assertEquals(keyAlias, paramSpecSlot.captured.keystoreAlias)
        assertEquals(KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT, paramSpecSlot.captured.purposes)
        assertEquals(256, paramSpecSlot.captured.keySize)
    }

    private fun setupExistingKeyScenario() {
        every { mockKeyStore.containsAlias(keyAlias) } returns true
        every { mockKeyStore.getKey(keyAlias, null) } returns mockSecretKey
    }

    private fun setupNewKeyScenario() {
        every { mockKeyStore.containsAlias(keyAlias) } returns false
        setupKeyGenerationForNewKey()
    }

    private fun setupKeyGenerationForNewKey() {
        every { mockKeyGenerator.init(any<KeyGenParameterSpec>()) } just runs
        every { mockKeyGenerator.generateKey() } returns mockSecretKey
    }
}
