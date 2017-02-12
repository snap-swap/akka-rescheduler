package com.snapswap.retry

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.TestProbe
import org.scalatest.{Matchers, WordSpec}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.{FiniteDuration, _}
import scala.util.control.NoStackTrace


class RetrySpec extends WordSpec with Matchers {

  implicit val system = ActorSystem.create("test")

  "retry" should {
    "retry while retryable exception is occurred" in {
      val listener = TestProbe()
      val retryAttempts = 2
      val sender = new Sender(listener.ref, Retry(retryAttempts))

      // after all retries there was last successful attempt
      val expectedSenderAttempts = retryAttempts + 1

      action(sender.send(), 10)
      listener.expectMsg(maxBackOff * retryAttempts.toLong, CallBackSuccessfulMessage(expectedSenderAttempts))
      sender.getAttemptsCounter shouldBe expectedSenderAttempts
    }
    "success without retry if there is no any exceptions" in {
      val listener = TestProbe()
      val retryAttempts = 0
      val sender = new Sender(listener.ref, Retry(retryAttempts))

      action(sender.send(), 10)

      // there are no any retries, only one successful attempt
      val expectedSenderAttempts = retryAttempts + 1

      listener.expectMsg(maxBackOff, CallBackSuccessfulMessage(expectedSenderAttempts))
      sender.getAttemptsCounter shouldBe expectedSenderAttempts
    }
    "stop retry if fatal exception is occurred" in {
      val listener = TestProbe()
      val retryAttempts = 2
      val sender = new Sender(listener.ref, Fatal(retryAttempts))

      val expectedSenderAttempts = retryAttempts + 1 // after all retries there was last fatal attempt

      action(sender.send(), 10)

      listener.expectNoMsg(maxBackOff * retryAttempts.toLong)
      sender.getAttemptsCounter shouldBe expectedSenderAttempts
    }
    "stop retry if max retry attempt limit is reached" in {
      val listener = TestProbe()
      val maxRetryAttempts = 2
      val sender = new Sender(listener.ref, Retry(10))

      action(sender.send(), maxRetryAttempts)

      listener.expectNoMsg(maxBackOff * maxRetryAttempts.toLong)
      sender.getAttemptsCounter shouldBe maxRetryAttempts + 1 // after all retries there was last out of limit attempt
    }

    "perform retry action every retry attempt" in {
      val callBack = TestProbe()
      val sender = new Sender(TestProbe().ref, Retry(10))

      action(sender.send(), 2, callBack.ref)

      callBack.expectMsgAllOf(maxBackOff * 2.toLong, CallBackRetryMessage(1), CallBackRetryMessage(2))
    }
    "perform failed action if fatal attempt will occurred" in {
      val callBack = TestProbe()
      val retryAttempts = 0
      val sender = new Sender(TestProbe().ref, Fatal(retryAttempts))

      action(sender.send(), 10, callBack.ref)

      callBack.expectMsg(maxBackOff, CallBackFatalMessage(retryAttempts))
    }
    "perform success action when succeed" in {
      val callBack = TestProbe()
      val retryAttempts = 0
      val sender = new Sender(TestProbe().ref, Retry(retryAttempts))

      action(sender.send(), 10, callBack.ref)

      callBack.expectMsg(maxBackOff, CallBackSuccessfulMessage(retryAttempts))
    }
  }


  // Errors thrown by action executor
  case class RetryableError() extends RetryableException

  case class FatalError(failedAtAttemptNo: Int) extends NoStackTrace


  // Classes for action executor behaviour handling
  trait ActionHandle

  case class Retry(retryAttempts: Int) extends ActionHandle

  case class Fatal(retryAttempts: Int) extends ActionHandle


  // Messages for test actors
  case class ListenerMessage(successfulAtAttemptNo: Int)

  case class CallBackSuccessfulMessage(attemptNo: Int)

  case class CallBackRetryMessage(attemptNo: Int)

  case class CallBackFatalMessage(attemptNo: Int)


  // Action executor, it will be passed into retry executor
  class Sender(listener: ActorRef, handle: ActionHandle) {
    private var attemptsCounter: Int = 0

    def getAttemptsCounter = attemptsCounter

    def send(): Future[Unit] = {
      attemptsCounter = attemptsCounter + 1

      handle match {
        case Retry(retryAttempts) if attemptsCounter <= retryAttempts =>
          throwRetryable()
        case Fatal(retryAttempts) =>
          if (attemptsCounter <= retryAttempts) {
            throwRetryable()
          } else {
            println(s"failed at attempt number $attemptsCounter")
            Future.failed(FatalError(attemptsCounter))
          }
        case _ =>
          Future.successful(println(s"successful at attempt number $attemptsCounter")).map(_ =>
            listener ! CallBackSuccessfulMessage(attemptsCounter)
          )
      }
    }

    private def throwRetryable() = {
      println(s"retry at attempt number $attemptsCounter")
      Future.failed(RetryableError())
    }
  }


  // Retry executor
  val maxBackOff = FiniteDuration(500, MILLISECONDS)

  def action(action: => Future[Unit], maxAttempts: Int, callBack: ActorRef = TestProbe().ref) = {
    val whenRetry: (String, RetryableException, Int) => Future[Unit] =
      (_: String, _: RetryableException, attemptNumber: Int) =>
        Future(callBack ! CallBackRetryMessage(attemptNumber))
    val whenFatal: (String, Throwable, Int) => Future[Unit] =
      (_: String, _: Throwable, attemptNumber: Int) =>
        Future(callBack ! CallBackFatalMessage(attemptNumber))
    val whenSuccess: (String, Int) => Future[Unit] =
      (_: String, attemptNumber: Int) =>
        Future(callBack ! CallBackSuccessfulMessage(attemptNumber))

    val params = ExponentialBackOff(FiniteDuration(1, MILLISECONDS), maxBackOff, 0.1)

    RetryableAction(action, "", params, maxAttempts)(
      whenRetryAction = whenRetry,
      whenFatalAction = whenFatal,
      whenSuccessAction = whenSuccess
    )
  }


}
