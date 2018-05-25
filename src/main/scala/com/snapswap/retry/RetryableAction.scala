package com.snapswap.retry

import akka.Done
import akka.actor.{ActorSystem, Cancellable}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal


class RetryableAction(action: => Future[Unit],
                      actionName: String,
                      attemptParams: AttemptParams,
                      maxRetryAttempts: Int,
                      log: Logger)
                     (whenRetryAction: (String, RetryableException, Int) => Future[Unit],
                      whenFatalAction: (String, Throwable, Int) => Future[Unit],
                      whenSuccessAction: (String, Int) => Future[Unit])
                     (implicit system: ActorSystem) extends Cancellable {

  import system.dispatcher

  private lazy val scheduler = system.scheduler

  private var canceled: Boolean = false
  private var launched: Boolean = false
  private var result: Either[Throwable, Option[Done]] = Right(None)
  private var scheduled: Option[Cancellable] = None

  def run(): Future[Unit] = if (launched)
    Future.failed(new RuntimeException(s"The same action can't be launched twice"))
  else
    Future(launched = true).map(_ => doWithRetry(attemptParams))

  def isCancelled: Boolean =
    scheduled.map(_.isCancelled).getOrElse(canceled)

  def cancel(): Boolean = {
    launched = true
    canceled = true
    log.debug(s"action [$actionName] cancelled")
    scheduled.map(_.cancel()).getOrElse(canceled)
  }

  def getStatus: Either[Throwable, Option[Done]] = result

  private def doWithRetry(state: AttemptParams): Future[Unit] = {
    if (canceled) {
      Future.successful(())
    } else {
      action.flatMap { _ =>
        result = Right(Some(Done))
        whenSuccessAction(actionName, state.getCurrentAttemptNumber)
      }.recoverWith {
        case ex: RetryableException =>
          processRetry(ex, state.tick, state.getNextAttemptDelay)
      }.recoverWith {
        case NonFatal(ex) =>
          result = Left(ex)
          log.error(ex, s"Recovery for action [$actionName] isn't possible")
          whenFatalAction(actionName, ex, state.getCurrentAttemptNumber)
      }
    }
  }

  private def processRetry(ex: RetryableException, state: AttemptParams, delay: FiniteDuration): Future[Unit] = {
    if (state.getCurrentAttemptNumber > maxRetryAttempts) {
      Future.failed(LimitOfAttemptsReached(maxRetryAttempts, actionName, state.getCurrentAttemptNumber))
    } else {
      log.debug(s"Trying to perform retry action for [$actionName], attempt ${state.getCurrentAttemptNumber}")
      whenRetryAction(actionName, ex, state.getCurrentAttemptNumber).recover {
        case retryEx =>
          log.debug(s"Retry action for [$actionName] at attempt ${state.getCurrentAttemptNumber} wasn't successful, $retryEx")
      }.map { _ =>
        log.debug(s"Action [$actionName] after retrying attempt ${state.getCurrentAttemptNumber} will be executed after $delay")
        scheduled = Some(scheduler.scheduleOnce(delay)(doWithRetry(state)))
      }
    }
  }
}


object RetryableAction {
  def apply(action: => Future[Unit],
            actionName: String,
            attemptParams: AttemptParams,
            maxAttempts: Int,
            log: Logger)
           (whenRetryAction: (String, RetryableException, Int) => Future[Unit] = (_, _, _) => Future.successful(()),
            whenFatalAction: (String, Throwable, Int) => Future[Unit] = (_, _, _) => Future.successful(()),
            whenSuccessAction: (String, Int) => Future[Unit] = (_, _) => Future.successful(()))
           (implicit system: ActorSystem, ctx: ExecutionContext): RetryableAction =
    new RetryableAction(
      action, actionName, attemptParams, maxAttempts, log
    )(
      whenRetryAction, whenFatalAction, whenSuccessAction
    )
}