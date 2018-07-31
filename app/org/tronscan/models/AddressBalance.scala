package org.tronscan.models

import com.google.inject.{Inject, Singleton}
import org.tronscan.db.PgProfile.api._
import org.tronscan.db.TableRepository
import play.api.db.slick.DatabaseConfigProvider
import play.api.libs.json.Json
import slick.dbio.Effect
import slick.sql.FixedSqlAction

import scala.concurrent.ExecutionContext.Implicits.global

case class AddressBalanceModel(
  address: String,
  name: String,
  balance: Long)

class AddressBalanceModelTable(tag: Tag) extends Table[AddressBalanceModel](tag, "address_balance") {
  def address = column[String]("address")
  def token = column[String]("token")
  def balance = column[Long]("balance")
  def * = (address, token, balance) <> (AddressBalanceModel.tupled, AddressBalanceModel.unapply)
}

@Singleton()
class AddressBalanceModelRepository @Inject() (val dbConfig: DatabaseConfigProvider) extends TableRepository[AddressBalanceModelTable, AddressBalanceModel] {

  lazy val table = TableQuery[AddressBalanceModelTable]

  def findAll = run {
    table.result
  }

  def findByAddress(address: String) = run {
    table.filter(_.address === address).result.headOption
  }

  def buildUpdateBalance(accountModel: AccountModel): Seq[FixedSqlAction[Int, NoStream, Effect.Write]] = {
    (
      Seq(table.filter(_.address === accountModel.address).delete)++
      accountModel.tokenBalances.as[Map[String, Long]].right.get.map {
        case (token, balance) =>
          table += AddressBalanceModel(accountModel.address, token, balance)
      }
    )
  }

  def updateBalance(accountModel: AccountModel) = run {
    DBIO.seq(buildUpdateBalance(accountModel): _*).transactionally
  }

  def countTokenHolders(tokenName: String) = run {
    table.filter(_.token === tokenName).size.result
  }

}