package me.fleey.ppocrv5.call

data class JdScreenPoint(val x: Float, val y: Float)

object JdFallbackTapPolicy {
  private val more = JdScreenPoint(x = 916f, y = 475f)
  private val claimAll = JdScreenPoint(x = 500f, y = 574f)

  fun pointFor(target: JdTarget): JdScreenPoint? = when (target) {
    JdTarget.MY -> null
    JdTarget.MORE -> more
    JdTarget.CLAIM_ALL -> claimAll
  }
}
