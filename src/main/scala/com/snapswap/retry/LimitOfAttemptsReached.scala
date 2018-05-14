package com.snapswap.retry

import scala.util.control.NoStackTrace


case class LimitOfAttemptsReached(maxRetryAttempts: Int, actionName: String, currentAttemptNumber: Int) extends NoStackTrace {
  override def getMessage =
    s"After $currentAttemptNumber attempts stop send retries for [$actionName], max allowed attempts limit reached (max attempts: '$maxRetryAttempts')"
}