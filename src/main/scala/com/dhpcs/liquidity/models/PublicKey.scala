package com.dhpcs.liquidity.models

import java.security.MessageDigest
import java.util

import com.dhpcs.json.ValueFormat
import com.google.common.io.BaseEncoding

case class PublicKey(value: Array[Byte]) {

  lazy val fingerprint = BaseEncoding.base16.encode(MessageDigest.getInstance("SHA-1").digest(value))

  override def equals(that: Any) = that match {

    case that: PublicKey => util.Arrays.equals(this.value, that.value)

    case _ => false

  }

  override def hashCode = util.Arrays.hashCode(value)

  override def toString = s"$productPrefix(${BaseEncoding.base64.encode(value)})"

}

object PublicKey {

  implicit val PublicKeyFormat = ValueFormat[PublicKey, String](
    publicKeyBase64 => PublicKey(BaseEncoding.base64.decode(publicKeyBase64)),
    publicKey => BaseEncoding.base64.encode(publicKey.value))

}