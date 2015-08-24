package com.dhpcs.liquidity.models

import java.util.UUID

import com.dhpcs.json.ValueFormat

trait UUIDIdentifier {

  def id: UUID

}

abstract class UUIDIdentifierCompanion[A <: UUIDIdentifier] {

  implicit val UUIDIdentifierFormat = ValueFormat[A, UUID](apply, _.id)

  def apply(id: UUID): A

  def generate = apply(UUID.randomUUID)

}