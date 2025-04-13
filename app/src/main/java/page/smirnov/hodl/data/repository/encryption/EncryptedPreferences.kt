package page.smirnov.hodl.data.repository.encryption

import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import page.smirnov.hodl.data.model.encryption.EncryptedData

interface EncryptedPreferences {
    suspend fun putString(key: String, value: String)
    fun getString(key: String): Flow<String>

    class NoKeyException : Exception()
}

class EncryptedPreferencesImpl(
    private val preferences: DataStore<Preferences>,
    private val dataEncryptor: DataEncryptor,
    private val ioDispatcher: CoroutineDispatcher,
    private val defaultDispatcher: CoroutineDispatcher,
) : EncryptedPreferences {

    private val logger = Logger.withTag(LOG_TAG)

    override suspend fun putString(key: String, value: String) {
        withContext(ioDispatcher) {
            val encryptedData = dataEncryptor.encryptString(value)

            val encryptedKey = stringPreferencesKey(key)
            val ivKey = stringPreferencesKey("$key$IV_SUFFIX")

            preferences.edit { preferences ->
                preferences[encryptedKey] = Base64.encodeToString(encryptedData.data, Base64.DEFAULT)
                preferences[ivKey] = Base64.encodeToString(encryptedData.iv, Base64.DEFAULT)
            }
        }
    }

    override fun getString(key: String): Flow<String> {
        return preferences.data
            .take(1)
            .mapNotNull { preferences ->
                val encryptedKey = stringPreferencesKey(key)
                val ivKey = stringPreferencesKey("$key$IV_SUFFIX")

                val encryptedBase64 = preferences[encryptedKey]
                val ivBase64 = preferences[ivKey]

                if (encryptedBase64 == null || ivBase64 == null) {
                    logger.d { "No secure data found for key: $key (or IV missing)" }

                    return@mapNotNull null
                }

                encryptedBase64 to ivBase64
            }
            .flowOn(ioDispatcher)
            .mapNotNull { (encryptedBase64, ivBase64) ->
                val encryptedData = Base64.decode(encryptedBase64, Base64.DEFAULT)
                val iv = Base64.decode(ivBase64, Base64.DEFAULT)

                if (encryptedData.isEmpty() || iv.isEmpty()) {
                    logger.w { "Empty encrypted data or IV found for key: $key" }
                    return@mapNotNull null
                }

                EncryptedData(
                    data = encryptedData,
                    iv = iv,
                )
            }
            .map { encryptedData ->
                dataEncryptor.decryptString(encryptedData)
            }
            .mapNotNull { it }
            .onEmpty {
                // TODO: Add separate exceptions for no key, retrieval and decryption fails cases
                logger.w { "Empty data" }
                throw EncryptedPreferences.NoKeyException()
            }
            .flowOn(defaultDispatcher)
    }

    private companion object {
        private const val LOG_TAG = "EncryptedPreferences"

        private const val IV_SUFFIX = "_iv"
    }
}