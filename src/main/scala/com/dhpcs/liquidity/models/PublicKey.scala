package com.dhpcs.liquidity.models

import java.util

import org.apache.commons.codec.binary.Base64
import play.api.libs.json._

object PublicKey {

  implicit val publicKeyReads =
    __.read[String].map(publicKeyBase64 => new PublicKey(Base64.decodeBase64(publicKeyBase64)))

  implicit val publicKeyWrites = Writes[PublicKey] {
    publicKey => JsString(Base64.encodeBase64String(publicKey.value))

  }

}

class PublicKey(val value: Array[Byte]) {

  lazy val base64encoded = Base64.encodeBase64String(value)

  override def equals(that: Any) = that match {

    case that: PublicKey => util.Arrays.equals(this.value, that.value)

    case _ => false

  }

  override def toString = base64encoded

}