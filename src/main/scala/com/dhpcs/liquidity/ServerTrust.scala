package com.dhpcs.liquidity

import java.io.InputStream
import java.security.cert.{CertificateException, X509Certificate}
import java.security.{KeyStore, PublicKey}
import javax.net.ssl.X509TrustManager

import okio.ByteString

import scala.collection.JavaConverters._

object ServerTrust {

  private val TrustManager = new X509TrustManager() {

    @throws(classOf[CertificateException])
    override def checkClientTrusted(chain: Array[X509Certificate], authType: String) =
      checkTrusted(chain)

    @throws(classOf[CertificateException])
    override def checkServerTrusted(chain: Array[X509Certificate], authType: String) =
      checkTrusted(chain)

    @throws(classOf[CertificateException])
    private def checkTrusted(chain: Array[X509Certificate]) {
      val publicKey = chain(0).getPublicKey
      if (!trustedKeys.contains(publicKey)) {
        throw new CertificateException(
          s"Unknown public key: ${ByteString.of(publicKey.getEncoded: _*).base64}"
        )
      }
    }

    override def getAcceptedIssuers = Array.empty[X509Certificate]

  }

  private var trustedKeys: Set[PublicKey] = _

  def getTrustManager(keyStoreInputStream: InputStream) = {
    loadTrustedKeys(keyStoreInputStream)
    TrustManager
  }

  private def loadTrustedKeys(keyStoreInputStream: InputStream) {
    if (trustedKeys == null) {
      val keyStore = KeyStore.getInstance("BKS")
      try {
        keyStore.load(keyStoreInputStream, Array.emptyCharArray)
        trustedKeys = keyStore.aliases.asScala.collect {
          case entryAlias if keyStore.isCertificateEntry(entryAlias) =>
            keyStore.getCertificate(entryAlias).getPublicKey
        }.toSet
      } finally {
        keyStoreInputStream.close()
      }
    }
  }

}
