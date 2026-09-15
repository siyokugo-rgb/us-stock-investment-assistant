package archive.poc

import java.security.MessageDigest

/** SHA-256 of exact bytes → lowercase hex. No String-normalization path. */
object Sha256Hex {
    fun of(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { b -> "%02x".format(b) }
    }
}
