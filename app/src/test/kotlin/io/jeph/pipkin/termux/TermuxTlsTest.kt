package io.jeph.pipkin.termux

import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class TermuxTlsTest {
  @Test
  fun bearerIsSentToThePairedTlsIdentity() {
    val identity = TermuxTls.createIdentity()
    val headers = exchange(identity, identity, succeeds = true)
    assertEquals("Authorization: Bearer test-secret", headers.single { it.startsWith("Authorization:") })
  }

  @Test
  fun anotherCertificateWithTheSameKeyCannotReceiveTheBearer() {
    val paired = TermuxTls.createIdentity()
    val impostor = HeldCertificate.Builder()
      .keyPair(paired.keyPair)
      .commonName("Different certificate, identical public key pin")
      .addSubjectAlternativeName("127.0.0.1")
      .addSubjectAlternativeName("localhost")
      .build()
    assertEquals(paired.certificate.publicKey, impostor.certificate.publicKey)
    assertFalse(exchange(paired, impostor, succeeds = false).any { it.startsWith("Authorization:") })
  }

  @Test
  fun hostnameVerificationStillRunsAfterExactCertificateTrust() {
    val wrongHost = HeldCertificate.Builder().addSubjectAlternativeName("wrong.example").build()
    assertFalse(exchange(wrongHost, wrongHost, succeeds = false).any { it.startsWith("Authorization:") })
  }

  @Test
  fun storedCertificateMustMatchItsPrivateKey() {
    val paired = TermuxTls.createIdentity()
    val unrelated = TermuxTls.createIdentity()
    val error = assertThrows(RuntimeFailure::class.java) {
      TermuxTls.loadIdentity(paired.certificatePem(), unrelated.privateKeyPkcs8Pem())
    }
    assertEquals(RuntimeFailureKind.Authentication, error.kind)
  }

  /** A real TLS server, not a mocked trust manager or an echo of a configured request. */
  private fun exchange(paired: HeldCertificate, serverIdentity: HeldCertificate, succeeds: Boolean): List<String> {
    val serverTls = HandshakeCertificates.Builder().heldCertificate(serverIdentity).build()
    val server = serverTls.sslContext().serverSocketFactory
      .createServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
    val executor = Executors.newSingleThreadExecutor()
    val client = TermuxTls.client(paired.certificate, 5000)
    try {
      server.soTimeout = 5000
      val received = executor.submit<List<String>> {
        val headers = mutableListOf<String>()
        try {
          (server.accept() as SSLSocket).use { socket ->
            socket.soTimeout = 5000
            socket.startHandshake()
            val reader = socket.inputStream.bufferedReader(Charsets.US_ASCII)
            var line = reader.readLine()
            while (!line.isNullOrEmpty()) {
              headers.add(line)
              line = reader.readLine()
            }
            if (headers.isNotEmpty()) {
              socket.outputStream.write(
                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray(),
              )
              socket.outputStream.flush()
            }
          }
        } catch (_: IOException) {
          // A refused handshake must not deliver any HTTP authorization bytes.
        }
        headers
      }
      val request = Request.Builder().url("https://127.0.0.1:${server.localPort}/rpc")
        .header("Authorization", "Bearer test-secret").build()
      if (succeeds) {
        client.newCall(request).execute().use { assertEquals(200, it.code) }
      } else {
        assertThrows(IOException::class.java) { client.newCall(request).execute().close() }
      }
      return received.get(10, TimeUnit.SECONDS)
    } finally {
      server.close()
      client.connectionPool.evictAll()
      client.dispatcher.executorService.shutdownNow()
      executor.shutdownNow()
    }
  }
}
