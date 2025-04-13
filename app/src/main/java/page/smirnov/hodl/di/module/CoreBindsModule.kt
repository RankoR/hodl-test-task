package page.smirnov.hodl.di.module

import dagger.Binds
import dagger.Module
import dagger.Reusable
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import page.smirnov.hodl.domain.debug.IsDebug
import page.smirnov.hodl.domain.debug.IsDebugImpl

@Module
@InstallIn(SingletonComponent::class)
internal abstract class CoreBindsModule {

    @Binds
    @Reusable
    abstract fun provideIsDebug(isDebug: IsDebugImpl): IsDebug
}