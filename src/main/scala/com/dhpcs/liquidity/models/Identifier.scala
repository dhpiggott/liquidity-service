package com.dhpcs.liquidity.models

import java.util.UUID

import com.dhpcs.json.ValueFormat

trait Identifier {

  def id: UUID

}

abstract class IdentifierCompanion[A <: Identifier] {

  implicit val IdentifierFormat = ValueFormat[A, UUID](apply, _.id)

  def apply(id: UUID): A

  def generate = apply(UUID.randomUUID)

}