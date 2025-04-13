package page.smirnov.hodl.data.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UtxoApiModel(
    @SerialName("txid")
    val txid: String,
    @SerialName("vout")
    val vout: Long,
    @SerialName("status")
    val status: Status,
    @SerialName("value")
    val value: Long,
) {

    @Serializable
    data class Status(
        @SerialName("confirmed")
        val isConfirmed: Boolean,
        @SerialName("block_height")
        val blockHeight: Int? = null,
        @SerialName("block_hash")
        val blockHash: String? = null,
        @SerialName("block_time")
        val blockTime: Int? = null,
    )
}
