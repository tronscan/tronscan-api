package org.tronscan.actions

import javax.inject.Inject
import org.tron.api.api.EmptyMessage
import org.tron.api.api.WalletGrpc.Wallet
import org.tron.api.api.WalletSolidityGrpc.WalletSolidity
import org.tronscan.Extensions._
import org.tronscan.grpc.WalletClient
import org.tronscan.models._
import org.tronscan.protocol.AddressFormatter
import play.api.cache.Cached
import play.api.libs.json.Json

import scala.concurrent.ExecutionContext

class VoteList @Inject() (
  srRepo: SuperRepresentativeModelRepository,
  accountModelRepository: AccountModelRepository,
  voteSnapshotModelRepository: VoteSnapshotModelRepository,
  walletSolidity: WalletSolidity,
  wallet: Wallet) extends AsyncAction {

  def execute(implicit executionContext: ExecutionContext) = {

    def getTotals(period: String = "now") = {
      period match {
        case "previous-hour" =>
          voteSnapshotModelRepository.findPreviousVotes(6)
        case "previous-day" =>
          voteSnapshotModelRepository.findPreviousVotes(24)
        case _ =>
          voteSnapshotModelRepository.findPreviousVotes(0)
      }
    }

    def getChange(current: Int, previous: Option[Int]): Int = {
      (current, previous) match {
        case (_, None) =>
          0
        case (curr, Some(prev))=>
          curr - prev
        case (_, _) =>
          0
      }
    }

    def buildRank(voteList: Map[String, Long]) = {
      voteList
        .toList
        .sortBy(_._2)
        .zipWithIndex.map { case ((address, _), rank) => (address, rank) }
        .toMap
    }

    for {
      witnesses <- wallet.listWitnesses(EmptyMessage()).map(_.witnesses)
      accounts <- accountModelRepository.findByAddresses(witnesses.map(_.address.encodeAddress)).map(_.map(x => x.address -> x.name).toMap)
      previousHour <- getTotals("previous-hour").map(_.toMap)
      previousDay <- getTotals("previous-day").map(_.toMap)
      superRepresentatives <- srRepo.findAll
    } yield {

      val witnessAddresses = witnesses.map(x => (x.address.encodeAddress, x.url)).toMap
      val witnessesCurrent = witnesses
        .map(w => (w.address.encodeAddress, w.voteCount))
        .toMap

      val totalVotes = witnesses.map(_.voteCount).sum

      val currentRank = buildRank(witnessesCurrent)
      val previousHourRank = buildRank(previousHour)
      val previousDayRank = buildRank(previousDay)

      val hasPages = superRepresentatives.map(x => (x.address, x.githubLink.map(_.trim).getOrElse("").nonEmpty)).toMap

      Json.obj(
        "total_votes" -> totalVotes,
        "candidates" -> witnessesCurrent.map { case (address, votes) =>
          val hasPage = hasPages.get(address).getOrElse(false)
          val name = accounts.get(address) match {
            case Some(accountName) if accountName.trim.isEmpty => ""
            case Some(accountName) => accountName
            case _ => ""
          }
          Json.obj(
            "address" -> address,
            "name" -> name,
            "url" -> witnessAddresses(address),
            "hasPage" -> hasPage,
            "votes" -> votes,
            "change_cycle" -> getChange(currentRank(address), previousHourRank.get(address)),
            "change_day" -> getChange(currentRank(address), previousDayRank.get(address))
          )
        }
      )
    }
  }
}
