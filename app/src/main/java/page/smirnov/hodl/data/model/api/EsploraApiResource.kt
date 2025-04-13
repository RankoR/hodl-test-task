package page.smirnov.hodl.data.model.api

import io.ktor.resources.*
import kotlinx.serialization.SerialName

// Hardcoded signet for simplicity
@Resource("/signet/api")
internal class EsploraApiResource {

    @Resource("address")
    class Address(val parent: EsploraApiResource = EsploraApiResource()) {

        @Resource("{address}")
        class Detail(
            val parent: Address = Address(),
            @SerialName("address")
            val address: String,
        ) {

            @Resource("utxo")
            class Utxo(val parent: Detail)
        }
    }

    @Resource("tx")
    class Transactions(val parent: EsploraApiResource = EsploraApiResource())
}