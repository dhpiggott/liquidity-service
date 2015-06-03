package com.dhpcs.json

import play.api.libs.json.{Format, Reads, Writes}

object ValueFormat {

  def apply[V, W: Format](apply: W => V, unapply: V => W) = Format(
    Reads.of[W].map(apply),
    Writes[V](a => Writes.of[W].writes(unapply(a)))
  )

}