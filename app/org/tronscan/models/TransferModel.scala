package org.tronscan.models

import java.util.UUID

import com.google.inject.{Inject, Singleton}
import org.joda.time.DateTime
import play.api.db.slick.DatabaseConfigProvider
import org.tronscan.db.PgProfile.api._
import org.tronscan.db.TableRepository


case class TransferModel(
  id: UUID = UUID.randomUUID(),
  transactionHash: String,
  block: Long,
  timestamp: DateTime,
  transferFromAddress: String = "",
  transferToAddress: String = "",
  amount: Long = 0L,
  tokenName: String = "TRX",
  confirmed: Boolean = false)

class TransferModelTable(tag: Tag) extends Table[TransferModel](tag, "transfers") {
  def id = column[UUID]("id", O.PrimaryKey)
  def transactionHash = column[String]("transaction_hash")
  def block = column[Long]("block")
  def timestamp = column[DateTime]("date_created")
  def transferFromAddress = column[String]("transfer_from_address")
  def transferToAddress = column[String]("transfer_to_address")
  def amount = column[Long]("amount")
  def tokenName = column[String]("token_name")
  def confirmed = column[Boolean]("confirmed")
  def * = (id, transactionHash, block, timestamp, transferFromAddress, transferToAddress, amount, tokenName, confirmed) <> (TransferModel.tupled, TransferModel.unapply)
}

@Singleton()
class TransferModelRepository @Inject() (val dbConfig: DatabaseConfigProvider) extends TableRepository[TransferModelTable, TransferModel] {

  lazy val table = TableQuery[TransferModelTable]

  def findAll = run {
    table.result
  }

  def countByAddress(address: String) = run {
    table.filter(x => x.transferToAddress === address || x.transferFromAddress === address).size.result
  }

  def countToAddress(address: String) = run {
    table.filter(x => x.transferToAddress === address).size.result
  }

  def countFromAddress(address: String) = run {
    table.filter(x => x.transferFromAddress === address).size.result
  }

  def countTokenTransfers(tokenName: String) = run {
    table.filter(x => x.tokenName === tokenName).size.result
  }

  def findByHash(hash: String) = run {
    table.filter(_.transactionHash === hash).result.headOption
  }

  def update(entity: TransferModel) = run {
    table.filter(_.transactionHash === entity.transactionHash).update(entity)
  }

}
