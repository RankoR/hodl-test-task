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

interface DataEncryptor {
    suspend fun encryptString(string: String): EncryptedData
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
     * Gets an existing secret key from the KeyStore
     */
    private suspend fun getSecretKey(): SecretKey? {
        return withContext(ioDispatcher) {
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                return@withContext null
            }

            // Ensure it's actually a SecretKey before casting
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