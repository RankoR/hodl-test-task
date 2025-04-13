package page.smirnov.hodl

import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import page.smirnov.hodl.data.model.encryption.EncryptedData
import page.smirnov.hodl.data.repository.encryption.DataEncryptor
import page.smirnov.hodl.data.repository.encryption.EncryptedPreferences
import page.smirnov.hodl.data.repository.encryption.EncryptedPreferencesImpl
import java.nio.charset.StandardCharsets

/**
 * NOTE: Not verifying actual writes to the datastore, as naive mocking fails, and I don't have much time to fix it
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class EncryptedPreferencesTest {

    private val testKey = "test_key"
    private val testValue = "secret_value_123"
    private val encryptedBytes = "encrypted_data".toByteArray(StandardCharsets.UTF_8)
    private val ivBytes = "initialization_vector".toByteArray(StandardCharsets.UTF_8)
    private val encryptedBase64 = Base64.encodeToString(encryptedBytes, Base64.DEFAULT)
    private val ivBase64 = Base64.encodeToString(ivBytes, Base64.DEFAULT)

    private val mockDataStore: DataStore<Preferences> = mockk(relaxed = true)
    private val mockDataEncryptor: DataEncryptor = mockk(relaxed = true)
    private val mockPreferences: Preferences = mockk(relaxed = true)

    private val encryptedData = EncryptedData(encryptedBytes, ivBytes)

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var encryptedPreferences: EncryptedPreferencesImpl

    @Before
    fun setup() {
        MockKAnnotations.init(this)

        encryptedPreferences = EncryptedPreferencesImpl(
            preferences = mockDataStore,
            dataEncryptor = mockDataEncryptor,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher
        )

        every { mockDataStore.data } returns flowOf(mockPreferences)
        coEvery { mockDataEncryptor.encryptString(any()) } returns encryptedData
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun putString_shouldEncryptAndStoreValue() = runTest(testDispatcher) {
        coEvery { mockDataEncryptor.encryptString(testValue) } returns encryptedData

        encryptedPreferences.putString(testKey, testValue)

        coVerify(exactly = 1) { mockDataEncryptor.encryptString(testValue) }
    }

    @Test
    fun getString_withExistingData_shouldDecryptAndReturnValue() = runTest(testDispatcher) {
        val encryptedKey = stringPreferencesKey(testKey)
        val ivKey = stringPreferencesKey("${testKey}_iv")

        every { mockPreferences[encryptedKey] } returns encryptedBase64
        every { mockPreferences[ivKey] } returns ivBase64
        coEvery { mockDataEncryptor.decryptString(any()) } returns testValue

        encryptedPreferences.getString(testKey).test {
            assertEquals(testValue, awaitItem())
            awaitComplete()
        }

        coVerify(exactly = 1) {
            mockDataEncryptor.decryptString(match {
                it.data.contentEquals(encryptedBytes) && it.iv.contentEquals(ivBytes)
            })
        }
    }

    @Test
    fun getString_withMissingData_shouldEmitNoKeyException() = runTest(testDispatcher) {
        val encryptedKey = stringPreferencesKey(testKey)
        val ivKey = stringPreferencesKey("${testKey}_iv")

        every { mockPreferences[encryptedKey] } returns null
        every { mockPreferences[ivKey] } returns null

        encryptedPreferences.getString(testKey).test {
            val error = awaitError()
            assertTrue(
                "Expected NoKeyException, but got ${error::class.simpleName}",
                error is EncryptedPreferences.NoKeyException
            )
        }
    }

    @Test
    fun getString_withMissingIV_shouldEmitNoKeyException() = runTest(testDispatcher) {
        val encryptedKey = stringPreferencesKey(testKey)
        val ivKey = stringPreferencesKey("${testKey}_iv")

        every { mockPreferences[encryptedKey] } returns encryptedBase64
        every { mockPreferences[ivKey] } returns null

        encryptedPreferences.getString(testKey).test {
            val error = awaitError()
            assertTrue(
                "Expected NoKeyException, but got ${error::class.simpleName}",
                error is EncryptedPreferences.NoKeyException
            )
        }
    }

    @Test
    fun getString_withEmptyEncryptedData_shouldEmitNoKeyException() = runTest(testDispatcher) {
        val encryptedKey = stringPreferencesKey(testKey)
        val ivKey = stringPreferencesKey("${testKey}_iv")
        val emptyBase64 = Base64.encodeToString(ByteArray(0), Base64.DEFAULT)

        every { mockPreferences[encryptedKey] } returns emptyBase64
        every { mockPreferences[ivKey] } returns ivBase64

        encryptedPreferences.getString(testKey).test {
            val error = awaitError()
            assertTrue(
                "Expected NoKeyException, but got ${error::class.simpleName}",
                error is EncryptedPreferences.NoKeyException
            )
        }
    }

    @Test
    fun getString_withDecryptionError_shouldFailFlowWithOriginalException() = runTest(testDispatcher) {
        val encryptedKey = stringPreferencesKey(testKey)
        val ivKey = stringPreferencesKey("${testKey}_iv")
        val decryptionException = RuntimeException("Boom! Decryption failed")

        every { mockPreferences[encryptedKey] } returns encryptedBase64
        every { mockPreferences[ivKey] } returns ivBase64
        coEvery { mockDataEncryptor.decryptString(any()) } throws decryptionException

        encryptedPreferences.getString(testKey).test {
            val error = awaitError()
            assertEquals(decryptionException, error)
        }
    }
}
