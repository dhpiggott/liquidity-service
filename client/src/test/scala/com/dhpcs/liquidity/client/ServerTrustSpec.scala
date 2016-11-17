package com.dhpcs.liquidity.client

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.security.cert.{CertificateException, X509Certificate}

import com.dhpcs.liquidity.certgen.CertGen
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec}

class ServerTrustSpec extends WordSpec with Matchers with BeforeAndAfterAll {

  private[this] val (certificate, _) = CertGen.generateCertKey(subjectAlternativeName = None)
  private[this] val keyStoreInputStream = {
    val to = new ByteArrayOutputStream
    CertGen.saveCert(to, "PKCS12", certificate)
    new ByteArrayInputStream(to.toByteArray)
  }

  "ServerTrust" should {
    "not trust empty certificate chains" in {
      val trustManager = ServerTrust.getTrustManager(keyStoreInputStream)
      val chain = Array.empty[X509Certificate]
      a[CertificateException] should be thrownBy trustManager.checkServerTrusted(chain, "test")
    }
    "not trust certificates of unpinned public keys" in {
      val trustManager = ServerTrust.getTrustManager(keyStoreInputStream)
      val (otherCertificate, _) = CertGen.generateCertKey(subjectAlternativeName = None)
      val chain = Array(otherCertificate)
      a[CertificateException] should be thrownBy trustManager.checkServerTrusted(chain, "test")
    }
    "trust certificates of pinned public keys" in {
      val trustManager = ServerTrust.getTrustManager(keyStoreInputStream)
      val chain = Array(certificate)
      trustManager.checkServerTrusted(chain, "test")
    }
  }
}
