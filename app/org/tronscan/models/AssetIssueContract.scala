package org.tronscan.models

import java.util.UUID

import com.google.inject.{Inject, Singleton}
import io.circe.Json
import org.joda.time.DateTime
import org.tron.protos.Contract.AssetIssueContract
import play.api.db.slick.DatabaseConfigProvider
import org.tronscan.App._
import org.tronscan.db.PgProfile.api._
import org.tronscan.db.TableRepository
import io.circe.syntax._
import io.circe.generic.auto._

object AssetIssueContractModel {
//  implicit val format = Json.format[AssetIssueContractModel]
//  implicit val frozenTokenformat = Json.format[FrozenToken]
}

object FrozenToken {
//  implicit val frozenTokenformat = Json.format[FrozenToken]
}

case class FrozenToken(amount: Double, days: Int)

case class AssetIssueContractModel(
  id: UUID = UUID.randomUUID(),
  block: Long,
  transaction: String,
  ownerAddress: String,
  name: String,
  abbr: String,
  totalSupply: Long,
  trxNum: Int,
  num: Int,
  startTime: DateTime,
  endTime: DateTime,
  voteScore: Int,
  description: String,
  url: String,
  dateCreated: DateTime = DateTime.now,
  frozen: Json = Json.arr()) {

  def price = trxNum / num

  def withFrozen(frozenSupply: Seq[AssetIssueContract.FrozenSupply])  = {
    copy(
      frozen = frozenSupply.map(f => Json.obj(
        "amount" -> f.frozenAmount.asJson,
        "days" -> f.frozenDays.asJson,
      )).asJson
    )
  }

  def frozenTokens = frozen.as[List[FrozenToken]].right.get
  def frozenSupply = frozenTokens.map(_.amount).sum
}

class AssetIssueContractModelTable(tag: Tag) extends Table[AssetIssueContractModel](tag, "asset_issue_contract") {
  def id = column[UUID ]("id")
  def block = column[Long]("block")
  def transaction = column[String]("transaction")
  def ownerAddress = column[String]("owner_address")
  def name = column[String]("name")
  def abbr = column[String]("abbr")
  def totalSupply = column[Long]("total_supply")
  def trxNum = column[Int]("trx_num")
  def num = column[Int]("num")
  def startTime = column[DateTime]("date_start")
  def endTime = column[DateTime]("date_end")
  def decayRatio = column[Int]("decay_ratio")
  def voteScore = column[Int]("vote_score")
  def description = column[String]("description")
  def url = column[String]("url")
  def dateCreated = column[DateTime]("date_created")
  def frozen = column[Json]("frozen")
  def * = (
    id, block, transaction, ownerAddress, name, abbr, totalSupply,
    trxNum, num, startTime, endTime, voteScore, description,
    url, dateCreated, frozen) <> ((AssetIssueContractModel.apply _).tupled, AssetIssueContractModel.unapply)
}

@Singleton()
class AssetIssueContractModelRepository @Inject() (val dbConfig: DatabaseConfigProvider) extends TableRepository[AssetIssueContractModelTable, AssetIssueContractModel] {

  lazy val table = TableQuery[AssetIssueContractModelTable]
  lazy val participateTable = TableQuery[ParticipateAssetIssueModelTable]
  lazy val accountTable = TableQuery[AccountModelTable]

  def findAll = run {
    table.result
  }

  def findLatest = run {
    table.sortBy(_.id.desc).result.headOption
  }

  def findByLimit(start: Long, limit: Long) = run {
    table.sortBy(_.id.asc).drop(start).take(limit).result
  }

  def findByName(name: String) = run {
    table.filter(_.name === name).result.headOption
  }


  def withParticipation() = { query: QueryType =>
    for {
      (token, participation) <- query join accountTable on (_.ownerAddress === _.address)
    } yield (token, participation)
  }
}
