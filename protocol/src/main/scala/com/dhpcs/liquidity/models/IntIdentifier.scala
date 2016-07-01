package com.dhpcs.liquidity.models

import com.dhpcs.json.ValueFormat

trait IntIdentifier {
  def id: Int
}

trait IntIdentifierCompanion[A <: IntIdentifier] {
  implicit final val IntIdentifierFormat = ValueFormat[A, Int](apply, _.id)

  def apply(id: Int): A
}
