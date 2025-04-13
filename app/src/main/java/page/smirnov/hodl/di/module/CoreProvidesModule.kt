package page.smirnov.hodl.di.module

import dagger.Module
import dagger.Provides
import dagger.Reusable
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import page.smirnov.hodl.di.qualifier.DispatcherDefault
import page.smirnov.hodl.di.qualifier.DispatcherIO
import page.smirnov.hodl.di.qualifier.DispatcherMain
import page.smirnov.hodl.domain.debug.IsDebug
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal class CoreProvidesModule {

    @Provides
    @Reusable
    @DispatcherDefault
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Reusable
    @DispatcherIO
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Reusable
    @DispatcherMain
    fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main

    @Provides
    @Singleton
    fun provideJson(
        isDebug: IsDebug,
    ): Json {
        return Json {
            prettyPrint = isDebug()

            ignoreUnknownKeys = true
        }
    }
}