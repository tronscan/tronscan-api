package org
package tronscan.importer

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Keep, Source}
import javax.inject.Inject
import monix.execution.Scheduler.Implicits.global
import org.tronscan.grpc.WalletClient
import org.tronscan.service.SynchronisationService
import play.api.Logger

import scala.async.Async._

/**
  * Takes care of importing the Full Node Data
  */
class FullNodeImporter @Inject()(
  importersFactory: ImportersFactory,
  importStreamFactory: ImportStreamFactory,
  synchronisationService: SynchronisationService,
  walletClient: WalletClient) {

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
  def buildStream(implicit actorSystem: ActorSystem) = {
    async {
      val nodeState = await(synchronisationService.nodeState)
//      Logger.info("BuildStream::nodeState -> " + nodeState)
      val importAction = await(importStreamFactory.buildImportActionFromImportStatus(nodeState))
//      Logger.info("BuildStream::importAction -> " + importAction)
      val importers = importersFactory.buildFullNodeImporters(importAction)
//      Logger.info("BuildStream::importers -> " + importers.debug)
      val synchronisationChecker = importStreamFactory.fullNodePreSynchronisationChecker
      val blockSource = importStreamFactory.buildBlockSource(walletClient)
      val blockSink = importStreamFactory.buildBlockSink(importers)

      Source
        .single(nodeState)
        .via(synchronisationChecker)
        .via(blockSource)
        .toMat(blockSink)(Keep.right)
    }
  }

}
