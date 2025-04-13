package page.smirnov.hodl.util.logging

import io.ktor.client.plugins.logging.*
import javax.inject.Inject

internal class KtorLogger @Inject constructor() : Logger {

    private val logger = co.touchlab.kermit.Logger.withTag(LOG_TAG)

    override fun log(message: String) {
        logger.v { message }
    }

    private companion object {
        private const val LOG_TAG = "Ktor"
    }
}