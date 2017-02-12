package com.snapswap.retry

import akka.actor.ActorSystem
import akka.event.Logging

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

  def doIt(): Future[Unit] =
    doWithRetry(None)

  private def doWithRetry(state: Option[AttemptParams]): Future[Unit] = {
    lazy val currentAttemptNumber: Int = state.map(_.currentAttemptNumber).getOrElse(0)

    action.flatMap { _ =>
      whenSuccessAction(actionName, currentAttemptNumber)
    }.recover {
      case ex: RetryableException =>
        val currentState = state.getOrElse(attemptParams)
        whenRetryAction(actionName, ex, currentState.currentAttemptNumber)
        processRetry(currentState)
      case NonFatal(ex) =>
        whenFatalAction(actionName, ex, currentAttemptNumber)
    }
  }

  private def processRetry(state: AttemptParams): Unit = {
    if (state.currentAttemptNumber > maxRetryAttempts) {
      log.error(s"After '$maxRetryAttempts' attempts stop send retry for [$actionName] at attempt number ${state.currentAttemptNumber}")
    } else {
      val next = state.nextAttemptParams

      log.info(s"Scheduling resend event at attempt number ${state.currentAttemptNumber} for [$actionName] after '${next.nextAttemptDelay.toSeconds}' seconds")
      scheduler.scheduleOnce(next.nextAttemptDelay) {
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
            attemptParams: AttemptParams,
            maxAttempts: Int)
           (whenRetryAction: (String, RetryableException, Int) => Future[Unit] = whenRetry,
            whenFatalAction: (String, Throwable, Int) => Future[Unit] = whenFatal,
            whenSuccessAction: (String, Int) => Future[Unit] = whenSuccess)
           (implicit system: ActorSystem, ctx: ExecutionContext): Future[Unit] =
    new RetryableAction(
      action, actionName, attemptParams, maxAttempts
    )(
      whenRetryAction, whenFatalAction, whenSuccessAction
    ).doIt()
}