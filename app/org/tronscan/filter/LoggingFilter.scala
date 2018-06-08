package org.tronscan.filter

import javax.inject.{Inject, Singleton}
import akka.stream.Materializer
import dispatch.host
import play.api.Logger
import play.api.mvc._
import org.tronscan.models.{RequestLogModel, RequestLogModelRepository}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class LoggingFilter @Inject() (implicit val mat: Materializer, ec: ExecutionContext, requestLogModelRepository: RequestLogModelRepository) extends Filter {

  def apply(nextFilter: RequestHeader => Future[Result])(requestHeader: RequestHeader): Future[Result] = {

    requestLogModelRepository.insertAsync(RequestLogModel(
      host = requestHeader.host,
      uri = requestHeader.uri,
      ip = requestHeader.headers.get("X-Real-IP").getOrElse(""),
      referer = requestHeader.headers.get("referer").getOrElse("")
    ))

    nextFilter(requestHeader)
  }
}
