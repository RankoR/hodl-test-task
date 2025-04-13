package page.smirnov.hodl.domain.debug

import page.smirnov.hodl.BuildConfig
import javax.inject.Inject


interface IsDebug {
    operator fun invoke(): Boolean
}

internal class IsDebugImpl @Inject constructor() : IsDebug {

    override fun invoke(): Boolean {
        // In a real application I would also add some flag to return false even for debug builds (useful in some cases)
        return BuildConfig.DEBUG
    }
}