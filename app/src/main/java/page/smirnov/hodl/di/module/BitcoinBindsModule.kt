package page.smirnov.hodl.di.module

import dagger.Binds
import dagger.Module
import dagger.Reusable
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import page.smirnov.hodl.data.repository.bitcoin.BitcoinKeyRepository
import page.smirnov.hodl.data.repository.bitcoin.BitcoinKeyRepositoryImpl
import page.smirnov.hodl.domain.interactor.bitcoin.BitcoinAmountsConverter
import page.smirnov.hodl.domain.interactor.bitcoin.BitcoinAmountsConverterImpl
import page.smirnov.hodl.domain.interactor.bitcoin.BitcoinKeyInteractor
import page.smirnov.hodl.domain.interactor.bitcoin.BitcoinKeyInteractorImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class BitcoinBindsModule {

    @Binds
    @Singleton
    abstract fun provideBitcoinRepository(impl: BitcoinKeyRepositoryImpl): BitcoinKeyRepository

    @Binds
    @Singleton
    abstract fun provideBitcoinKeyInteractor(impl: BitcoinKeyInteractorImpl): BitcoinKeyInteractor

    @Binds
    @Reusable
    abstract fun provideBitcoinAmountsConverter(impl: BitcoinAmountsConverterImpl): BitcoinAmountsConverter
}