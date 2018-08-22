package org.tronscan.utils

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import akka.pattern.after
import akka.actor.Scheduler

object FutureUtils {

  def retry[T](minBackoff: FiniteDuration, maxBackoff: FiniteDuration, randomFactor: Double)(f: () => Future[T])(implicit ec: ExecutionContext, s: Scheduler): Future[T] = {
    f() recoverWith {
      case _ if minBackoff < maxBackoff =>
        val nextBackof = minBackoff.toMillis * (1 + randomFactor)
        after(nextBackof.milliseconds, s)(retry(nextBackof.milliseconds, maxBackoff, randomFactor)(f))
    }
  }

//  def retry[T](minBackoff: FiniteDuration, maxBackoff: FiniteDuration, randomFactor: Double)(f: () => T)(implicit ec: ExecutionContext, s: Scheduler): Future[T] = {
//    try {
//      Future.successful(f())
//    } catch {
//      case e: Exception  if minBackoff > maxBackoff =>
//        val nextBackof = minBackoff.toMillis * randomFactor
//        after(nextBackof.milliseconds, s)(retry(nextBackof.milliseconds, maxBackoff, randomFactor)(f))
//    }
//  }
}
