package eu.kanade.tachiyomi.util.standalone

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/** Stable signing identity for apps generated on this device. */
object StandaloneSigningKey {

    private const val KEYSTORE_TYPE = "AndroidKeyStore"
    private fun alias(context: Context) = "extyomi_standalone_signer.${context.packageName}"

    data class Identity(
        val privateKey: PrivateKey,
        val certificate: X509Certificate,
    )

    fun getOrCreate(context: Context): Identity {
        val keyStore = KeyStore.getInstance(KEYSTORE_TYPE).apply { load(null) }

        val alias = alias(context)
        if (!keyStore.containsAlias(alias)) {
            val generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA,
                KEYSTORE_TYPE,
            )
            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN,
            )
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .build()
            generator.initialize(spec)
            generator.generateKeyPair()
        }

        val entry = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            ?: error("Standalone signing key is unavailable")
        val certificate = entry.certificate as? X509Certificate
            ?: error("Standalone signing certificate is unavailable")
        return Identity(entry.privateKey, certificate)
    }
}
