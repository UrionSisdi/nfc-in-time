package io.github.urionsisdi.nfcintime.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.Certificate
import java.security.spec.ECGenParameterSpec

/**
 * The installation's identity: an ECDSA P-256 key that never leaves the secure
 * element. It signs transfers at contact and every `/v1/sync` body, and the
 * attestation chain issued with it is what tells the server the key is really in
 * hardware and the APK is really ours.
 *
 * Losing it is not losing the player: the account lives in Telegram and the
 * balance on the server, so a reinstall issues a new key and inherits the old
 * balance.
 */
object DeviceKey {
    private const val ALIAS = "nfcit.device"
    private const val PROVIDER = "AndroidKeyStore"
    private const val SIGNATURE = "SHA256withECDSA"

    private val keyStore: KeyStore
        get() = KeyStore.getInstance(PROVIDER).apply { load(null) }

    /**
     * Generates the key pair, replacing any previous one, and returns the
     * attestation chain that came with it. [challenge] is the nonce from
     * `/v1/auth/challenge`: it is baked into the leaf certificate, so the server
     * reads it back out instead of taking the client's word for it.
     */
    fun generate(challenge: ByteArray): List<String> {
        delete()
        try {
            generateWith(challenge, strongBox = true)
        } catch (e: StrongBoxUnavailableException) {
            // Plenty of devices have no StrongBox at all; TEE-backed attestation
            // still proves the key is in hardware.
            generateWith(challenge, strongBox = false)
        }
        return chain()
    }

    fun publicKeyBase64(): String = Base64.encodeToString(publicKeyDer(), Base64.NO_WRAP)

    fun chain(): List<String> =
        (keyStore.getCertificateChain(ALIAS) ?: emptyArray<Certificate>())
            .map { Base64.encodeToString(it.encoded, Base64.NO_WRAP) }

    /** ASN.1 signature over the SHA-256 of [message], base64 — what the server verifies. */
    fun sign(message: ByteArray): String {
        val signature = Signature.getInstance(SIGNATURE).apply {
            initSign(privateKey())
            update(message)
        }
        return Base64.encodeToString(signature.sign(), Base64.NO_WRAP)
    }

    private fun delete() {
        val store = keyStore
        if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS)
    }

    private fun publicKeyDer(): ByteArray = certificate().publicKey.encoded

    private fun generateWith(challenge: ByteArray, strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(challenge)
            // The key signs background syncs, so it cannot be gated on the lock screen.
            .setUserAuthenticationRequired(false)
            .setIsStrongBoxBacked(strongBox)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
            .apply { initialize(spec) }
            .generateKeyPair()
    }

    private fun certificate(): Certificate =
        keyStore.getCertificate(ALIAS) ?: error("device key is missing")

    private fun privateKey(): PrivateKey =
        keyStore.getKey(ALIAS, null) as? PrivateKey ?: error("device key is missing")
}
