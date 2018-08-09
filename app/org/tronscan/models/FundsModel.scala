package org.tronscan.models

import com.google.inject.{Inject, Singleton}
import org.tronscan.db.PgProfile.api._
import org.tronscan.db.TableRepository
import org.joda.time.DateTime
import play.api.db.slick.DatabaseConfigProvider
import play.api.libs.json.Json
import org.tronscan.App._

import scala.concurrent.{ExecutionContext, Future}


object FundsModel {
  implicit val format = Json.format[FundsModel]
}

case class FundsModel(address: String)

class FundModelTable(tag: Tag) extends Table[FundsModel](tag, "funds") {
  def address = column[String]("address")
  def * = (address) <> ((FundsModel.apply _).tupled, FundsModel.unapply)
}

@Singleton()
class FundModelRepository @Inject() (val dbConfig: DatabaseConfigProvider) extends TableRepository[FundModelTable, FundsModel] {

  lazy val table = TableQuery[FundModelTable]

  def findAll = run { implicit request =>
    table.filter(_.address === "")
  }
}

