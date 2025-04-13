package page.smirnov.hodl.di.module

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import page.smirnov.hodl.domain.interactor.wallet.WalletInteractor
import page.smirnov.hodl.domain.interactor.wallet.WalletInteractorImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class WalletBindsModule {

    @Binds
    @Singleton
    abstract fun bindWalletInteractor(impl: WalletInteractorImpl): WalletInteractor
}