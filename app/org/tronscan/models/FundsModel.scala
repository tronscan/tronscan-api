package org.tronscan.models

import com.google.inject.{Inject, Singleton}
import org.tronscan.db.PgProfile.api._
import org.tronscan.db.TableRepository
import play.api.db.slick.DatabaseConfigProvider

import scala.concurrent.ExecutionContext


case class FundsModel(id: Int, address: String)

class FundsModelTable(tag: Tag) extends Table[FundsModel](tag, "funds") {
  def id = column[Int]("id")
  def address = column[String]("address")
  def * = (id, address) <> ((FundsModel.apply _).tupled, FundsModel.unapply)
}

@Singleton()
class FundsModelRepository @Inject() (val dbConfig: DatabaseConfigProvider) extends TableRepository[FundsModelTable, FundsModel] {

  lazy val table = TableQuery[FundsModelTable]

  def findAll()(implicit executionContext: ExecutionContext) = run {
    sql"""
       SELECT f.id, f.address, a.balance, a.power
       FROM funds f LEFT JOIN accounts a
       ON f.address = a.address
    """.as[(String, String, Long, Long)]
  }.map(r => r)

  def getFundsBalanceSum()(implicit executionContext: ExecutionContext) = run {
    sql"""
       SELECT SUM(a.balance) balance
       FROM funds f LEFT JOIN accounts a
       ON f.address = a.address
    """.as[Long]
  }.map(r => r)
}