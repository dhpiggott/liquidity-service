package com.dhpcs.liquidity.models

import java.util

import com.dhpcs.json.ValueFormat
import okio.ByteString

case class PublicKey(value: Array[Byte]) {

  lazy val fingerprint = ByteString.of(value: _*).sha256.hex

  override def equals(that: Any) = that match {

    case that: PublicKey => util.Arrays.equals(this.value, that.value)

    case _ => false

  }

  override def hashCode = util.Arrays.hashCode(value)

  override def toString = s"$productPrefix(${ByteString.of(value: _*).base64})"

}

object PublicKey {

  implicit val PublicKeyFormat = ValueFormat[PublicKey, String](
    publicKeyBase64 => PublicKey(ByteString.decodeBase64(publicKeyBase64).toByteArray),
    publicKey => ByteString.of(publicKey.value: _*).base64)

}