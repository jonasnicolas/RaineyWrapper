package com.raineywrapper.converter

import android.content.Context
import android.os.Environment
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Properties

/**
 * The key every game APK is signed with. Android only lets an app update over an
 * existing one when both carry the same signature, so this key must never change.
 *
 * Uses the same files as the desktop builder (~/.raineywrapper/release.keystore +
 * keystore.properties). If they're placed in /RaineyWrapper/key/ on the device they're
 * imported, so games built on the computer and on the device can update each other.
 * Otherwise a new key is generated on first use.
 */
object SigningKey {
    class Loaded(val key: PrivateKey, val certs: List<X509Certificate>, val origin: String)

    private const val KEYSTORE = "release.keystore"
    private const val PROPS = "keystore.properties"

    // BouncyCastle reads the PKCS12 files JDK keytool writes (AES-256 / HMAC-SHA256),
    // which Android's built-in provider doesn't on older versions.
    private val bc = BouncyCastleProvider()

    val importFolder: File get() = File(Environment.getExternalStorageDirectory(), "RaineyWrapper/key")

    private fun dir(context: Context) = File(context.filesDir, "key").apply { mkdirs() }

    fun exists(context: Context) = File(dir(context), KEYSTORE).exists() && File(dir(context), PROPS).exists()

    /** Imports from [importFolder] if present (then deletes it there), else generates a key if none exists. */
    fun ensure(context: Context): String {
        val src = importFolder
        if (File(src, KEYSTORE).exists() && File(src, PROPS).exists()) {
            val target = dir(context)
            File(src, KEYSTORE).copyTo(File(target, KEYSTORE), overwrite = true)
            File(src, PROPS).copyTo(File(target, PROPS), overwrite = true)
            load(context) // fail now, not at convert time, if the files are bad
            // The private key shouldn't sit on shared storage any longer than needed.
            src.listFiles()?.forEach { it.delete() }
            src.delete()
            File(target, "origin").writeText("imported")
            return "imported"
        }
        if (exists(context)) return "existing"
        generate(context)
        return "generated"
    }

    fun load(context: Context): Loaded {
        val props = Properties().apply { File(dir(context), PROPS).inputStream().use { load(it) } }
        val password = props.getProperty("storePassword").toCharArray()
        val alias = props.getProperty("keyAlias", "raineywrapper")
        val ks = KeyStore.getInstance("PKCS12", bc)
        File(dir(context), KEYSTORE).inputStream().use { ks.load(it, password) }
        val bcKey = ks.getKey(alias, password) as PrivateKey
        // Convert to platform objects so apksig's Signature lookups use Android's own providers.
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bcKey.encoded))
        val cf = CertificateFactory.getInstance("X.509")
        val certs = ks.getCertificateChain(alias).map { cf.generateCertificate(it.encoded.inputStream()) as X509Certificate }
        val origin = File(dir(context), "origin").takeIf { it.exists() }?.readText() ?: "existing"
        return Loaded(key, certs, origin)
    }

    private fun generate(context: Context) {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(4096) }.generateKeyPair()
        val name = X500Name("CN=RaineyWrapper Personal Builds")
        val now = System.currentTimeMillis()
        val cert = JcaX509CertificateConverter().setProvider(bc).getCertificate(
            JcaX509v3CertificateBuilder(
                name, BigInteger(64, SecureRandom()), Date(now - 86_400_000L), Date(now + 100L * 365 * 86_400_000L), name, pair.public,
            ).build(JcaContentSignerBuilder("SHA256withRSA").setProvider(bc).build(pair.private)),
        )
        val password = BigInteger(130, SecureRandom()).toString(36)
        val ks = KeyStore.getInstance("PKCS12", bc).apply {
            load(null, null)
            setKeyEntry("raineywrapper", pair.private, password.toCharArray(), arrayOf(cert))
        }
        File(dir(context), KEYSTORE).outputStream().use { ks.store(it, password.toCharArray()) }
        File(dir(context), PROPS).writeText("storePassword=$password\nkeyAlias=raineywrapper\n")
        File(dir(context), "origin").writeText("generated")
    }
}
