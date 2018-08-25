package org.tronscan.test

import monix.eval.Task
import monix.execution.Scheduler

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Awaitable}

trait Awaiters {
  def awaitSync[T](awaitable: Awaitable[T]): T = Await.result(awaitable, Duration.Inf)
  def awaitSync[T](awaitable: Task[T]): T = Await.result(awaitable.runAsync(Scheduler.Implicits.global), Duration.Inf)
}
