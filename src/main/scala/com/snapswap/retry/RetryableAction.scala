package com.snapswap.retry

import akka.actor.ActorSystem
import akka.event.Logging

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal


class RetryableAction(action: => Future[Unit],
                      actionName: String,
                      minBackoff: FiniteDuration,
                      maxBackoff: FiniteDuration,
                      maxAttempts: Int,
                      randomFactor: Double)
                     (whenRetryAction: (String, RetryableException, Int) => Future[Unit],
                      whenFatalAction: (String, Throwable, Int) => Future[Unit],
                      whenSuccessAction: (String, Int) => Future[Unit])
                     (implicit system: ActorSystem, ctx: ExecutionContext) {

  private lazy val log = Logging(system, this.getClass)
  private lazy val scheduler = system.scheduler

  def doIt(): Future[Unit] =
    doWithRetry(None)

  private def doWithRetry(state: Option[ExponentialBackOff]): Future[Unit] = {
    lazy val currentAttemptNumber: Int = state.map(_.restartCount).getOrElse(0)

    action.flatMap { _ =>
      whenSuccessAction(actionName, currentAttemptNumber)
    }.recover {
      case ex: RetryableException =>
        val currentState = state.getOrElse(ExponentialBackOff(minBackoff, maxBackoff, randomFactor))
        whenRetryAction(actionName, ex, currentState.restartCount)
        processRetry(currentState)
      case NonFatal(ex) =>
        whenFatalAction(actionName, ex, currentAttemptNumber)
    }
  }

  private def processRetry(state: ExponentialBackOff): Unit = {
    if (state.restartCount > maxAttempts) {
      log.error(s"After '$maxAttempts' attempts stop send retry for [$actionName] at attempt number ${state.restartCount}")
    } else {
      val next = state.nextBackOff()

      log.info(s"Scheduling resend event at attempt number ${state.restartCount} for [$actionName] after '${next.calculateDelay.toSeconds}' seconds")
      scheduler.scheduleOnce(next.calculateDelay) {
        doWithRetry(Some(next))
      }
    }
  }
}


object RetryableAction {
  private val whenRetry: (String, RetryableException, Int) => Future[Unit] =
    (actionName: String, retryableException: RetryableException, retryAttemptNumber: Int) => Future.successful(())

  private val whenFatal: (String, Throwable, Int) => Future[Unit] =
    (actionName: String, fatalException: Throwable, retryAttemptNumber: Int) => Future.successful(())

  private val whenSuccess: (String, Int) => Future[Unit] =
    (actionName: String, retryAttemptNumber: Int) => Future.successful(())

  def apply(action: => Future[Unit],
            actionName: String,
            minBackoff: FiniteDuration,
            maxBackoff: FiniteDuration,
            maxAttempts: Int,
            randomFactor: Double)
           (whenRetryAction: (String, RetryableException, Int) => Future[Unit] = whenRetry,
            whenFatalAction: (String, Throwable, Int) => Future[Unit] = whenFatal,
            whenSuccessAction: (String, Int) => Future[Unit] = whenSuccess)
           (implicit system: ActorSystem, ctx: ExecutionContext): Future[Unit] =
    new RetryableAction(
      action, actionName, minBackoff, maxBackoff, maxAttempts, randomFactor
    )(
      whenRetryAction, whenFatalAction, whenSuccessAction
    ).doIt()
}