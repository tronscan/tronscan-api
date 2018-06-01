package org.tronscan.models

import com.google.inject.{Inject, Singleton}
import io.circe.Json
import org.tronscan.db.PgProfile.api._
import org.tronscan.db.TableRepository
import org.joda.time.DateTime
import play.api.db.slick.DatabaseConfigProvider
import org.tronscan.App._

object AccountModel {
//  implicit val format = Json.format[AccountModel]
}

case class AccountModel(
  address: String,
  name: String,
  balance: Long,
  power: Long,
  tokenBalances: Json = Json.obj(),
  dateCreated: DateTime = DateTime.now,
  dateUpdated: DateTime = DateTime.now)

class AccountModelTable(tag: Tag) extends Table[AccountModel](tag, "accounts") {
  def address = column[String]("address", O.PrimaryKey)
  def name = column[String]("name")
  def balance = column[Long]("balance")
  def power = column[Long]("power")
  def tokenBalances = column[Json]("token_balances")
  def dateCreated = column[DateTime]("date_created")
  def dateUpdated = column[DateTime]("date_updated")
  def * = (address, name, balance, power, tokenBalances, dateCreated, dateUpdated) <> ((AccountModel.apply _).tupled, AccountModel.unapply)
}

@Singleton()
class AccountModelRepository @Inject() (val dbConfig: DatabaseConfigProvider) extends TableRepository[AccountModelTable, AccountModel] {

  lazy val table = TableQuery[AccountModelTable]

  def findAll = run {
    table.result
  }

  def findByAddress(address: String) = run {
    table.filter(_.address === address).result.headOption
  }

  def findByAddresses(addresses: Seq[String]) = run {
    table.filter(_.address.inSet(addresses)).result
  }

  def insertOrUpdate(accountModel: AccountModel) = run {
    table.insertOrUpdate(accountModel)
  }

  def getBetweenBalance(startBalance: Long, endBalance: Long) = run {
    sql"""
      SELECT
        COUNT(address) as total,
        SUM(balance) as totalBalance
      FROM accounts
      WHERE balance >= $startBalance AND balance < $endBalance
    """.as[(Long, Long)].head
  }

  def getTotals() = run {
    sql"""
      SELECT
        COUNT(address) as total,
        SUM(balance) as totalBalance
      FROM accounts
      WHERE balance > 0
    """.as[(Long, Long)].head
  }

}