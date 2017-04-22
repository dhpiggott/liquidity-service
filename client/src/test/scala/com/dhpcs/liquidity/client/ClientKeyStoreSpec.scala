package com.dhpcs.liquidity.client

import java.nio.file.Files
import java.security.Security
import javax.net.ssl.X509KeyManager

import com.dhpcs.liquidity.server._
import org.scalatest.{BeforeAndAfterAll, FreeSpec}
import org.spongycastle.jce.provider.BouncyCastleProvider

class ClientKeyStoreSpec extends FreeSpec with BeforeAndAfterAll {

  Security.addProvider(new BouncyCastleProvider)

  private[this] val filesDir       = Files.createTempDirectory("liquidity-client-key-store-spec-files-dir")
  private[this] val clientKeyStore = ClientKeyStore(filesDir.toFile)

  override protected def afterAll(): Unit = {
    delete(filesDir)
    super.afterAll()
  }

  "ClientKey" - {
    "will provide a single key manager" in assert(
      clientKeyStore.keyManagers.length === 1
    )
    "will expose the key manager's public key" in {
      val expectedPublicKey = clientKeyStore
        .keyManagers(0)
        .asInstanceOf[X509KeyManager]
        .getCertificateChain("identity")(0)
        .getPublicKey
        .getEncoded
      assert(clientKeyStore.publicKey.value.toByteArray === expectedPublicKey)
    }
  }
}
