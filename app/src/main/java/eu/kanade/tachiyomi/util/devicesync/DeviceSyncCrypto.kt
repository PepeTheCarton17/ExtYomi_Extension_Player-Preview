package eu.kanade.tachiyomi.util.devicesync

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Minimal Base64 so the codec also works in plain JVM unit tests. */
object DeviceBase64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val DECODE = ALPHABET.mapIndexed { index, c -> c to index }.toMap()

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < bytes.size) {
            val value = (bytes[i].toInt() and 0xff) shl 16 or
                ((bytes[i + 1].toInt() and 0xff) shl 8) or
                (bytes[i + 2].toInt() and 0xff)
            out.append(ALPHABET[value shr 18 and 0x3f])
            out.append(ALPHABET[value shr 12 and 0x3f])
            out.append(ALPHABET[value shr 6 and 0x3f])
            out.append(ALPHABET[value and 0x3f])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val value = (bytes[i].toInt() and 0xff) shl 16
                out.append(ALPHABET[value shr 18 and 0x3f])
                out.append(ALPHABET[value shr 12 and 0x3f])
                out.append("==")
            }
            2 -> {
                val value = (bytes[i].toInt() and 0xff) shl 16 or
                    ((bytes[i + 1].toInt() and 0xff) shl 8)
                out.append(ALPHABET[value shr 18 and 0x3f])
                out.append(ALPHABET[value shr 12 and 0x3f])
                out.append(ALPHABET[value shr 6 and 0x3f])
                out.append('=')
            }
        }
        return out.toString()
    }

    fun decode(text: String): ByteArray {
        val clean = text.trim().filter { it == '=' || it in DECODE }
        require(clean.length % 4 == 0) { "Invalid Base64 length" }
        val out = ByteArray(clean.length / 4 * 3)
        var outIndex = 0
        var i = 0
        while (i < clean.length) {
            val a = DECODE.getValue(clean[i])
            val b = DECODE.getValue(clean[i + 1])
            val c = if (clean[i + 2] == '=') 0 else DECODE.getValue(clean[i + 2])
            val d = if (clean[i + 3] == '=') 0 else DECODE.getValue(clean[i + 3])
            val value = a shl 18 or (b shl 12) or (c shl 6) or d
            if (outIndex < out.size) out[outIndex++] = (value shr 16 and 0xff).toByte()
            if (outIndex < out.size && clean[i + 2] != '=') {
                out[outIndex++] = (value shr 8 and 0xff).toByte()
            }
            if (outIndex < out.size && clean[i + 3] != '=') {
                out[outIndex++] = (value and 0xff).toByte()
            }
            i += 4
        }
        return out.copyOf(outIndex)
    }
}

/**
 * End-to-end encryption for library data. The key comes from the 8-digit session code and the
 * 10-letter password shown by the first device; the password is never sent over the network.
 */
object DeviceSyncCrypto {
    private const val ITERATIONS = 120_000
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun deriveKey(code: String, password: String): SecretKeySpec {
        require(code.isNotBlank() && password.isNotBlank()) { "Missing pairing secret" }
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(
            password.toCharArray(),
            code.toByteArray(Charsets.UTF_8),
            ITERATIONS,
            256,
        )
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    fun encrypt(plain: String, key: SecretKeySpec): Pair<String, String> {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return DeviceBase64.encode(iv) to DeviceBase64.encode(cipher.doFinal(plain.toByteArray()))
    }

    fun decrypt(iv: String, ciphertext: String, key: SecretKeySpec): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(TAG_BITS, DeviceBase64.decode(iv)),
        )
        return String(cipher.doFinal(DeviceBase64.decode(ciphertext)), Charsets.UTF_8)
    }
}
