package com.dhpcs.liquidity.models

import java.security.MessageDigest
import java.util

import org.apache.commons.codec.binary.{Base64, Hex}
import play.api.libs.json._

case class PublicKey(value: Array[Byte]) {

  lazy val fingerprint = Hex.encodeHexString(MessageDigest.getInstance("SHA-1").digest(value))

  override def equals(that: Any) = that match {

    case that: PublicKey => util.Arrays.equals(this.value, that.value)

    case _ => false

  }

  override def hashCode = util.Arrays.hashCode(value)

  override def toString = s"$productPrefix(${Base64.encodeBase64String(value)})"

}

object PublicKey {

  implicit val publicKeyReads =
    __.read[String].map(publicKeyBase64 => PublicKey(Base64.decodeBase64(publicKeyBase64)))

  implicit val publicKeyWrites = Writes[PublicKey] {
    publicKey => JsString(Base64.encodeBase64String(publicKey.value))
  }

}