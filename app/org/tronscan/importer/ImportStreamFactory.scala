package org.tronscan.importer

import akka.stream.SinkShape
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge, Sink}
import akka.{Done, NotUsed}
import javax.inject.Inject
import org.tron.protos.Tron.{Block, Transaction}
import org.tronscan.Extensions._
import org.tronscan.domain.Types.Address
import org.tronscan.grpc.{FullNodeBlockChain, SolidityBlockChain, WalletClient}
import org.tronscan.importer.StreamTypes._
import org.tronscan.models.MaintenanceRoundModelRepository
import org.tronscan.service.SynchronisationService
import org.tronscan.utils.StreamUtils
import play.api.Logger

import scala.async.Async._
import scala.concurrent.{ExecutionContext, Future}

/**
  * Holds the flows that will handle the specicif objects extracted from blocks
  *
  * @param blocks flows whichs handle the blocks
  * @param addresses flows which handle the addresses
  * @param contracts flows which handle the contracts
  */
case class BlockchainImporters(
  blocks: List[Flow[Block, Block, NotUsed]] = List.empty,
  addresses: List[Flow[Address, Address, NotUsed]] = List.empty,
  contracts: List[Flow[ContractFlow, ContractFlow, NotUsed]] = List.empty,
) {

  def addBlock(block: Flow[Block, Block, NotUsed]) = {
    copy(blocks = blocks :+ block)
  }

  def addAddress(address: Flow[Address, Address, NotUsed]) = {
    copy(addresses = addresses :+ address)
  }

  def addContract(contract: Flow[ContractFlow, ContractFlow, NotUsed]) = {
    copy(contracts = contracts :+ contract)
  }

  def debug = {

    List(
      "Blocks: " + blocks.map(_.getClass.getSimpleName).mkString(", "),
      "Addresses: " + blocks.map(_.getClass.getSimpleName).mkString(", "),
      "Contracts: " + blocks.map(_.getClass.getSimpleName).mkString(", ")
    ).mkString("\n")
  }
}

case class ImportAction(
  /**
   * If all blocks should be confirmed
   */
  confirmBlocks: Boolean = false,

  /**
   * If the accounts should be imported from GRPC and updated into the database
   */
  updateAccounts: Boolean = false,

  /**
   * If redis should be resetted
   */
  cleanRedisCache: Boolean = false,

  /**
   * If address importing should be done asynchronously
   */
  asyncAddressImport: Boolean = false,

  /**
   * If events should be published to the websockets
   */
  publishEvents: Boolean = false,

  /**
   * If db should be reset
   */
  resetDB: Boolean = false,

  /**
    * If every block should be logged
    */
  logAllBlocks: Boolean = true,
)

class ImportStreamFactory @Inject()(
  syncService: SynchronisationService,
  blockChainBuilder: BlockChainStreamBuilder) {

  /**
    * Build import action from import status
    */
  def buildImportActionFromImportStatus(importStatus: NodeState)(implicit executionContext: ExecutionContext) = async {
    var autoConfirmBlocks = false
    var updateAccounts = false
    var redisCleaner = true
    var asyncAddressImport = true
    var publishEvents = true
    var logAllBlocks = true

    val fullNodeBlockHash = await(syncService.getFullNodeHashByNum(importStatus.solidityBlock))
    val resetDB = !await(syncService.isSameChain())

    // If solidity isn't being synced then take over Solidity node tasks
    if (!importStatus.solidityEnabled) {
      autoConfirmBlocks = true
      updateAccounts = true
    }

    // If the solidity and full node hash are the same then confirm everything
    if ((importStatus.dbLatestBlock <= importStatus.solidityBlock - 250) && (fullNodeBlockHash == importStatus.solidityBlockHash)) {
      autoConfirmBlocks = true
      updateAccounts = true
    }

    // Don't publish events when there is lots to sync
    if (importStatus.dbLatestBlock < (importStatus.fullNodeBlock - 250)) {
      publishEvents = false
      logAllBlocks = false
    }

    // No need to clean cache when starting a clean sync
    if (importStatus.dbLatestBlock == 0) {
      redisCleaner = false
    }

    ImportAction(
      confirmBlocks       = autoConfirmBlocks,
      updateAccounts      = updateAccounts,
      cleanRedisCache     = redisCleaner,
      asyncAddressImport  = asyncAddressImport,
      publishEvents       = publishEvents,
      resetDB             = resetDB,
      logAllBlocks        = logAllBlocks,
    )
  }

  /**
    * A sync that extracts all the blocks, transactions, contracts, addresses from the blocks and passes them to streams
    */
  def buildBlockSink(importers: BlockchainImporters): Sink[Block, Future[Done]] = {
    Sink.fromGraph(GraphDSL.create(Sink.ignore) { implicit b => sink =>
      import GraphDSL.Implicits._
      val blocks = b.add(Broadcast[Block](3))
      val transactions = b.add(Broadcast[(Block, Transaction)](1))
      val contracts = b.add(Broadcast[ContractFlow](2))
      val addresses = b.add(Merge[Address](2))
      val out = b.add(Merge[Any](3))

      /***** Channels *****/

      // Pass block witness addresses to address stream
      blocks.map(_.witness).filter(_.length == 34) ~> addresses

      // Transactions
      blocks.mapConcat(b => b.transactions.map(t => (b, t)).toList) ~> transactions.in

      // Contracts
      transactions.mapConcat { case (block, t) => t.getRawData.contract.map(c => (block, t, c)).toList } ~> contracts.in

      // Read addresses from contracts
      contracts.mapConcat(_._3.addresses) ~> addresses

      /** Importers **/

      // Extract blocks
      blocks ~> StreamUtils.pipe(importers.blocks).async ~> out

      // Extract addresses
      addresses ~> StreamUtils.distinct[Address] ~> StreamUtils.pipe(importers.addresses).async ~> out

      // Extract contracts
      contracts ~> StreamUtils.pipe(importers.contracts).async ~> out

      /** Close Stream **/

      // Route everything to sink
      out ~> sink.in

      SinkShape(blocks.in)
    })
  }

  /**
    * Verifies that blocks are properly sequential
    */
  def buildBlockSequenceChecker = {
    Flow[Block]
      .statefulMapConcat { () =>
        var number = -1L
        block => {
          val currentNumber = block.getBlockHeader.getRawData.number
          if (number == -1) {
            number = currentNumber
            List(block)
          } else if (number + 1 == currentNumber) {
            number = block.getBlockHeader.getRawData.number
            List(block)
          } else if (number == currentNumber) {
            List.empty
          } else {
            throw new Exception(s"Incorrect block number sequence, $number => $currentNumber")
          }
        }
      }
  }

  /**
    * Build a stream of blocks from a solidity node
    */
  def buildBlockSource(walletClient: WalletClient)(implicit context: ExecutionContext) = {
    Flow[NodeState]
      .mapAsync(1) { status =>
        walletClient.full.map { walletFull =>
          val fullNodeBlockChain = new FullNodeBlockChain(walletFull)
          val fromBlock = if (status.dbLatestBlock <= 0) 0 else status.dbLatestBlock + 1
          val toBlock = status.fullNodeBlock - 1

          // Switch between batch or single depending how far the sync is behind
          if (status.fullNodeBlocksToSync < 100)  blockChainBuilder.readFullNodeBlocks(fromBlock, toBlock)(fullNodeBlockChain.client)
          else                                    blockChainBuilder.readFullNodeBlocksBatched(fromBlock, toBlock, 90)(walletClient)
        }
      }
      .flatMapConcat(blockStream => blockStream)
  }

  /**
    * Build a stream of solidity blocks
    */
  def buildSolidityBlockSource(walletClient: WalletClient)(implicit context: ExecutionContext) = {
    Flow[NodeState]
      .mapAsync(1) { status =>
        walletClient.solidity.map { walletSolidity =>
          val client = new SolidityBlockChain(walletSolidity).client
          blockChainBuilder.readSolidityBlocks(status.dbUnconfirmedBlock, status.soliditySyncToBlock)(client)
        }
      }
      .flatMapConcat(blockStream => blockStream)
  }


  /**
    * Retrieves the latest synchronisation status and checks if the sync should proceed
    */
  def fullNodePreSynchronisationChecker = {
    Flow[NodeState]
      .filter {
        // Stop if there are more then 100 blocks to sync for full node
        case status if status.fullNodeBlocksToSync > 0 =>
          Logger.info(s"START SYNC FROM ${status.dbLatestBlock} TO ${status.fullNodeBlock}.")
          true
        case status =>
          Logger.info("IGNORE FULL NODE SYNC: " + status.toString)
          false
      }
  }

  /**
    * Retrieves the latest synchronisation status and checks if the sync should proceed
    */
  def solidityNodePreSynchronisationChecker = {
    Flow[NodeState]
      .filter {
        // Stop if there are more then 100 blocks to sync for full node
        case status if status.fullNodeBlocksToSync > 100 =>
          false
        // Accept if there are blocks to sync
        case status if status.solidityBlocksToSync > 0 =>
          true
        case _ =>
          false
      }
  }

}
