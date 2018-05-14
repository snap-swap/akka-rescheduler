package com.snapswap.retry

import scala.concurrent.duration.FiniteDuration


trait AttemptParams {
  def nextAttemptDelay: FiniteDuration

  def getNextAttemptParams: AttemptParams

  def currentAttemptNumber: Int
}
