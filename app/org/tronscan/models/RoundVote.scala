package org.tronscan.models

import com.google.inject.{Inject, Singleton}
import io.circe.parser.parse
import org.joda.time.DateTime
import org.tronscan.db.PgProfile.api._
import org.tronscan.db.TableRepository
import play.api.db.slick.DatabaseConfigProvider

import scala.concurrent.ExecutionContext

case class RoundVoteModel(
  address: String,
  round: Int,
  candidate: String,
  votes: Long,
)

class RoundVoteModelTable(tag: Tag) extends Table[RoundVoteModel](tag, "round_votes") {
  def address = column[String]("address", O.PrimaryKey)
  def round = column[Int]("round", O.PrimaryKey)
  def candidate = column[String]("candidate", O.PrimaryKey)
  def votes = column[Long]("votes")
  def * = (address, round, candidate, votes) <> (RoundVoteModel.tupled, RoundVoteModel.unapply)
}

@Singleton()
class RoundVoteModelRepository @Inject() (val dbConfig: DatabaseConfigProvider) extends TableRepository[RoundVoteModelTable, RoundVoteModel] {

  lazy val table = TableQuery[RoundVoteModelTable]
  lazy val transactionTable = TableQuery[TransactionModelTable]
  lazy val transferTable = TableQuery[TransferModelTable]

  def findAll = run {
    table.result
  }

  def findByNumber(number: Int) = run {
    table.filter(_.round === number).result.headOption
  }

  def insertVoteRounds(votes: Map[String, Map[String, Long]], round: Int) = run {
    val models = for {
      (address, candidateVotes) <- votes
      (candidate, voteCount) <- candidateVotes
    } yield RoundVoteModel(address, round, candidate, voteCount)
    DBIO.seq(Seq(table.filter(_.round === round).delete) ++ models.map(m => table += m): _*).transactionally.withPinnedSession
  }

}