package com.snapswap.retry

import akka.actor.ActorSystem


trait Logger {
  def error(ex: Throwable, message: String): Unit

  def warn(message: String): Unit

  def info(message: String): Unit

  def debug(message: String): Unit
}

object Logger {
  def fromLoggingAdapter(implicit system: ActorSystem): Logger = new Logger {
    override def error(ex: Throwable, message: String): Unit = system.log.error(ex, message)

    override def warn(message: String): Unit = system.log.warning(message)

    override def info(message: String): Unit = system.log.info(message)

    override def debug(message: String): Unit = system.log.debug(message)
  }
}
