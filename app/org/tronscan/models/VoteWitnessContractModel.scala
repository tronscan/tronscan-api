package org.tronscan.models

import java.util.UUID

import com.google.inject.{Inject, Singleton}
import org.tronscan.db.PgProfile.api._
import org.tronscan.db.TableRepository
import org.joda.time.DateTime
import play.api.db.slick.DatabaseConfigProvider
import play.api.libs.json.Json
import play.api.libs.json.JodaWrites._
import play.api.libs.json.JodaReads._
import scala.concurrent.ExecutionContext.Implicits.global

object VoteWitnessContractModel {
  implicit val format = Json.format[VoteWitnessContractModel]
}

case class VoteWitnessContractModel(
  id: UUID = UUID.randomUUID(),
  block: Long,
  transaction: String,
  timestamp: DateTime,
  voterAddress: String = "",
  candidateAddress: String = "",
  votes: Long = 0L)

class VoteWitnessContractModelTable(tag: Tag) extends Table[VoteWitnessContractModel](tag, "vote_witness_contract") {
  def id = column[UUID]("id")
  def block = column[Long]("block")
  def transaction = column[String]("transaction")
  def timestamp = column[DateTime]("date_created")
  def voterAddress = column[String]("voter_address")
  def candidateAddress = column[String]("candidate_address")
  def votes = column[Long]("votes")
  def * = (id, block, transaction, timestamp, voterAddress, candidateAddress, votes) <> ((VoteWitnessContractModel.apply _).tupled, VoteWitnessContractModel.unapply)
}

@Singleton()
class VoteWitnessContractModelRepository @Inject() (val dbConfig: DatabaseConfigProvider) extends TableRepository[VoteWitnessContractModelTable, VoteWitnessContractModel] {

  lazy val table = TableQuery[VoteWitnessContractModelTable]
  lazy val witnessTable = TableQuery[WitnessModelTable]
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

  def updateVotes(address: String, votes: Seq[VoteWitnessContractModel]) = run {
    DBIO.seq(Seq(table.filter(_.voterAddress === address).delete) ++ votes.map(x => table += x): _*).transactionally
  }

  def buildUpdateVotes(address: String, votes: Seq[VoteWitnessContractModel]) = {
    Seq(table.filter(_.voterAddress === address).delete) ++ votes.map(x => table += x)
  }

  def readTotalVotes[TR, TG](func: QueryType => QueryType) = run {
    func(table).map(_.votes).sum.result
  }

  def votesByAddress = run {
    table
      .groupBy(_.candidateAddress)
      .map {
        case (address, row) =>
          (address, row.map(_.votes).sum)
      }
      .result
  }.map { addresses =>
    addresses.map {
      case (address, votes) => (address, CandidateStats(address, votes.getOrElse(0L)))
    }.toMap
  }

  def withWitness() = { query: QueryType =>
    for {
      (((vote, witness), candidateAccount), voterAccount) <- query join witnessTable on (_.candidateAddress === _.address) join accountTable on (_._1.candidateAddress === _.address) join accountTable on (_._1._1.voterAddress === _.address)
    } yield (vote, witness, candidateAccount, voterAccount)
  }
}

case class CandidateStats(address: String, votes: Long)
