package eu.kanade.tachiyomi.util.devicesync

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.security.SecureRandom

/** Everything the second device needs to reach and identify the first one. */
@Serializable
data class DevicePairingQr(
    val v: Int = 1,
    val code: String,
    val name: String = "",
    val model: String = "",
    val endpoint: String = "",
    val relayUrl: String = "",
)

/** Session codes and pairing secrets, generated locally and never transmitted as secrets. */
object DevicePairing {
    private const val QR_PREFIX = "EXTYOMISYNC1:"

    // No I/O/0/1 so the password can be read aloud without ambiguity.
    private const val PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val random = SecureRandom()

    /** 8 digits, as shown by the first device and confirmed by the second one. */
    fun newCode(): String = buildString(8) {
        repeat(8) { append(('0'.code + random.nextInt(10)).toChar()) }
    }

    /** 10 random letters shown by the first device only after the code is confirmed. */
    fun newPassword(): String = buildString(10) {
        repeat(10) { append(PASSWORD_ALPHABET[random.nextInt(PASSWORD_ALPHABET.length)]) }
    }

    fun encodeQr(qr: DevicePairingQr): String =
        QR_PREFIX + DeviceBase64.encode(deviceSyncJson.encodeToString(qr).toByteArray())

    fun decodeQr(text: String): DevicePairingQr? = runCatching {
        require(text.startsWith(QR_PREFIX)) { "Not an ExtYomi pairing code" }
        val json = String(DeviceBase64.decode(text.removePrefix(QR_PREFIX)), Charsets.UTF_8)
        deviceSyncJson.decodeFromString<DevicePairingQr>(json)
            .takeIf { it.code.length == 8 && it.code.all { c -> c in '0'..'9' } }
    }.getOrNull()

    fun formatCode(code: String): String =
        if (code.length == 8) "${code.take(4)} ${code.takeLast(4)}" else code
}
