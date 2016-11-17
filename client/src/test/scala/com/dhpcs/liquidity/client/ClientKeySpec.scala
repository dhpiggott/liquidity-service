package com.dhpcs.liquidity.client

import java.nio.file.Files
import java.security.Security
import javax.net.ssl.X509KeyManager

import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec}
import org.spongycastle.jce.provider.BouncyCastleProvider

class ClientKeySpec extends WordSpec with Matchers with BeforeAndAfterAll {

  private[this] val clientKeyDirectory = {
    val clientKeyDirectory = Files.createTempDirectory("liquidity-client-key").toFile
    clientKeyDirectory.deleteOnExit()
    clientKeyDirectory
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    Security.addProvider(new BouncyCastleProvider)
  }

  override protected def afterAll(): Unit = {
    Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
    super.afterAll()
  }

  "ClientKey" should {
    "provide a single key manager" in {
      ClientKey.getKeyManagers(clientKeyDirectory).length shouldBe 1
    }
    "expose the key manager's public key" in {
      val expectedPublicKey = ClientKey
        .getKeyManagers(clientKeyDirectory)(0)
        .asInstanceOf[X509KeyManager]
        .getCertificateChain("identity")(0)
        .getPublicKey
        .getEncoded
      ClientKey.getPublicKey(clientKeyDirectory).value.toByteArray shouldBe expectedPublicKey
    }
  }
}
