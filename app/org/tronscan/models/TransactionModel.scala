package org.tronscan.models

import com.google.inject.{Inject, Singleton}
import io.circe.Json
import org.joda.time.DateTime
import play.api.db.slick.DatabaseConfigProvider
import org.tronscan.db.PgProfile.api._
import org.tronscan.db.TableRepository

case class TransactionModel(
  hash: String,
  block: Long,
  timestamp: DateTime,
  confirmed: Boolean = false,
  ownerAddress: String = "",
  toAddress: String = "",
  contractData: Json = io.circe.Json.obj(),
  contractType: Int = -1,
  data: String = "")

class TransactionModelTable(tag: Tag) extends Table[TransactionModel](tag, "transactions") {
  def hash = column[String]("hash", O.PrimaryKey)
  def block = column[Long]("block")
  def timestamp = column[DateTime]("date_created")
  def confirmed = column[Boolean]("confirmed")
  def ownerAddress = column[String]("owner_address")
  def toAddress = column[String]("to_address")
  def contractData = column[io.circe.Json]("contract_data")
  def contractType = column[Int]("contract_type")
  def data = column[String]("data")
  def * = (hash, block, timestamp, confirmed, ownerAddress, toAddress, contractData, contractType, data) <> (TransactionModel.tupled, TransactionModel.unapply)
}

@Singleton()
class TransactionModelRepository @Inject() (val dbConfig: DatabaseConfigProvider) extends TableRepository[TransactionModelTable, TransactionModel] {

  lazy val table = TableQuery[TransactionModelTable]

  def findAll = run {
    table.result
  }

  def findByHash(hash: String) = run {
    table.filter(_.hash === hash).result.headOption
  }

  def update(entity: TransactionModel) = run {
    table.filter(_.hash === entity.hash).update(entity)
  }

  def deleteByNum(num: Long) = {
    table.filter(_.block === num).delete
  }
}
