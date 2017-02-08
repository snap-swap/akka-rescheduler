package com.snapswap.retry

import java.util.concurrent.ThreadLocalRandom

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.control.NoStackTrace


trait RetryableException extends NoStackTrace

case class ExponentialBackOff(protected val minBackoff: FiniteDuration,
                              protected val maxBackoff: FiniteDuration,
                              protected val randomFactor: Double,
                              restartCount: Int = 1) {
  require(minBackoff > Duration.Zero, "minBackoff must be > 0")
  require(maxBackoff >= minBackoff, "maxBackoff must be >= minBackoff")
  require(0.0 <= randomFactor && randomFactor <= 1.0, "randomFactor must be between 0.0 and 1.0")

  def calculateDelay: FiniteDuration = {
    val rnd = 1.0 + ThreadLocalRandom.current().nextDouble() * randomFactor
    if (restartCount >= 30) {
      // Duration overflow protection (> 100 years)
      maxBackoff
    } else {
      maxBackoff.min(minBackoff * math.pow(2, restartCount.toDouble)) * rnd match {
        case f: FiniteDuration ⇒ f
        case _ ⇒ maxBackoff
      }
    }
  }

  def nextBackOff(): ExponentialBackOff = {
    copy(restartCount = restartCount + 1)
  }
}