package com.dhpcs.liquidity.client

import java.io.{File, FileInputStream, FileReader, FileWriter}
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.security.{KeyPair, KeyPairGenerator, KeyStore, PrivateKey}

import org.spongycastle.openssl.jcajce.{JcaPEMKeyConverter, JcaPEMWriter}
import org.spongycastle.openssl.{PEMKeyPair, PEMParser}

object ClientKeyStore {

  private final val LegacyBksKeyStoreFilename = "client.keystore"
  private final val EntryAlias                = "identity"
  private final val KeySize                   = 2048
  private final val PrivateKeyFilename        = "id_rsa"

  def apply(filesDir: File): ClientKeyStore = {
    val keyStoreFile   = new File(filesDir, LegacyBksKeyStoreFilename)
    val privateKeyFile = new File(filesDir, PrivateKeyFilename)
    val keyPair        = readKey(privateKeyFile).orElse(loadFromLegacyBksKeyStore(keyStoreFile)).getOrElse(generateKey())
    if (!privateKeyFile.exists) writeKey(privateKeyFile, keyPair)
    deleteLegacyBksKeyStoreIfExists(keyStoreFile)
    ClientKeyStore(keyPair.getPublic.asInstanceOf[RSAPublicKey], keyPair.getPrivate.asInstanceOf[RSAPrivateKey])
  }

  private def readKey(privateKeyFile: File): Option[KeyPair] =
    if (!privateKeyFile.exists)
      None
    else {
      val privateKeyPemParser = new PEMParser(new FileReader(privateKeyFile))
      try {
        val keyPair = new JcaPEMKeyConverter().getKeyPair(privateKeyPemParser.readObject().asInstanceOf[PEMKeyPair])
        Some(keyPair)
      } finally privateKeyPemParser.close()
    }

  private def loadFromLegacyBksKeyStore(keyStoreFile: File): Option[KeyPair] = {
    if (!keyStoreFile.exists)
      None
    else {
      val keyStore                = KeyStore.getInstance("BKS")
      val keyStoreFileInputStream = new FileInputStream(keyStoreFile)
      try keyStore.load(keyStoreFileInputStream, Array.emptyCharArray)
      finally keyStoreFileInputStream.close()
      val publicKey  = keyStore.getCertificate(EntryAlias).getPublicKey
      val privateKey = keyStore.getKey(EntryAlias, Array.emptyCharArray).asInstanceOf[PrivateKey]
      Some(new KeyPair(publicKey, privateKey))
    }
  }

  private def generateKey(): KeyPair = {
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
    keyPairGenerator.initialize(KeySize)
    keyPairGenerator.generateKeyPair
  }

  private def writeKey(privateKeyFile: File, keyPair: KeyPair): Unit = {
    val privateKeyPemWriter = new JcaPEMWriter(new FileWriter(privateKeyFile))
    try privateKeyPemWriter.writeObject(keyPair.getPrivate)
    finally privateKeyPemWriter.close()
  }

  private def deleteLegacyBksKeyStoreIfExists(keyStoreFile: File): Unit =
    if (keyStoreFile.exists) {
      keyStoreFile.delete(); ()
    }

}

case class ClientKeyStore private (publicKey: RSAPublicKey, privateKey: RSAPrivateKey)
