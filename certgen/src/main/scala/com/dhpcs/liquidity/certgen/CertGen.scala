package com.dhpcs.liquidity.certgen

import java.io._
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
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

object CertGen {

  private final val SubjectAlternativeName = "liquidity.dhpcs.com"
  private final val CommonName             = "liquidity.dhpcs.com"
  private final val KeyLength              = 2048

  private final val CertKeyStoreFilename   = "liquidity.dhpcs.com.keystore.p12"
  private final val CertKeyStoreEntryAlias = "identity"

  private final val CertStoreFilename   = "liquidity.dhpcs.com.truststore.p12"
  private final val CertStoreEntryAlias = "identity"

  def main(args: Array[String]): Unit = {
    val (certificate, privateKey) = loadCertKey(
      new FileInputStream("server/src/main/resources/liquidity.dhpcs.com.keystore.p12"),
      "PKCS12"
    )
    val keyPair            = new KeyPair(certificate.getPublicKey, privateKey)
    val updatedCertificate = generateCert(keyPair, subjectAlternativeName = Some(SubjectAlternativeName))
    saveCertKey(new FileOutputStream(CertKeyStoreFilename), "PKCS12", updatedCertificate, privateKey)
    saveCert(new FileOutputStream(CertStoreFilename), "PKCS12", updatedCertificate)
  }

  def generateCertKey(subjectAlternativeName: Option[String]): (X509Certificate, PrivateKey) = {
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
    keyPairGenerator.initialize(KeyLength)
    val keyPair     = keyPairGenerator.generateKeyPair
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
      subjectAlternativeName
        .fold(
          ifEmpty = certBuilder
        )(
          subjectAlternativeName =>
            certBuilder.addExtension(
              Extension.subjectAlternativeName,
              false,
              new GeneralNames(new GeneralName(GeneralName.dNSName, subjectAlternativeName))
          ))
        .build(
          new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate)
        )
    )
  }

  def loadCertKey(from: InputStream, keyStoreType: String): (X509Certificate, PrivateKey) = {
    val keyStore = loadKeyStore(from, keyStoreType)
    val privateKeyEntry = keyStore
      .getEntry(
        CertKeyStoreEntryAlias,
        new KeyStore.PasswordProtection(Array.emptyCharArray)
      )
      .asInstanceOf[PrivateKeyEntry]
    (privateKeyEntry.getCertificate.asInstanceOf[X509Certificate], privateKeyEntry.getPrivateKey)
  }

  def loadCert(from: InputStream, keyStoreType: String): X509Certificate = {
    val keyStore = loadKeyStore(from, keyStoreType)
    keyStore.getCertificate(CertStoreEntryAlias).asInstanceOf[X509Certificate]
  }

  private def loadKeyStore(from: InputStream, keyStoreType: String): KeyStore = {
    val keyStore = KeyStore.getInstance(keyStoreType)
    try keyStore.load(from, Array.emptyCharArray)
    finally from.close()
    keyStore
  }

  def saveCertKey(to: OutputStream, keyStoreType: String, certificate: X509Certificate, privateKey: PrivateKey): Unit = {
    val keyStore = createKeyStore(keyStoreType)
    keyStore.setKeyEntry(
      CertKeyStoreEntryAlias,
      privateKey,
      Array.emptyCharArray,
      Array[Certificate](certificate)
    )
    saveKeyStore(to, keyStore)
  }

  def saveCert(to: OutputStream, keyStoreType: String, certificate: X509Certificate): Unit = {
    val keyStore = createKeyStore(keyStoreType)
    keyStore.setCertificateEntry(
      CertStoreEntryAlias,
      certificate
    )
    saveKeyStore(to, keyStore)
  }

  private def createKeyStore(keyStoreType: String): KeyStore = {
    val keyStore = KeyStore.getInstance(keyStoreType)
    keyStore.load(null, null)
    keyStore
  }

  private def saveKeyStore(to: OutputStream, keyStore: KeyStore): Unit = {
    try keyStore.store(to, Array.emptyCharArray)
    finally to.close()
  }
}
