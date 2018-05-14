package com.snapswap.retry

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{TestKit, TestProbe}
import org.scalatest.{Matchers, WordSpecLike}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NoStackTrace


class RetrySpec extends TestKit(ActorSystem.create("RetrySpec")) with WordSpecLike with Matchers {

  val maxBackOff = 500.millis

  "Retryable action" should {
    "retry while retryable exception is occurred" in {
      val listener = TestProbe()
      val sender = new Sender(listener.ref, 2, 100500)
      // after all retries should be successful execution
      val expectedExecutions = sender.numberOfRetries + 1

      action(sender.send(), 10)
      listener.expectMsg(maxBackOff * sender.numberOfRetries, CallBackSuccessfulMessage(expectedExecutions))
      sender.getAttemptsCounter shouldBe expectedExecutions
    }
    "success without retry if there were no any exceptions" in {
      val listener = TestProbe()
      val sender = new Sender(listener.ref, 0, 100500)
      // there are no any retries, only one successful attempt
      val expectedSenderAttempts = sender.numberOfRetries + 1

      action(sender.send(), 10)
      listener.expectMsg(maxBackOff, CallBackSuccessfulMessage(expectedSenderAttempts))
      sender.getAttemptsCounter shouldBe expectedSenderAttempts
    }
    "stop retry if fatal exception was occurred" in {
      val listener = TestProbe()
      val sender = new Sender(listener.ref, 100500, 2)
      // after all retries should be last fatal execution
      val expectedSenderAttempts = sender.fatalExceptionAfterRetries + 1

      action(sender.send(), 10)
      listener.expectNoMsg(maxBackOff * sender.fatalExceptionAfterRetries)
      sender.getAttemptsCounter shouldBe expectedSenderAttempts
    }
    "stop retry if max retry attempt limit was reached" in {
      val listener = TestProbe()
      val maxRetryAttempts = 2
      val sender = new Sender(listener.ref, 100500, 100500)

      action(sender.send(), maxRetryAttempts)
      listener.expectNoMsg(maxBackOff * maxRetryAttempts.toLong)
      sender.getAttemptsCounter shouldBe maxRetryAttempts + 1 // after all retries should be last out of limit attempt
    }
    "perform retry action every retry attempt" in {
      val callBack = TestProbe()
      val sender = new Sender(TestProbe().ref, 100500, 100500)

      action(sender.send(), 2, callBack.ref)
      callBack.expectMsgAllOf(maxBackOff * 2.toLong, CallBackRetryMessage(1), CallBackRetryMessage(2))
    }
    "perform failed action if fatal exception was occurred" in {
      val callBack = TestProbe()
      val sender = new Sender(TestProbe().ref, 100500, 0)

      action(sender.send(), 10, callBack.ref)
      callBack.expectMsg(maxBackOff, CallBackFatalMessage(sender.fatalExceptionAfterRetries))
    }
    "perform success action when succeed" in {
      val callBack = TestProbe()
      val sender = new Sender(TestProbe().ref, 0, 100500)

      action(sender.send(), 10, callBack.ref)
      callBack.expectMsg(maxBackOff, CallBackSuccessfulMessage(sender.numberOfRetries))
    }
  }


  // Errors thrown by action executor
  case class RetryableError() extends RetryableException

  case class FatalError(failedAtAttemptNo: Int) extends NoStackTrace


  // Messages for test actors
  case class ListenerMessage(successfulAtAttemptNo: Int)

  case class CallBackSuccessfulMessage(attemptNo: Int)

  case class CallBackRetryMessage(attemptNo: Int)

  case class CallBackFatalMessage(attemptNo: Int)


  // Action executor, it will be passed into retry executor
  class Sender(listener: ActorRef, val numberOfRetries: Int, val fatalExceptionAfterRetries: Int) {
    private var executionsCounter: Int = 0

    def getAttemptsCounter: Int = executionsCounter

    def send(): Future[Unit] = {
      executionsCounter = executionsCounter + 1

      if (executionsCounter <= numberOfRetries && executionsCounter <= fatalExceptionAfterRetries) {
        println(s"retry at attempt number $executionsCounter")
        Future.failed(RetryableError())
      } else if (executionsCounter > fatalExceptionAfterRetries) {
        println(s"failed at execution number $executionsCounter")
        Future.failed(FatalError(executionsCounter))
      } else {
        println(s"successful at execution number $executionsCounter")
        Future(listener ! CallBackSuccessfulMessage(executionsCounter))
      }

    }

  }


  def action(action: => Future[Unit], maxAttempts: Int, callBack: ActorRef = TestProbe().ref): Future[Unit] = {
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
