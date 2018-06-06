package org
package tronscan.actors

import akka.actor.{Actor, Cancellable}
import javax.inject.Inject
import org.joda.time.{DateTime, Interval}
import org.tron.api.api.EmptyMessage
import org.tron.api.api.WalletGrpc.Wallet
import org.tron.api.api.WalletSolidityGrpc.WalletSolidity
import org.tron.common.utils.Base58
import org.tronscan.models.{VoteSnapshotModel, VoteSnapshotModelRepository, VoteWitnessContractModelRepository}

import scala.concurrent.duration._

case class MakeSnapshot()

class VoteScraper @Inject() (
  walletSolidity: WalletSolidity,
  repo: VoteWitnessContractModelRepository,
  voteSnapshotModelRepository: VoteSnapshotModelRepository) extends Actor {

  import context.dispatcher

  var cancellable: Cancellable = Cancellable.alreadyCancelled

  def scheduleNext() = {
    cancellable = context.system.scheduler.scheduleOnce(10.minutes, self, MakeSnapshot())
  }

  def makeSnapshot() = async {
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

  override def preStart(): Unit = {
    scheduleNext()
  }


  override def postStop(): Unit = {
    if (!cancellable.isCancelled) {
      cancellable.cancel()
    }
  }

  def receive = {
    case MakeSnapshot() =>
      makeSnapshot().onComplete { _ =>
        scheduleNext()
      }
  }

}
