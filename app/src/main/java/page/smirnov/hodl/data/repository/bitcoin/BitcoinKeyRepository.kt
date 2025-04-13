package page.smirnov.hodl.data.repository.bitcoin

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.bitcoinj.base.Network
import org.bitcoinj.base.ScriptType
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.ECKey
import org.bitcoinj.crypto.HDKeyDerivation
import org.bitcoinj.params.SigNetParams
import org.bitcoinj.wallet.DeterministicSeed
import page.smirnov.hodl.data.model.key.BitcoinKey
import page.smirnov.hodl.data.repository.encryption.EncryptedPreferences
import page.smirnov.hodl.di.module.BitcoinKeyPreferences
import page.smirnov.hodl.di.qualifier.DispatcherDefault
import page.smirnov.hodl.util.extension.flow.typedFlow
import java.security.SecureRandom
import java.time.Instant
import javax.inject.Inject

interface BitcoinKeyRepository {
    fun getKey(): Flow<BitcoinKey>
    fun createKey(): Flow<BitcoinKey>

    open class KeyException(message: String? = null) : Exception(message)
    class NoKeyException : KeyException()
    class KeyDecodeException(message: String?) : KeyException(message)
}

// Using mnemonic to simplify local testing
internal class BitcoinKeyRepositoryImpl @Inject constructor(
    @BitcoinKeyPreferences
    private val encryptedPreferences: EncryptedPreferences,
    @DispatcherDefault
    private val defaultDispatcher: CoroutineDispatcher,
) : BitcoinKeyRepository {

    private val logger = Logger.withTag(LOG_TAG)

    // TODO: Hard-coded for simplicity, should be configurable in the future
    private val networkParameters: NetworkParameters = SigNetParams.get()

    private val network: Network = networkParameters.network()

    /**
     * Gets stored Bitcoin key
     * @throws BitcoinKeyRepository.NoKeyException when no key is stored
     * @throws BitcoinKeyRepository.KeyDecodeException when key decoding failed
     */
    override fun getKey(): Flow<BitcoinKey> {
        return encryptedPreferences.getString(SEED_PREFERENCE_KEY)
            .map { mnemonicString ->
                mnemonicString.split(" ")
            }
            .map { mnemonicWords ->
                // For debugging purposes
                logger.w { "Loaded key: ${mnemonicWords.joinToString(" ")}" }

                DeterministicSeed.ofMnemonic(mnemonicWords, PASSPHRASE, Instant.now())
            }
            .map { seed ->
                deriveKeyFromSeed(seed, 0)
            }
            .catch { t ->
                if (t is EncryptedPreferences.NoKeyException) {
                    logger.i { "No seed found, so no key stored" }
                    throw BitcoinKeyRepository.NoKeyException()
                }

                logger.e(t) { "Error reconstructing key from saved mnemonic" }
                throw BitcoinKeyRepository.KeyDecodeException(t.message)
            }
            .flowOn(defaultDispatcher)
    }

    /**
     * Creates a new random Bitcoin key
     * @return The generated BitcoinKey
     */
    override fun createKey(): Flow<BitcoinKey> {
        return typedFlow {
            val secureRandom = SecureRandom()
            val seedEntropy = ByteArray(SEED_ENTROPY_SIZE_BYTES)
            secureRandom.nextBytes(seedEntropy)

            DeterministicSeed.ofEntropy(seedEntropy, PASSPHRASE, Instant.now())
        }.map { seed ->
            val mnemonicWords = seed.mnemonicCode ?: throw BitcoinKeyRepository.KeyException("No mnemonic in seed")
            encryptedPreferences.putString(SEED_PREFERENCE_KEY, mnemonicWords.joinToString(" "))

            // For debugging purposes
            logger.w { "Key created: ${mnemonicWords.joinToString(" ")}" }

            seed
        }.map { seed ->
            deriveKeyFromSeed(seed, 0)
        }.flowOn(defaultDispatcher)
    }

    /**
     * Derives a BitcoinKey from a seed using BIP44 path: m/44'/1'/0'/0/index
     * @param seed The deterministic seed to derive from
     * @param index The index of the key to derive (default: 0)
     * @return The BitcoinKey model
     */
    private fun deriveKeyFromSeed(seed: DeterministicSeed, index: Int): BitcoinKey {
        // Derive HD wallet key chain
        val masterKey = HDKeyDerivation.createMasterPrivateKey(seed.seedBytes)

        // BIP44 path for Bitcoin: m/44'/1'/0'/0/index
        val purposeKey = HDKeyDerivation.deriveChildKey(masterKey, ChildNumber(44, true))
        val coinTypeKey = HDKeyDerivation.deriveChildKey(purposeKey, ChildNumber(1, true))
        val accountKey = HDKeyDerivation.deriveChildKey(coinTypeKey, ChildNumber(0, true))
        val changeKey = HDKeyDerivation.deriveChildKey(accountKey, ChildNumber(0, false))
        val addressKey = HDKeyDerivation.deriveChildKey(changeKey, ChildNumber(index, false))

        // Convert to ECKey for compatibility
        val privateKey = addressKey.privKey
        val ecKey = ECKey.fromPrivate(privateKey)

        return BitcoinKey(
            ecKey = ecKey,
            address = ecKey.toAddress(ScriptType.P2WPKH, network).toString(),
        )
    }

    private companion object {
        private const val LOG_TAG = "BitcoinKeyRepository"

        private const val PASSPHRASE = ""
        private const val SEED_PREFERENCE_KEY = "seed_mnemonic"

        private const val SEED_ENTROPY_SIZE_BYTES = 16
    }
}
