package com.dhpcs.json

import play.api.libs.json.{Format, Writes, _}

object ValueFormat {

  def apply[A, B: Format](apply: B => A, unapply: A => B): Format[A] = Format(
    Reads(
      json => Reads.of[B].reads(json).map(apply(_))
    ),
    Writes(
      a => Writes.of[B].writes(unapply(a))
    )
  )

}
