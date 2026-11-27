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
    /** Id of the host, so the joiner can trust and address it without waiting for CONFIRM. */
    val deviceId: String = "",
    val name: String = "",
    val model: String = "",
    val endpoint: String = "",
    val relayUrl: String = "",
    /** Every LAN address of the host, so the joiner can skip an unreachable virtual adapter. */
    val endpoints: List<String> = emptyList(),
)

/** Session codes and pairing secrets, generated locally and never transmitted as secrets. */
object DevicePairing {
    private const val QR_PREFIX = "EXTYOMISYNC1:"
    private const val INVITE_PREFIX = "extyomisync://pair#"

    // No I/O/0/1 so the password can be read aloud without ambiguity.
    private const val PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val random = SecureRandom()

    /** 8 digits, as shown by the first device and confirmed by the second one. */
    fun newCode(): String = buildString(8) {
        repeat(8) { append(('0'.code + random.nextInt(10)).toChar()) }
    }

    /** 10 random letters/digits (no 0, 1, I or O), shown by the first device. */
    fun newPassword(): String = buildString(10) {
        repeat(10) { append(PASSWORD_ALPHABET[random.nextInt(PASSWORD_ALPHABET.length)]) }
    }

    /** Single place used by both the generator and the password field, so they cannot diverge. */
    fun isPasswordChar(value: Char): Boolean = value.uppercaseChar() in PASSWORD_ALPHABET

    fun encodeQr(qr: DevicePairingQr): String = QR_PREFIX + encodePayload(qr)

    /** A URL-like string the first device can show and the second one can simply paste. */
    fun encodeInvite(qr: DevicePairingQr): String = INVITE_PREFIX + encodePayload(qr)

    /**
     * Accepts the invite URL, the legacy QR payload, or a bare 8-digit code (LAN only).
     * Nothing here is fetched from the network: the payload is decoded locally.
     */
    fun decodeInvite(text: String): DevicePairingQr? {
        val value = text.trim()
        return when {
            value.startsWith(INVITE_PREFIX) -> decodePayload(value.removePrefix(INVITE_PREFIX))
            value.startsWith(QR_PREFIX) -> decodePayload(value.removePrefix(QR_PREFIX))
            value.length == 8 && value.all { it in '0'..'9' } -> DevicePairingQr(code = value)
            else -> null
        }
    }

    fun decodeQr(text: String): DevicePairingQr? = runCatching {
        require(text.trim().startsWith(QR_PREFIX)) { "Not an ExtYomi pairing code" }
        decodePayload(text.trim().removePrefix(QR_PREFIX)) ?: error("Invalid pairing payload")
    }.getOrNull()

    private fun encodePayload(qr: DevicePairingQr): String =
        DeviceBase64.encode(deviceSyncJson.encodeToString(qr).toByteArray())

    private fun decodePayload(payload: String): DevicePairingQr? = runCatching {
        val json = String(DeviceBase64.decode(payload), Charsets.UTF_8)
        deviceSyncJson.decodeFromString<DevicePairingQr>(json)
            .takeIf { it.code.length == 8 && it.code.all { c -> c in '0'..'9' } }
    }.getOrNull()

    fun formatCode(code: String): String =
        if (code.length == 8) "${code.take(4)} ${code.takeLast(4)}" else code
}
