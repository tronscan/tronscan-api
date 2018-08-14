package org.tronscan.models

import java.util.UUID

import com.google.inject.{Inject, Singleton}
import org.joda.time.DateTime
import play.api.db.slick.DatabaseConfigProvider
import org.tronscan.db.PgProfile.api._
import org.tronscan.db.TableRepository

case class RequestLogModel(
  id: UUID = UUID.randomUUID(),
  timestamp: DateTime = DateTime.now,
  referer: String,
  host: String,
  uri: String,
  ip: String)

class RequestLogModelTable(tag: Tag) extends Table[RequestLogModel](tag, Some("analytics"), "requests") {
  def id = column[UUID]("id")
  def timestamp = column[DateTime]("timestamp")
  def referer = column[String]("referer")
  def host = column[String]("host")
  def uri = column[String]("uri")
  def ip = column[String]("ip")
  def * = (id, timestamp, referer, host, uri, ip) <> (RequestLogModel.tupled, RequestLogModel.unapply)
}

@Singleton()
class RequestLogModelRepository @Inject() (val dbConfig: DatabaseConfigProvider) extends TableRepository[RequestLogModelTable, RequestLogModel] {

  lazy val table = TableQuery[RequestLogModelTable]


}