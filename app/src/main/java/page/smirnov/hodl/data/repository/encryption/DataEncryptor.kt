package page.smirnov.hodl.data.repository.encryption

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import page.smirnov.hodl.data.model.encryption.EncryptedData
import page.smirnov.hodl.di.qualifier.DispatcherDefault
import page.smirnov.hodl.di.qualifier.DispatcherIO
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject

/**
 * Interface for encrypting and decrypting data using the Android Keystore system.
 */
interface DataEncryptor {
    /**
     * Encrypts the given string using a securely stored key.
     *
     * @param string The plaintext string to encrypt.
     * @return [EncryptedData] containing the ciphertext and necessary IV.
     */
    suspend fun encryptString(string: String): EncryptedData

    /**
     * Decrypts the given [EncryptedData] using the securely stored key.
     *
     * @param encryptedData The data to decrypt (ciphertext and IV).
     * @return The original plaintext string.
     */
    suspend fun decryptString(encryptedData: EncryptedData): String
}

internal class DataEncryptorImpl @Inject constructor(
    @DispatcherIO
    private val ioDispatcher: CoroutineDispatcher,
    @DispatcherDefault
    private val defaultDispatcher: CoroutineDispatcher,
) : DataEncryptor {

    private val logger = Logger.withTag(LOG_TAG)

    private val keyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    override suspend fun encryptString(string: String): EncryptedData {
        return withContext(defaultDispatcher) {
            val cipher = Cipher.getInstance(AES_MODE)
            val secretKey = getOrCreateSecretKey()

            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv

            val encryptedBytes = cipher.doFinal(string.toByteArray(Charsets.UTF_8))

            EncryptedData(
                data = encryptedBytes,
                iv = iv,
            )
        }
    }

    override suspend fun decryptString(encryptedData: EncryptedData): String {
        return withContext(defaultDispatcher) {
            val cipher = Cipher.getInstance(AES_MODE)
            val secretKey = getOrCreateSecretKey() // Ensure key exists

            val spec = GCMParameterSpec(GCM_TAG_LENGTH, encryptedData.iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            val decryptedBytes = cipher.doFinal(encryptedData.data)
            String(decryptedBytes, Charsets.UTF_8)
        }
    }

    /**
     * Gets or creates a secret key for encryption
     */
    private suspend fun getOrCreateSecretKey(): SecretKey {
        return withContext(ioDispatcher) {
            getSecretKey() ?: createSecretKey()
        }
    }

    /**
     * Attempts to retrieve an existing secret key with the predefined alias from the Android Keystore.
     * @return [SecretKey] or null if the key doesn't exist or if the entry is not a SecretKey.
     */
    private suspend fun getSecretKey(): SecretKey? {
        return withContext(ioDispatcher) {
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                return@withContext null
            }

            val key = keyStore.getKey(KEY_ALIAS, null)
            if (key is SecretKey) {
                key
            } else {
                logger.e { "Key found for alias $KEY_ALIAS is not a SecretKey" }
                keyStore.deleteEntry(KEY_ALIAS)
                null
            }
        }
    }


    /**
     * Creates a new secret key in the KeyStore
     * @return Created [SecretKey]
     */
    private suspend fun createSecretKey(): SecretKey {
        return withContext(ioDispatcher) {
            logger.i { "Creating new secret key with alias: $KEY_ALIAS" }
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE,
            )

            val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE)
                .build()

            keyGenerator.init(keyGenParameterSpec)
            keyGenerator.generateKey()
        }
    }


    private companion object {
        private const val LOG_TAG = "DataEncryptor"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val AES_MODE = "AES/GCM/NoPadding"
        private const val KEY_ALIAS = "BitcoinKeysEncryptionKey_v1"

        private const val KEY_SIZE = 256 // AES-256
        private const val GCM_TAG_LENGTH = 128
    }

}