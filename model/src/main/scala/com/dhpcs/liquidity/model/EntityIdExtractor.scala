package com.dhpcs.liquidity.model

trait EntityIdExtractor[E, I] {
  def extractId(entity: E): I
}

object EntityIdExtractor {
  def instance[E, I](apply: E => I): EntityIdExtractor[E, I] = new EntityIdExtractor[E, I] {
    override def extractId(e: E): I = apply(e)
  }
}
