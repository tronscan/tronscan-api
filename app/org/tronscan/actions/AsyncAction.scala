package org.tronscan.actions

import scala.concurrent.{ExecutionContext, Future}

/**
  * Standard Async Action
  */
trait AsyncAction {
  def execute(implicit executionContext: ExecutionContext): Future[_]
}
