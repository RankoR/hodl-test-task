package page.smirnov.hodl.di.module

import dagger.Module
import dagger.Provides
import dagger.Reusable
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.resources.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.util.network.*
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import page.smirnov.hodl.BuildConfig
import page.smirnov.hodl.di.qualifier.DefaultHttpClient
import page.smirnov.hodl.domain.debug.IsDebug

private const val CONNECT_TIMEOUT_MS = 1000L * 10
private const val REQUEST_TIMEOUT_MS = 1000L * 20
private const val SOCKET_TIMEOUT_MS = 1000L * 20

private const val MAX_NETWORK_ERRORS_RETRIES = 5
private const val MAX_SERVER_ERRORS_RETRIES = 5

@Module
@InstallIn(SingletonComponent::class)
internal class ApiProvidesModule {

    @Provides
    @Reusable
    @DefaultHttpClient
    fun provideHttpClient(
        logger: Logger,
        isDebug: IsDebug,
        json: Json,
    ): HttpClient {
        return HttpClient(CIO) {

            install(Logging) {
                this.logger = logger

                level = if (isDebug()) LogLevel.ALL else LogLevel.HEADERS
                sanitizeHeader { header ->
                    header in setOf(
                        HttpHeaders.Authorization,
                    )
                }
            }

            install(ContentNegotiation) {
                json(json)
            }

            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
            }

            defaultRequest {
                host = BuildConfig.ESPLORA_API_HOST
                port = BuildConfig.ESPLORA_API_PORT

                url {
                    @Suppress("KotlinConstantConditions")
                    protocol = if (BuildConfig.ESPLORA_API_HTTPS) URLProtocol.HTTPS else URLProtocol.HTTP
                }

                contentType(ContentType.Application.Json)
            }

            install(Resources)
            install(HttpRequestRetry) {
                retryOnServerErrors(maxRetries = MAX_SERVER_ERRORS_RETRIES)
                retryOnExceptionIf(maxRetries = MAX_NETWORK_ERRORS_RETRIES) { request, cause ->
                    cause is UnresolvedAddressException || cause is IOException
                }
                exponentialDelay()
            }
        }
    }
}