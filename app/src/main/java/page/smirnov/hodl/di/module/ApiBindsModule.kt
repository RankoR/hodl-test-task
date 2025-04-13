package page.smirnov.hodl.di.module

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.plugins.logging.*
import page.smirnov.hodl.data.repository.api.EsploraApiRepository
import page.smirnov.hodl.data.repository.api.EsploraApiRepositoryImpl
import page.smirnov.hodl.util.logging.KtorLogger

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ApiBindsModule {

    @Binds
    abstract fun provideKtorLogger(impl: KtorLogger): Logger

    @Binds
    abstract fun provideEsploraApiRepository(impl: EsploraApiRepositoryImpl): EsploraApiRepository
}