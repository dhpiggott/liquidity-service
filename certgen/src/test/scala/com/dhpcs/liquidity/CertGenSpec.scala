package com.dhpcs.liquidity

import java.io.File
import java.security.interfaces.RSAPrivateKey
import java.security.{KeyPair, Security}

import com.dhpcs.liquidity.CertGenSpec._
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.scalatest.{BeforeAndAfterAll, MustMatchers, WordSpec}

object CertGenSpec {
  private final val TempFilePrefix = "liquidity-cert-gen-spec"

  private def withTempFile(body: File => Unit): Unit = {
    val certFile = File.createTempFile(TempFilePrefix, null)
    try body(certFile) finally certFile.delete()
  }
}

class CertGenSpec extends WordSpec with MustMatchers with BeforeAndAfterAll {

  override def beforeAll(): Unit = {
    super.beforeAll()
    Security.addProvider(new BouncyCastleProvider)
  }

  override def afterAll(): Unit = {
    Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
    super.afterAll()
  }

  "CertGen" must {
    "create 2048 bit RSA private keys" in {
      val (_, privateKey) = CertGen.generateCertKey(subjectAlternativeName = None)
      privateKey mustBe a[RSAPrivateKey]
      privateKey.asInstanceOf[RSAPrivateKey].getModulus.bitLength mustBe 2048
    }
    "create certificates from existing keypairs" in {
      val (certificate, privateKey) = CertGen.generateCertKey(subjectAlternativeName = None)
      val keyPair = new KeyPair(certificate.getPublicKey, privateKey)
      val expectedPublicKey = certificate.getPublicKey
      val publicKey = CertGen.generateCert(keyPair, subjectAlternativeName = None).getPublicKey
      publicKey mustBe expectedPublicKey
    }
    "round-trip certificates and private keys via JKS keystores" in roundTripCertKey("JKS")
    "round-trip certificates via JKS keystores" in roundTripCert("JKS")
    "round-trip certificates and private keys via PKCS12 keystores" in roundTripCertKey("PKCS12")
    "round-trip certificates via PKCS12 keystores" in roundTripCert("PKCS12")
    "round-trip certificates and private keys via BKS-V1 keystores" in roundTripCertKey("BKS-V1")
    "round-trip certificates via BKS-V1 keystores" in roundTripCert("BKS-V1")
  }

  private def roundTripCertKey(keyStoreType: String): Unit = {
    val (expectedCertificate, expectedPrivateKey) = CertGen.generateCertKey(subjectAlternativeName = None)
    withTempFile { certKeyFile =>
      CertGen.saveCertKey(certKeyFile, keyStoreType, expectedCertificate, expectedPrivateKey)
      val (certificate, privateKey) = CertGen.loadCertKey(certKeyFile, keyStoreType)
      certificate mustBe expectedCertificate
      privateKey mustBe expectedPrivateKey
    }
  }

  private def roundTripCert(keyStoreType: String): Unit = {
    val (expectedCertificate, _) = CertGen.generateCertKey(subjectAlternativeName = None)
    withTempFile { certFile =>
      CertGen.saveCert(certFile, keyStoreType, expectedCertificate)
      val certificate = CertGen.loadCert(certFile, keyStoreType)
      certificate mustBe expectedCertificate
    }
  }
}
