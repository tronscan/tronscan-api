package org.tronscan.models

import com.google.inject.{Inject, Singleton}
import org.joda.time.DateTime
import play.api.db.slick.DatabaseConfigProvider
import play.api.libs.json.Json
import org.tronscan.db.PgProfile.api._
import org.tronscan.db.TableRepository
import org.tronscan.App._

import scala.concurrent.{ExecutionContext, Future}

case class VoteSnapshotModel(
  id: Option[Long] = None,
  address: String,
  timestamp: DateTime,
  votes: Long)

class VoteSnapshotModelTable(tag: Tag) extends Table[VoteSnapshotModel](tag, Some("analytics"), "vote_snapshot") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def address = column[String]("address")
  def timestamp = column[DateTime]("timestamp")
  def votes = column[Long]("votes")
  def * = (id.?, address, timestamp, votes) <> (VoteSnapshotModel.tupled, VoteSnapshotModel.unapply)
}

@Singleton()
class VoteSnapshotModelRepository @Inject() (val dbConfig: DatabaseConfigProvider) extends TableRepository[VoteSnapshotModelTable, VoteSnapshotModel] {

  lazy val table = TableQuery[VoteSnapshotModelTable]

  def findAll = run {
    table.result
  }

  def findByAddress(address: String) = run {
    table.filter(_.address === address).result.headOption
  }

  def update(witness: VoteSnapshotModel) = run {
    table.filter(_.address === witness.address).update(witness)
  }

  def updateVotes(snapshots: Seq[VoteSnapshotModel]) = run {
    DBIO.seq(snapshots.map(x => table += x): _*)
  }

  def findPreviousVotes(hours: Int)(implicit executionContext: ExecutionContext) = run {
    sql"""
       SELECT
       address,
       votes
       FROM
       analytics.vote_snapshot
       WHERE
       timestamp = (
         SELECT MAX(timestamp) FROM analytics.vote_snapshot
         WHERE timestamp < (now() - interval '#$hours hour')
        LIMIT 1
       )
    """.as[(String, Long)]
  }.map(_.toMap)
}
