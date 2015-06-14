package com.dhpcs.json

import play.api.libs.json.{Format, Writes, _}

object ValueFormat {

  def apply[A, B: Format](apply: B => A, unapply: A => B) = Format(
    __.read[B].map(apply),
    Writes[A](a => Writes.of[B].writes(unapply(a)))
  )

}