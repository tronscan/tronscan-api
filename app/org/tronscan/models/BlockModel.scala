package org.tronscan.models

import com.google.inject.{Inject, Singleton}
import org.tronscan.db.PgProfile.api._
import org.tronscan.db.TableRepository
import org.joda.time.DateTime
import play.api.db.slick.DatabaseConfigProvider
import play.api.libs.json.Json
import org.tronscan.App._

import scala.concurrent.{ExecutionContext, Future}

object BlockModel {
  implicit val format = Json.format[BlockModel]
}

case class BlockModel(
    number: Long,
    hash: String,
    size: Int,
    timestamp: DateTime,
    txTrieRoot: String,
    parentHash: String,
    witnessId: Long,
    witnessAddress: String,
    nrOfTrx: Int,
    confirmed: Boolean = false)

class BlockModelTable(tag: Tag) extends Table[BlockModel](tag, "blocks") {
  def number = column[Long]("id", O.PrimaryKey)
  def hash = column[String]("hash")
  def size = column[Int]("size")
  def timestamp = column[DateTime]("date_created")
  def trieRoot = column[String]("trie")
  def parentHash = column[String]("parent_hash")
  def witnessId = column[Long]("witness_id")
  def witnessAddress = column[String]("witness_address")
  def nrOfTrx = column[Int]("transactions")
  def confirmed = column[Boolean]("confirmed")
  def * = (number, hash, size, timestamp, trieRoot, parentHash, witnessId, witnessAddress, nrOfTrx, confirmed) <> ((BlockModel.apply _).tupled, BlockModel.unapply)
}

@Singleton()
class BlockModelRepository @Inject() (val dbConfig: DatabaseConfigProvider) extends TableRepository[BlockModelTable, BlockModel] {

  lazy val table = TableQuery[BlockModelTable]
  lazy val transactionTable = TableQuery[TransactionModelTable]
  lazy val transferTable = TableQuery[TransferModelTable]

  def findAll = run {
    table.result
  }

  def findLatest = run {
    table.sortBy(_.number.desc).result.headOption
  }

  def findLatestUnconfirmed = run {
    table.filter(_.confirmed === false).sortBy(_.number.asc).result.headOption
  }

  def updateAsync(id: Long, entity: BlockModel) = run {
    table.filter(_.number === id).update(entity)
  }

  def confirmBlock(id: Long) = run {
    DBIO.seq(
      table.filter(_.number === id).map(_.confirmed).update(true),
      transactionTable.filter(_.block === id).map(_.confirmed).update(true),
      transferTable.filter(_.block === id).map(_.confirmed).update(true),
    )
  }
  def buildConfirmBlock(id: Long) = {
    Seq(
      table.filter(_.number === id).map(_.confirmed).update(true),
      transactionTable.filter(_.block === id).map(_.confirmed).update(true),
      transferTable.filter(_.block === id).map(_.confirmed).update(true),
    )
  }

  def clearAll = run {
    sql"""
    TRUNCATE TABLE accounts;
    TRUNCATE TABLE address_balance;
    TRUNCATE TABLE asset_issue_contract;
    TRUNCATE TABLE blocks CASCADE;
    TRUNCATE TABLE analytics.vote_snapshot;
    TRUNCATE TABLE ip_geo;
    TRUNCATE TABLE participate_asset_issue;
    TRUNCATE TABLE transactions;
    TRUNCATE TABLE vote_witness_contract;
    TRUNCATE TABLE witness_create_contract;
    """.asUpdate
  }


  def findFirst = {
    findByNumber(2)
  }

  def findByLimit(start: Long, limit: Long) = run {
    table.sortBy(_.number.asc).drop(start).take(limit).result
  }

  def findByNumber(number: Long) = run {
    table.filter(_.number === number).result.headOption
  }

  def deleteByNumber(number: Long) = run {
    table.filter(_.number === number).delete
  }

  def buildDeleteByNumber(number: Long) = {
    table.filter(_.number === number).delete
  }

  def filterByCreateTime(time: Long) = run {
    table.filter(_.timestamp > new DateTime(time)).result
  }

  def maintenanceStatistic(time: String)(implicit executionContext: ExecutionContext) = run {
    sql"""
       SELECT t2.witness_address, t2.name, w.url, t2.blockCount
       FROM witness_create_contract w RIGHT JOIN
          (SELECT
          a.name, t1.blockCount, t1.witness_address
          FROM
          accounts a RIGHT JOIN
            (SELECT
              witness_address,
              COUNT(*) as blockCount
            FROM
              blocks
            WHERE
              date_created > '#$time'
            GROUP BY
              witness_address) t1
           ON a.address = t1.witness_address)t2
       ON w.address = t2.witness_address

    """.as[(String, String, String, Long)]
  }.map(r => r)

  def maintenanceTotalBlocks(time: String)(implicit executionContext: ExecutionContext) = run {
    sql"""
       SELECT COUNT(*) as total
       FROM
         blocks
       WHERE
         date_created > '#$time'
    """.as[Long]
  }.map(r => r)

}