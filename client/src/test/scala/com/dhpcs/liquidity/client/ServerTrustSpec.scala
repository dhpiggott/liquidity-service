package com.dhpcs.liquidity.client

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.security.cert.CertificateException

import com.dhpcs.liquidity.certgen.CertGen
import org.scalatest.{BeforeAndAfterAll, MustMatchers, WordSpec}

class ServerTrustSpec extends WordSpec with MustMatchers with BeforeAndAfterAll {

  private[this] val (certificate, _) = CertGen.generateCertKey(subjectAlternativeName = None)
  private[this] val keyStoreInputStream = {
    val to = new ByteArrayOutputStream
    CertGen.saveCert(to, "PKCS12", certificate)
    new ByteArrayInputStream(to.toByteArray)
  }

  "ServerTrust" must {
    "trust certs containing the pinned public key" in {
      val trustManager = ServerTrust.getTrustManager(keyStoreInputStream)
      val chain = Array(certificate)
      trustManager.checkServerTrusted(chain, "test")
    }
    "not trust certs not containing the pinned public key" in {
      val trustManager = ServerTrust.getTrustManager(keyStoreInputStream)
      val (otherCertificate, _) = CertGen.generateCertKey(subjectAlternativeName = None)
      val chain = Array(otherCertificate)
      a[CertificateException] must be thrownBy trustManager.checkServerTrusted(chain, "test")
    }
  }
}
