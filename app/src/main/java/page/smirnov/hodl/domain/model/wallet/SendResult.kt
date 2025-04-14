package page.smirnov.hodl.domain.model.wallet

/**
 * Represents the successful result of a send operation.
 * Contains information about the broadcasted transaction.
 * Naming is okay for this simple case, but could be more specific like `TransactionBroadcastResult` in a larger app.
 */
data class SendResult(
    val txId: String,
)
