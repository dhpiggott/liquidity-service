package com.dhpcs.liquidity.models

import java.security.MessageDigest
import java.util

import com.google.common.io.BaseEncoding
import play.api.libs.json._

case class PublicKey(value: Array[Byte]) {

  lazy val fingerprint = BaseEncoding.base16().encode(MessageDigest.getInstance("SHA-1").digest(value))

  override def equals(that: Any) = that match {

    case that: PublicKey => util.Arrays.equals(this.value, that.value)

    case _ => false

  }

  override def hashCode = util.Arrays.hashCode(value)

  override def toString = s"$productPrefix(${BaseEncoding.base64().encode(value)})"

}

object PublicKey {

  implicit val publicKeyReads =
    __.read[String].map(publicKeyBase64 => PublicKey(BaseEncoding.base64().decode(publicKeyBase64)))

  implicit val publicKeyWrites = Writes[PublicKey] {
    publicKey => JsString(BaseEncoding.base64().encode(publicKey.value))
  }

}