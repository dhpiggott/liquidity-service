package com.dhpcs.liquidity

import java.io.{File, FileOutputStream, FileWriter}
import java.math.BigInteger
import java.security.cert.{Certificate, X509Certificate}
import java.security.{KeyPairGenerator, KeyStore, PrivateKey, Security}
import java.util.{Calendar, Locale}

import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.{Extension, Time}
import org.bouncycastle.asn1.{ASN1GeneralizedTime, ASN1UTCTime}
import org.bouncycastle.cert.jcajce.{JcaX509CertificateConverter, JcaX509ExtensionUtils, JcaX509v3CertificateBuilder}
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JcaMiscPEMGenerator
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.util.io.pem.PemWriter

object CertGen {
  private final val CommonName = "liquidity.dhpcs.com"
  private final val KeyLength = 2048

  private final val KeyStoreFilename = "liquidity.dhpcs.com.keystore"
  private final val KeyStorePassword = Array.emptyCharArray
  private final val KeyStoreEntryAlias = "identity"
  private final val KeyStoreEntryPassword = Array.emptyCharArray

  private final val TrustStoreFilename = "liquidity.dhpcs.com.truststore"
  private final val TrustStorePassword = Array.emptyCharArray
  private final val TrustStoreEntryAlias = "identity"

  private final val CertificateFilename = "liquidity.dhpcs.com.crt"
  private final val KeyFilename = "liquidity.dhpcs.com.key"

  def main(args: Array[String]): Unit = {
    Security.addProvider(new BouncyCastleProvider)
    val (certificate, privateKey) = generateCertKeyPair
    val keyStore = KeyStore.getInstance("JKS")
    val keyStoreFile = new File(KeyStoreFilename)
    keyStore.load(null, null)
    keyStore.setKeyEntry(
      KeyStoreEntryAlias,
      privateKey,
      KeyStoreEntryPassword,
      Array[Certificate](certificate)
    )
    val keyStoreFileOutputStream = new FileOutputStream(keyStoreFile)
    try {
      keyStore.store(keyStoreFileOutputStream, KeyStorePassword)
    } finally {
      keyStoreFileOutputStream.close()
    }
    val certificateFileWriter = new FileWriter(CertificateFilename)
    try {
      val certificatePemWriter = new PemWriter(certificateFileWriter)
      try {
        certificatePemWriter.writeObject(new JcaMiscPEMGenerator(certificate))
      } finally {
        certificatePemWriter.close()
      }
    } finally {
      certificateFileWriter.close()
    }
    val keyFileWriter = new FileWriter(KeyFilename)
    try {
      val keyPemWriter = new PemWriter(keyFileWriter)
      try {
        keyPemWriter.writeObject(new JcaMiscPEMGenerator(privateKey))
      } finally {
        keyPemWriter.close()
      }
    } finally {
      keyFileWriter.close()
    }
    val trustStore = KeyStore.getInstance("BKS-V1")
    val trustStoreFile = new File(TrustStoreFilename)
    trustStore.load(null, null)
    trustStore.setCertificateEntry(
      TrustStoreEntryAlias,
      certificate
    )
    val trustStoreFileOutputStream = new FileOutputStream(trustStoreFile)
    try {
      trustStore.store(trustStoreFileOutputStream, TrustStorePassword)
    } finally {
      trustStoreFileOutputStream.close()
    }
  }

  private def generateCertKeyPair: (X509Certificate, PrivateKey) = {
    val identity = new X500NameBuilder().addRDN(BCStyle.CN, CommonName).build
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
    keyPairGenerator.initialize(KeyLength)
    val keyPair = keyPairGenerator.generateKeyPair
    val certificate = new JcaX509CertificateConverter().getCertificate(
      new JcaX509v3CertificateBuilder(
        identity,
        BigInteger.ONE,
        new Time(new ASN1UTCTime(Calendar.getInstance.getTime, Locale.US)),
        new Time(new ASN1GeneralizedTime("99991231235959Z")),
        identity,
        keyPair.getPublic
      ).addExtension(
        Extension.subjectKeyIdentifier,
        false,
        new JcaX509ExtensionUtils().createSubjectKeyIdentifier(keyPair.getPublic)
      ).build(
        new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate)
      )
    )
    (certificate, keyPair.getPrivate)
  }
}
