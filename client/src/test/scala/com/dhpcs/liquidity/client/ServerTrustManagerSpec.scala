package com.dhpcs.liquidity.client

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.security.cert.{CertificateException, X509Certificate}

import com.dhpcs.liquidity.certgen.CertGen
import org.scalatest.{BeforeAndAfterAll, FreeSpec}

class ServerTrustManagerSpec extends FreeSpec with BeforeAndAfterAll {

  private[this] val (certificate, _) = CertGen.generateCertKey(subjectAlternativeName = None)
  private[this] val serverTrustManager = {
    val keyStoreInputStream = {
      val out = new ByteArrayOutputStream
      CertGen.saveCert(out, "PKCS12", certificate)
      new ByteArrayInputStream(out.toByteArray)
    }
    ServerTrustManager(keyStoreInputStream)
  }

  "ServerTrustManager" - {
    "will not trust empty certificate chains" in {
      val chain = Array.empty[X509Certificate]
      intercept[CertificateException](serverTrustManager.checkServerTrusted(chain, "test"))
    }
    "will not trust certificates of unpinned public keys" in {
      val (otherCertificate, _) = CertGen.generateCertKey(subjectAlternativeName = None)
      val chain                 = Array(otherCertificate)
      intercept[CertificateException](serverTrustManager.checkServerTrusted(chain, "test"))
    }
    "will trust certificates of pinned public keys" in {
      val chain = Array(certificate)
      serverTrustManager.checkServerTrusted(chain, "test")
    }
  }
}
