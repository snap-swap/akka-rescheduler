package com.snapswap.retry

import scala.concurrent.duration.FiniteDuration


trait AttemptParams {

  def getNextAttemptDelay: FiniteDuration

  private var currentAttemptNumber: Int = 0

  final def getCurrentAttemptNumber: Int = currentAttemptNumber

  final def tick: this.type = {
    currentAttemptNumber += 1
    this
  }
}
