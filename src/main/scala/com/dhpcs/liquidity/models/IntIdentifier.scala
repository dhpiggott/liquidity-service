package com.dhpcs.liquidity.models

import com.dhpcs.json.ValueFormat

trait IntIdentifier {

  def id: Int

}

abstract class IntIdentifierCompanion[A <: IntIdentifier] {

  implicit val LongIdentifierFormat = ValueFormat[A, Int](apply, _.id)

  def apply(id: Int): A

}