package org
package tronscan.actions

import javax.inject.Inject
import org.joda.time.DateTime
import org.tron.api.api.WalletSolidityGrpc.WalletSolidity
import org.tronscan.models.{VoteSnapshotModel, VoteSnapshotModelRepository, VoteWitnessContractModelRepository}

import scala.concurrent.ExecutionContext

case class MakeSnapshot()

class VoteScraper @Inject() (
  walletSolidity: WalletSolidity,
  repo: VoteWitnessContractModelRepository,
  voteSnapshotModelRepository: VoteSnapshotModelRepository) extends AsyncAction {

  def execute(implicit executionContext: ExecutionContext) = async {
    val timestamp = DateTime.now

    val witnesses = await(repo.votesByAddress)

    val votes = witnesses.map { case (address, stats) =>
      VoteSnapshotModel(
        address = address,
        timestamp = timestamp,
        votes = stats.votes,
      )
    }.toList

    await(voteSnapshotModelRepository.updateVotes(votes))
  }
}
