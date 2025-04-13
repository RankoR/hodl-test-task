package page.smirnov.hodl.util.extension.flow

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

fun <T> errorFlow(throwable: Throwable): Flow<T> {
    return flow {
        throw throwable
    }
}

inline fun <T> typedFlow(crossinline block: suspend FlowCollector<T>.() -> T): Flow<T> {
    return flow {
        val value = block()

        emit(value)
    }
}
