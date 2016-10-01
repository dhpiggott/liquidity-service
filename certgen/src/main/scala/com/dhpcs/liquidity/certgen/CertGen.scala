package com.dhpcs.liquidity.certgen

import java.io.{File, FileInputStream, FileOutputStream}
import java.math.BigInteger
import java.security.KeyStore.PrivateKeyEntry
import java.security._
import java.security.cert.{Certificate, X509Certificate}
import java.util.{Calendar, Locale}

import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.{Extension, GeneralName, GeneralNames, Time}
import org.bouncycastle.asn1.{ASN1GeneralizedTime, ASN1UTCTime}
import org.bouncycastle.cert.jcajce.{JcaX509CertificateConverter, JcaX509ExtensionUtils, JcaX509v3CertificateBuilder}
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

object CertGen {
  private final val SubjectAlternativeName = "liquidity.dhpcs.com"
  private final val CommonName = "liquidity.dhpcs.com"
  private final val KeyLength = 2048

  private final val CertKeyStoreFilename = "liquidity.dhpcs.com.keystore.p12"
  private final val CertKeyStoreEntryAlias = "identity"

  private final val CertStoreFilename = "liquidity.dhpcs.com.truststore.bks"
  private final val CertStoreEntryAlias = "identity"

  def main(args: Array[String]): Unit = {
    Security.addProvider(new BouncyCastleProvider)
    val (certificate, privateKey) = loadCertKey(
      new File("server/src/main/resources/liquidity.dhpcs.com.keystore.p12"),
      "PKCS12"
    )
    val keyPair = new KeyPair(certificate.getPublicKey, privateKey)
    val updatedCertificate = generateCert(keyPair, subjectAlternativeName = Some(SubjectAlternativeName))
    saveCertKey(new File(CertKeyStoreFilename), "PKCS12", updatedCertificate, privateKey)
    saveCert(new File(CertStoreFilename), "BKS-V1", updatedCertificate)
  }

  def generateCertKey(subjectAlternativeName: Option[String]): (X509Certificate, PrivateKey) = {
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
    keyPairGenerator.initialize(KeyLength)
    val keyPair = keyPairGenerator.generateKeyPair
    val certificate = generateCert(keyPair, subjectAlternativeName)
    (certificate, keyPair.getPrivate)
  }

  def generateCert(keyPair: KeyPair, subjectAlternativeName: Option[String]): X509Certificate = {
    val identity = new X500NameBuilder().addRDN(BCStyle.CN, CommonName).build
    val certBuilder = new JcaX509v3CertificateBuilder(
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
    )
    new JcaX509CertificateConverter().getCertificate(
      subjectAlternativeName.fold(
        ifEmpty = certBuilder
      )(subjectAlternativeName =>
        certBuilder.addExtension(
          Extension.subjectAlternativeName,
          false,
          new GeneralNames(new GeneralName(GeneralName.dNSName, subjectAlternativeName))
        )
      ).build(
        new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate)
      )
    )
  }

  def loadCertKey(from: File, keyStoreType: String): (X509Certificate, PrivateKey) = {
    val keyStore = loadKeyStore(from, keyStoreType)
    val privateKeyEntry = keyStore.getEntry(
      CertKeyStoreEntryAlias,
      new KeyStore.PasswordProtection(Array.emptyCharArray)
    ).asInstanceOf[PrivateKeyEntry]
    (privateKeyEntry.getCertificate.asInstanceOf[X509Certificate], privateKeyEntry.getPrivateKey)
  }

  def loadCert(from: File, keyStoreType: String): X509Certificate = {
    val keyStore = loadKeyStore(from, keyStoreType)
    keyStore.getCertificate(CertStoreEntryAlias).asInstanceOf[X509Certificate]
  }

  private def loadKeyStore(from: File, keyStoreType: String): KeyStore = {
    val keyStore = KeyStore.getInstance(keyStoreType)
    val keyStoreFileInputStream = new FileInputStream(from)
    try keyStore.load(keyStoreFileInputStream, Array.emptyCharArray)
    finally keyStoreFileInputStream.close()
    keyStore
  }

  def saveCertKey(to: File, keyStoreType: String, certificate: X509Certificate, privateKey: PrivateKey): Unit = {
    val keyStore = createKeyStore(keyStoreType)
    keyStore.setKeyEntry(
      CertKeyStoreEntryAlias,
      privateKey,
      Array.emptyCharArray,
      Array[Certificate](certificate)
    )
    saveKeyStore(to, keyStoreType, keyStore)
  }

  def saveCert(to: File, keyStoreType: String, certificate: X509Certificate): Unit = {
    val keyStore = createKeyStore(keyStoreType)
    keyStore.setCertificateEntry(
      CertStoreEntryAlias,
      certificate
    )
    saveKeyStore(to, keyStoreType, keyStore)
  }

  private def createKeyStore(keyStoreType: String): KeyStore = {
    val keyStore = KeyStore.getInstance(keyStoreType)
    keyStore.load(null, null)
    keyStore
  }

  private def saveKeyStore(to: File, keyStoreType: String, keyStore: KeyStore): Unit = {
    val trustStoreFileOutputStream = new FileOutputStream(to)
    try keyStore.store(trustStoreFileOutputStream, Array.emptyCharArray)
    finally trustStoreFileOutputStream.close()
  }
}
