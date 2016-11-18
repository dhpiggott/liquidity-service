package com.dhpcs.liquidity.client

import java.io.InputStream
import java.security.cert.{CertificateException, X509Certificate}
import java.security.{KeyStore, PublicKey}
import javax.net.ssl.X509TrustManager

import scala.collection.JavaConverters._

object ServerTrust {

  private final val TrustManager = new X509TrustManager {

    @throws(classOf[CertificateException])
    override def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit =
      throw new CertificateException

    @throws(classOf[CertificateException])
    override def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit =
      if (!chain.toSeq.headOption.map(_.getPublicKey).fold(false)(trustedKeys.contains)) throw new CertificateException

    override def getAcceptedIssuers: Array[X509Certificate] = Array.empty

  }

  private var trustedKeys: Set[PublicKey] = _

  def getTrustManager(keyStoreInputStream: InputStream): X509TrustManager = {
    loadTrustedKeys(keyStoreInputStream)
    TrustManager
  }

  private def loadTrustedKeys(keyStoreInputStream: InputStream): Unit = {
    if (trustedKeys == null) {
      val keyStore = KeyStore.getInstance("PKCS12")
      try {
        keyStore.load(keyStoreInputStream, Array.emptyCharArray)
        trustedKeys = keyStore.aliases.asScala.collect {
          case entryAlias if keyStore.isCertificateEntry(entryAlias) =>
            keyStore.getCertificate(entryAlias).getPublicKey
        }.toSet
      } finally keyStoreInputStream.close()
    }
  }
}
