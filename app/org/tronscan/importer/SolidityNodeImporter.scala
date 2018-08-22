package org.tronscan.importer

import akka.actor.Actor
import akka.stream._
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import javax.inject.Inject
import monix.execution.Scheduler.Implicits.global
import org.tronscan.grpc.{SolidityBlockChain, WalletClient}
import org.tronscan.importer.ImportManager.Sync
import org.tronscan.service.SynchronisationService
import play.api.Logger

import scala.async.Async.{async, await}
import scala.concurrent.duration._
import scala.util.{Failure, Success}


class SolidityNodeImporter @Inject()(
  importersFactory: ImportersFactory,
  importStreamFactory: ImportStreamFactory,
  synchronisationService: SynchronisationService,
  walletClient: WalletClient) extends Actor {


  /**
    * Builds the stream
    *
    * 1. `nodeState`: First check what the state of the nodes are by requesting the nodeState
    * 2. `importAction`: Determine how the import should behave based on the `nodeState`
    * 3. `importers`: Build the importers based on the `importAction`
    * 4. `blockSink`: Build the sink that extracts all the data from the blockchain
    * 5. `synchronisationChecker`: verifies if the synchranisation should start
    * 6. `blockSource`: Builds the source from which the blocks will be read from the blockchain
    */
  def buildStream() = {
    implicit val system = context.system
    async {
      val nodeState = await(synchronisationService.nodeState)
      Logger.info("BuildStream::nodeState -> " + nodeState)
      val importAction = await(importStreamFactory.buildImportActionFromImportStatus(nodeState))
      Logger.info("BuildStream::importAction -> " + importAction)
      val importers = importersFactory.buildImporters(importAction)
      Logger.info("BuildStream::importers -> " + importers.debug)
      val synchronisationChecker = importStreamFactory.solidityNodePreSynchronisationChecker
      val blockSource = importStreamFactory.buildSolidityBlockSource(walletClient)
      val blockSink = importStreamFactory.buildBlockSink(importers)

      Source
        .single(nodeState)
        .via(synchronisationChecker)
        .via(blockSource)
        .toMat(blockSink)(Keep.right)
    }
  }

  def startSync() = {

    val decider: Supervision.Decider = { exc =>
      Logger.error("FULL NODE ERROR", exc)
      Supervision.Restart
    }

    implicit val materializer = ActorMaterializer(
      ActorMaterializerSettings(context.system)
        .withSupervisionStrategy(decider))(context)

    Logger.info("START SOLIDITY NODE SYNC")

    Source.tick(0.seconds, 2.8.seconds, "")
      .mapAsync(1)(_ => buildStream().flatMap(_.run()))
      .runWith(Sink.ignore)
      .andThen {
        case Success(_) =>
          Logger.info("SOLIDITY SYNC SUCCESS")
        case Failure(exc) =>
          Logger.error("SOLIDITY SYNC FAILURE", exc)
      }
  }

  def receive = {
    case Sync() =>
      startSync()
  }
}
