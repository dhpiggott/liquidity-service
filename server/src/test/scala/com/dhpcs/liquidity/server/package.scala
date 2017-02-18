package com.dhpcs.liquidity

import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.security.cert.Certificate
import java.security.{KeyStore, PrivateKey}
import javax.net.ssl.{KeyManager, KeyManagerFactory}

package object server {

  private final val KeyStoreEntryAlias                 = "identity"
  private final val KeyStoreEntryPassword: Array[Char] = Array.emptyCharArray

  def createKeyManagers(certificate: Certificate, privateKey: PrivateKey): Array[KeyManager] = {
    val keyStore = KeyStore.getInstance("PKCS12")
    keyStore.load(null, null)
    keyStore.setKeyEntry(
      KeyStoreEntryAlias,
      privateKey,
      KeyStoreEntryPassword,
      Array(certificate)
    )
    val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    keyManagerFactory.init(
      keyStore,
      Array.emptyCharArray
    )
    keyManagerFactory.getKeyManagers
  }

  def freePort(): Int = {
    val serverSocket = ServerSocketChannel.open().socket()
    serverSocket.bind(new InetSocketAddress("localhost", 0))
    val port = serverSocket.getLocalPort
    serverSocket.close()
    port
  }
}
