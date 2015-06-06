package com.dhpcs.json

import play.api.libs.json.{Format, Writes, _}

object ValueFormat {

  def apply[V, W: Format](apply: W => V, unapply: V => W) = Format(
    __.read[W].map(apply),
    Writes[V](a => Writes.of[W].writes(unapply(a)))
  )

}