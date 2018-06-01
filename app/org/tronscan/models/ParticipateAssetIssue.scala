package org.tronscan.models

import java.util.UUID

import com.google.inject.{Inject, Singleton}
import org.tronscan.db.PgProfile.api._
import org.tronscan.db.TableRepository
import org.joda.time.DateTime
import play.api.db.slick.DatabaseConfigProvider
import play.api.libs.json.Json
import org.tronscan.App._

object ParticipateAssetIssueModel {
  implicit val format = Json.format[ParticipateAssetIssueModel]
}

case class ParticipateAssetIssueModel(
  id: UUID = UUID.randomUUID(),
  block: Long,
  amount: Long,
  token: String,
  ownerAddress: String,
  toAddress: String,
  dateCreated: DateTime)

class ParticipateAssetIssueModelTable(tag: Tag) extends Table[ParticipateAssetIssueModel](tag, "participate_asset_issue") {
  def id = column[UUID]("id")
  def block = column[Long]("block")
  def amount = column[Long]("amount")
  def token = column[String]("token_name")
  def ownerAddress = column[String]("owner_address")
  def toAddress = column[String]("to_address")
  def dateCreated = column[DateTime]("date_created")
  def * = (id, block, amount, token, ownerAddress, toAddress, dateCreated) <> ((ParticipateAssetIssueModel.apply _).tupled, ParticipateAssetIssueModel.unapply)
}

@Singleton()
class ParticipateAssetIssueModelRepository @Inject() (val dbConfig: DatabaseConfigProvider) extends TableRepository[ParticipateAssetIssueModelTable, ParticipateAssetIssueModel] {

  lazy val table = TableQuery[ParticipateAssetIssueModelTable]

  def findAll = run {
    table.result
  }

  def assetParticipation = run {
    table
      .groupBy(x => (x.toAddress, x.token))
      .map { case ((owner, tokenName), row) => (owner, tokenName, row.map(_.amount).sum.getOrElse(0L)) }
      .result
  }
}