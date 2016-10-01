package com.dhpcs.liquidity.client

import java.security.Security
import javax.net.ssl.X509KeyManager

import org.iq80.leveldb.util.FileUtils
import org.scalatest.{BeforeAndAfterAll, MustMatchers, WordSpec}
import org.spongycastle.jce.provider.BouncyCastleProvider

class ClientKeySpec extends WordSpec with MustMatchers with BeforeAndAfterAll {

  private[this] val clientKeyDirectory = FileUtils.createTempDir("liquidity-client-key")

  override def beforeAll(): Unit = {
    super.beforeAll()
    Security.addProvider(new BouncyCastleProvider)
  }

  override def afterAll(): Unit = {
    FileUtils.deleteRecursively(clientKeyDirectory)
    Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
    super.afterAll()
  }

  "ClientKey" must {
    "provide a single key manager" in {
      ClientKey.getKeyManagers(clientKeyDirectory).length mustBe 1
    }
    "provide the public key" in {
      val expectedPublicKey = ClientKey.getKeyManagers(clientKeyDirectory)(0)
        .asInstanceOf[X509KeyManager].getCertificateChain("identity")(0)
        .getPublicKey.getEncoded
      ClientKey.getPublicKey(clientKeyDirectory).value.toByteArray mustBe expectedPublicKey
    }
  }
}
