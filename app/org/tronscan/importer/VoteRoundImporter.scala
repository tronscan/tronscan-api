package org.tronscan.importer

import akka.stream.scaladsl.{Keep, Sink, Source}
import io.circe.Json
import javax.inject.Inject
import org.tron.protos.Tron.Transaction.Contract.ContractType
import org.tronscan.Extensions._
import org.tronscan.api.models.TransactionSerializer._
import org.tronscan.models.{MaintenanceRoundModelRepository, RoundVoteModelRepository}
import play.api.Logger

import scala.concurrent.ExecutionContext

class VoteRoundImporter @Inject() (
  maintenanceRepository: MaintenanceRoundModelRepository,
  roundVoteModelRepository: RoundVoteModelRepository) {

  /**
    * Import all vote rounds
    */
  def importRounds()(implicit executionContext: ExecutionContext) = {

    Source
      .single(0)
      // Load rounds
      .mapAsync(1)(_ => maintenanceRepository.findAllRounds)
      // Combine rounds
      .mapConcat(_.sliding(2).toList)
      // Iterate all votes
      .foldAsync(Map[String, Map[String, Long]]()) { case (previousVotes, Seq(currentRound, nextRound)) =>
        Logger.info(s"Importing round ${currentRound.number}")
        for {
          votes <- maintenanceRepository.getVotesBetweenBlocks(currentRound.block, nextRound.block)
          newMap = buildVotes(previousVotes, votes)
          _ <- roundVoteModelRepository.insertVoteRounds(newMap, currentRound.number)
        } yield newMap
      }
      .toMat(Sink.ignore)(Keep.right)
  }

  def buildVotes(voteMap: Map[String, Map[String, Long]] = Map.empty, roundVotes: Vector[(String, Int, Json)]) = {

    roundVotes.foldLeft(voteMap) {
      case (votes, (address, contractType, contractData)) =>
        if (contractType == ContractType.UnfreezeBalanceContract.value) {
          votes - address
        } else if (contractType == ContractType.VoteWitnessContract.value) {
          val contractVotes = contractData.as[org.tron.protos.Contract.VoteWitnessContract].toOption.get
          votes ++ Map(address -> contractVotes.votes.map(x => (x.voteAddress.encodeAddress, x.voteCount)).toMap)
        } else {
          votes
        }
    }
  }

}
