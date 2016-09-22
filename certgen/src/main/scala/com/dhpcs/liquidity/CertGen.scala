package com.dhpcs.liquidity

import java.io.{File, FileOutputStream}
import java.math.BigInteger
import java.security.cert.{Certificate, X509Certificate}
import java.security.{KeyPairGenerator, KeyStore, PrivateKey, Security}
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

  private final val KeyStoreFilename = "liquidity.dhpcs.com.keystore"
  private final val KeyStoreEntryAlias = "identity"

  private final val TrustStoreFilename = "liquidity.dhpcs.com.truststore"
  private final val TrustStoreEntryAlias = "identity"

  def main(args: Array[String]): Unit = {
    Security.addProvider(new BouncyCastleProvider)
    val (certificate, privateKey) = generateCertKeyPair(Some(SubjectAlternativeName))
    val keyStore = KeyStore.getInstance("JKS")
    val keyStoreFile = new File(KeyStoreFilename)
    keyStore.load(null, null)
    keyStore.setKeyEntry(
      KeyStoreEntryAlias,
      privateKey,
      Array.emptyCharArray,
      Array[Certificate](certificate)
    )
    val keyStoreFileOutputStream = new FileOutputStream(keyStoreFile)
    try {
      keyStore.store(keyStoreFileOutputStream, Array.emptyCharArray)
    } finally {
      keyStoreFileOutputStream.close()
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
      trustStore.store(trustStoreFileOutputStream, Array.emptyCharArray)
    } finally {
      trustStoreFileOutputStream.close()
    }
  }

  def generateCertKeyPair(subjectAlternativeName: Option[String]): (X509Certificate, PrivateKey) = {
    val identity = new X500NameBuilder().addRDN(BCStyle.CN, CommonName).build
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
    keyPairGenerator.initialize(KeyLength)
    val keyPair = keyPairGenerator.generateKeyPair
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
    val certificate = new JcaX509CertificateConverter().getCertificate(
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
    (certificate, keyPair.getPrivate)
  }
}
