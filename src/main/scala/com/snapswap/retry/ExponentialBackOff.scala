package com.snapswap.retry

import java.util.concurrent.ThreadLocalRandom

import scala.concurrent.duration._


case class ExponentialBackOff(protected val minBackoff: FiniteDuration,
                              protected val maxBackoff: FiniteDuration,
                              protected val randomFactor: Double) extends AttemptParams {
  require(minBackoff > Duration.Zero, "minBackoff must be > 0")
  require(maxBackoff >= minBackoff, "maxBackoff must be >= minBackoff")
  require(0.0 <= randomFactor && randomFactor <= 1.0, "randomFactor must be between 0.0 and 1.0")

  override def getNextAttemptDelay: FiniteDuration = {
    val rnd = 1.0 + ThreadLocalRandom.current().nextDouble() * randomFactor
    if (getCurrentAttemptNumber >= 30) {
      // Duration overflow protection (> 100 years)
      maxBackoff
    } else {
      maxBackoff.min(minBackoff * math.pow(2, getCurrentAttemptNumber.toDouble)) * rnd match {
        case f: FiniteDuration ⇒ f
        case _ ⇒ maxBackoff
      }
    }
  }

}
