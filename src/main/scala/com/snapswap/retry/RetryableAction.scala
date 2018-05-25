package com.snapswap.retry

import akka.actor.{ActorSystem, Cancellable}
import akka.event.Logging

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal


class RetryableAction(action: => Future[Unit],
                      actionName: String,
                      attemptParams: AttemptParams,
                      maxRetryAttempts: Int)
                     (whenRetryAction: (String, RetryableException, Int) => Future[Unit],
                      whenFatalAction: (String, Throwable, Int) => Future[Unit],
                      whenSuccessAction: (String, Int) => Future[Unit])
                     (implicit system: ActorSystem, ctx: ExecutionContext) {

  private lazy val log = Logging(system, this.getClass)
  private lazy val scheduler = system.scheduler

  private var canceled: Boolean = false
  private var scheduled: Option[Cancellable] = None

  def run(): Future[Unit] =
    doWithRetry(attemptParams)

  def cancel(): Boolean = {
    canceled = true
    log.info(s"action [$actionName] cancelled")
    scheduled.map(_.cancel()).getOrElse(canceled)
  }

  private def doWithRetry(state: AttemptParams): Future[Unit] = {
    if (canceled) {
      Future.successful(())
    } else {
      action.flatMap { _ =>
        whenSuccessAction(actionName, state.getCurrentAttemptNumber)
      }.recoverWith {
        case ex: RetryableException =>
          processRetry(ex, state.tick, state.getNextAttemptDelay)
      }.recoverWith {
        case NonFatal(ex) =>
          log.error(ex, s"Recovery for action [$actionName] isn't possible")
          whenFatalAction(actionName, ex, state.getCurrentAttemptNumber)
      }
    }
  }

  private def processRetry(ex: RetryableException, state: AttemptParams, delay: FiniteDuration): Future[Unit] = {
    if (state.getCurrentAttemptNumber > maxRetryAttempts) {
      Future.failed(LimitOfAttemptsReached(maxRetryAttempts, actionName, state.getCurrentAttemptNumber))
    } else {
      log.info(s"Trying to perform retry action for [$actionName], attempt ${state.getCurrentAttemptNumber}")
      whenRetryAction(actionName, ex, state.getCurrentAttemptNumber).recover {
        case retryEx =>
          log.info(s"Retry action for [$actionName] at attempt ${state.getCurrentAttemptNumber} wasn't successful, $retryEx")
      }.map { _ =>
        log.info(s"Action [$actionName] after retrying attempt ${state.getCurrentAttemptNumber} will be executed after $delay")
        scheduled = Some(scheduler.scheduleOnce(delay)(doWithRetry(state)))
      }
    }
  }
}


object RetryableAction {
  def apply(action: => Future[Unit],
            actionName: String,
            attemptParams: AttemptParams,
            maxAttempts: Int)
           (whenRetryAction: (String, RetryableException, Int) => Future[Unit] = (_, _, _) => Future.successful(()),
            whenFatalAction: (String, Throwable, Int) => Future[Unit] = (_, _, _) => Future.successful(()),
            whenSuccessAction: (String, Int) => Future[Unit] = (_, _) => Future.successful(()))
           (implicit system: ActorSystem, ctx: ExecutionContext): RetryableAction =
    new RetryableAction(
      action, actionName, attemptParams, maxAttempts
    )(
      whenRetryAction, whenFatalAction, whenSuccessAction
    )
}