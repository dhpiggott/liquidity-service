package com.dhpcs.json

import play.api.libs.json.{Format, Reads, Writes}

object ValueFormat {

  def valueFormat[A, B: Format](apply: B => A)(unapply: A => B): Format[A] = Format(
    Reads.of[B].map(apply), Writes(a => Writes.of[B].writes(unapply(a))))

}