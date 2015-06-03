package com.dhpcs.liquidity.models

import java.util.UUID

import com.dhpcs.json.ValueFormat

trait Identifier {

  def id: UUID

}

abstract class IdentifierCompanion[T <: Identifier] {

  implicit val IdentifierFormat = ValueFormat[T, UUID](apply, _.id)

  def apply(id: UUID): T

  def generate = apply(UUID.randomUUID)

}