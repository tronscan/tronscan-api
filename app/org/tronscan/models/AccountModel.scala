package org.tronscan.models

import com.google.inject.{Inject, Singleton}
import io.circe.Json
import org.joda.time.DateTime
import org.tronscan.db.PgProfile.api._
import org.tronscan.db.TableRepository
import play.api.db.slick.DatabaseConfigProvider

import scala.concurrent.ExecutionContext

case class AccountModel(
  address: String,
  name: String,
  balance: Long,
  power: Long,
  tokenBalances: Json = Json.obj(),
  dateCreated: DateTime = DateTime.now,
  dateUpdated: DateTime = DateTime.now,
  dateSynced: DateTime = DateTime.now)

class AccountModelTable(tag: Tag) extends Table[AccountModel](tag, "accounts") {
  def address = column[String]("address", O.PrimaryKey)
  def name = column[String]("name")
  def balance = column[Long]("balance")
  def power = column[Long]("power")
  def tokenBalances = column[Json]("token_balances")
  def dateCreated = column[DateTime]("date_created")
  def dateUpdated = column[DateTime]("date_updated")
  def dateSynced = column[DateTime]("date_synced")
  def * = (address, name, balance, power, tokenBalances, dateCreated, dateUpdated, dateSynced) <> (AccountModel.tupled, AccountModel.unapply)
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

  /**
    * Find addresses which need sync
    */
  def findAddressesWhichNeedSync() = run {
    table.filter(address => address.dateUpdated > address.dateSynced).take(100).result
  }

  def insertOrUpdate(accountModel: AccountModel) = run {
    table.insertOrUpdate(accountModel)
  }

  /**
    * Marks the address as dirty
    *
    * @param address the address to mark dirty
    * @return returns true if the address exists and has been marked dirty, returns false if there wasn't an existing record
    */
  def markDirty(address: String)(implicit executionContext: ExecutionContext) = run {
    table.filter(_.address === address).map(_.dateUpdated).update(DateTime.now)
  }.map(_ >= 1)

  /**
    * Marks the address as dirty
    *;
    * @param address the address to mark dirty
    * @return returns true if the address exists and has been marked dirty, returns false if there wasn't an existing record
    */
  def buildDirty(address: String)(implicit executionContext: ExecutionContext) = {
    sql"""
      INSERT INTO
        accounts ( address, date_updated )
      VALUES
        ($address, now())
      ON CONFLICT (address)
      DO UPDATE
        SET date_updated = now()
    """.asUpdate
  }

  /**
    * Retrieve all acounts which have a balance between the start and end balance
    */
  def getBetweenBalance(startBalance: Long, endBalance: Long) = run {
    sql"""
      SELECT
        COUNT(address) as total,
        SUM(balance) as totalBalance
      FROM accounts
      WHERE balance >= $startBalance AND balance < $endBalance
    """.as[(Long, Long)].head
  }

  /**
    * Retrieves the total balance of all accounts
    */
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