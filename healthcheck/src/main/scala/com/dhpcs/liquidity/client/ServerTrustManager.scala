package com.dhpcs.liquidity.client

import java.io.InputStream
import java.security.cert.{CertificateException, X509Certificate}
import java.security.{KeyStore, PublicKey}
import javax.net.ssl.X509TrustManager

import scala.collection.JavaConverters._

object ServerTrustManager {

  def apply(keyStoreInputStream: InputStream): ServerTrustManager = {
    val keyStore = KeyStore.getInstance("PKCS12")
    try {
      keyStore.load(keyStoreInputStream, Array.emptyCharArray)
      val trustedKeys = keyStore.aliases.asScala.collect {
        case entryAlias if keyStore.isCertificateEntry(entryAlias) =>
          keyStore.getCertificate(entryAlias).getPublicKey
      }.toSet
      new ServerTrustManager(trustedKeys)
    } finally keyStoreInputStream.close()
  }
}

class ServerTrustManager private (trustedKeys: Set[PublicKey]) extends X509TrustManager {

  override def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit =
    throw new CertificateException

  override def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit =
    if (!chain.headOption.map(_.getPublicKey).exists(trustedKeys.contains))
      throw new CertificateException

  override def getAcceptedIssuers: Array[X509Certificate] = Array.empty

}
