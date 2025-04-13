package page.smirnov.hodl.data.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BroadcastResultApiModel(
    @SerialName("txid")
    val txId: String,
)
