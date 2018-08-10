package org
package tronscan.api

import io.circe.Json
import io.circe.generic.auto._
import io.circe.syntax._
import io.circe.generic.auto._
import io.circe.syntax._
import javax.inject.Inject
import org.joda.time.DateTime
import org.tron.api.api.EmptyMessage
import org.tron.api.api.WalletSolidityGrpc.WalletSolidity
import org.tronscan.App._
import org.tronscan.actions.VoteList
import org.tronscan.actions.VoteList
import org.tronscan.db.PgProfile.api._
import org.tronscan.domain.Constants
import org.tronscan.grpc.WalletClient
import org.tronscan.models._
import play.api.cache.redis.CacheAsyncApi
import play.api.cache.{Cached, NamedCache}
import play.api.mvc.InjectedController
import play.api.cache.{Cached, NamedCache}
import play.api.mvc.InjectedController

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class VoteApi @Inject()(
  cached: Cached,
  repo: VoteWitnessContractModelRepository,
  witnessModelRepository: WitnessModelRepository,
  srRepo: SuperRepresentativeModelRepository,
  walletClient: WalletClient,
  accountModelRepository: AccountModelRepository,
  voteSnapshotModelRepository: VoteSnapshotModelRepository,
  walletSolidity: WalletSolidity,
  voteList: VoteList,
  @NamedCache("redis") redisCache: CacheAsyncApi) extends InjectedController {

  def findAll() = Action.async { implicit request =>

    import repo._

    var q =  sortWithRequest() {
      case (t, "timestamp") => t.timestamp
      case (t, "votes") => t.votes
    }

    q = q andThen filterRequest {
      case (query, ("block", value)) =>
        query.filter(_.block === value.toLong)
      case (query, ("transaction", value)) =>
        query.filter(_.transaction === value)
      case (query, ("voter", value)) =>
        query.filter(_.voterAddress === value)
      case (query, ("candidate", value)) =>
        query.filter(_.candidateAddress === value)
      case (query, _) =>
        query
    }

    for {
      total <- readTotals(q)
      totalVotes <- readTotalVotes(q).map(_.getOrElse(0L))
      accounts <- readQuery(q andThen limitWithRequest() andThen withWitness())
    } yield {
      Ok(Json.obj(
        "total" -> total.asJson,
        "totalVotes" -> totalVotes.asJson,
        "data" -> accounts.map { case (vote, witness, candidateAccount, voterAccounts) => {
          vote.asJson.deepMerge(Json.obj(
            "candidateUrl" -> witness.url.asJson,
            "candidateName" -> candidateAccount.name.asJson,
            "voterAvailableVotes" -> (voterAccounts.power / Constants.ONE_TRX).asJson,
          ))
        }}.asJson,
      ))
    }
  }

  def currentCycle = cached.status(x => "current_votes_cycle", 200, 10.seconds) {
    Action.async {
      repo.votesByAddress.map { votes =>
        Ok(io.circe.Json.obj(
          "data" -> votes.asJson
        ))
      }
    }
  }

  def candidateTotals = Action.async { request =>
    for {
      json <- redisCache.getOrFuture(s"votes.candidates_total", 10.seconds)(voteList.execute)
    } yield Ok(json)
  }

  def nextCycle = Action.async {

    for {
      client <- walletClient.full
      nextMaintenanceTime <- client.getNextMaintenanceTime(EmptyMessage()).map(_.num)
      currentTime <- client.getNowBlock(EmptyMessage()).map(_.getBlockHeader.getRawData.timestamp)
    } yield {
      Ok(Json.obj(
        "nextCycle" -> (nextMaintenanceTime - currentTime).asJson
      ))
    }
  }

  def stats = Action.async {
    import voteSnapshotModelRepository._

    val after = DateTime.now.minusDays(3)

    val q2 = query
      .filter(_.timestamp > after)
      .groupBy(x => (x.address, x.timestamp.trunc("hour")))
      .map {
        case (day, row) =>
          (day, row.map(_.votes).max)
      }
      .sortBy(x => (x._1._1, x._1._2))

    for {
      data <- readAsync(q2)
    } yield {
      Ok(Json.obj(
        "results" -> data.map(row => Json.obj(
          "address" -> row._1._1.asJson,
          "timestamp" -> row._1._2.asJson,
          "votes" -> row._2.asJson,
        )).asJson,
      ))
    }
  }
}