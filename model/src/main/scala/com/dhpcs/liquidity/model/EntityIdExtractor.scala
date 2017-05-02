package com.dhpcs.liquidity.model

trait EntityIdExtractor[E, I] {
  def extractId(entity: E): I
}

object EntityIdExtractor {
  def instance[E, I](apply: E => I): EntityIdExtractor[E, I] = (e: E) => apply(e)
}
