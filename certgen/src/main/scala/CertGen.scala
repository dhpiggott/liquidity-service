import java.io.{File, FileOutputStream, FileWriter}
import java.math.BigInteger
import java.security.cert.Certificate
import java.security.{KeyPairGenerator, KeyStore, Security}
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

  private val CommonName = "liquidity.dhpcs.com"
  private val KeyLength = 2048

  private val KeyStoreFilename = "liquidity.dhpcs.com.keystore"
  private val KeyStorePassword = Array.emptyCharArray
  private val KeyStoreEntryAlias = "identity"
  private val KeyStoreEntryPassword = Array.emptyCharArray

  private val TrustStoreFilename = "liquidity.dhpcs.com.truststore"
  private val TrustStorePassword = Array.emptyCharArray
  private val TrustStoreEntryAlias = "identity"

  private val CertificateFilename = "liquidity.dhpcs.com.crt"
  private val KeyFilename = "liquidity.dhpcs.com.key"

  private def generateCertKeyPair = {
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

  def main(args: Array[String]) {
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

}
