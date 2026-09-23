package io.jeph.pipkin.termux

import java.net.Proxy
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.CertificatePinner
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

/** A paired leaf is an identity, not a CA: even another certificate with its key is not trusted. */
internal object TermuxTls {
  fun createIdentity(): HeldCertificate = HeldCertificate.Builder()
    .commonName("Pipkin Termux supervisor")
    .addSubjectAlternativeName("127.0.0.1")
    .addSubjectAlternativeName("localhost")
    .duration(3650, TimeUnit.DAYS)
    .build()

  fun loadIdentity(certificatePem: String, privateKeyPem: String): HeldCertificate {
    try {
      val held = HeldCertificate.decode(certificatePem + "\n" + privateKeyPem)
      val certificate = held.certificate
      certificate.checkValidity()
      require(certificate.subjectX500Principal == certificate.issuerX500Principal)
      require(certificate.basicConstraints == -1)
      certificate.verify(certificate.publicKey)
      val names = certificate.subjectAlternativeNames.orEmpty()
      require(names.any { it[0] == 7 && it[1] == "127.0.0.1" })
      require(names.any { it[0] == 2 && it[1] == "localhost" })
      // HeldCertificate.decode parses the key but does not prove that it belongs to the certificate.
      val algorithm = when (held.keyPair.private.algorithm) {
        "EC" -> "SHA256withECDSA"
        "RSA" -> "SHA256withRSA"
        else -> throw IllegalArgumentException("Unsupported pairing key")
      }
      val challenge = "pipkin-pairing-key-check".toByteArray(Charsets.US_ASCII)
      val signature = Signature.getInstance(algorithm).run {
        initSign(held.keyPair.private)
        update(challenge)
        sign()
      }
      require(
        Signature.getInstance(algorithm).run {
          initVerify(certificate.publicKey)
          update(challenge)
          verify(signature)
        },
      )
      return held
    } catch (error: GeneralSecurityException) {
      throw invalidIdentity(error)
    } catch (error: IllegalArgumentException) {
      throw invalidIdentity(error)
    }
  }

  fun client(certificate: X509Certificate, timeoutMillis: Long): OkHttpClient {
    val certificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate).build()
    val trust = ExactCertificateTrust(certificates.trustManager, certificate)
    val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
    return OkHttpClient.Builder()
      .sslSocketFactory(tls.socketFactory, trust)
      .certificatePinner(CertificatePinner.Builder().add("127.0.0.1", CertificatePinner.pin(certificate)).build())
      // Keep OkHttp's hostname verifier. No system roots, proxies, redirects or cleartext fallback.
      .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
      .proxy(Proxy.NO_PROXY)
      .followRedirects(false)
      .followSslRedirects(false)
      .retryOnConnectionFailure(false)
      .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
      .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
      .build()
  }

  private fun invalidIdentity(error: Exception) = RuntimeFailure(
    RuntimeFailureKind.Authentication,
    "Private Termux TLS identity is invalid or expired. Stop the old supervisor in Termux, then choose Re-pair. " +
      "The existing state was preserved.",
    error,
  )

  private class ExactCertificateTrust(private val delegate: X509TrustManager, certificate: X509Certificate) :
    X509TrustManager {
    private val encoded = certificate.encoded

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String): Unit =
      throw CertificateException("Client certificates are not used")

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
      if (chain.size != 1 || !MessageDigest.isEqual(encoded, chain[0].encoded)) {
        throw CertificateException("The server does not have the paired certificate")
      }
      chain[0].checkValidity()
      delegate.checkServerTrusted(chain, authType)
    }
  }
}
