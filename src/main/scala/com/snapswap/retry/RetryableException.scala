package com.snapswap.retry

import scala.util.control.NoStackTrace

trait RetryableException extends NoStackTrace

case class LimitOfAttemptsReached(maxRetryAttempts: Int, actionName: String, currentAttemptNumber: Int) extends NoStackTrace {
  override def getMessage =
    s"After '$maxRetryAttempts' attempts stop send retry for [$actionName] at attempt number $currentAttemptNumber"
}