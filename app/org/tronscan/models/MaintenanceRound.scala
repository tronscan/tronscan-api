package org.tronscan.models

import com.google.inject.{Inject, Singleton}
import io.circe.parser.parse
import org.joda.time.DateTime
import org.tronscan.db.PgProfile.api._
import org.tronscan.db.TableRepository
import play.api.db.slick.DatabaseConfigProvider

import scala.concurrent.ExecutionContext

case class MaintenanceRoundModel(
  block: Long,
  number: Int,
  timestamp: Long,
  dateStart: DateTime = DateTime.now,
  dateEnd: Option[DateTime] = None,
)

class MaintenanceRoundModelTable(tag: Tag) extends Table[MaintenanceRoundModel](tag, "maintenance_round") {
  def block = column[Long]("block", O.PrimaryKey)
  def number = column[Int]("number")
  def timestamp = column[Long]("timestamp")
  def dateStart = column[DateTime]("date_start")
  def dateEnd = column[DateTime]("date_end")
  def * = (block, number, timestamp, dateStart, dateEnd.?) <> (MaintenanceRoundModel.tupled, MaintenanceRoundModel.unapply)
}

@Singleton()
class MaintenanceRoundModelRepository @Inject() (val dbConfig: DatabaseConfigProvider) extends TableRepository[MaintenanceRoundModelTable, MaintenanceRoundModel] {

  lazy val table = TableQuery[MaintenanceRoundModelTable]
  lazy val transactionTable = TableQuery[TransactionModelTable]
  lazy val transferTable = TableQuery[TransferModelTable]

  def findAll = run {
    table.result
  }

  def findAllRounds = run {
    table.sortBy(_.number.asc).result
  }

  def findLatest = run {
    table.sortBy(_.number.desc).result.headOption
  }

  /**
    * Retrieve the votes for the given blocks
    */
  def getVotesBetweenBlocks(fromBlock: Long, toBlock: Long)(implicit executionContext: ExecutionContext) = run {
    sql"""
       SELECT
         t.owner_address,
         t.contract_type,
         t.contract_data
       FROM  (
         SELECT
           owner_address,
           max(date_created) as ts
         FROM transactions
         WHERE (block > $fromBlock AND block <= $toBlock)
         AND (contract_type = 12 OR contract_type = 4)
         GROUP BY owner_address
       ) r
       INNER JOIN transactions t
       ON (t.owner_address = r.owner_address AND t.date_created = r.ts)
    """.as[(String, Int, String)]
      .map { result =>
        result.map { case (owner, contractType, contract) =>
          (owner, contractType, parse(contract).toOption.get)
        }
      }
  }


  def findByNumber(number: Int) = run {
    table.filter(_.number === number).result.headOption
  }
}