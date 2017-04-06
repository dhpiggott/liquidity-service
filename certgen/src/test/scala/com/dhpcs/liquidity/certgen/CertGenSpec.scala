package com.dhpcs.liquidity.certgen

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.security.KeyPair
import java.security.interfaces.RSAPrivateKey

import org.scalatest.{BeforeAndAfterAll, FreeSpec}

class CertGenSpec extends FreeSpec with BeforeAndAfterAll {

  "CertGen.generateCertKey" - {
    "will create 2048 bit RSA private keys" in {
      val (_, privateKey) = CertGen.generateCertKey(subjectAlternativeName = None)
      assert(privateKey.asInstanceOf[RSAPrivateKey].getModulus.bitLength === 2048)
    }
    "will create certificates from existing keypairs" in {
      val (certificate, privateKey) = CertGen.generateCertKey(subjectAlternativeName = None)
      val keyPair                   = new KeyPair(certificate.getPublicKey, privateKey)
      val expectedPublicKey         = certificate.getPublicKey
      val publicKey                 = CertGen.generateCert(keyPair, subjectAlternativeName = None).getPublicKey
      assert(publicKey === expectedPublicKey)
    }
    "will round-trip certificates and private keys" in {
      val (expectedCertificate, expectedPrivateKey) = CertGen.generateCertKey(subjectAlternativeName = None)
      val to                                        = new ByteArrayOutputStream
      CertGen.saveCertKey(to, "PKCS12", expectedCertificate, expectedPrivateKey)
      val from                      = new ByteArrayInputStream(to.toByteArray)
      val (certificate, privateKey) = CertGen.loadCertKey(from, "PKCS12")
      assert(certificate === expectedCertificate)
      assert(privateKey === expectedPrivateKey)
    }
    "will round-trip certificates" in {
      val (expectedCertificate, _) = CertGen.generateCertKey(subjectAlternativeName = None)
      val to                       = new ByteArrayOutputStream
      CertGen.saveCert(to, "PKCS12", expectedCertificate)
      val from        = new ByteArrayInputStream(to.toByteArray)
      val certificate = CertGen.loadCert(from, "PKCS12")
      assert(certificate === expectedCertificate)
    }
  }
}
