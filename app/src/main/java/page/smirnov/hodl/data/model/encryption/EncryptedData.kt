package page.smirnov.hodl.data.model.encryption

/**
 * Holds the result of an encryption operation, typically containing the ciphertext
 * and the Initialization Vector (IV) required for decryption
 */
data class EncryptedData(
    val data: ByteArray,
    val iv: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EncryptedData

        if (!data.contentEquals(other.data)) return false
        if (!iv.contentEquals(other.iv)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + iv.contentHashCode()
        return result
    }

    override fun toString(): String {
        return "EncryptedData(data=${data.size} bytes, iv=${iv.size} bytes)"
    }
}
