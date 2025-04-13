package page.smirnov.hodl.di.module

import dagger.Binds
import dagger.Module
import dagger.Reusable
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import page.smirnov.hodl.data.repository.encryption.DataEncryptor
import page.smirnov.hodl.data.repository.encryption.DataEncryptorImpl

@Module
@InstallIn(SingletonComponent::class)
internal abstract class EncryptionBindsModule {

    @Binds
    @Reusable
    abstract fun provideDataEncryptor(impl: DataEncryptorImpl): DataEncryptor
}