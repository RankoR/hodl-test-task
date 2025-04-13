package page.smirnov.hodl.domain.exception.wallet

abstract class WalletException(
    message: String? = null,
) : Exception(message)

/**
 * Thrown when operation can not be completed because there is no bitcoin key
 */
class NoBitcoinKeyException : WalletException()

/**
 * Not enough funds to send
 */
class NotEnoughFundsException : WalletException()
