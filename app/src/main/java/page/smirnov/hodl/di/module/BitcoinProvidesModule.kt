package page.smirnov.hodl.di.module

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import page.smirnov.hodl.data.repository.encryption.DataEncryptor
import page.smirnov.hodl.data.repository.encryption.EncryptedPreferences
import page.smirnov.hodl.data.repository.encryption.EncryptedPreferencesImpl
import page.smirnov.hodl.di.qualifier.DispatcherDefault
import page.smirnov.hodl.di.qualifier.DispatcherIO
import javax.inject.Qualifier
import javax.inject.Singleton

@Retention(AnnotationRetention.BINARY)
@Qualifier
annotation class BitcoinKeyPreferences

private const val BITCOIN_KEY_PREFERENCES_FILE = "bitcoin_key_preferences"

@Module
@InstallIn(SingletonComponent::class)
internal class BitcoinProvidesModule {

    @Provides
    @Singleton
    @BitcoinKeyPreferences
    fun provideBitcoinKeyPreferences(
        @ApplicationContext
        context: Context,
        @DispatcherIO
        ioDispatcher: CoroutineDispatcher,
    ): DataStore<Preferences> {
        return PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler(
                produceNewData = { emptyPreferences() }
            ),
            scope = CoroutineScope(ioDispatcher + SupervisorJob()),
            produceFile = { context.preferencesDataStoreFile(BITCOIN_KEY_PREFERENCES_FILE) },
        )
    }

    @Provides
    @Singleton
    @BitcoinKeyPreferences
    fun provideBitcoinKeyEncryptedPreferences(
        dataEncryptor: DataEncryptor,
        @BitcoinKeyPreferences
        preferences: DataStore<Preferences>,
        @DispatcherIO
        ioDispatcher: CoroutineDispatcher,
        @DispatcherDefault
        defaultDispatcher: CoroutineDispatcher,
    ): EncryptedPreferences {
        return EncryptedPreferencesImpl(
            preferences = preferences,
            dataEncryptor = dataEncryptor,
            ioDispatcher = ioDispatcher,
            defaultDispatcher = defaultDispatcher,
        )
    }
}