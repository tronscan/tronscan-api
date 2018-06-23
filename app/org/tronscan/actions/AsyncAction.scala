package org.tronscan.actions

import scala.concurrent.{ExecutionContext, Future}

trait AsyncAction {
  def execute(implicit executionContext: ExecutionContext): Future[_]
}
