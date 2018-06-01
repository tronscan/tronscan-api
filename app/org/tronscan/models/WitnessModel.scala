package org.tronscan.models

import com.google.inject.{Inject, Singleton}
import org.tronscan.db.PgProfile.api._
import org.tronscan.db.TableRepository
import play.api.db.slick.DatabaseConfigProvider
import play.api.libs.json.{JsValue, Json}

import scala.concurrent.ExecutionContext

object WitnessModel {
  implicit val format = Json.format[WitnessModel]
}

case class WitnessModel(
  address: String,
  url: String)

class WitnessModelTable(tag: Tag) extends Table[WitnessModel](tag, "witness_create_contract") {
  def address = column[String]("address", O.PrimaryKey)
  def url = column[String]("url")
  def * = (address, url) <> ((WitnessModel.apply _).tupled, WitnessModel.unapply)
}

@Singleton()
class WitnessModelRepository @Inject() (val dbConfig: DatabaseConfigProvider) extends TableRepository[WitnessModelTable, WitnessModel] {

  lazy val table = TableQuery[WitnessModelTable]

  def findAll = run {
    table.result
  }

  def findByAddress(address: String) = run {
    table.filter(_.address === address).result.headOption
  }

  def update(witness: WitnessModel) = run {
    table.filter(_.address === witness.address).update(witness)
  }

  def buildUpdate(witness: WitnessModel) = {
    table.filter(_.address === witness.address).update(witness)
  }

  def findTransactionsByWitness()(implicit executionContext: ExecutionContext) = run {
    sql"""
      SELECT
        b.witness_address,
        COUNT(t.hash) as trx
      FROM
        blocks b
      LEFT JOIN
        transactions t on b.id = t.block
      GROUP BY
        b.witness_address
    """.as[(String, Long)]
  }.map(_.toMap)

}