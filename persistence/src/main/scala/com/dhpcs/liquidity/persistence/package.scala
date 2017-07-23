package com.dhpcs.liquidity

import com.dhpcs.liquidity.model._

import scala.util.matching.Regex

package object persistence {

  final val ZoneIdStringPattern: Regex =
    """zone-([0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})""".r

  implicit class RichZoneId(val zoneId: ZoneId) extends AnyVal {
    def persistenceId: String = s"zone-${zoneId.id}"
  }
}
