package com.dhpcs.liquidity.client

import java.io.{File, FileInputStream, FileOutputStream}
import java.math.BigInteger
import java.security.cert.X509Certificate
import java.security.{KeyPairGenerator, KeyStore, PrivateKey}
import java.util.{Calendar, Locale}
import javax.net.ssl.{KeyManager, KeyManagerFactory}

import com.dhpcs.liquidity.model.PublicKey
import org.spongycastle.asn1.x500.X500NameBuilder
import org.spongycastle.asn1.x500.style.BCStyle
import org.spongycastle.asn1.x509.{Extension, Time}
import org.spongycastle.asn1.{ASN1GeneralizedTime, ASN1UTCTime}
import org.spongycastle.cert.jcajce.{JcaX509CertificateConverter, JcaX509ExtensionUtils, JcaX509v3CertificateBuilder}
import org.spongycastle.operator.jcajce.JcaContentSignerBuilder

object ClientKey {

  private final val KeystoreFilename = "client.keystore"
  private final val EntryAlias       = "identity"
  private final val CommonName       = "com.dhpcs.liquidity"
  private final val KeyLength        = 2048

  private var keyStore: KeyStore             = _
  private var publicKey: PublicKey           = _
  private var keyManagers: Array[KeyManager] = _

  def getKeyManagers(filesDir: File): Array[KeyManager] = {
    if (keyManagers == null) {
      val keyStore          = getOrLoadOrCreateKeyStore(filesDir)
      val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
      keyManagerFactory.init(keyStore, Array.emptyCharArray)
      keyManagers = keyManagerFactory.getKeyManagers
    }
    keyManagers
  }

  def getPublicKey(filesDir: File): PublicKey = {
    if (publicKey == null) {
      val keyStore = getOrLoadOrCreateKeyStore(filesDir)
      publicKey = PublicKey(
        keyStore.getCertificate(ClientKey.EntryAlias).getPublicKey.getEncoded
      )
    }
    publicKey
  }

  private def getOrLoadOrCreateKeyStore(filesDir: File): KeyStore = {
    if (keyStore == null) {
      keyStore = KeyStore.getInstance("BKS")
      val keyStoreFile = new File(filesDir, KeystoreFilename)
      if (!keyStoreFile.exists) {
        val (certificate, privateKey) = generateCertKey()
        keyStore.load(null, null)
        keyStore.setKeyEntry(
          EntryAlias,
          privateKey,
          Array.emptyCharArray,
          Array(certificate)
        )
        val keyStoreFileOutputStream = new FileOutputStream(keyStoreFile)
        try keyStore.store(keyStoreFileOutputStream, Array.emptyCharArray)
        finally keyStoreFileOutputStream.close()
      } else {
        val keyStoreFileInputStream = new FileInputStream(keyStoreFile)
        try keyStore.load(keyStoreFileInputStream, Array.emptyCharArray)
        finally keyStoreFileInputStream.close()
      }
    }
    keyStore
  }

  private def generateCertKey(): (X509Certificate, PrivateKey) = {
    val identity         = new X500NameBuilder().addRDN(BCStyle.CN, CommonName).build
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
        )
        .build(
          new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate)
        )
    )
    (certificate, keyPair.getPrivate)
  }
}
