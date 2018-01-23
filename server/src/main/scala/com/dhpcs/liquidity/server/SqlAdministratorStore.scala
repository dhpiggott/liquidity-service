package com.dhpcs.liquidity.server

import com.dhpcs.liquidity.model._
import doobie._
import doobie.implicits._
import okio.ByteString

object SqlAdministratorStore {

  implicit val PublicKeyMeta: Meta[PublicKey] =
    Meta[Array[Byte]]
      .xmap(bytes => PublicKey(ByteString.of(bytes: _*)), _.value.toByteArray)

  object AdministratorsStore {

    def exists(publicKey: PublicKey): ConnectionIO[Boolean] =
      sql"""
           SELECT 1
             FROM administrators
             WHERE public_key = $publicKey
         """
        .query[Int]
        .option
        .map(_.isDefined)

  }
}
