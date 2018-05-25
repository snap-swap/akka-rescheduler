package com.snapswap.retry

import scala.concurrent.duration._


case class ExponentialBackOff(protected val minBackOff: FiniteDuration,
                              protected val maxBackOff: FiniteDuration,
                              protected val scaleFactor: Double) extends AttemptParams {
  require(minBackOff > Duration.Zero, "minBackOff must be > 0")
  require(maxBackOff >= minBackOff, "maxBackOff should be >= minBackOff")

  private var stopIncreasing: Boolean = false

  override def getNextAttemptDelay: FiniteDuration = {
    if (stopIncreasing) {
      maxBackOff
    } else {
      val nextDelayNanos: Long = math.min(
        maxBackOff.toNanos,
        (minBackOff.toNanos * math.exp(scaleFactor * getCurrentAttemptNumber)).toLong
      )

      if (maxBackOff.toNanos <= nextDelayNanos)
        stopIncreasing = true

      nextDelayNanos.nanos
    }
  }
}

case class LinearBackOff(initialBackOff: FiniteDuration) extends AttemptParams {
  override def getNextAttemptDelay: FiniteDuration =
    initialBackOff * getCurrentAttemptNumber.toLong
}

case class ConstantBackOff(getNextAttemptDelay: FiniteDuration) extends AttemptParams
