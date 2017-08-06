package com.dhpcs.liquidity.testkit

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, IOException, InputStream}
import java.math.BigInteger
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, Files, Path, SimpleFileVisitor}
import java.security.cert.{Certificate, X509Certificate}
import java.security.{KeyPairGenerator, KeyStore, PrivateKey}
import java.util.{Calendar, Locale}
import javax.net.ssl.{KeyManager, KeyManagerFactory}

import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.{Extension, GeneralName, GeneralNames, Time}
import org.bouncycastle.asn1.{ASN1GeneralizedTime, ASN1UTCTime}
import org.bouncycastle.cert.jcajce.{JcaX509CertificateConverter, JcaX509ExtensionUtils, JcaX509v3CertificateBuilder}
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

object TestKit {

  private final val KeySize = 2048

  def generateCertKey(subjectAlternativeName: Option[String]): (X509Certificate, PrivateKey) = {
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
    keyPairGenerator.initialize(KeySize)
    val keyPair  = keyPairGenerator.generateKeyPair
    val identity = new X500NameBuilder().addRDN(BCStyle.CN, "liquidity.dhpcs.com").build
    val certBuilder = new JcaX509v3CertificateBuilder(
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
    val certificate = new JcaX509CertificateConverter().getCertificate(
      subjectAlternativeName
        .fold(
          ifEmpty = certBuilder
        )(
          subjectAlternativeName =>
            certBuilder.addExtension(
              Extension.subjectAlternativeName,
              false,
              new GeneralNames(new GeneralName(GeneralName.dNSName, subjectAlternativeName))
          ))
        .build(
          new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate)
        )
    )
    (certificate, keyPair.getPrivate)
  }

  def toInputStream(certificate: Certificate): InputStream = {
    val keyStore = KeyStore.getInstance("PKCS12")
    keyStore.load(null, null)
    keyStore.setCertificateEntry(
      "identity",
      certificate
    )
    val out = new ByteArrayOutputStream
    try keyStore.store(out, Array.emptyCharArray)
    finally out.close()
    new ByteArrayInputStream(out.toByteArray)
  }

  def createKeyManagers(certificate: Certificate, privateKey: PrivateKey): Array[KeyManager] = {
    val keyStore = KeyStore.getInstance("PKCS12")
    keyStore.load(null, null)
    keyStore.setKeyEntry(
      "identity",
      privateKey,
      Array.emptyCharArray,
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

  def delete(path: Path): Unit = {
    Files.walkFileTree(
      path,
      new SimpleFileVisitor[Path]() {
        override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
          Files.delete(file)
          FileVisitResult.CONTINUE
        }

        override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult = {
          Files.delete(dir)
          FileVisitResult.CONTINUE
        }
      }
    ); ()
  }
}
